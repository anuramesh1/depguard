package dev.depguard.api;

import dev.depguard.scan.ScanReport;
import dev.depguard.scan.ScanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST a pom.xml, get a vulnerability report.
 *
 * <pre>curl -X POST localhost:8080/api/scans -H 'Content-Type: application/xml' --data-binary @samples/vulnerable-pom.xml</pre>
 */
@RestController
@RequestMapping("/api/scans")
public class ScanController {

    /** Real poms are a few KB; this guards against someone posting a huge file. */
    static final int MAX_POM_CHARS = 1_000_000;

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping(
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ScanReport scan(@RequestBody String pomXml) {
        if (pomXml.length() > MAX_POM_CHARS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "pom.xml is larger than 1 MB");
        }
        return scanService.scan(pomXml);
    }
}
