package dev.depguard.osv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.depguard.dependency.Dependency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Tests the HTTP contract with OSV against a fake server: no network, fast and repeatable. */
class OsvClientTest {

    private MockRestServiceServer server;
    private OsvClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://osv.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OsvClient(builder.build());
    }

    @Test
    void sendsOneBatchQueryAndReturnsIdsInOrder() {
        server.expect(requestTo("https://osv.test/v1/querybatch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"queries":[
                          {"package":{"name":"org.apache.logging.log4j:log4j-core","ecosystem":"Maven"},"version":"2.14.1"},
                          {"package":{"name":"com.google.guava:guava","ecosystem":"Maven"},"version":"33.0.0-jre"}
                        ]}"""))
                .andRespond(withSuccess("""
                        {"results":[
                          {"vulns":[{"id":"GHSA-jfh8-c2jp-5v3q","modified":"2024-01-01T00:00:00Z"}]},
                          {}
                        ]}""", MediaType.APPLICATION_JSON));

        List<List<String>> ids = client.findVulnerabilityIds(List.of(
                new Dependency("org.apache.logging.log4j", "log4j-core", "2.14.1", "compile"),
                new Dependency("com.google.guava", "guava", "33.0.0-jre", "compile")));

        assertThat(ids).containsExactly(List.of("GHSA-jfh8-c2jp-5v3q"), List.of());
        server.verify();
    }

    @Test
    void readsAdvisoryAndIgnoresFieldsWeDoNotMap() {
        server.expect(requestTo("https://osv.test/v1/vulns/GHSA-jfh8-c2jp-5v3q"))
                .andRespond(withSuccess("""
                        {"id":"GHSA-jfh8-c2jp-5v3q","summary":"Remote code injection in Log4j",
                         "details":"long text","aliases":["CVE-2021-44228"],
                         "affected":[{"package":{"name":"org.apache.logging.log4j:log4j-core","ecosystem":"Maven"},
                                      "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.13.0"},{"fixed":"2.15.0"}]}]}],
                         "database_specific":{"severity":"CRITICAL","cwe_ids":["CWE-502"]}}""",
                        MediaType.APPLICATION_JSON));

        OsvVulnerability vulnerability = client.getVulnerability("GHSA-jfh8-c2jp-5v3q");

        assertThat(vulnerability.summary()).isEqualTo("Remote code injection in Log4j");
        assertThat(vulnerability.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(vulnerability.cveIds()).containsExactly("CVE-2021-44228");
    }

    @Test
    void wrapsUpstreamErrors() {
        server.expect(requestTo("https://osv.test/v1/vulns/X")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getVulnerability("X")).isInstanceOf(OsvUnavailableException.class);
    }
}
