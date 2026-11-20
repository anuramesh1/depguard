package dev.depguard.osv;

/** OSV could not be reached or answered with something unexpected. Maps to HTTP 503. */
public class OsvUnavailableException extends RuntimeException {

    public OsvUnavailableException(String message) {
        super(message);
    }

    public OsvUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
