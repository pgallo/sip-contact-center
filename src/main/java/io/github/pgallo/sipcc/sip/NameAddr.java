package io.github.pgallo.sipcc.sip;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** name-addr / addr-spec as used by From, To, Contact, Route, Record-Route and Refer-To. */
public record NameAddr(String display, SipUri uri, Map<String, String> params) {

    public NameAddr {
        params = new LinkedHashMap<>(params);
    }

    public static NameAddr of(SipUri uri) {
        return new NameAddr(null, uri, Map.of());
    }

    public static NameAddr parse(String text) {
        if (text == null) {
            throw new SipParseException("missing address header");
        }
        String s = text.trim();
        String display = null;
        if (s.startsWith("\"")) {
            int end = s.indexOf('"', 1);
            if (end < 0) {
                throw new SipParseException("unterminated display name: " + text);
            }
            display = s.substring(1, end);
            s = s.substring(end + 1).trim();
        }
        String uriText;
        String rest;
        int lt = s.indexOf('<');
        if (lt >= 0) {
            int gt = s.indexOf('>', lt);
            if (gt < 0) {
                throw new SipParseException("unterminated '<' in " + text);
            }
            if (display == null && lt > 0) {
                display = s.substring(0, lt).trim();
            }
            uriText = s.substring(lt + 1, gt);
            rest = s.substring(gt + 1);
        } else {
            int semi = s.indexOf(';');
            uriText = semi < 0 ? s : s.substring(0, semi);
            rest = semi < 0 ? "" : s.substring(semi);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String p : rest.split(";")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 0) {
                params.put(t.toLowerCase(Locale.ROOT), "");
            } else {
                params.put(t.substring(0, eq).trim().toLowerCase(Locale.ROOT), t.substring(eq + 1).trim());
            }
        }
        return new NameAddr(display == null || display.isEmpty() ? null : display, SipUri.parse(uriText), params);
    }

    public String tag() {
        return params.get("tag");
    }

    public NameAddr withTag(String tag) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("tag", tag);
        return new NameAddr(display, uri, p);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (display != null) {
            sb.append('"').append(display).append("\" ");
        }
        sb.append('<').append(uri).append('>');
        params.forEach((k, v) -> sb.append(';').append(k).append(v.isEmpty() ? "" : "=" + v));
        return sb.toString();
    }
}
