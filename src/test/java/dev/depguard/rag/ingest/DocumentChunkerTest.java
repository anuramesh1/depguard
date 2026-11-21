package dev.depguard.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.rag.KnowledgeChunk;
import dev.depguard.rag.RagProperties.Guide;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

    private static final String FILLER =
            "This paragraph explains the change, with enough words to count as real content for a reader. ";

    @Test
    void splitsAsciiDocBySectionAndKeepsTheHeadingPath() {
        String doc = """
                == Upgrade to Spring Boot 3
                %s
                === Jakarta EE
                Replace javax.* imports with jakarta.* imports. %s
                === Logging Date Format
                %s
                """.formatted(FILLER, FILLER, FILLER);

        List<DocumentChunker.Section> sections = DocumentChunker.split(doc);

        assertThat(sections).extracting(DocumentChunker.Section::headingPath).containsExactly(
                "Upgrade to Spring Boot 3",
                "Upgrade to Spring Boot 3 › Jakarta EE",
                "Upgrade to Spring Boot 3 › Logging Date Format");
        assertThat(sections.get(1).text()).startsWith("Replace javax.*");
    }

    @Test
    void ignoresHeadingLikeLinesInsideCodeBlocksAndDropsTinySections() {
        String doc = """
                ## Build
                See the link.
                ## Maven
                %s
                ```
                # this is a shell comment, not a heading
                mvn verify
                ```
                """.formatted(FILLER);

        List<DocumentChunker.Section> sections = DocumentChunker.split(doc);

        assertThat(sections).hasSize(1); // "Build" is too short to be useful
        assertThat(sections.getFirst().headingPath()).isEqualTo("Maven");
        assertThat(sections.getFirst().text()).contains("# this is a shell comment");
    }

    @Test
    void splitsLongSectionsAtParagraphBreaks() {
        String paragraph = FILLER.repeat(15); // ~1,200 characters
        String doc = "## Long\n" + paragraph + "\n\n" + paragraph + "\n\n" + paragraph;

        List<DocumentChunker.Section> sections = DocumentChunker.split(doc);

        assertThat(sections).hasSizeGreaterThan(1)
                .allSatisfy(section -> assertThat(section.text().length()).isLessThanOrEqualTo(DocumentChunker.MAX_CHARS));
    }

    @Test
    void guideChunksGetStableIdsTitlesAndTheReadableUrl() {
        Guide guide = new Guide("sb3", "Spring Boot 3.0 Migration Guide", URI.create("https://raw.example/sb3.adoc"),
                "https://example.org/wiki/sb3");

        List<KnowledgeChunk> chunks = GuideFeed.toChunks(guide, "== Jakarta EE\n" + FILLER + " See CVE-2022-22965.");

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.id()).isEqualTo("guide:sb3#0");
            assertThat(chunk.title()).isEqualTo("Spring Boot 3.0 Migration Guide › Jakarta EE");
            assertThat(chunk.url()).isEqualTo("https://example.org/wiki/sb3");
            assertThat(chunk.cveIds()).containsExactly("CVE-2022-22965");
        });
    }
}
