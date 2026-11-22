package dev.depguard.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.depguard.agent.TriageFacts;
import dev.depguard.agent.TriageFactsService;
import dev.depguard.cache.JdbcSqlRunner;
import dev.depguard.dependency.PomParser;
import dev.depguard.osv.Severity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** The approval rules, with a fake GitHub and the real SQL against Postgres (skipped without Docker). */
@Testcontainers(disabledWithoutDocker = true)
class UpgradeServiceTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final String POM = """
            <project><dependencies>
              <dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId><version>2.14.1</version></dependency>
            </dependencies></project>
            """;

    /** Records what would happen on GitHub instead of calling it. */
    static class FakeGitHub extends GitHubClient {
        String pomSha = "sha-1";
        boolean failOnCommit;
        final List<String> calls = new ArrayList<>();

        FakeGitHub(GitHubProperties github) {
            super(RestClient.builder(), github);
        }

        @Override
        public RepoFile readFile(String path, String branch) {
            return new RepoFile(POM, pomSha);
        }

        @Override
        public String branchHead(String branch) {
            return "head-sha";
        }

        @Override
        public void createBranch(String name, String fromSha) {
            calls.add("branch " + name);
        }

        @Override
        public void commitFile(String path, String branch, String content, String currentSha, String message) {
            if (failOnCommit) {
                throw new IllegalStateException("GitHub said no");
            }
            calls.add("commit " + path + " " + (content.contains("2.25.4") ? "upgraded" : "unchanged"));
        }

        @Override
        public String openPullRequest(String head, String base, String title, String body) {
            calls.add("pr " + head + " -> " + base);
            return "https://github.com/example/demo/pull/1";
        }
    }

    private FakeGitHub gitHub;
    private UpgradeService service;

    @BeforeEach
    void setUp() {
        GitHubProperties props = new GitHubProperties("token", "example/demo", "main", "pom.xml", "https://api.github.test");
        gitHub = new FakeGitHub(props);
        TriageFactsService facts = mock(TriageFactsService.class);
        var log4shell = new TriageFacts.Cve("CVE-2021-44228", 0.9445, true, true);
        var advisory = new TriageFacts.Advisory("GHSA-jfh8", Severity.CRITICAL, "2.15.0", "Log4Shell", List.of(log4shell));
        var log4j = new TriageFacts.VulnerableDependency(1, "org.apache.logging.log4j:log4j-core:2.14.1", "compile",
                Severity.CRITICAL, 8, "2.25.4", false, true, true, 0.9445, List.of(advisory));
        when(facts.gather(anyList())).thenReturn(new TriageFacts(1, List.of(log4j), List.of()));
        var sql = new JdbcSqlRunner(new SimpleDriverDataSource(new Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        service = new UpgradeService(gitHub, props, new PomParser(), facts,
                new UpgradeProposalStore(sql, JsonMapper.builder().build()), true);
    }

    @Test
    void aMaintainerOtherThanTheProposerOpensThePullRequest() {
        UpgradeProposal proposal = service.propose("viewer-vic");

        assertThat(proposal.status()).isEqualTo(UpgradeProposal.PENDING);
        assertThat(proposal.changes()).singleElement().satisfies(c -> {
            assertThat(c.toVersion()).isEqualTo("2.25.4");
            assertThat(c.reason()).contains("known exploited", "CVE-2021-44228");
        });
        assertThat(proposal.diff()).contains("- <version>2.14.1</version>", "+ <version>2.25.4</version>");
        assertThat(gitHub.calls).isEmpty(); // proposing changes nothing on GitHub

        UpgradeProposal opened = service.approve(proposal.id(), "maintainer-mo");

        assertThat(opened.status()).isEqualTo(UpgradeProposal.OPENED);
        assertThat(opened.decidedBy()).isEqualTo("maintainer-mo");
        assertThat(opened.pullRequest()).isEqualTo("https://github.com/example/demo/pull/1");
        assertThat(gitHub.calls).containsExactly("branch depguard/upgrade-" + proposal.id().substring(0, 8),
                "commit pom.xml upgraded", "pr depguard/upgrade-" + proposal.id().substring(0, 8) + " -> main");
        assertThat(service.get(proposal.id()).status()).isEqualTo(UpgradeProposal.OPENED);
    }

    @Test
    void fourEyesStaleRepositoryAndDoubleDecisionsAreRefused() {
        UpgradeProposal own = service.propose("mo");
        assertThatThrownBy(() -> service.approve(own.id(), "mo")).hasMessageContaining("Four-eyes");

        gitHub.pomSha = "sha-2"; // someone pushed to the pom after the proposal
        assertThatThrownBy(() -> service.approve(own.id(), "ann")).hasMessageContaining("changed on GitHub");

        UpgradeProposal rejected = service.reject(own.id(), "ann");
        assertThat(rejected.status()).isEqualTo(UpgradeProposal.REJECTED);
        assertThatThrownBy(() -> service.approve(own.id(), "ann")).hasMessageContaining("REJECTED");
        assertThat(gitHub.calls).isEmpty();
    }

    @Test
    void aGitHubFailureLeavesTheProposalPendingSoItCanBeRetried() {
        UpgradeProposal proposal = service.propose("vic");
        gitHub.failOnCommit = true;

        assertThatThrownBy(() -> service.approve(proposal.id(), "mo")).hasMessageContaining("still PENDING");
        assertThat(service.get(proposal.id()).status()).isEqualTo(UpgradeProposal.PENDING);

        gitHub.failOnCommit = false;
        assertThat(service.approve(proposal.id(), "mo").status()).isEqualTo(UpgradeProposal.OPENED);
    }
}
