package dev.depguard.agent;

import dev.depguard.agent.TriageFacts.Advisory;
import dev.depguard.agent.TriageFacts.Cve;
import dev.depguard.agent.TriageFacts.VulnerableDependency;
import dev.depguard.dependency.Dependency;
import dev.depguard.exploit.EpssClient;
import dev.depguard.exploit.EpssClient.EpssScore;
import dev.depguard.exploit.KevClient;
import dev.depguard.exploit.KevClient.KevStatus;
import dev.depguard.scan.Finding;
import dev.depguard.scan.ScanReport;
import dev.depguard.scan.ScanService;
import dev.depguard.scan.VulnerabilitySummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gathers the triage facts in code: OSV findings, then EPSS and CISA KEV for their CVEs, ranked by DepGuard's rule.
 *
 * <p>Why code and not the model: these lookups are the same for every triage, so letting the model ask for them
 * costs two round trips (each re-sending the growing conversation) and leaves the ranking to the model, which
 * sometimes got it wrong. The model now gets the facts already ranked and only writes the explanation, deciding
 * itself when to search the migration guides. "Use an LLM only for what code can't do."
 */
@Service
public class TriageFactsService {

    private static final Logger log = LoggerFactory.getLogger(TriageFactsService.class);
    /** Most severe advisories shown per dependency; the count says how many exist in total. Keeps prompts small. */
    static final int TOP_ADVISORIES = 5;
    static final int MAX_SUMMARY_CHARS = 160;

    /**
     * DepGuard's ranking rule, the same one the system prompt used to ask the model to apply: compile/runtime scope
     * before test scope, then known exploited (CISA KEV), then exploit probability (EPSS), then severity.
     */
    static final Comparator<VulnerableDependency> MOST_URGENT_FIRST = Comparator
            .comparing((VulnerableDependency d) -> "test".equals(d.scope()))
            .thenComparing(VulnerableDependency::knownExploited, Comparator.reverseOrder())
            .thenComparing(d -> d.maxEpss() == null ? 0.0 : d.maxEpss(), Comparator.reverseOrder())
            .thenComparing(VulnerableDependency::worstSeverity);

    /**
     * Release trains: every artifact in these groups is released with the same version, and mixing versions breaks at
     * runtime (spring-webmvc 7 with spring-beans 5 won't start). The model was told this and still mixed them in 2 of
     * 2 eval runs, so the facts align them instead. Other groups (e.g. org.apache.commons) version each artifact
     * independently and are left alone.
     */
    static final java.util.Set<String> RELEASE_TRAINS = java.util.Set.of(
            "org.springframework", "com.fasterxml.jackson.core", "org.apache.logging.log4j", "io.netty");

    private final ScanService scanService;
    private final EpssClient epssClient;
    private final KevClient kevClient;

    public TriageFactsService(ScanService scanService, EpssClient epssClient, KevClient kevClient) {
        this.scanService = scanService;
        this.epssClient = epssClient;
        this.kevClient = kevClient;
    }

    public TriageFacts gather(List<Dependency> dependencies) {
        ScanReport report = scanService.scanDependencies(dependencies, List.of());
        List<String> notes = new ArrayList<>();
        List<String> cves = report.findings().stream()
                .flatMap(f -> f.vulnerabilities().stream().limit(TOP_ADVISORIES))
                .flatMap(v -> v.cveIds().stream())
                .distinct()
                .toList();

        // If a feed is down, carry on without it and tell the model, so the answer can say so.
        Map<String, Double> epss = Map.of();
        Map<String, KevStatus> kev = Map.of();
        if (!cves.isEmpty()) {
            try {
                epss = epssClient.scores(cves).stream()
                        .collect(Collectors.toMap(EpssScore::cve, EpssScore::probability, (a, b) -> a));
            } catch (RuntimeException e) {
                log.warn("EPSS lookup failed", e);
                notes.add("EPSS exploit probabilities were unavailable; rank without them and say so.");
            }
            try {
                kev = kevClient.lookup(cves).stream()
                        .collect(Collectors.toMap(KevStatus::cve, Function.identity(), (a, b) -> a));
            } catch (RuntimeException e) {
                log.warn("KEV lookup failed", e);
                notes.add("The CISA KEV catalog was unavailable; say that known exploitation could not be checked.");
            }
        }

        Map<String, Double> epssByCve = epss;
        Map<String, KevStatus> kevByCve = kev;
        List<VulnerableDependency> ranked = alignReleaseTrains(report.findings().stream()
                .map(finding -> toFacts(finding, epssByCve, kevByCve))
                .toList()).stream()
                .sorted(MOST_URGENT_FIRST)
                .toList();
        List<VulnerableDependency> vulnerable = java.util.stream.IntStream.range(0, ranked.size())
                .mapToObj(i -> withRank(ranked.get(i), i + 1))
                .toList();
        return new TriageFacts(report.dependenciesScanned(), vulnerable, notes);
    }

