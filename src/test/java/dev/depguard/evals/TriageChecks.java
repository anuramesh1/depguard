package dev.depguard.evals;

import dev.depguard.agent.TriageResult;
import dev.depguard.evals.GoldenSet.TriageCase;
import dev.depguard.evals.TriageOracle.DependencyFacts;
import dev.depguard.evals.TriageOracle.Expected;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Code-based checks of one triage answer against the oracle. Objective, free and repeatable: no LLM involved.
 *
 * <p>Two kinds of check:
 * <ul>
 *   <li><b>Gates</b> must pass on every run (safety and grounding). A failing gate fails the eval build.</li>
 *   <li><b>Tracked</b> checks measure quality as a pass rate (ranking, versions). Models are not deterministic,
 *       so a single failure is information, not necessarily a bug; the trend matters.</li>
 * </ul>
 */
final class TriageChecks {

    /** Outcome of one check. {@code score} is 0..1 for checks that grade several items (e.g. pair ordering). */
    record Check(String name, boolean gate, boolean passed, double score, String detail) {
        static Check of(String name, boolean gate, boolean passed, String detail) {
            return new Check(name, gate, passed, passed ? 1 : 0, detail);
        }
    }

    private TriageChecks() {
    }

    static List<Check> run(TriageCase testCase, Expected expected, TriageResult result) {
        String answer = result.answer() == null ? "" : result.answer();
        String upgradeFirst = AnswerParser.section(answer, "Upgrade first");
        String canWait = AnswerParser.section(answer, "Can wait");
        List<Check> checks = new ArrayList<>();

        // --- Gates: grounding and safety -------------------------------------------------------------
        checks.add(Check.of("cites-only-verified-cves", true, result.unverifiedCves().isEmpty(),
                result.unverifiedCves().isEmpty() ? "" : "unverified: " + result.unverifiedCves()));

        for (String regex : testCase.mustNotMatch()) {
            var m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(answer);
            boolean found = m.find();
            checks.add(Check.of("must-not-say: " + regex, true, !found, found ? "found: \"" + m.group() + "\"" : ""));
        }

        if (!testCase.expectTriage()) {
            return checks; // declining is correct here; only grounding and safety apply
        }

        if (!expected.vulnerable().isEmpty()) {
            List<String> missing = expected.vulnerable().stream()
                    .filter(d -> !mentionsDependency(answer, d))
                    .map(DependencyFacts::artifactId).toList();
            checks.add(Check.of("mentions-every-vulnerable-dependency", true, missing.isEmpty(),
                    missing.isEmpty() ? "" : "missing: " + missing));
        }

        // --- Tracked: quality ----------------------------------------------------------------------------
        List<String> items = AnswerParser.numberedItems(upgradeFirst);
        List<String> itemDeps = items.stream()
                .map(item -> AnswerParser.dependencyOf(item, expected.allArtifactIds()).orElse("?")).toList();

        if (expected.vulnerable().isEmpty()) {
            boolean quiet = expected.clean().stream().noneMatch(id -> AnswerParser.mentions(upgradeFirst, id));
            checks.add(Check.of("nothing-to-upgrade", false, quiet, quiet ? "" : "listed: " + itemDeps));
        } else {
            List<String> falseAlarms = itemDeps.stream().filter(expected.clean()::contains).toList();
            checks.add(Check.of("no-false-alarms", false, falseAlarms.isEmpty(),
                    falseAlarms.isEmpty() ? "" : "clean but listed: " + falseAlarms));

            String top = itemDeps.isEmpty() ? "(none)" : itemDeps.getFirst();
            boolean topOk = expected.acceptableTopPicks().contains(top);
            checks.add(Check.of("top-pick", false, topOk,
                    topOk ? "" : "got " + top + ", expected one of " + expected.acceptableTopPicks()));

            checks.add(pairwiseRanking(expected, itemDeps, canWait));
            checks.add(recommendedVersions(expected, items, itemDeps));
            alignedVersions(expected, items, itemDeps).ifPresent(checks::add);
        }

        for (String regex : testCase.mustMention()) {
            boolean found = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(answer).find();
            checks.add(Check.of("must-mention: " + regex, false, found, found ? "" : "not found"));
        }
        return checks;
    }

    /**
     * The artifactId, or a groupId that ends in it: "com.thoughtworks.xstream 1.4.17 → 1.4.21" names xstream clearly
     * even without ":xstream" (a grader false negative the evals hit in phase 6).
     */
    static boolean mentionsDependency(String answer, DependencyFacts d) {
        String groupId = d.coordinates().split(":")[0];
        return AnswerParser.mentions(answer, d.artifactId())
                || groupId.endsWith("." + d.artifactId()) && answer.contains(groupId);
    }

