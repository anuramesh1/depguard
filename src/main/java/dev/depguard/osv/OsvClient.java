package dev.depguard.osv;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.depguard.dependency.Dependency;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the OSV.dev API (free, no API key): https://google.github.io/osv.dev/api/
 *
 * <p>Two calls are used:
 * <ul>
 *   <li>POST /v1/querybatch: which advisory IDs affect each dependency (one call for the whole pom)</li>
 *   <li>GET /v1/vulns/{id}: the full advisory (summary, CVE, severity, fixed versions)</li>
 * </ul>
 */
@Component
public class OsvClient {

    private static final String MAVEN = "Maven";

    private final RestClient restClient;

    public OsvClient(@Qualifier("osvRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Batching: one HTTP call for all dependencies instead of one per dependency.
     * Returns advisory IDs per dependency, in the same order as the input.
     *
     * <p>Limits we accept for phase 1: OSV allows up to 1000 queries per batch, and pages results
     * for packages with very many advisories (we read the first page only).
     */
    public List<List<String>> findVulnerabilityIds(List<Dependency> dependencies) {
        if (dependencies.isEmpty()) {
            return List.of();
        }
        BatchRequest request = new BatchRequest(dependencies.stream()
                .map(d -> new Query(new PackageQuery(d.coordinates(), MAVEN), d.version()))
                .toList());

        BatchResponse response = call(() -> restClient.post()
                .uri("/v1/querybatch")
                .body(request)
                .retrieve()
                .body(BatchResponse.class));

        if (response == null || response.results() == null || response.results().size() != dependencies.size()) {
            throw new OsvUnavailableException("OSV returned an unexpected batch response");
        }
        return response.results().stream()
                .map(result -> result.vulns() == null
                        ? List.<String>of()
                        : result.vulns().stream().map(VulnRef::id).toList())
                .toList();
    }

    public OsvVulnerability getVulnerability(String id) {
        OsvVulnerability vulnerability = call(() -> restClient.get()
                .uri("/v1/vulns/{id}", id)
                .retrieve()
                .body(OsvVulnerability.class));
        if (vulnerability == null) {
            throw new OsvUnavailableException("OSV returned an empty advisory for " + id);
        }
        return vulnerability;
    }

    /** Turns any HTTP/network failure into one exception type the API layer knows how to report. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new OsvUnavailableException("OSV request failed: " + e.getMessage(), e);
        }
    }

    // --- OSV request/response shapes (only what we need) ---

    record BatchRequest(List<Query> queries) {
    }

    record Query(@JsonProperty("package") PackageQuery pkg, String version) {
    }

    record PackageQuery(String name, String ecosystem) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatchResponse(List<Result> results) {
    }

    /** An empty object {} when the dependency has no known advisories. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(List<VulnRef> vulns) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VulnRef(String id) {
    }
}
