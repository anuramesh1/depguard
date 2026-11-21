package dev.depguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.depguard.agent.TriageFacts.VulnerableDependency;
import dev.depguard.dependency.Dependency;
import dev.depguard.exploit.EpssClient;
import dev.depguard.exploit.EpssClient.EpssScore;
import dev.depguard.exploit.KevClient;
import dev.depguard.exploit.KevClient.KevStatus;
import dev.depguard.osv.Severity;
import dev.depguard.scan.Finding;
import dev.depguard.scan.ScanReport;
import dev.depguard.scan.ScanService;
import dev.depguard.scan.VulnerabilitySummary;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The facts the model is given, and the ranking rule that is now code instead of a prompt instruction. */
class TriageFactsServiceTest {

    private final ScanService scanService = mock(ScanService.class);
    private final EpssClient epssClient = mock(EpssClient.class);
    private final KevClient kevClient = mock(KevClient.class);
    private final TriageFactsService service = new TriageFactsService(scanService, epssClient, kevClient);

    private static Finding finding(String artifact, String version, String scope, String fixed, String... cves) {
        Dependency dependency = new Dependency("org.example", artifact, version, scope);
        List<VulnerabilitySummary> advisories = java.util.Arrays.stream(cves)
                .map(cve -> new VulnerabilitySummary("GHSA-" + cve, List.of(cve), "summary", Severity.HIGH, fixed))
                .toList();
        return new Finding(dependency, Severity.HIGH, advisories, fixed);
    }

    private void givenFindings(Finding... findings) {
        when(scanService.scanDependencies(anyList(), anyList()))
                .thenReturn(new ScanReport(findings.length, findings.length, List.of(findings), List.of(), Instant.EPOCH));
    }

    @Test
    void ranksKnownExploitedFirstThenByExploitProbabilityNotByAdvisoryCount() {
        givenFindings(
                finding("many-advisories", "2.9.8", "compile", "2.18.11", "CVE-2020-1", "CVE-2020-2", "CVE-2020-3"),
                finding("high-epss", "1.9", "compile", "1.10.0", "CVE-2022-9"),
                finding("exploited", "2.14.1", "compile", "2.25.4", "CVE-2021-44228"),
                finding("test-only", "4.12", "test", "4.13.1", "CVE-2020-15250"));
        when(epssClient.scores(anyList())).thenReturn(List.of(
                new EpssScore("CVE-2020-1", 0.27, 0.9), new EpssScore("CVE-2022-9", 0.999, 0.99),
                new EpssScore("CVE-2021-44228", 0.94, 0.99), new EpssScore("CVE-2020-15250", 0.99, 0.99)));
        when(kevClient.lookup(anyList())).thenReturn(List.of(
                new KevStatus("CVE-2021-44228", true, "2021-12-10", "Known")));

        TriageFacts facts = service.gather(List.of());

        assertThat(facts.vulnerable()).extracting(VulnerableDependency::dependency).containsExactly(
                "org.example:exploited:2.14.1",       // known exploited beats everything
                "org.example:high-epss:1.9",          // 99.9% EPSS beats 3 advisories at 27%
                "org.example:many-advisories:2.9.8",
                "org.example:test-only:4.12");        // test scope last, even with high EPSS
        VulnerableDependency exploited = facts.vulnerable().getFirst();
        assertThat(exploited.knownExploited()).isTrue();
        assertThat(exploited.ransomwareUse()).isTrue();
        assertThat(exploited.maxEpss()).isEqualTo(0.94);
        assertThat(facts.cveIds()).contains("CVE-2021-44228", "CVE-2022-9").hasSize(6);
    }

    @Test
    void keepsPromptsSmallTopAdvisoriesAndShortSummaries() {
        Dependency jackson = new Dependency("com.fasterxml.jackson.core", "jackson-databind", "2.9.8", "compile");
        List<VulnerabilitySummary> sixty = IntStream.range(0, 60)
                .mapToObj(i -> new VulnerabilitySummary("GHSA-" + i, List.of("CVE-2020-" + (1000 + i)), "x".repeat(300),
                        Severity.CRITICAL, "2.9.10"))
                .toList();
        givenFindings(new Finding(jackson, Severity.CRITICAL, sixty, "2.18.11"));
        when(epssClient.scores(anyList())).thenReturn(List.of());
        when(kevClient.lookup(anyList())).thenReturn(List.of());

        VulnerableDependency facts = service.gather(List.of(jackson)).vulnerable().getFirst();

        assertThat(facts.advisoryCount()).isEqualTo(60);
        assertThat(facts.topAdvisories()).hasSize(TriageFactsService.TOP_ADVISORIES);
        assertThat(facts.topAdvisories().getFirst().summary()).hasSize(TriageFactsService.MAX_SUMMARY_CHARS + 1);
        assertThat(facts.majorUpgrade()).isFalse(); // 2.9.8 -> 2.18.11 stays on 2.x
        assertThat(facts.maxEpss()).isNull();       // no EPSS score is not the same as 0%
    }

    @Test
    void alignsReleaseTrainsButNotIndependentlyVersionedGroups() {
        var webmvc = new VulnerableDependency(0, "org.springframework:spring-webmvc:5.3.17", "compile", Severity.CRITICAL,
                18, "7.0.9", true, true, false, 0.99, List.of());
        var beans = new VulnerableDependency(0, "org.springframework:spring-beans:5.3.17", "compile", Severity.CRITICAL,
                2, "5.3.20", false, true, false, 0.99, List.of());
        var text = new VulnerableDependency(0, "org.apache.commons:commons-text:1.9", "compile", Severity.CRITICAL,
                1, "1.10.0", false, false, false, 0.99, List.of());

        List<VulnerableDependency> aligned = TriageFactsService.alignReleaseTrains(List.of(webmvc, beans, text));

        assertThat(aligned.get(1).recommendedVersion()).isEqualTo("7.0.9"); // spring-beans follows spring-webmvc
        assertThat(aligned.get(1).majorUpgrade()).isTrue();                // ...which makes it a major upgrade too
        assertThat(aligned.get(2).recommendedVersion()).isEqualTo("1.10.0"); // commons-* isn't a release train
        assertThat(TriageFactsService.round4(0.99977)).isEqualTo(0.9998);
    }

    @Test
    void aFeedOutageBecomesANoteForTheModel() {
        givenFindings(finding("lib", "1.0", "compile", "2.0", "CVE-2024-1"));
        when(epssClient.scores(anyList())).thenThrow(new IllegalStateException("EPSS down"));
        when(kevClient.lookup(anyList())).thenReturn(List.of());

        TriageFacts facts = service.gather(List.of());

        assertThat(facts.vulnerable()).hasSize(1);
        assertThat(facts.notes()).singleElement().asString().contains("EPSS");
        assertThat(facts.vulnerable().getFirst().majorUpgrade()).isTrue(); // 1.0 -> 2.0
    }
}
