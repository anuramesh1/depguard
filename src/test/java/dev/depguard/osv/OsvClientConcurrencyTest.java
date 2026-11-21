package dev.depguard.osv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * However many advisories are fetched at once, at most {@link OsvClient#MAX_CONCURRENT_REQUESTS} requests reach OSV
 * at the same time (OSV rejects too many concurrent HTTP/2 streams on one connection).
 */
class OsvClientConcurrencyTest {

    @Test
    void neverSendsMoreThanTheLimitAtOnce() throws Exception {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // A fake OSV that takes 20 ms per advisory and records how many requests overlap.
        ClientHttpRequestFactory slowOsv = (uri, method) -> new MockClientHttpRequest(method, uri) {
            @Override
            protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                peak.accumulateAndGet(current.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                InputStream body = new ByteArrayInputStream("{\"id\":\"GHSA-x\"}".getBytes(StandardCharsets.UTF_8));
                MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return response;
            }
        };
        OsvClient client = new OsvClient(RestClient.builder().baseUrl("https://osv.test")
                .requestFactory(slowOsv).defaultHeaders(HttpHeaders::clear).build());

        List<Future<OsvVulnerability>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                results.add(executor.submit(() -> client.getVulnerability("GHSA-x")));
            }
            for (Future<OsvVulnerability> result : results) {
                assertThat(result.get().id()).isEqualTo("GHSA-x");
            }
        }

        assertThat(peak.get()).isLessThanOrEqualTo(OsvClient.MAX_CONCURRENT_REQUESTS).isGreaterThan(1);
    }
}
