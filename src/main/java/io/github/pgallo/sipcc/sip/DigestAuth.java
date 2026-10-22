package io.github.pgallo.sipcc.sip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * HTTP Digest authentication as profiled for SIP (RFC 2617, MD5 algorithm of RFC 7616), with
 * qop=auth. The server side issues nonces with a lifetime and verifies Authorization headers; the
 * client side answers a WWW-Authenticate challenge.
 */
public final class DigestAuth {

    private DigestAuth() {}

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String ha1(String user, String realm, String password) {
        return md5Hex(user + ":" + realm + ":" + password);
    }

    /** The request-digest of RFC 2617 section 3.2.2.1. */
    public static String response(String ha1, String nonce, String nc, String cnonce, String qop,
                                  String method, String uri) {
        String ha2 = md5Hex(method + ":" + uri);
        if (qop == null || qop.isEmpty()) {
            return md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }
        return md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
    }

    /** Parses {@code Digest k="v", k=v} into a map with lower-case keys. */
    public static Map<String, String> parseParams(String header) {
        String s = header.trim();
        if (!s.regionMatches(true, 0, "Digest ", 0, 7)) {
            throw new SipParseException("not a Digest header: " + header);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : SipParser.splitList(s.substring(7))) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new SipParseException("malformed digest parameter: " + part);
            }
            String v = part.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            out.put(part.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT), v);
        }
        return out;
    }

    /** Client side: builds an Authorization header value answering {@code challenge}. */
    public static String authorization(String challenge, String method, String uri, String user,
                                       String password, String cnonce, int nc) {
        Map<String, String> c = parseParams(challenge);
        String realm = c.get("realm");
        String nonce = c.get("nonce");
        String qop = c.containsKey("qop") ? "auth" : null;
        String ncHex = String.format("%08x", nc);
        String resp = response(ha1(user, realm, password), nonce, ncHex, cnonce, qop, method, uri);
        StringBuilder sb = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", response=\"").append(resp)
                .append("\", algorithm=MD5");
        if (qop != null) {
            sb.append(", qop=auth, nc=").append(ncHex).append(", cnonce=\"").append(cnonce).append('"');
        }
        return sb.toString();
    }

    /** Server side: nonce issuance and verification. Not thread-safe; owned by one event loop. */
    public static final class Server {
        private final String realm;
        private final long nonceLifetimeMs;
        private final LongSupplier clock;
        private final Map<String, Long> nonces = new HashMap<>();
        private final SecureRandom random = new SecureRandom();

        public Server(String realm, long nonceLifetimeMs, LongSupplier clock) {
            this.realm = realm;
            this.nonceLifetimeMs = nonceLifetimeMs;
            this.clock = clock;
        }

        public String realm() {
            return realm;
        }

        public String challenge() {
            byte[] b = new byte[16];
            random.nextBytes(b);
            String nonce = HexFormat.of().formatHex(b);
            long now = clock.getAsLong();
            nonces.values().removeIf(issued -> now - issued > nonceLifetimeMs);
            nonces.put(nonce, now);
            return "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\", algorithm=MD5, qop=\"auth\"";
        }

        /** Returns the authenticated user name, or null if the header does not prove knowledge of the password. */
        public String verify(String authorization, String method, Map<String, String> passwords) {
            if (authorization == null) {
                return null;
            }
            Map<String, String> p;
            try {
                p = parseParams(authorization);
            } catch (SipParseException e) {
                return null;
            }
            String user = p.get("username");
            String nonce = p.get("nonce");
            Long issued = nonce == null ? null : nonces.get(nonce);
            if (user == null || issued == null || clock.getAsLong() - issued > nonceLifetimeMs
                    || !realm.equals(p.get("realm")) || !passwords.containsKey(user)) {
                return null;
            }
            String expected = response(ha1(user, realm, passwords.get(user)), nonce, p.get("nc"), p.get("cnonce"),
                    p.get("qop"), method, p.get("uri"));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    String.valueOf(p.get("response")).getBytes(StandardCharsets.US_ASCII)) ? user : null;
        }
    }
}
