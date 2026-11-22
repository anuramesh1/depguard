package dev.depguard.api;

import dev.depguard.agent.TriageAgent;
import dev.depguard.agent.TriageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST a pom.xml, get an AI triage: what to upgrade first and why.
 *
 * <pre>
 * curl -X POST 'localhost:8080/api/triage?question=What+should+I+fix+this+week' \
 *      -H 'Content-Type: application/xml' --data-binary @samples/vulnerable-pom.xml
 * </pre>
 *
 * When deployed, this endpoint needs a Cognito access token with the VIEWER or MAINTAINER role (see SecurityConfig): every call costs model tokens.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

    static final int MAX_QUESTION_CHARS = 500;

    private final TriageAgent triageAgent;

    public TriageController(TriageAgent triageAgent) {
        this.triageAgent = triageAgent;
    }

    @PostMapping(
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TriageResult triage(@RequestBody String pomXml, @RequestParam(required = false) String question) {
        if (pomXml.length() > ScanController.MAX_POM_CHARS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "pom.xml is larger than 1 MB");
        }
        if (question != null && question.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is longer than 500 characters");
        }
        return triageAgent.triage(pomXml, question);
    }
}
