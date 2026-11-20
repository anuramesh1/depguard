package dev.depguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.depguard.dependency.PomParser;
import dev.depguard.osv.OsvClient;
import dev.depguard.osv.OsvVulnerability;
import dev.depguard.osv.OsvVulnerability.Affected;
import dev.depguard.osv.OsvVulnerability.Event;
import dev.depguard.osv.OsvVulnerability.PackageRef;
import dev.depguard.osv.OsvVulnerability.Range;
import dev.depguard.osv.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanServiceTest {

    private static final String POM = """
            <project><dependencies>
              <dependency><groupId>g</groupId><artifactId>low-risk</artifactId><version>1.0</version></dependency>
              <dependency><groupId>g</groupId><artifactId>safe</artifactId><version>1.0</version></dependency>
              <dependency><groupId>g</groupId><artifactId>critical</artifactId><version>1.0</version></dependency>
            </dependencies></project>
            """;

    private final OsvClient osv = mock(OsvClient.class);
    private final ScanService service = new ScanService(new PomParser(), osv);

    @Test
    void reportsVulnerableDependenciesMostSevereFirst() {
        when(osv.findVulnerabilityIds(anyList())).thenReturn(List.of(
                List.of("LOW-1"), List.of(), List.of("CRIT-1", "LOW-1")));
        when(osv.getVulnerability("LOW-1")).thenReturn(advisory("LOW-1", "LOW", Map.of("g:low-risk", "1.1", "g:critical", "1.2")));
        when(osv.getVulnerability("CRIT-1")).thenReturn(advisory("CRIT-1", "CRITICAL", Map.of("g:critical", "2.0")));

        ScanReport report = service.scan(POM);

        assertThat(report.dependenciesScanned()).isEqualTo(3);
        assertThat(report.vulnerableDependencies()).isEqualTo(2);
        Finding first = report.findings().getFirst();
        assertThat(first.dependency().artifactId()).isEqualTo("critical");
        assertThat(first.worstSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(first.recommendedVersion()).isEqualTo("2.0"); // clears both CRIT-1 (2.0) and LOW-1 (1.2)
        assertThat(report.findings().get(1).dependency().artifactId()).isEqualTo("low-risk");

        verify(osv, times(1)).getVulnerability("LOW-1"); // shared advisory fetched once
    }

    @Test
    void noRecommendationWhenAnAdvisoryHasNoFix() {
        List<VulnerabilitySummary> vulnerabilities = List.of(
                new VulnerabilitySummary("A", List.of(), "", Severity.HIGH, "1.5"),
                new VulnerabilitySummary("B", List.of(), "", Severity.LOW, null));

        assertThat(ScanService.recommendedUpgrade(vulnerabilities)).isNull();
    }

    private static OsvVulnerability advisory(String id, String severity, Map<String, String> fixByPackage) {
        List<Affected> affected = fixByPackage.entrySet().stream()
                .map(e -> new Affected(new PackageRef(e.getKey(), "Maven"),
                        List.of(new Range("ECOSYSTEM", List.of(new Event("0", null), new Event(null, e.getValue()))))))
                .toList();
        return new OsvVulnerability(id, "summary " + id, List.of(), affected, Map.of("severity", severity));
    }
}
