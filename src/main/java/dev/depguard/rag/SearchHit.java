package dev.depguard.rag;

import java.util.List;

/**
 * One hybrid-search result.
 *
 * <p>The two ranks show <em>why</em> it was found: {@code vectorRank} is its position in the semantic
 * (embedding) results, {@code keywordRank} its position in the full-text results; null means that search
 * didn't find it. {@code score} is the Reciprocal Rank Fusion score that combines both.
 */
public record SearchHit(
        String id,
        String source,
        String title,
        String url,
        String text,
        List<String> cveIds,
        Long vectorRank,
        Long keywordRank,
        double score) {
}
