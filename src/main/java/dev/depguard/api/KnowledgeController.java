package dev.depguard.api;

import dev.depguard.rag.HybridRetriever;
import dev.depguard.rag.SearchHit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Search the knowledge base directly, without the agent: useful to see what retrieval finds, and why.
 *
 * <pre>
 * curl 'localhost:8080/api/knowledge/search?q=Text4Shell+StringSubstitutor'
 * </pre>
 *
 * Each result shows its rank in the semantic and keyword searches and the fused RRF score, which makes
 * the value of hybrid search visible: exact terms (CVE IDs, class names) come from the keyword side,
 * paraphrases from the semantic side. Needs the API key when deployed (each search embeds the query).
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    static final int MAX_QUERY_CHARS = 300;
    static final int EXCERPT_CHARS = 300;

    private final HybridRetriever retriever;

    public KnowledgeController(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    @GetMapping("/search")
    public List<Result> search(@RequestParam String q, @RequestParam(defaultValue = "5") int limit) {
        if (q.isBlank() || q.length() > MAX_QUERY_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must be 1 to 300 characters");
        }
        return retriever.search(q, limit).stream().map(Result::from).toList();
    }

    public record Result(String title, String url, String source, Long vectorRank, Long keywordRank, double score,
                         String excerpt) {
        static Result from(SearchHit hit) {
            String excerpt = hit.text().length() <= EXCERPT_CHARS ? hit.text() : hit.text().substring(0, EXCERPT_CHARS) + "…";
            return new Result(hit.title(), hit.url(), hit.source(), hit.vectorRank(), hit.keywordRank(),
                    Math.round(hit.score() * 10_000) / 10_000.0, excerpt);
        }
    }
}
