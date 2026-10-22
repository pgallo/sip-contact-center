package io.github.pgallo.sipcc.sip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for the subset of the RFC 3261 message grammar used here: request and status lines,
 * header folding, compact names, comma-separated list headers and Content-Length framing.
 * Anything outside the subset is rejected with a {@link SipParseException} instead of guessed at.
 */
public final class SipParser {

    private static final String TOKEN_CHARS = "-.!%*_+`'~";

    private SipParser() {}

    public static SipMessage parse(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return parse(b, b.length);
    }

    public static SipMessage parse(byte[] data, int length) {
        int start = 0;
        while (start + 1 < length && data[start] == '\r' && data[start + 1] == '\n') {
            start += 2; // leading CRLFs are keep-alives (RFC 3261 7.5)
        }
        int headEnd = indexOf(data, start, length, new byte[] {'\r', '\n', '\r', '\n'});
        int sepLen = 4;
        if (headEnd < 0) {
            headEnd = indexOf(data, start, length, new byte[] {'\n', '\n'});
            sepLen = 2;
        }
        if (headEnd < 0) {
            throw new SipParseException("no blank line after headers");
        }
        String head = new String(data, start, headEnd - start, StandardCharsets.UTF_8);
        List<String> lines = unfold(head.split("\r?\n"));
        if (lines.isEmpty() || lines.get(0).isBlank()) {
            throw new SipParseException("empty start line");
        }
        SipMessage msg = startLine(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new SipParseException("malformed header line: " + line);
            }
            String name = line.substring(0, colon).trim();
            if (!isToken(name)) {
                throw new SipParseException("invalid header name: " + name);
            }
            String canonical = HeaderNames.canonical(name);
            String value = line.substring(colon + 1).trim();
            if (HeaderNames.LIST_HEADERS.contains(canonical) && !value.equals("*")) {
                for (String part : splitList(value)) {
                    msg.addHeader(canonical, part);
                }
            } else {
                msg.addHeader(canonical, value);
            }
        }
        validate(msg);

        int bodyStart = headEnd + sepLen;
        int available = length - bodyStart;
        String cl = msg.header("Content-Length");
        int bodyLen = available;
        if (cl != null) {
            try {
                bodyLen = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new SipParseException("malformed Content-Length: " + cl);
            }
            if (bodyLen < 0) {
                throw new SipParseException("negative Content-Length");
            }
            if (bodyLen > available) {
                throw new SipParseException("body shorter than Content-Length (" + available + " < " + bodyLen + ")");
            }
        }
        byte[] body = new byte[bodyLen];
        System.arraycopy(data, bodyStart, body, 0, bodyLen);
        msg.setRawBody(body);
        msg.removeAll("Content-Length");
        return msg;
    }

    private static SipMessage startLine(String line) {
        if (line.startsWith("SIP/")) {
            String[] p = line.split(" ", 3);
            if (p.length < 2 || !p[0].equals("SIP/2.0")) {
                throw new SipParseException("bad status line: " + line);
            }
            int code;
            try {
                code = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                throw new SipParseException("bad status code: " + line);
            }
            if (p[1].length() != 3 || code < 100 || code > 699) {
                throw new SipParseException("status code out of range: " + line);
            }
            return SipMessage.response(code, p.length == 3 ? p[2] : "");
        }
        String[] p = line.split(" ", -1);
        if (p.length != 3) {
            throw new SipParseException("bad request line: " + line);
        }
        if (!isToken(p[0]) || !p[0].equals(p[0].toUpperCase(Locale.ROOT))) {
            throw new SipParseException("bad method: " + p[0]);
        }
        if (!p[2].equals("SIP/2.0")) {
            throw new SipParseException("unsupported version: " + p[2]);
        }
        SipUri.parse(p[1]);
        return SipMessage.request(p[0], p[1]);
    }

    private static void validate(SipMessage m) {
        for (String h : new String[] {"Via", "From", "To", "Call-ID", "CSeq"}) {
            if (m.header(h) == null) {
                throw new SipParseException("missing mandatory header " + h);
            }
        }
        CSeq cseq = m.cseq();
        if (m.isRequest() && !cseq.method().equals(m.method())) {
            throw new SipParseException("CSeq method " + cseq.method() + " does not match " + m.method());
        }
        m.from();
        m.to();
        m.topVia();
        String mf = m.header("Max-Forwards");
        if (mf != null) {
            try {
                Integer.parseInt(mf.trim());
            } catch (NumberFormatException e) {
                throw new SipParseException("malformed Max-Forwards: " + mf);
            }
        }
    }

    private static List<String> unfold(String[] raw) {
        List<String> out = new ArrayList<>();
        for (String l : raw) {
            if (!out.isEmpty() && !l.isEmpty() && (l.charAt(0) == ' ' || l.charAt(0) == '\t')) {
                int last = out.size() - 1;
                out.set(last, out.get(last) + " " + l.trim());
            } else {
                out.add(l);
            }
        }
        return out;
    }

    /** Splits on commas that are not inside quotes or angle brackets. */
    static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int from = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' && (i == 0 || value.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '<') {
                depth++;
            } else if (!quoted && c == '>') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                out.add(value.substring(from, i).trim());
                from = i + 1;
            }
        }
        if (quoted || depth != 0) {
            throw new SipParseException("unbalanced quotes or brackets: " + value);
        }
        out.add(value.substring(from).trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && TOKEN_CHARS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, int from, int length, byte[] pattern) {
        outer:
        for (int i = from; i <= length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
