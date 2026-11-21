package dev.depguard.rag.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Markdown or AsciiDoc guide into chunks, one per section.
 *
 * <p>Why by section: a migration guide's headings are its natural units ("Jakarta EE", "Logging Date
 * Format"), so one chunk answers one topic, and its heading path makes a meaningful title. Sections
 * longer than {@link #MAX_CHARS} are split at paragraph breaks so each chunk stays focused (a very long
 * chunk's embedding blurs many topics together). Tiny sections, usually just a link, are dropped.
 */
public final class DocumentChunker {

    static final int MAX_CHARS = 2500;
    static final int MIN_CHARS = 80;
    /** "## Title" (Markdown) or "== Title" (AsciiDoc); the number of marks is the level. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6}|={1,6})\\s+(.+?)\\s*$");
    /** Code blocks: ``` (Markdown) or ---- (AsciiDoc). A "# comment" inside one is not a heading. */
    private static final Pattern FENCE = Pattern.compile("^(```|----)\\s*\\S*\\s*$");

    private DocumentChunker() {
    }

    /** One chunk of a guide: where it sits ("Upgrade to Spring Boot 3 › Jakarta EE") and its text. */
    public record Section(String headingPath, String text) {
    }

    public static List<Section> split(String document) {
        List<Section> sections = new ArrayList<>();
        TreeMap<Integer, String> headings = new TreeMap<>(); // level -> title, for the current position
        StringBuilder body = new StringBuilder();
        boolean inCode = false;

        for (String line : document.split("\\R")) {
            if (FENCE.matcher(line).matches()) {
                inCode = !inCode;
            }
            Matcher heading = HEADING.matcher(line);
            if (!inCode && heading.matches()) {
                addSection(sections, headings, body);
                int level = heading.group(1).length();
                headings.tailMap(level, true).clear(); // a new heading closes deeper ones
                headings.put(level, heading.group(2));
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(sections, headings, body);
        return sections;
    }

    private static void addSection(List<Section> sections, TreeMap<Integer, String> headings, StringBuilder body) {
        String text = body.toString().strip();
        if (text.length() < MIN_CHARS) {
            return;
        }
        String path = String.join(" › ", headings.values());
        for (String part : splitLong(text)) {
            sections.add(new Section(path, part));
        }
    }

    /** Packs paragraphs into parts of at most MAX_CHARS (a single huge paragraph is cut hard). */
    private static List<String> splitLong(String text) {
        if (text.length() <= MAX_CHARS) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            if (!current.isEmpty() && current.length() + paragraph.length() + 2 > MAX_CHARS) {
                parts.add(current.toString().strip());
                current.setLength(0);
            }
            while (paragraph.length() > MAX_CHARS) {
                parts.add(paragraph.substring(0, MAX_CHARS));
                paragraph = paragraph.substring(MAX_CHARS);
            }
            current.append(paragraph).append("\n\n");
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().strip());
        }
        return parts;
    }
}
