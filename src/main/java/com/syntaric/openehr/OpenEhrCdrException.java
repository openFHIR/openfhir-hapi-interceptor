package com.syntaric.openehr;

import java.net.http.HttpHeaders;

/**
 * Thrown when an OpenEHR CDR HTTP call returns a non-2xx status or fails entirely.
 * Carries enough detail to populate a diagnostic DeviceRequest.
 */
public class OpenEhrCdrException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final HttpHeaders responseHeaders;

    public OpenEhrCdrException(final String message, final int statusCode,
                               final String responseBody, final HttpHeaders responseHeaders) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
    }

    public OpenEhrCdrException(final String message, final Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
        this.responseHeaders = null;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public HttpHeaders getResponseHeaders() { return responseHeaders; }

    public String toDetailString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("status=").append(statusCode < 0 ? "N/A" : statusCode);
        if (responseBody != null) sb.append("\nbody=").append(responseBody);
        if (responseHeaders != null) sb.append("\nheaders=").append(responseHeaders.map());
        if (getCause() != null) sb.append("\ncause=").append(getCause().getMessage());
        return sb.toString();
    }
}
