package io.github.pgallo.sipcc.sip;

/** Thrown for input that does not conform to the supported subset of the RFC 3261 grammar. */
public final class SipParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SipParseException(String message) {
        super(message);
    }
}
