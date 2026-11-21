package dev.depguard.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.rag.KnowledgeChunk;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OsvAdvisoryFeedTest {

    private static final String TEXT4SHELL = """
            {
              "id": "GHSA-599f-7c49-w659",
              "summary": "Arbitrary code execution in Apache Commons Text",
              "details": "Apache Commons Text performs variable interpolation ... StringSubstitutor ...",
              "aliases": ["CVE-2022-42889"],
              "affected": [{
                "package": {"name": "org.apache.commons:commons-text", "ecosystem": "Maven"},
                "ranges": [{"type": "ECOSYSTEM", "events": [{"introduced": "1.5"}, {"fixed": "1.10.0"}]}]
              }],
              "database_specific": {"severity": "CRITICAL"},
              "some_field_we_ignore": true
            }""";

    private static final String WITHDRAWN = """
            {"id": "GHSA-xxxx-withdrawn", "summary": "Published by mistake", "withdrawn": "2024-01-01T00:00:00Z"}""";

    private final OsvAdvisoryFeed feed = new OsvAdvisoryFeed(HttpClient.newHttpClient(), JsonMapper.builder().build());

    @Test
    void turnsAnAdvisoryIntoOneSearchableChunk() throws IOException {
        List<KnowledgeChunk> chunks = feed.read(zip(TEXT4SHELL), 0);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.id()).isEqualTo("GHSA-599f-7c49-w659");
            assertThat(chunk.source()).isEqualTo(KnowledgeChunk.SOURCE_OSV);
            assertThat(chunk.title()).isEqualTo("GHSA-599f-7c49-w659: Arbitrary code execution in Apache Commons Text");
            assertThat(chunk.url()).isEqualTo("https://osv.dev/vulnerability/GHSA-599f-7c49-w659");
            assertThat(chunk.cveIds()).containsExactly("CVE-2022-42889");
            // Exact identifiers go into the text so keyword search can match them.
            assertThat(chunk.text())
                    .contains("CVE-2022-42889", "Severity: CRITICAL",
                            "Affects org.apache.commons:commons-text; fixed in 1.10.0", "StringSubstitutor");
        });
    }

    @Test
    void skipsWithdrawnAdvisoriesAndHonoursTheLimit() throws IOException {
        assertThat(feed.read(zip(WITHDRAWN, TEXT4SHELL), 0)).extracting(KnowledgeChunk::id)
                .containsExactly("GHSA-599f-7c49-w659");
        assertThat(feed.read(zip(TEXT4SHELL, TEXT4SHELL.replace("w659", "aaaa")), 1)).hasSize(1);
    }

    /** Builds an in-memory zip shaped like OSV's export: one JSON file per advisory. */
    private static ZipInputStream zip(String... advisories) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < advisories.length; i++) {
                out.putNextEntry(new ZipEntry("advisory-" + i + ".json"));
                out.write(advisories[i].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
