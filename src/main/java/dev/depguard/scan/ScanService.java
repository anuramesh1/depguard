package dev.depguard.scan;

import dev.depguard.dependency.Dependency;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
import dev.depguard.osv.OsvClient;
import dev.depguard.osv.OsvUnavailableException;
import dev.depguard.osv.OsvVulnerability;
import dev.depguard.osv.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.springframework.stereotype.Service;

/**
 * Scans a pom.xml: parse it, ask OSV which dependencies are vulnerable, and build a report.
 * No LLM here yet. Phase 2 gives this logic to an agent as tools.
 */
@Service
public class ScanService {

    private final PomParser pomParser;
    private final OsvClient osvClient;

    public ScanService(PomParser pomParser, OsvClient osvClient) {
        this.pomParser = pomParser;
        this.osvClient = osvClient;
    }

    public ScanReport scan(String pomXml) {
        ParsedPom pom = pomParser.parse(pomXml);
        List<Dependency> dependencies = pom.dependencies();

        // 1. One batched call: advisory IDs for every dependency.
        List<List<String>> idsPerDependency = osvClient.findVulnerabilityIds(dependencies);

        // 2. Fetch each distinct advisory once (two dependencies can share an advisory).
        Set<String> uniqueIds = idsPerDependency.stream()
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, OsvVulnerability> advisories = fetchAdvisories(uniqueIds);

        // 3. Build one finding per vulnerable dependency, most severe first.
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < dependencies.size(); i++) {
            List<String> ids = idsPerDependency.get(i);
            if (!ids.isEmpty()) {
                findings.add(toFinding(dependencies.get(i), ids, advisories));
            }
        }
        findings.sort(Comparator.comparing(Finding::worstSeverity));

        return new ScanReport(dependencies.size(), findings.size(), findings, pom.skipped(), Instant.now());
    }

    /**
     * Fetches advisories in parallel. Java 21 virtual threads make "one thread per HTTP call" cheap,
     * so this is plain blocking code with no reactive framework.
     */
    private Map<String, OsvVulnerability> fetchAdvisories(Set<String> ids) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<OsvVulnerability>> pending = new LinkedHashMap<>();
            for (String id : ids) {
                pending.put(id, executor.submit(() -> osvClient.getVulnerability(id)));
            }
            Map<String, OsvVulnerability> result = new HashMap<>();
            for (Map.Entry<String, Future<OsvVulnerability>> entry : pending.entrySet()) {
                result.put(entry.getKey(), await(entry.getValue()));
            }
            return result;
        }
    }

    private Finding toFinding(Dependency dependency, List<String> ids, Map<String, OsvVulnerability> advisories) {
        List<VulnerabilitySummary> vulnerabilities = ids.stream()
                .map(advisories::get)
                .filter(Objects::nonNull)
                .map(v -> new VulnerabilitySummary(
                        v.id(),
                        v.cveIds(),
                        v.summary(),
                        v.severity(),
                        v.fixedVersionFor(dependency).orElse(null)))
                .sorted(Comparator.comparing(VulnerabilitySummary::severity))
                .toList();

        Severity worst = vulnerabilities.isEmpty() ? Severity.UNKNOWN : vulnerabilities.getFirst().severity();
        return new Finding(dependency, worst, vulnerabilities, recommendedUpgrade(vulnerabilities));
    }

    /** The highest of the per-advisory fixed versions clears them all; null if any advisory is unfixed. */
    static String recommendedUpgrade(List<VulnerabilitySummary> vulnerabilities) {
        if (vulnerabilities.isEmpty() || vulnerabilities.stream().anyMatch(v -> v.fixedVersion() == null)) {
            return null;
        }
        return vulnerabilities.stream()
                .map(v -> new ComparableVersion(v.fixedVersion()))
                .max(Comparator.naturalOrder())
                .map(ComparableVersion::toString)
                .orElse(null);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime; // keep OsvUnavailableException so the API returns 503
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OsvUnavailableException("Interrupted while calling OSV", e);
        }
    }
}
