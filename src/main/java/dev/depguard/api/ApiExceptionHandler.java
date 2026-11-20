package dev.depguard.api;

import dev.depguard.dependency.PomParseException;
import dev.depguard.osv.OsvUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
