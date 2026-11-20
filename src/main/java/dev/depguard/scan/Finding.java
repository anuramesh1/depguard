package dev.depguard.scan;

import dev.depguard.dependency.Dependency;
import dev.depguard.osv.Severity;
import java.util.List;

/**
 * A vulnerable dependency.
 *
 * @param recommendedVersion lowest version that fixes every listed advisory, or null if at least
 *                           one advisory has no fix yet
 */
public record Finding(
        Dependency dependency,
        Severity worstSeverity,
        List<VulnerabilitySummary> vulnerabilities,
        String recommendedVersion) {
}
