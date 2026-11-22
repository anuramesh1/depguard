package dev.depguard.api;

import dev.depguard.dependency.PomParseException;
import dev.depguard.osv.OsvUnavailableException;
import dev.depguard.upgrade.UpgradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.rdsdata.model.RdsDataException;

/**
 * Turns our exceptions into RFC 9457 "problem detail" JSON responses.
 * Internal details (stack traces, upstream URLs) are logged, never sent to the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PomParseException.class)
    ProblemDetail invalidPom(PomParseException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid pom.xml");
        return problem;
    }

    @ExceptionHandler(OsvUnavailableException.class)
    ProblemDetail osvUnavailable(OsvUnavailableException e) {
        log.warn("OSV call failed", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The vulnerability database is unavailable. Try again shortly.");
        problem.setTitle("Upstream unavailable");
        return problem;
    }

    /**
     * Knowledge-base errors: local Postgres not running, or Aurora unreachable or resuming. More specific than
     * SdkException below, so Spring picks this handler for Data API errors.
     */
    @ExceptionHandler({DataAccessException.class, RdsDataException.class})
    ProblemDetail knowledgeBaseUnavailable(RuntimeException e) {
        log.warn("Knowledge base call failed", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The knowledge base is unavailable right now. Try again shortly.");
        problem.setTitle("Knowledge base unavailable");
        return problem;
    }

    /** Upgrade-proposal rules and states (four eyes, already decided, pom changed...): the message is safe to show. */
    @ExceptionHandler(UpgradeService.UpgradeException.class)
    ProblemDetail upgradeRule(UpgradeService.UpgradeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setTitle("Upgrade not possible");
        return problem;
    }

    /** GitHub API errors (bad token, missing repo): details only in the logs. */
    @ExceptionHandler(org.springframework.web.client.RestClientResponseException.class)
    ProblemDetail gitHubFailed(org.springframework.web.client.RestClientResponseException e) {
        log.warn("GitHub call failed: HTTP {}", e.getStatusCode());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "GitHub rejected the request. Check the repository and token configuration.");
        problem.setTitle("GitHub unavailable");
        return problem;
    }

    /** Bedrock errors: throttling, no model access, timeouts, expired credentials. */
    @ExceptionHandler(SdkException.class)
    ProblemDetail modelUnavailable(SdkException e) {
        log.warn("Bedrock call failed", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The AI model is unavailable right now. Try again shortly.");
        problem.setTitle("Model unavailable");
        return problem;
    }
}