    /**
     * Of all clear-cut pairs (one is known exploited and the other isn't, or EPSS differs a lot), how many
     * are in the right order? "Upgrade first" items come before "Can wait" items.
     */
    private static Check pairwiseRanking(Expected expected, List<String> upgradeFirstDeps, String canWait) {
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < upgradeFirstDeps.size(); i++) {
            position.putIfAbsent(upgradeFirstDeps.get(i), i);
        }
        int offset = 1000;
        for (DependencyFacts d : expected.vulnerable()) {
            if (!position.containsKey(d.artifactId()) && AnswerParser.mentions(canWait, d.artifactId())) {
                position.put(d.artifactId(), offset++); // "Can wait" ranks below everything in "Upgrade first"
            }
        }
        int pairs = 0;
        int correct = 0;
        List<String> wrong = new ArrayList<>();
        for (DependencyFacts a : expected.vulnerable()) {
            for (DependencyFacts b : expected.vulnerable()) {
                if (a == b || !Expected.clearlyBefore(a, b)) {
                    continue;
                }
                Integer pa = position.get(a.artifactId());
                Integer pb = position.get(b.artifactId());
                if (pa == null || pb == null) {
                    continue;
                }
                pairs++;
                if (pa < pb) {
                    correct++;
                } else {
                    wrong.add(b.artifactId() + " before " + a.artifactId());
                }
            }
        }
        double score = pairs == 0 ? 1 : (double) correct / pairs;
        return new Check("pairwise-ranking", false, wrong.isEmpty(), score,
                pairs == 0 ? "no clear-cut pairs" : correct + "/" + pairs + (wrong.isEmpty() ? "" : "; wrong: " + wrong));
    }

    /**
     * Modules of one framework (same groupId, e.g. org.springframework) must be recommended on the same major
     * version: spring-webmvc 7.x with spring-beans 5.x won't run. Only applies when two or more are listed.
     */
    private static Optional<Check> alignedVersions(Expected expected, List<String> items, List<String> itemDeps) {
        Map<String, String> groupOf = new HashMap<>();
        expected.vulnerable().forEach(d -> groupOf.put(d.artifactId(), d.coordinates().split(":")[0]));
        Map<String, Map<String, String>> majorsByGroup = new HashMap<>(); // groupId -> artifactId -> major
        for (int i = 0; i < items.size(); i++) {
            String group = groupOf.get(itemDeps.get(i));
            Optional<String> version = AnswerParser.recommendedVersion(items.get(i));
            if (group != null && version.isPresent()) {
                majorsByGroup.computeIfAbsent(group, g -> new HashMap<>())
                        .put(itemDeps.get(i), version.get().split("[.\\-]")[0]);
            }
        }
        List<Map<String, String>> groups = majorsByGroup.values().stream().filter(m -> m.size() > 1).toList();
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, String>> mixed = groups.stream().filter(m -> m.values().stream().distinct().count() > 1).toList();
        return Optional.of(Check.of("aligned-versions", false, mixed.isEmpty(),
                mixed.isEmpty() ? "" : "mixed major versions: " + mixed));
    }

    /**
     * Each listed vulnerable dependency's "→ version" must be at least OSV's lowest fixing version (Maven
     * ordering). Higher is fine: aligning framework modules can mean going past one module's own fix.
     */
    private static Check recommendedVersions(Expected expected, List<String> items, List<String> itemDeps) {
        Map<String, DependencyFacts> facts = new HashMap<>();
        expected.vulnerable().forEach(d -> facts.put(d.artifactId(), d));
        int checked = 0;
        int correct = 0;
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DependencyFacts d = facts.get(itemDeps.get(i));
            if (d == null || d.recommendedVersion() == null) {
                continue; // not vulnerable, or no fix released: nothing to compare
            }
            checked++;
            Optional<String> got = AnswerParser.recommendedVersion(items.get(i));
            if (got.isPresent() && new ComparableVersion(got.get()).compareTo(new ComparableVersion(d.recommendedVersion())) >= 0) {
                correct++;
            } else {
                wrong.add(d.artifactId() + ": " + got.orElse("none") + " (needs at least " + d.recommendedVersion() + ")");
            }
        }
        double score = checked == 0 ? 1 : (double) correct / checked;
        return new Check("recommended-versions", false, wrong.isEmpty(), score,
                checked == 0 ? "none to check" : correct + "/" + checked + (wrong.isEmpty() ? "" : "; " + wrong));
    }
}
