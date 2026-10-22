package io.github.pgallo.sipcc.sip;

/** The CSeq header: a sequence number below 2^31 and a method. */
public record CSeq(long seq, String method) {

    public static CSeq parse(String value) {
        if (value == null) {
            throw new SipParseException("missing CSeq");
        }
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2) {
            throw new SipParseException("malformed CSeq: " + value);
        }
        long seq;
        try {
            seq = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new SipParseException("malformed CSeq number: " + value);
        }
        if (seq < 0 || seq >= (1L << 31)) {
            throw new SipParseException("CSeq out of range: " + value);
        }
        return new CSeq(seq, parts[1]);
    }

    @Override
    public String toString() {
        return seq + " " + method;
    }
}
