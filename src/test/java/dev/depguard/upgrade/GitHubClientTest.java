package dev.depguard.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The exact URLs sent to GitHub. A "/" inside "owner/repo" must stay a "/", not become %2F (GitHub answers 404). */
class GitHubClientTest {

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(builder,
                new GitHubProperties("test-token", "owner/demo-app", "main", "app/pom.xml", "https://api.github.test"));
    }

    @Test
    void readsANestedFileFromTheRightRepository() {
        String pom = Base64.getEncoder().encodeToString("<project/>".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.github.test/repos/owner/demo-app/contents/app/pom.xml?ref=main"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"content\":\"" + pom + "\",\"sha\":\"abc\"}", MediaType.APPLICATION_JSON));

        assertThat(client.readFile("app/pom.xml", "main")).isEqualTo(new GitHubClient.RepoFile("<project/>", "abc"));
        server.verify();
    }

    @Test
    void branchCommitAndPullRequestUrls() {
        server.expect(requestTo("https://api.github.test/repos/owner/demo-app/git/ref/heads/main"))
                .andRespond(withSuccess("{\"object\":{\"sha\":\"head\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/demo-app/git/refs"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess());
        server.expect(requestTo("https://api.github.test/repos/owner/demo-app/contents/app/pom.xml"))
                .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess());
        server.expect(requestTo("https://api.github.test/repos/owner/demo-app/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"html_url\":\"https://github.com/owner/demo-app/pull/7\",\"number\":7}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.branchHead("main")).isEqualTo("head");
        client.createBranch("depguard/upgrade-1234abcd", "head");
        client.commitFile("app/pom.xml", "depguard/upgrade-1234abcd", "<project/>", "abc", "Upgrade");
        assertThat(client.openPullRequest("depguard/upgrade-1234abcd", "main", "t", "b"))
                .isEqualTo("https://github.com/owner/demo-app/pull/7");
        server.verify();
    }
}
