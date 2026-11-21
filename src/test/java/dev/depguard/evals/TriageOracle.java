package dev.depguard.evals;

import dev.depguard.agent.TriageFacts;
import dev.depguard.agent.TriageFactsService;
import dev.depguard.dependency.Dependency;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out the correct triage for a pom from the same live data the agent sees, in plain code.
 *
 * <p>It gathers the same facts the agent is shown (OSV advisories, EPSS and KEV per CVE) and applies the ranking
 * rule itself: known exploited (KEV) first, then EPSS exploit probability; test scope matters less.
 * The checks then compare the agent's answer with this.
 */
final class TriageOracle {

    /** EPSS differences smaller than this are treated as a tie: either order is fine. */
    static final double EPSS_TIE = 0.05;
    /** A pair only counts for ranking when the order is clear-cut: KEV differs, or EPSS differs this much. */
    static final double CLEAR_EPSS_GAP = 0.2;

    private final PomParser pomParser;
    private final TriageFactsService factsService;

    TriageOracle(PomParser pomParser, TriageFactsService factsService) {
        this.pomParser = pomParser;
        this.factsService = factsService;
    }

    /** One CVE as the agent is shown it. {@code epss} is 0..1, or null if EPSS has no score. */
    record CveFact(String cve, String severity, String fixedVersion, Double epss, boolean knownExploited) {
    }

    /** What we know about one vulnerable dependency, from the data. */
    record DependencyFacts(String artifactId, String coordinates, String scope, String recommendedVersion,
                           int advisoryCount, boolean knownExploited, boolean ransomware, double maxEpss,
                           List<CveFact> cveFacts) {
        boolean testScope() {
            return "test".equals(scope);
        }

        String currentVersion() {
            return coordinates.substring(coordinates.lastIndexOf(':') + 1);
        }

        /** Computed, so the judge doesn't have to reason about version numbers (it got 5 → 6 wrong). */
        boolean majorUpgrade() {
            return recommendedVersion != null && !majorOf(currentVersion()).equals(majorOf(recommendedVersion));
        }

        private static String majorOf(String version) {
            return version.split("[.\\-]")[0];
        }
    }

    /**
     * @param vulnerable  vulnerable dependencies, in the correct order (most urgent first)
     * @param clean       artifactIds of checked dependencies with no advisories
     * @param skipped     dependencies the agent can't check (no version)
     */
    record Expected(List<DependencyFacts> vulnerable, Set<String> clean, List<String> skipped) {

        /** Dependencies that are acceptable as the #1 pick (ties allowed). Empty if nothing is vulnerable. */
        Set<String> acceptableTopPicks() {
            List<DependencyFacts> pool = vulnerable.stream().anyMatch(d -> !d.testScope())
                    ? vulnerable.stream().filter(d -> !d.testScope()).toList()
                    : vulnerable;
            boolean anyKev = pool.stream().anyMatch(DependencyFacts::knownExploited);
            List<DependencyFacts> tier = pool.stream().filter(d -> d.knownExploited() == anyKev).toList();
            double best = tier.stream().mapToDouble(DependencyFacts::maxEpss).max().orElse(0);
            return tier.stream().filter(d -> d.maxEpss() >= best - EPSS_TIE)
                    .map(DependencyFacts::artifactId).collect(Collectors.toSet());
        }

        /** True when {@code a} should clearly come before {@code b}. Test-scoped pairs are not judged. */
        static boolean clearlyBefore(DependencyFacts a, DependencyFacts b) {
            if (a.testScope() || b.testScope()) {
                return false;
            }
            if (a.knownExploited() != b.knownExploited()) {
                return a.knownExploited();
            }
            return a.maxEpss() - b.maxEpss() >= CLEAR_EPSS_GAP;
        }

        Set<String> allArtifactIds() {
            Set<String> ids = new java.util.HashSet<>(clean);
            vulnerable.forEach(d -> ids.add(d.artifactId()));
            return ids;
        }
    }

    /**
     * Uses the same fact gathering as the agent (so it judges the answer against exactly what the model was shown),
     * but re-derives the ranking here, independently of the agent's code.
     */
    Expected expectedFor(String pomXml) {
        ParsedPom pom = pomParser.parse(pomXml);
        Map<String, Dependency> byCoordinates = pom.dependencies().stream()
                .collect(Collectors.toMap(d -> d.groupId() + ":" + d.artifactId() + ":" + d.version(),
                        Function.identity(), (a, b) -> a));
        TriageFacts facts = factsService.gather(pom.dependencies());

        List<DependencyFacts> vulnerable = facts.vulnerable().stream().map(v -> {
            Dependency dep = byCoordinates.get(v.dependency());
            List<CveFact> cveFacts = v.topAdvisories().stream()
                    .flatMap(a -> a.cves().stream().map(c -> new CveFact(c.id(), String.valueOf(a.severity()),
                            a.fixedVersion(), c.epss(), c.knownExploited())))
                    .toList();
            boolean exploited = cveFacts.stream().anyMatch(CveFact::knownExploited);
            boolean ransomware = v.topAdvisories().stream().flatMap(a -> a.cves().stream()).anyMatch(TriageFacts.Cve::ransomwareUse);
            double maxEpss = cveFacts.stream().mapToDouble(c -> c.epss() == null ? 0 : c.epss()).max().orElse(0);
            return new DependencyFacts(dep.artifactId(), v.dependency(), dep.scope(), v.recommendedVersion(),
                    v.advisoryCount(), exploited, ransomware, maxEpss, cveFacts);
        }).sorted(Comparator
                .comparing(DependencyFacts::testScope)                      // non-test first
                .thenComparing(DependencyFacts::knownExploited, Comparator.reverseOrder())
                .thenComparing(DependencyFacts::maxEpss, Comparator.reverseOrder()))
                .toList();

        Set<String> vulnerableIds = vulnerable.stream().map(DependencyFacts::artifactId).collect(Collectors.toSet());
        Set<String> clean = pom.dependencies().stream().map(Dependency::artifactId)
                .filter(id -> !vulnerableIds.contains(id)).collect(Collectors.toSet());
        return new Expected(vulnerable, clean, pom.skipped());
    }
}
