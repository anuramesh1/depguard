package dev.depguard.scan;

import java.time.Instant;
import java.util.List;

/** The response of POST /api/scans. Findings are sorted most severe first. */
public record ScanReport(
        int dependenciesScanned,
        int vulnerableDependencies,
        List<Finding> findings,
        List<String> skipped,
        Instant scannedAt) {
}
