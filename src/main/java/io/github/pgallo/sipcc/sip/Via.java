package io.github.pgallo.sipcc.sip;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A single Via header value. Only branches with the RFC 3261 magic cookie are generated. */
public record Via(String transport, String host, int port, Map<String, String> params) {

    public static final String MAGIC_COOKIE = "z9hG4bK";
    private static final SecureRandom RANDOM = new SecureRandom();

    public Via {
        params = new LinkedHashMap<>(params);
    }

    public static Via of(String host, int port, String branch) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("branch", branch);
        p.put("rport", "");
        return new Via("UDP", host, port, p);
    }

    public static String newBranch() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return MAGIC_COOKIE + HexFormat.of().formatHex(b);
    }

    public static Via parse(String text) {
        String s = text.trim();
        int sp = s.indexOf(' ');
        if (sp < 0) {
            throw new SipParseException("malformed Via: " + text);
        }
        String proto = s.substring(0, sp);
        String[] protoParts = proto.split("/");
        if (protoParts.length != 3 || !protoParts[0].equalsIgnoreCase("SIP") || !protoParts[1].equals("2.0")) {
            throw new SipParseException("malformed Via protocol: " + text);
        }
        String[] parts = s.substring(sp + 1).trim().split(";");
        String hostport = parts[0].trim();
        String host = hostport;
        int port = 5060;
        int colon = hostport.lastIndexOf(':');
        if (colon >= 0) {
            host = hostport.substring(0, colon);
            try {
                port = Integer.parseInt(hostport.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new SipParseException("bad Via port: " + text);
            }
        }
        if (host.isEmpty()) {
            throw new SipParseException("Via without host: " + text);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq < 0) {
                params.put(p.toLowerCase(Locale.ROOT), "");
            } else {
                params.put(p.substring(0, eq).toLowerCase(Locale.ROOT), p.substring(eq + 1));
            }
        }
        return new Via(protoParts[2].toUpperCase(Locale.ROOT), host, port, params);
    }

    public String branch() {
        return params.get("branch");
    }

    public Via withParam(String name, String value) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put(name, value);
        return new Via(transport, host, port, p);
    }

    /** Where responses go: received/rport if the server stamped them (RFC 3581), otherwise sent-by. */
    public InetSocketAddress responseAddress() {
        String h = params.getOrDefault("received", host);
        String rp = params.get("rport");
        int p = rp != null && !rp.isEmpty() ? Integer.parseInt(rp) : port;
        return new InetSocketAddress(h, p);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SIP/2.0/").append(transport).append(' ').append(host).append(':').append(port);
        params.forEach((k, v) -> sb.append(';').append(k).append(v.isEmpty() ? "" : "=" + v));
        return sb.toString();
    }
}
