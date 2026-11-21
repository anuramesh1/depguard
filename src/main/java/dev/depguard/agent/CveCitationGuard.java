package dev.depguard.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardrail: every CVE ID in the answer must have come from a tool result in this request.
 *
 * <p>LLMs can produce plausible-looking but wrong CVE IDs. This is a cheap, deterministic check (no
 * second LLM call): anything the tools didn't return is reported as unverified, so the caller can
 * show a warning instead of trusting it.
 */
public final class CveCitationGuard {

    private static final Pattern CVE_ID = Pattern.compile("CVE-\\d{4}-\\d{4,}");

    private CveCitationGuard() {
    }

    public static Result check(String answer, Set<String> knownCves) {
        Set<String> cited = new LinkedHashSet<>();
        Matcher matcher = CVE_ID.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            cited.add(matcher.group());
        }
        List<String> unverified = cited.stream().filter(cve -> !knownCves.contains(cve)).toList();
        return new Result(List.copyOf(cited), unverified);
    }

    /**
     * @param cited      CVE IDs mentioned in the answer, in order of first mention
     * @param unverified the ones no tool returned
     */
    public record Result(List<String> cited, List<String> unverified) {
        public boolean passed() {
            return unverified.isEmpty();
        }
    }
}
