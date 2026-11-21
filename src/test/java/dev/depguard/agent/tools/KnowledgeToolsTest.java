package dev.depguard.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.depguard.agent.TriageSession;
import dev.depguard.rag.HybridRetriever;
import dev.depguard.rag.SearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class KnowledgeToolsTest {

    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final KnowledgeTools tools = new KnowledgeTools(retriever, 5);
    private final TriageSession session = new TriageSession();
    private final ToolContext context = new ToolContext(Map.of("triageSession", session));

    @Test
    void returnsShortExcerptsAndRecordsSourcesAndCves() {
        String longText = "Replace javax.* with jakarta.* ".repeat(50);
        when(retriever.search("jakarta migration", 5)).thenReturn(List.of(
                new SearchHit("guide:sb3#4", "guide", "Spring Boot 3.0 Migration Guide › Jakarta EE",
                        "https://example/sb3", longText, List.of(), 1L, 2L, 0.032),
                new SearchHit("GHSA-1", "osv", "GHSA-1: RCE", "https://osv.dev/vulnerability/GHSA-1", "short",
                        List.of("CVE-2022-22965"), null, 1L, 0.016)));

        KnowledgeTools.SearchResult result = tools.searchAdvisories("jakarta migration", context);

        assertThat(result.error()).isNull();
        assertThat(result.results()).hasSize(2);
        assertThat(result.results().getFirst().excerpt()).hasSize(KnowledgeTools.MAX_EXCERPT_CHARS + 1).endsWith("…");
        assertThat(session.sources()).containsExactly(
                "Spring Boot 3.0 Migration Guide › Jakarta EE (https://example/sb3)",
                "GHSA-1: RCE (https://osv.dev/vulnerability/GHSA-1)");
        assertThat(session.knownCves()).containsExactly("CVE-2022-22965");
    }

    @Test
    void aDatabaseOutageBecomesAMessageForTheModel() {
        when(retriever.search(anyString(), anyInt())).thenThrow(new IllegalStateException("Connection refused"));

        KnowledgeTools.SearchResult result = tools.searchAdvisories("anything", context);

        assertThat(result.results()).isEmpty();
        assertThat(result.error()).contains("knowledge base is unavailable");
    }
}
