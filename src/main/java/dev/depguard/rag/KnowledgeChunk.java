package dev.depguard.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * One searchable piece of text in the knowledge base: a whole OSV advisory, or one section of a migration guide.
 *
 * @param id      stable ID, so re-ingesting updates rows instead of duplicating them (e.g. "GHSA-jfh8-c2jp-5v3q",
 *                "guide:spring-boot-3.0-migration#12")
 * @param source  "osv" or "guide"
 * @param title   short human-readable title (shown in answers as the source)
 * @param url     where a person can read the original
 * @param text    the text that is embedded and keyword-indexed
 * @param cveIds  CVE IDs this chunk is about (the citation guardrail treats these as known)
 */
public record KnowledgeChunk(String id, String source, String title, String url, String text, List<String> cveIds) {

    public static final String SOURCE_OSV = "osv";
    public static final String SOURCE_GUIDE = "guide";

    /** Fingerprint of the content. Ingestion skips chunks whose hash hasn't changed, so re-runs cost nothing. */
    public String contentHash() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String content = title + "\n" + url + "\n" + String.join(",", cveIds) + "\n" + text;
            return HexFormat.of().formatHex(sha256.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    /** What gets embedded: the title carries meaning too ("Jakarta EE", "Log4Shell"). */
    public String textForEmbedding() {
        return title + "\n\n" + text;
    }
}
