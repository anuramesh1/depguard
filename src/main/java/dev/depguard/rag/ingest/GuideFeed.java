package dev.depguard.rag.ingest;

import dev.depguard.rag.KnowledgeChunk;
import dev.depguard.rag.RagProperties.Guide;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Downloads the configured migration guides and splits each into section chunks.
 *
 * <p>The guides are fetched at ingest time, not copied into this repo: they belong to their projects,
 * and fetching keeps them current.
 */
public class GuideFeed {

    /** Guides name CVEs rarely, but when they do the citation guardrail should know them. */
    private static final Pattern CVE_ID = Pattern.compile("CVE-\\d{4}-\\d{4,}");

    private final HttpClient httpClient;

    public GuideFeed(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<KnowledgeChunk> fetch(Guide guide) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(guide.rawUrl()).timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(guide.id() + " returned HTTP " + response.statusCode());
        }
        return toChunks(guide, response.body());
    }

    static List<KnowledgeChunk> toChunks(Guide guide, String document) {
        List<DocumentChunker.Section> sections = DocumentChunker.split(document);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            DocumentChunker.Section section = sections.get(i);
            String title = section.headingPath().isBlank()
                    ? guide.title()
                    : guide.title() + " › " + section.headingPath();
            List<String> cves = CVE_ID.matcher(section.text()).results().map(match -> match.group()).distinct().toList();
            chunks.add(new KnowledgeChunk(
                    "guide:" + guide.id() + "#" + i,
                    KnowledgeChunk.SOURCE_GUIDE,
                    title,
                    guide.pageUrl(),
                    section.text(),
                    cves));
        }
        return chunks;
    }
}
