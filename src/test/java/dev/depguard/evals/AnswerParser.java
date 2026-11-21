package dev.depguard.evals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the agent's Markdown answer into something the checks can grade: its sections and numbered items.
 *
 * <p>The system prompt fixes the answer format ("## Upgrade first", "## Can wait", "## Upgrade notes"),
 * which is what makes code-based checks possible at all. Parsing is lenient (bold markers, "->" or "→").
 */
final class AnswerParser {

    private static final Pattern HEADING = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("(?m)^\\s*\\d+\\.\\s+");
    /** "→ 2.25.4", "-> **2.25.4**". The version is the first token after the arrow. */
    private static final Pattern RECOMMENDED = Pattern.compile("(?:→|->)\\s*\\**\\s*`?([0-9][\\w.\\-]*[\\w])");

    private AnswerParser() {
    }

    /** The text under "## {heading}" up to the next "## ", or "" if the section is missing. */
    static String section(String answer, String heading) {
        Matcher m = HEADING.matcher(answer);
        int start = -1;
        while (m.find()) {
            if (start >= 0) {
                return answer.substring(start, m.start()).strip();
            }
            if (m.group(1).toLowerCase().startsWith(heading.toLowerCase())) {
                start = m.end();
            }
        }
        return start >= 0 ? answer.substring(start).strip() : "";
    }

    /** Numbered list items ("1. ...") with their continuation lines. */
    static List<String> numberedItems(String sectionText) {
        List<String> items = new ArrayList<>();
        Matcher m = NUMBERED.matcher(sectionText);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : sectionText.length();
            items.add(sectionText.substring(starts.get(i), end).strip());
        }
        return items;
    }

    /**
     * Which dependency an item is about: the artifactId mentioned earliest in it. Boundaries matter:
     * "spring-web" must not match inside "spring-webmvc".
     */
    static Optional<String> dependencyOf(String item, Collection<String> artifactIds) {
        String best = null;
        int bestIndex = Integer.MAX_VALUE;
        for (String artifactId : artifactIds) {
            Matcher m = mentionPattern(artifactId).matcher(item);
            if (m.find() && m.start() < bestIndex) {
                best = artifactId;
                bestIndex = m.start();
            }
        }
        return Optional.ofNullable(best);
    }

    static boolean mentions(String text, String artifactId) {
        return mentionPattern(artifactId).matcher(text).find();
    }

    /** The version after the first arrow in an item, e.g. "log4j-core 2.14.1 → 2.25.4" gives "2.25.4". */
    static Optional<String> recommendedVersion(String item) {
        Matcher m = RECOMMENDED.matcher(item);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static Pattern mentionPattern(String artifactId) {
        return Pattern.compile("(?<![\\w.-])" + Pattern.quote(artifactId) + "(?![\\w-])", Pattern.CASE_INSENSITIVE);
    }
}
