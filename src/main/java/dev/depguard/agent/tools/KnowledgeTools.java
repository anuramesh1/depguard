package dev.depguard.agent.tools;

import dev.depguard.agent.TriageSession;
import dev.depguard.rag.HybridRetriever;
import dev.depguard.rag.SearchHit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent tool: search DepGuard's knowledge base (RAG).
 *
 * <p>This grounds the "Upgrade notes" part of an answer: instead of relying on what the model remembers
 * about Spring Boot 3 or Jackson 3, it reads the official migration guides. Every source it reads is
 * recorded, so the API response can list them.
 */
@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);
    /** Enough text to be useful, small enough that 5 results stay ~1,000 tokens. */
    static final int MAX_EXCERPT_CHARS = 700;

    private final HybridRetriever retriever;
    private final int topK;

    public KnowledgeTools(HybridRetriever retriever, @Value("${depguard.rag.top-k:5}") int topK) {
        this.retriever = retriever;
        this.topK = topK;
    }

    @Tool(description = """
            Searches DepGuard's knowledge base: official migration guides (Spring Boot 3 and 4, Spring Framework 6
            and 7, Jackson 3) and the text of every OSV advisory for Maven packages. Uses hybrid keyword + semantic
            search, so include exact names (e.g. "jakarta servlet Spring Boot 3 migration", "Text4Shell
            commons-text StringSubstitutor"). Use it to explain what an upgrade involves.""")
    public SearchResult searchAdvisories(
            @ToolParam(description = "What to look for, in a few specific words") String query,
            ToolContext toolContext) {
        TriageSession session = TriageSession.from(toolContext);
        if (!session.recordToolCall("searchAdvisories(\"" + query + "\")")) {
            return SearchResult.unavailable(TriageSession.BUDGET_EXHAUSTED);
        }
        try {
            List<SearchHit> hits = retriever.search(query, topK);
            hits.forEach(hit -> {
                session.recordCves(hit.cveIds());
                session.recordSource(hit.title() + " (" + hit.url() + ")");
            });
            return new SearchResult(hits.stream().map(KnowledgeTools::toResult).toList(), null);
        } catch (RuntimeException e) {
            // e.g. local Postgres not running, or Aurora resuming from auto-pause
            log.warn("Knowledge base search failed: {}", e.getMessage());
            return SearchResult.unavailable(
                    "The knowledge base is unavailable right now; use general upgrade knowledge and say so.");
        }
    }

    private static Result toResult(SearchHit hit) {
        String excerpt = hit.text().length() <= MAX_EXCERPT_CHARS
                ? hit.text()
                : hit.text().substring(0, MAX_EXCERPT_CHARS) + "…";
        return new Result(hit.title(), hit.url(), excerpt);
    }

    /** One search result as the model sees it. */
    public record Result(String title, String url, String excerpt) {
    }

    public record SearchResult(List<Result> results, String error) {
        static SearchResult unavailable(String message) {
            return new SearchResult(List.of(), message);
        }
    }
}
