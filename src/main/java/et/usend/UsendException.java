package et.usend;

/**
 * An error from the usend API: its HTTP status code, error name and message (the API's
 * {@code { statusCode, name, message }} shape). Network failures use name {@code application_error}
 * and status code 0.
 */
public class UsendException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorName;

    public UsendException(String message, String errorName, int statusCode) {
        super(message);
        this.errorName = errorName;
        this.statusCode = statusCode;
    }

    public UsendException(String message, String errorName, int statusCode, Throwable cause) {
        super(message, cause);
        this.errorName = errorName;
        this.statusCode = statusCode;
    }

    /** HTTP status, or 0 when no response was received. */
    public int getStatusCode() { return statusCode; }

    /** The API's error name, for example {@code validation_error} or {@code rate_limit_exceeded}. */
    public String getErrorName() { return errorName; }

    @Override
    public String toString() {
        return "UsendException{statusCode=" + statusCode + ", name=" + errorName + ", message=" + getMessage() + "}";
    }
}
