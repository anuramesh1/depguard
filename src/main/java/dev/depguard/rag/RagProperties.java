package dev.depguard.rag;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code depguard.rag}.
 *
 * @param store    where the knowledge base lives: "jdbc" (local Postgres) or "data-api" (Aurora on AWS)
 * @param topK     how many search results the agent's tool returns
 * @param jdbc     local Postgres connection
 * @param dataApi  Aurora cluster and secret, set by Terraform on Lambda and by scripts/ingest.sh
 * @param ingest   what ingestion indexes and how fast
 */
@ConfigurationProperties("depguard.rag")
public record RagProperties(String store, int topK, Jdbc jdbc, DataApi dataApi, Ingest ingest) {

    public static final String STORE_JDBC = "jdbc";
    public static final String STORE_DATA_API = "data-api";

    public record Jdbc(String url, String username, String password) {
    }

    public record DataApi(String clusterArn, String secretArn, String database) {
    }

    /**
     * @param osvDumpUrl     OSV's daily export of every Maven advisory (one zip of JSON files)
     * @param guides         migration guides to index (Markdown or AsciiDoc), fetched at ingest time
     * @param maxAdvisories  0 = all; a small number makes a quick local trial cheap
     * @param concurrency    embedding calls in flight at once (Bedrock throttles above its quota)
     */
    public record Ingest(URI osvDumpUrl, List<Guide> guides, int maxAdvisories, int concurrency) {
    }

    /**
     * A guide to index.
     *
     * @param id      prefix for its chunk IDs
     * @param title   shown as the source in answers
     * @param rawUrl  plain-text Markdown/AsciiDoc to download
     * @param pageUrl the page a person would read (cited in answers)
     */
    public record Guide(String id, String title, URI rawUrl, String pageUrl) {
    }
}
