package dev.depguard.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.evals.GoldenSet.RetrievalCase;
import dev.depguard.rag.AdvisoryStore;
import dev.depguard.rag.HybridSearchSql;
import dev.depguard.rag.RagProperties;
import dev.depguard.rag.SearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Measures retrieval quality on its own, and answers "is hybrid search actually better?" with numbers.
 *
 * <pre>
 * docker compose up -d postgres && scripts/ingest.sh local      # the full local knowledge base
 * ./mvnw test -Pevals -Dtest=RetrievalEval                       # ~30 query embeddings: well under $0.01
 * </pre>
 *
 * One hybrid query returns both candidate lists (up to 40 each) with their ranks, so the same call also
 * yields the vector-only and keyword-only rankings: no extra SQL needed to compare the three.
 *
 * <p>Metrics: <b>hit@k</b> = share of queries with a relevant result in the top k;
 * <b>MRR@10</b> = mean of 1/rank of the first relevant result (0 if not in the top 10).
 */
@Tag("eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetrievalEval {

    /** The build fails if hybrid search falls below this. Set from the first measured run, with some margin. */
    static final double MIN_HYBRID_HIT_AT_5 = 0.80;

    @Autowired EmbeddingModel embeddingModel;
    @Autowired AdvisoryStore store;
    @Autowired RagProperties properties;

    /** Keyword weights tried in the fusion sweep; 1.0 is plain RRF, what the SQL does. */
    static final double[] KEYWORD_WEIGHTS = {0.25, 0.5, 0.75, 1.0, 1.5};

    /** @param weighted rank of the first relevant hit for each keyword weight in {@link #KEYWORD_WEIGHTS} */
    record Ranks(Integer hybrid, Integer vector, Integer keyword, Integer[] weighted) {
    }

    @Test
    void retrievalGoldenSet() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(new Driver(), properties.jdbc().url(),
                properties.jdbc().username(), properties.jdbc().password()));
        Integer chunks = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Integer.class);
        assertThat(chunks).as("knowledge base is empty: run scripts/ingest.sh local first").isGreaterThan(1000);

        Map<RetrievalCase, Ranks> results = new LinkedHashMap<>();
        for (RetrievalCase c : GoldenSet.retrievalCases()) {
            Set<String> relevant = relevantIds(jdbc, c);
            assertThat(relevant).as("golden case has no relevant chunk in the index: " + c.query()).isNotEmpty();

            float[] embedding = embeddingModel.embed(c.query());
            List<SearchHit> hits = store.search(c.query(), embedding, 2 * HybridSearchSql.CANDIDATES_PER_SEARCH);
            List<SearchHit> vectorOnly = hits.stream().filter(h -> h.vectorRank() != null)
                    .sorted(Comparator.comparing(SearchHit::vectorRank)).toList();
            List<SearchHit> keywordOnly = hits.stream().filter(h -> h.keywordRank() != null)
                    .sorted(Comparator.comparing(SearchHit::keywordRank)).toList();
            Integer[] weighted = new Integer[KEYWORD_WEIGHTS.length];
            for (int i = 0; i < KEYWORD_WEIGHTS.length; i++) {
                weighted[i] = firstRelevant(fuse(hits, KEYWORD_WEIGHTS[i]), relevant);
            }
            results.put(c, new Ranks(firstRelevant(hits, relevant), firstRelevant(vectorOnly, relevant),
                    firstRelevant(keywordOnly, relevant), weighted));
        }

        String markdown = report(results);
        Path dir = Path.of("target", "evals");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("retrieval-scorecard.md"), markdown);
        System.out.println(markdown);

        double hybridHitAt5 = hitAt(results.values().stream().map(Ranks::hybrid).toList(), 5);
        assertThat(hybridHitAt5).as("hybrid hit@5").isGreaterThanOrEqualTo(MIN_HYBRID_HIT_AT_5);
    }

    /** Relevance comes from the data, never from search results: CVE aliases, or guide section titles. */
    private static Set<String> relevantIds(JdbcTemplate jdbc, RetrievalCase c) {
        if (c.relevantCve() != null) {
            return Set.copyOf(jdbc.queryForList(
                    "SELECT id FROM knowledge_chunk WHERE (',' || cve_ids || ',') LIKE ?", String.class,
                    "%," + c.relevantCve() + ",%"));
        }
        Set<String> ids = new java.util.HashSet<>();
        for (String title : c.relevantTitles()) {
            ids.addAll(jdbc.queryForList("SELECT id FROM knowledge_chunk WHERE source = 'guide' AND title ILIKE ?",
                    String.class, "%" + title + "%"));
        }
        return ids;
    }

    /**
     * Weighted RRF, recomputed in Java from the two ranks each hit carries: 1/(60 + vectorRank) +
     * w/(60 + keywordRank). Lets the report compare fusion weights without changing the SQL.
     */
    private static List<SearchHit> fuse(List<SearchHit> hits, double keywordWeight) {
        Function<SearchHit, Double> score = h ->
                (h.vectorRank() == null ? 0 : 1.0 / (HybridSearchSql.RRF_K + h.vectorRank()))
                + (h.keywordRank() == null ? 0 : keywordWeight / (HybridSearchSql.RRF_K + h.keywordRank()));
        return hits.stream().sorted(Comparator.comparing(score).reversed()).toList();
    }

    /** 1-based rank of the first relevant hit, or null if none was returned. */
    private static Integer firstRelevant(List<SearchHit> ranked, Set<String> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i).id())) {
                return i + 1;
            }
        }
        return null;
    }

    private static double hitAt(List<Integer> ranks, int k) {
        return ranks.stream().filter(r -> r != null && r <= k).count() / (double) ranks.size();
    }

    private static double mrrAt10(List<Integer> ranks) {
        return ranks.stream().mapToDouble(r -> r != null && r <= 10 ? 1.0 / r : 0).average().orElse(0);
    }

    private static String report(Map<RetrievalCase, Ranks> results) {
        StringBuilder md = new StringBuilder("# Retrieval eval scorecard\n\n");
        md.append(results.size()).append(" queries. Relevance is defined by the data (CVE aliases, guide titles).\n\n");
        md.append("| Query kind | Queries | Mode | hit@1 | hit@5 | MRR@10 |\n|---|---|---|---|---|---|\n");
        Map<String, List<Ranks>> byKind = new LinkedHashMap<>();
        byKind.put("all", new ArrayList<>(results.values()));
        results.forEach((c, r) -> byKind.computeIfAbsent(c.kind(), k -> new ArrayList<>()).add(r));
        Map<String, Function<Ranks, Integer>> modes = new LinkedHashMap<>();
        modes.put("vector only", Ranks::vector);
        modes.put("keyword only", Ranks::keyword);
        modes.put("**hybrid (RRF)**", Ranks::hybrid);
        byKind.forEach((kind, ranks) -> modes.forEach((mode, get) -> {
            List<Integer> r = ranks.stream().map(get).toList();
            md.append(String.format(Locale.ROOT, "| %s | %d | %s | %.2f | %.2f | %.2f |%n",
                    kind, ranks.size(), mode, hitAt(r, 1), hitAt(r, 5), mrrAt10(r)));
        }));

        md.append("\n## Fusion weight sweep (keyword weight in RRF; 1.0 = what the SQL uses)\n\n");
        md.append("| Keyword weight | all hit@5 | all MRR@10 | exact-id hit@5 | description hit@5 | guide hit@5 |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (int i = 0; i < KEYWORD_WEIGHTS.length; i++) {
            int w = i;
            Function<String, List<Integer>> ranksOf = kind -> byKind.getOrDefault(kind, List.of()).stream()
                    .map(r -> r.weighted()[w]).toList();
            md.append(String.format(Locale.ROOT, "| %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |%n", KEYWORD_WEIGHTS[i],
                    hitAt(ranksOf.apply("all"), 5), mrrAt10(ranksOf.apply("all")), hitAt(ranksOf.apply("exact-id"), 5),
                    hitAt(ranksOf.apply("description"), 5), hitAt(ranksOf.apply("guide"), 5)));
        }

        md.append("\n## Per query (rank of the first relevant result; — = not found)\n\n");
        md.append("| Kind | Query | Vector | Keyword | Hybrid |\n|---|---|---|---|---|\n");
        results.forEach((c, r) -> md.append(String.format("| %s | %s | %s | %s | %s |%n", c.kind(), c.query(),
                show(r.vector()), show(r.keyword()), show(r.hybrid()))));
        md.append("\nMisses in hybrid top 5: ").append(results.entrySet().stream()
                .filter(e -> e.getValue().hybrid() == null || e.getValue().hybrid() > 5)
                .map(e -> "\"" + e.getKey().query() + "\"").collect(Collectors.joining(", ", "", "")))
                .append(results.values().stream().allMatch(r -> r.hybrid() != null && r.hybrid() <= 5) ? "none" : "")
                .append('\n');
        return md.toString();
    }

    private static String show(Integer rank) {
        return rank == null ? "—" : rank.toString();
    }
}
