package dev.depguard.agent;

import dev.depguard.osv.Severity;
import java.util.List;

/**
 * Everything the model needs to know about a pom's vulnerabilities, gathered in code before the model runs.
 *
 * <p>Until phase 6 the model fetched this itself with tool calls (OSV, then EPSS and KEV), which cost two extra
 * model round trips, each re-sending the whole conversation. The calls were always the same, so code does them now.
 *
 * @param dependenciesChecked how many dependencies were looked up
 * @param vulnerable          vulnerable dependencies, already ranked most urgent first by DepGuard's rule
 * @param notes               data-quality notes for the model, e.g. "EPSS was unavailable"
 */
public record TriageFacts(int dependenciesChecked, List<VulnerableDependency> vulnerable, List<String> notes) {

    /**
     * @param rank               1 = most urgent. Explicit, because the model re-sorted by severity when only told
     *                           to "keep the order" (found by the evals: CRITICAL H2 above 99.5%-EPSS SnakeYAML)
     * @param recommendedVersion lowest version fixing every listed advisory; null when one has no fix yet
     * @param majorUpgrade       computed, so the model never has to guess whether 2.9 → 2.18 is a major upgrade
     * @param maxEpss            highest EPSS probability (0 to 1) among its CVEs; null if none has a score
     */
    public record VulnerableDependency(int rank, String dependency, String scope, Severity worstSeverity, int advisoryCount,
                                       String recommendedVersion, boolean majorUpgrade, boolean knownExploited,
                                       boolean ransomwareUse, Double maxEpss, List<Advisory> topAdvisories) {
    }

    public record Advisory(String id, Severity severity, String fixedVersion, String summary, List<Cve> cves) {
    }

    /** @param epss probability (0 to 1) of exploitation in the next 30 days; null when EPSS has no score */
    public record Cve(String id, Double epss, boolean knownExploited, boolean ransomwareUse) {
    }

    /** True when any dependency needs a major-version upgrade: only then are migration guides worth searching. */
    public boolean anyMajorUpgrade() {
        return vulnerable.stream().anyMatch(VulnerableDependency::majorUpgrade);
    }

    /** Every CVE the model is shown: the citation guardrail accepts exactly these (plus RAG results). */
    public List<String> cveIds() {
        return vulnerable.stream()
                .flatMap(d -> d.topAdvisories().stream())
                .flatMap(a -> a.cves().stream())
                .map(Cve::id)
                .distinct()
                .toList();
    }
}
