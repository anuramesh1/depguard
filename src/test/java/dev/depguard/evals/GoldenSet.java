package dev.depguard.evals;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The golden sets: hand-written eval cases, loaded from src/test/resources/evals/. */
final class GoldenSet {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GoldenSet() {
    }

    /**
     * One triage case. The expected answer is NOT written here: {@link TriageOracle} derives it from live
     * OSV, EPSS and KEV data at eval time, so the golden set doesn't go stale when advisories change. What is
     * written here is what the data can't say: what the answer must mention, and what it must never say.
     *
     * @param id           short name, used in reports
     * @param why          what this case tests
     * @param dependencies "groupId:artifactId:version[:scope]"; a missing version means "managed by a BOM"
     * @param question     optional; null uses the agent's default question
     * @param mustMention  regexes the answer must contain (case-insensitive)
     * @param mustNotMatch regexes the answer must never contain: safety and known failure modes
     * @param expectTriage false when declining is the right answer (off-topic question): then only the
     *                     safety checks apply
     * @param smoke        part of the small, cheap smoke tier (-Devals.tier=smoke) used while iterating on prompts
     */
    record TriageCase(String id, String why, List<String> dependencies, String question,
                      List<String> mustMention, List<String> mustNotMatch, Boolean expectTriage, Boolean smoke) {

        TriageCase {
            expectTriage = expectTriage == null || expectTriage;
            smoke = smoke != null && smoke;
            mustMention = mustMention == null ? List.of() : mustMention;
            mustNotMatch = mustNotMatch == null ? List.of() : mustNotMatch;
        }

        String pomXml() {
            String deps = dependencies.stream().map(TriageCase::dependencyXml).collect(Collectors.joining("\n"));
            return "<project><dependencies>\n" + deps + "\n</dependencies></project>";
        }

        private static String dependencyXml(String coordinates) {
            String[] p = coordinates.split(":");
            StringBuilder xml = new StringBuilder("  <dependency><groupId>").append(p[0])
                    .append("</groupId><artifactId>").append(p[1]).append("</artifactId>");
            if (p.length > 2 && !p[2].isBlank()) {
                xml.append("<version>").append(p[2]).append("</version>");
            }
            if (p.length > 3) {
                xml.append("<scope>").append(p[3]).append("</scope>");
            }
            return xml.append("</dependency>").toString();
        }
    }

    /**
     * One retrieval case. Relevance is defined by the data, not by what search returns: either "chunks whose
     * advisory aliases include this CVE", or "guide sections whose title contains one of these phrases".
     *
     * @param kind          "exact-id", "description" or "guide": reported separately
     * @param relevantCve   relevant = advisories listing this CVE
     * @param relevantTitles relevant = guide sections whose title contains any of these
     */
    record RetrievalCase(String query, String kind, String relevantCve, List<String> relevantTitles) {
    }

    static List<TriageCase> triageCases() {
        return load("/evals/triage-golden.json", new TypeReference<>() { });
    }

    static List<RetrievalCase> retrievalCases() {
        return load("/evals/retrieval-golden.json", new TypeReference<>() { });
    }

    private static <T> T load(String resource, TypeReference<T> type) {
        try (InputStream in = GoldenSet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
