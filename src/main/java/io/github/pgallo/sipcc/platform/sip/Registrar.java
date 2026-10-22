package io.github.pgallo.sipcc.platform.sip;

import io.github.pgallo.sipcc.sip.DigestAuth;
import io.github.pgallo.sipcc.sip.NameAddr;
import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipUri;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Registrar (RFC 3261 section 10.3) with digest authentication and binding expiry. */
public final class Registrar {

    private record Binding(SipUri contact, long expiresAt) {}

    private final Map<String, String> passwords;
    private final DigestAuth.Server auth;
    private final LongSupplier clock;
    private final Map<String, Binding> bindings = new HashMap<>();
    private final BiConsumer<String, Boolean> onChange;

    public Registrar(String realm, Map<String, String> passwords, LongSupplier clock,
                     BiConsumer<String, Boolean> onChange) {
        this.passwords = passwords;
        this.clock = clock;
        this.auth = new DigestAuth.Server(realm, 300_000, clock);
        this.onChange = onChange;
    }

    public SipMessage handle(SipMessage req) {
        String user = req.to().uri().user();
        if (user == null || !passwords.containsKey(user)) {
            return SipMessage.responseTo(req, 404, "Not Found");
        }
        String authenticated = auth.verify(req.header("Authorization"), "REGISTER", passwords);
        if (!user.equals(authenticated)) {
            SipMessage r = SipMessage.responseTo(req, 401, "Unauthorized");
            r.addHeader("WWW-Authenticate", auth.challenge());
            return r;
        }
        String contactHeader = req.header("Contact");
        if (contactHeader == null) { // a query: return current bindings
            return withBinding(SipMessage.responseTo(req, 200, "OK"), user);
        }
        NameAddr contact = NameAddr.parse(contactHeader);
        int expires = 3600;
        if (contact.params().containsKey("expires")) {
            expires = Integer.parseInt(contact.params().get("expires"));
        } else if (req.header("Expires") != null) {
            expires = Integer.parseInt(req.header("Expires").trim());
        }
        boolean wasBound = lookup(user).isPresent();
        if (expires == 0) {
            bindings.remove(user);
            if (wasBound) {
                onChange.accept(user, false);
            }
        } else {
            bindings.put(user, new Binding(contact.uri(), clock.getAsLong() + expires * 1000L));
            if (!wasBound) {
                onChange.accept(user, true);
            }
        }
        return withBinding(SipMessage.responseTo(req, 200, "OK"), user);
    }

    private SipMessage withBinding(SipMessage resp, String user) {
        Binding b = bindings.get(user);
        if (b != null) {
            long left = Math.max(0, (b.expiresAt() - clock.getAsLong()) / 1000);
            resp.addHeader("Contact", "<" + b.contact() + ">;expires=" + left);
        }
        return resp;
    }

    public Optional<SipUri> lookup(String user) {
        Binding b = bindings.get(user);
        if (b == null) {
            return Optional.empty();
        }
        if (b.expiresAt() <= clock.getAsLong()) {
            bindings.remove(user);
            onChange.accept(user, false);
            return Optional.empty();
        }
        return Optional.of(b.contact());
    }
}
