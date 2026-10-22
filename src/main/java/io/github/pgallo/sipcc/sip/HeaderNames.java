package io.github.pgallo.sipcc.sip;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical header names, compact-form expansion (RFC 3261 section 7.3.3, RFC 3515) and list headers. */
public final class HeaderNames {

    private static final Map<String, String> COMPACT = Map.ofEntries(
            Map.entry("i", "Call-ID"), Map.entry("m", "Contact"), Map.entry("e", "Content-Encoding"),
            Map.entry("l", "Content-Length"), Map.entry("c", "Content-Type"), Map.entry("f", "From"),
            Map.entry("s", "Subject"), Map.entry("k", "Supported"), Map.entry("t", "To"),
            Map.entry("v", "Via"), Map.entry("r", "Refer-To"), Map.entry("b", "Referred-By"),
            Map.entry("o", "Event"));

    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        for (String n : new String[] {"Via", "From", "To", "Call-ID", "CSeq", "Contact", "Max-Forwards",
                "Content-Type", "Content-Length", "Content-Encoding", "Record-Route", "Route", "Expires",
                "Authorization", "WWW-Authenticate", "Refer-To", "Referred-By", "Event", "Subscription-State",
                "Replaces", "User-Agent", "Allow", "Supported", "Subject"}) {
            KNOWN.put(n.toLowerCase(Locale.ROOT), n);
        }
    }

    /** Headers whose comma-separated values are split into separate entries on parse. */
    public static final Set<String> LIST_HEADERS = Set.of("Via", "Route", "Record-Route", "Contact");

    private HeaderNames() {}

    public static String canonical(String name) {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        String c = COMPACT.get(lower);
        if (c != null) {
            return c;
        }
        return KNOWN.getOrDefault(lower, name.trim());
    }
}
