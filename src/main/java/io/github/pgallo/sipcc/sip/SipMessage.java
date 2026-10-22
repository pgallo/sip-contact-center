package io.github.pgallo.sipcc.sip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A SIP request or response. Headers keep their wire order and are stored one value per entry,
 * so a comma-separated Via line becomes several entries; lookups are case-insensitive and use the
 * canonical long form produced by {@link HeaderNames}.
 */
public final class SipMessage {

    public record Header(String name, String value) {}

    private final String method;
    private final String requestUri;
    private final int status;
    private final String reason;
    private final List<Header> headers = new ArrayList<>();
    private byte[] body = new byte[0];

    private SipMessage(String method, String requestUri, int status, String reason) {
        this.method = method;
        this.requestUri = requestUri;
        this.status = status;
        this.reason = reason;
    }

    public static SipMessage request(String method, String requestUri) {
        return new SipMessage(method, requestUri, 0, null);
    }

    public static SipMessage response(int status, String reason) {
        return new SipMessage(null, null, status, reason);
    }

    /** Builds a response that copies the headers RFC 3261 section 8.2.6 requires from the request. */
    public static SipMessage responseTo(SipMessage request, int status, String reason) {
        SipMessage r = response(status, reason);
        for (Header h : request.headers) {
            switch (h.name()) {
                case "Via", "From", "To", "Call-ID", "CSeq" -> r.headers.add(h);
                case "Record-Route" -> {
                    if (status > 100) {
                        r.headers.add(h);
                    }
                }
                default -> { }
            }
        }
        return r;
    }

    public boolean isRequest() {
        return method != null;
    }

    public String method() {
        return method;
    }

    public String requestUri() {
        return requestUri;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public SipMessage withRequestUri(String uri) {
        SipMessage m = request(method, uri);
        m.headers.addAll(headers);
        m.body = body;
        return m;
    }

    public SipMessage copy() {
        SipMessage m = new SipMessage(method, requestUri, status, reason);
        m.headers.addAll(headers);
        m.body = body;
        return m;
    }

    // ---- headers

    public List<Header> headers() {
        return List.copyOf(headers);
    }

    public String header(String name) {
        String n = HeaderNames.canonical(name);
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(n)) {
                return h.value();
            }
        }
        return null;
    }

    public List<String> headerValues(String name) {
        String n = HeaderNames.canonical(name);
        List<String> out = new ArrayList<>();
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(n)) {
                out.add(h.value());
            }
        }
        return out;
    }

    public SipMessage addHeader(String name, String value) {
        headers.add(new Header(HeaderNames.canonical(name), value));
        return this;
    }

    /** Inserts before the first header with the same name, or at the top: used for Via and Record-Route. */
    public SipMessage addHeaderFirst(String name, String value) {
        String n = HeaderNames.canonical(name);
        int at = 0;
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).name().equalsIgnoreCase(n)) {
                at = i;
                break;
            }
        }
        headers.add(at, new Header(n, value));
        return this;
    }

    public SipMessage setHeader(String name, String value) {
        String n = HeaderNames.canonical(name);
        int at = -1;
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (headers.get(i).name().equalsIgnoreCase(n)) {
                headers.remove(i);
                at = i;
            }
        }
        if (at < 0) {
            headers.add(new Header(n, value));
        } else {
            headers.add(at, new Header(n, value));
        }
        return this;
    }

    public boolean removeFirst(String name) {
        String n = HeaderNames.canonical(name);
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).name().equalsIgnoreCase(n)) {
                headers.remove(i);
                return true;
            }
        }
        return false;
    }

    public void removeAll(String name) {
        String n = HeaderNames.canonical(name);
        headers.removeIf(h -> h.name().equalsIgnoreCase(n));
    }

    // ---- typed accessors

    public String callId() {
        return header("Call-ID");
    }

    public CSeq cseq() {
        return CSeq.parse(header("CSeq"));
    }

    public Via topVia() {
        String v = header("Via");
        return v == null ? null : Via.parse(v);
    }

    public void replaceTopVia(Via via) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).name().equals("Via")) {
                headers.set(i, new Header("Via", via.toString()));
                return;
            }
        }
    }

    public NameAddr from() {
        return NameAddr.parse(header("From"));
    }

    public NameAddr to() {
        return NameAddr.parse(header("To"));
    }

    // ---- body

    public byte[] body() {
        return body.clone();
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public SipMessage setBody(String contentType, String text) {
        this.body = text.getBytes(StandardCharsets.UTF_8);
        setHeader("Content-Type", contentType);
        return this;
    }

    void setRawBody(byte[] raw) {
        this.body = raw;
    }

    // ---- encoding

    public byte[] encode() {
        StringBuilder sb = new StringBuilder(256 + body.length);
        if (isRequest()) {
            sb.append(method).append(' ').append(requestUri).append(" SIP/2.0\r\n");
        } else {
            sb.append("SIP/2.0 ").append(status).append(' ').append(reason).append("\r\n");
        }
        for (Header h : headers) {
            if (!h.name().equals("Content-Length")) {
                sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    @Override
    public String toString() {
        return new String(encode(), StandardCharsets.UTF_8);
    }

    /** One-line form for logs. */
    public String summary() {
        return isRequest() ? method + " " + requestUri : status + " " + reason + " (" + header("CSeq") + ")";
    }
}
