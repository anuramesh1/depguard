package dev.depguard.rag.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.depguard.osv.OsvVulnerability.Affected;
import dev.depguard.osv.OsvVulnerability.Event;
import dev.depguard.rag.KnowledgeChunk;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads every Maven advisory from OSV's daily export (one zip of JSON files, ~10 MB, ~7,000 advisories)
 * and turns each into one knowledge chunk.
 *
 * <p>Why the export instead of the API: it's one download instead of thousands of calls, and it's how
 * OSV recommends bulk use. The API stays the source of truth for live scans.
 */
public class OsvAdvisoryFeed {

    /** Advisory "details" can be very long (full write-ups); the start carries most of the meaning. */
    static final int MAX_DETAILS_CHARS = 4000;

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;

    public OsvAdvisoryFeed(HttpClient httpClient, JsonMapper jsonMapper) {
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
    }

    public List<KnowledgeChunk> fetch(URI dumpUrl, int maxAdvisories) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(dumpUrl).timeout(Duration.ofMinutes(2)).build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("OSV export returned HTTP " + response.statusCode());
        }
        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            return read(zip, maxAdvisories);
        }
    }

    /** Reads advisories from the zip; package-private so tests can feed a zip built in memory. */
    List<KnowledgeChunk> read(ZipInputStream zip, int maxAdvisories) throws IOException {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (!entry.getName().endsWith(".json")) {
                continue;
            }
            Advisory advisory = jsonMapper.readValue(zip.readAllBytes(), Advisory.class);
            if (advisory.withdrawn() == null) { // withdrawn advisories were published by mistake
                chunks.add(toChunk(advisory));
            }
            if (maxAdvisories > 0 && chunks.size() >= maxAdvisories) {
                break;
            }
        }
        return chunks;
    }

    static KnowledgeChunk toChunk(Advisory advisory) {
        List<String> cves = nullSafe(advisory.aliases()).stream().filter(alias -> alias.startsWith("CVE-")).toList();
        Map<String, List<String>> fixedVersions = fixedVersionsByPackage(advisory);
        String summary = advisory.summary() == null || advisory.summary().isBlank()
                ? "Vulnerability in " + String.join(", ", fixedVersions.keySet())
                : advisory.summary().strip();

        // The header puts exact identifiers (IDs, package names, versions) into the text, so the keyword half
        // of hybrid search can match them even when the prose never repeats them.
        StringBuilder text = new StringBuilder()
                .append("Advisory ").append(advisory.id());
        if (!cves.isEmpty()) {
            text.append(" (").append(String.join(", ", cves)).append(')');
        }
        text.append(". Severity: ").append(advisory.severity()).append(".\n");
        fixedVersions.forEach((pkg, fixed) -> text.append("Affects ").append(pkg)
                .append(fixed.isEmpty() ? "; no fixed version yet" : "; fixed in " + String.join(", ", fixed))
                .append(".\n"));
        String details = advisory.details() == null ? "" : advisory.details().strip();
        if (details.length() > MAX_DETAILS_CHARS) {
            details = details.substring(0, MAX_DETAILS_CHARS) + " …";
        }
        text.append('\n').append(details);

        return new KnowledgeChunk(
                advisory.id(),
                KnowledgeChunk.SOURCE_OSV,
                advisory.id() + ": " + summary,
                "https://osv.dev/vulnerability/" + advisory.id(),
                text.toString().strip(),
                cves);
    }

    /** "group:artifact" -> fixed versions, in the order OSV lists them. */
    private static Map<String, List<String>> fixedVersionsByPackage(Advisory advisory) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Affected affected : nullSafe(advisory.affected())) {
            if (affected.pkg() == null) {
                continue;
            }
            List<String> fixed = result.computeIfAbsent(affected.pkg().name(), name -> new ArrayList<>());
            nullSafe(affected.ranges()).stream()
                    .flatMap(range -> nullSafe(range.events()).stream())
                    .map(Event::fixed)
                    .filter(Objects::nonNull)
                    .filter(version -> !fixed.contains(version))
                    .forEach(fixed::add);
        }
        return result;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** The fields of an OSV advisory that ingestion needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Advisory(
            String id,
            String summary,
            String details,
            List<String> aliases,
            List<Affected> affected,
            String withdrawn,
            @JsonProperty("database_specific") Map<String, Object> databaseSpecific) {

        String severity() {
            Object label = databaseSpecific == null ? null : databaseSpecific.get("severity");
            return label == null ? "UNKNOWN" : label.toString();
        }
    }
}
