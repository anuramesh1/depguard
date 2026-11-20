package dev.depguard.osv;

import java.util.Locale;

/** Declared worst-first, so sorting by this enum puts the most urgent items at the top. */
public enum Severity {
    CRITICAL, HIGH, MODERATE, LOW, UNKNOWN;

    /** Reads the GitHub-advisory severity label that OSV passes through ("HIGH", "MODERATE", ...). */
    public static Severity from(String label) {
        if (label == null || label.isBlank()) {
            return UNKNOWN;
        }
        String normalised = label.trim().toUpperCase(Locale.ROOT);
        if (normalised.equals("MEDIUM")) {
            return MODERATE;
        }
        try {
            return valueOf(normalised);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
