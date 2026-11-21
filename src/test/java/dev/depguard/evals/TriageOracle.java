package dev.depguard.evals;

import dev.depguard.agent.tools.VulnerabilityTools;
import dev.depguard.agent.tools.VulnerabilityTools.VulnerabilityResult;
import dev.depguard.dependency.Dependency;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
import dev.depguard.exploit.EpssClient;
import dev.depguard.exploit.EpssClient.EpssScore;
import dev.depguard.exploit.KevClient;
import dev.depguard.exploit.KevClient.KevStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out the correct triage for a pom from the same live data the agent sees, in plain code.
 *
 * <p>It calls the agent's own findVulnerabilities tool (so it sees exactly the advisories the model sees),
 * then EPSS and KEV for those CVEs, and applies the ranking rule from the system prompt:
 * known exploited (KEV) first, then EPSS exploit probability; test scope matters less.
 * The checks then compare the agent's answer with this.
 */
final class TriageOracle {

    /** EPSS differences smaller than this are treated as a tie: either order is fine. */
    static final double EPSS_TIE = 0.05;
    /** A pair only counts for ranking when the order is clear-cut: KEV differs, or EPSS differs this much. */
    static final double CLEAR_EPSS_GAP = 0.2;

    private final PomParser pomParser;
    private final VulnerabilityTools vulnerabilityTools;
    private final EpssClient epssClient;
    private final KevClient kevClient;

    TriageOracle(PomParser pomParser, VulnerabilityTools vulnerabilityTools, EpssClient epssClient, KevClient kevClient) {
        this.pomParser = pomParser;
        this.vulnerabilityTools = vulnerabilityTools;
        this.epssClient = epssClient;
        this.kevClient = kevClient;
    }

    /** One CVE as the agent's tools report it. {@code epss} is 0..1, or null if EPSS has no score. */
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

    Expected expectedFor(String pomXml) {
        ParsedPom pom = pomParser.parse(pomXml);
        Map<String, Dependency> byCoordinates = pom.dependencies().stream()
                .collect(Collectors.toMap(d -> d.groupId() + ":" + d.artifactId() + ":" + d.version(),
                        Function.identity(), (a, b) -> a));
        VulnerabilityResult result = vulnerabilityTools.findVulnerabilities(List.copyOf(byCoordinates.keySet()), null);

        List<String> allCves = result.vulnerable().stream()
                .flatMap(v -> v.topAdvisories().stream()).flatMap(a -> a.cveIds().stream()).distinct().toList();
        Map<String, Double> epss = allCves.isEmpty() ? Map.of() : epssClient.scores(allCves).stream()
                .collect(Collectors.toMap(EpssScore::cve, EpssScore::probability, (a, b) -> a));
        Map<String, KevStatus> kev = allCves.isEmpty() ? Map.of() : kevClient.lookup(allCves).stream()
                .collect(Collectors.toMap(KevStatus::cve, Function.identity(), (a, b) -> a));

        List<DependencyFacts> vulnerable = result.vulnerable().stream().map(v -> {
            Dependency dep = byCoordinates.get(v.dependency());
            List<String> cves = v.topAdvisories().stream().flatMap(a -> a.cveIds().stream()).distinct().toList();
            List<CveFact> cveFacts = v.topAdvisories().stream()
                    .flatMap(a -> a.cveIds().stream().map(c -> new CveFact(c, String.valueOf(a.severity()),
                            a.fixedVersion(), epss.get(c), kev.containsKey(c) && kev.get(c).knownExploited())))
                    .toList();
            boolean exploited = cves.stream().anyMatch(c -> kev.containsKey(c) && kev.get(c).knownExploited());
            boolean ransomware = cves.stream().anyMatch(c -> kev.containsKey(c) && "Known".equalsIgnoreCase(kev.get(c).ransomwareUse()));
            double maxEpss = cves.stream().mapToDouble(c -> epss.getOrDefault(c, 0.0)).max().orElse(0);
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
