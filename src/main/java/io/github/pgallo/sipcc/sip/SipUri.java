package io.github.pgallo.sipcc.sip;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A sip: or sips: URI with user, host, port, parameters and headers ({@code ?Replaces=...}).
 * Header values are kept decoded; {@link #toString()} percent-encodes them again.
 */
public record SipUri(String scheme, String user, String host, int port,
                     Map<String, String> params, Map<String, String> headers) {

    public SipUri {
        params = params.isEmpty() ? Map.of() : new LinkedHashMap<>(params);
        headers = headers.isEmpty() ? Map.of() : new LinkedHashMap<>(headers);
    }

    public static SipUri of(String user, String host, int port) {
        return new SipUri("sip", user, host, port, Map.of(), Map.of());
    }

    public static SipUri parse(String text) {
        String s = text.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) {
            throw new SipParseException("URI without scheme: " + text);
        }
        String scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("sip") && !scheme.equals("sips")) {
            throw new SipParseException("unsupported URI scheme: " + text);
        }
        String rest = s.substring(colon + 1);
        Map<String, String> headers = new LinkedHashMap<>();
        int q = rest.indexOf('?');
        if (q >= 0) {
            for (String kv : rest.substring(q + 1).split("&")) {
                if (kv.isEmpty()) {
                    continue;
                }
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    throw new SipParseException("malformed URI header: " + kv);
                }
                headers.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1)));
            }
            rest = rest.substring(0, q);
        }
        String user = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            user = decode(rest.substring(0, at));
            rest = rest.substring(at + 1);
        }
        String[] parts = rest.split(";");
        String hostport = parts[0];
        if (hostport.isEmpty()) {
            throw new SipParseException("URI without host: " + text);
        }
        String host = hostport;
        int port = -1;
        int pc = hostport.lastIndexOf(':');
        if (pc >= 0) {
            host = hostport.substring(0, pc);
            try {
                port = Integer.parseInt(hostport.substring(pc + 1));
            } catch (NumberFormatException e) {
                throw new SipParseException("bad port in URI: " + text);
            }
            if (port <= 0 || port > 65535) {
                throw new SipParseException("port out of range: " + text);
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            int eq = parts[i].indexOf('=');
            if (eq < 0) {
                params.put(parts[i].toLowerCase(Locale.ROOT), "");
            } else {
                params.put(parts[i].substring(0, eq).toLowerCase(Locale.ROOT), parts[i].substring(eq + 1));
            }
        }
        return new SipUri(scheme, user, host.toLowerCase(Locale.ROOT), port, params, headers);
    }

    public SipUri withoutHeaders() {
        return new SipUri(scheme, user, host, port, params, Map.of());
    }

    public SipUri withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new SipUri(scheme, user, host, port, params, h);
    }

    public SipUri withParam(String name, String value) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put(name, value);
        return new SipUri(scheme, user, host, port, p, headers);
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(host, port < 0 ? 5060 : port);
    }

    public boolean sameHostPort(String otherHost, int otherPort) {
        return host.equalsIgnoreCase(otherHost) && (port < 0 ? 5060 : port) == otherPort;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(scheme).append(':');
        if (user != null) {
            sb.append(encode(user, "-_.!~*'()&=+$,;?/")).append('@');
        }
        sb.append(host);
        if (port > 0) {
            sb.append(':').append(port);
        }
        params.forEach((k, v) -> sb.append(';').append(k).append(v.isEmpty() ? "" : "=" + v));
        char sep = '?';
        for (var e : headers.entrySet()) {
            sb.append(sep).append(encode(e.getKey(), "-_.!~*'()")).append('=').append(encode(e.getValue(), "-_.!~*'()"));
            sep = '&';
        }
        return sb.toString();
    }

    static String encode(String s, String safe) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || safe.indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new SipParseException("truncated escape in " + s);
                }
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                } catch (NumberFormatException e) {
                    throw new SipParseException("bad escape in " + s);
                }
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
