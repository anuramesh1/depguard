package dev.depguard.dependency;

/** The uploaded text is not a pom.xml we can read. Maps to HTTP 400. */
public class PomParseException extends RuntimeException {

    public PomParseException(String message) {
        super(message);
    }

    public PomParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