    /** Same release train, same recommended version: the highest one any of its vulnerable artifacts needs. */
    static List<VulnerableDependency> alignReleaseTrains(List<VulnerableDependency> dependencies) {
        Map<String, String> highest = new java.util.HashMap<>();
        for (VulnerableDependency d : dependencies) {
            String group = d.dependency().split(":")[0];
            if (RELEASE_TRAINS.contains(group) && d.recommendedVersion() != null) {
                highest.merge(group, d.recommendedVersion(), (a, b) ->
                        new org.apache.maven.artifact.versioning.ComparableVersion(a)
                                .compareTo(new org.apache.maven.artifact.versioning.ComparableVersion(b)) >= 0 ? a : b);
            }
        }
        return dependencies.stream().map(d -> {
            String target = highest.get(d.dependency().split(":")[0]);
            if (target == null || target.equals(d.recommendedVersion())) {
                return d;
            }
            String current = d.dependency().substring(d.dependency().lastIndexOf(':') + 1);
            return new VulnerableDependency(d.rank(), d.dependency(), d.scope(), d.worstSeverity(), d.advisoryCount(),
                    target, isMajorUpgrade(current, target), d.knownExploited(), d.ransomwareUse(), d.maxEpss(),
                    d.topAdvisories());
        }).toList();
    }

    private static VulnerableDependency withRank(VulnerableDependency d, int rank) {
        return new VulnerableDependency(rank, d.dependency(), d.scope(), d.worstSeverity(), d.advisoryCount(),
                d.recommendedVersion(), d.majorUpgrade(), d.knownExploited(), d.ransomwareUse(), d.maxEpss(),
                d.topAdvisories());
    }

    private static VulnerableDependency toFacts(Finding finding, Map<String, Double> epss, Map<String, KevStatus> kev) {
        Dependency d = finding.dependency();
        List<Advisory> top = finding.vulnerabilities().stream()
                .limit(TOP_ADVISORIES)
                .map(v -> toAdvisory(v, epss, kev))
                .toList();
        List<Cve> cves = top.stream().flatMap(a -> a.cves().stream()).toList();
        Double maxEpss = cves.stream().map(Cve::epss).filter(e -> e != null).max(Double::compare).orElse(null);
        return new VulnerableDependency(
                0, // ranked after sorting
                d.groupId() + ":" + d.artifactId() + ":" + d.version(),
                d.scope(),
                finding.worstSeverity(),
                finding.vulnerabilities().size(),
                finding.recommendedVersion(),
                isMajorUpgrade(d.version(), finding.recommendedVersion()),
                cves.stream().anyMatch(Cve::knownExploited),
                cves.stream().anyMatch(Cve::ransomwareUse),
                maxEpss,
                top);
    }

    private static Advisory toAdvisory(VulnerabilitySummary v, Map<String, Double> epss, Map<String, KevStatus> kev) {
        String summary = v.summary() == null ? "" : v.summary();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS) + "…";
        }
        List<Cve> cves = v.cveIds().stream().map(id -> {
            KevStatus status = kev.get(id);
            return new Cve(id, round4(epss.get(id)), status != null && status.knownExploited(),
                    status != null && "Known".equalsIgnoreCase(status.ransomwareUse()));
        }).toList();
        return new Advisory(v.id(), v.severity(), v.fixedVersion(), summary, cves);
    }

    /**
     * EPSS arrives with up to 5 decimals (0.99977). Four (0.9998 = 99.98%) are plenty, keep prompts shorter, and give
     * the model and the eval judge the same number to quote: the judge flagged "99.977%" against its rounded "99.98%".
     */
    static Double round4(Double probability) {
        return probability == null ? null : Math.round(probability * 10_000) / 10_000.0;
    }

    /** "2.9.8" → "2.18.11" is not a major upgrade; "5.3.20" → "6.1.14" is. */
    static boolean isMajorUpgrade(String current, String recommended) {
        if (current == null || recommended == null) {
            return false;
        }
        return !current.split("[.\\-]")[0].equals(recommended.split("[.\\-]")[0]);
    }
}
