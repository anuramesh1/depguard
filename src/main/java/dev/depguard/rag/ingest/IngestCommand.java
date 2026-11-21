package dev.depguard.rag.ingest;

import dev.depguard.rag.AdvisoryStore;
import dev.depguard.rag.RagProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs ingestion once and exits, when the app starts with the "ingest" profile (see scripts/ingest.sh).
 *
 * <p>Why a separate run instead of an endpoint: a full ingest takes minutes, longer than API Gateway's
 * 30-second limit, and it should never be triggered by a public URL. Because the Data API is reachable
 * with IAM credentials from anywhere, the same command fills the local Postgres or the Aurora cluster.
 */
@Component
@Profile("ingest")
class IngestCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestCommand.class);

    private final IngestService ingestService;

    IngestCommand(RagProperties properties, EmbeddingModel embeddingModel, AdvisoryStore store, JsonMapper jsonMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.ingestService = new IngestService(properties.ingest(), new GuideFeed(httpClient),
                new OsvAdvisoryFeed(httpClient, jsonMapper), embeddingModel, store);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        IngestService.Report report = ingestService.ingest();
        log.info("Ingest finished in {} s: {} chunks, {} embedded, {} unchanged, {} failed, {} deleted",
                report.durationMs() / 1000, report.total(), report.embedded(), report.unchanged(), report.failed(),
                report.deleted());
        if (report.failed() > 0) {
            // A non-zero exit tells the script (and CI) something went wrong; re-running retries only these.
            throw new IllegalStateException(report.failed() + " chunks failed to embed. Run the ingest again to retry them.");
        }
    }
}
