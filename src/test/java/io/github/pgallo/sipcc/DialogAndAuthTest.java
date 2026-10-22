package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.sip.DigestAuth;
import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipParser;
import io.github.pgallo.sipcc.time.ManualTimerService;
import io.github.pgallo.sipcc.ua.Dialog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogAndAuthTest {

    private static SipMessage invite() {
        return SipParser.parse(String.join("\r\n",
                "INVITE sip:2001@cc.test SIP/2.0",
                "Via: SIP/2.0/UDP 127.0.0.1:5080;branch=z9hG4bKc",
                "Record-Route: <sip:127.0.0.1:6001;lr>, <sip:127.0.0.1:6002;lr>",
                "From: <sip:caller01@cc.test>;tag=caller", "To: <sip:2001@cc.test>",
                "Call-ID: d1", "CSeq: 7 INVITE", "Contact: <sip:caller01@127.0.0.1:5080>", "", ""));
    }

    private static SipMessage ok(SipMessage invite) {
        SipMessage ok = SipMessage.responseTo(invite, 200, "OK");
        ok.setHeader("To", "<sip:2001@cc.test>;tag=agent");
        ok.addHeader("Contact", "<sip:2001@127.0.0.1:5090>");
        return ok;
    }

    @Test
    void uacDialogReversesRecordRouteAndBuildsInDialogRequests() {
        SipMessage inv = invite();
        Dialog d = Dialog.asUac(inv, ok(inv), "sip:caller01@127.0.0.1:5080");
        assertEquals(new Dialog.Id("d1", "caller", "agent"), d.id());
        assertEquals(List.of("<sip:127.0.0.1:6002;lr>", "<sip:127.0.0.1:6001;lr>"), d.routeSet());
        SipMessage bye = d.newRequest("BYE");
        assertEquals("sip:2001@127.0.0.1:5090", bye.requestUri());
        assertEquals(d.routeSet(), bye.headerValues("Route"));
        assertEquals(8L, bye.cseq().seq());
        assertEquals("agent", bye.to().tag());
        assertEquals(6002, d.nextHop().getPort());
        assertEquals(7L, d.ack(7).cseq().seq());
    }

    @Test
    void uasDialogKeepsRouteOrderAndMatchesRequests() {
        SipMessage inv = invite();
        Dialog d = Dialog.asUas(inv, "agent", "sip:2001@127.0.0.1:5090");
        assertEquals(List.of("<sip:127.0.0.1:6001;lr>", "<sip:127.0.0.1:6002;lr>"), d.routeSet());
        assertEquals(6001, d.nextHop().getPort());

        Dialog caller = Dialog.asUac(inv, ok(inv), "sip:caller01@127.0.0.1:5080");
        SipMessage bye = caller.newRequest("BYE");
        assertTrue(d.matches(bye));
        assertTrue(d.acceptRemoteSeq(bye.cseq().seq()));
        assertFalse(d.acceptRemoteSeq(bye.cseq().seq() - 1), "out-of-order CSeq is rejected");

        SipMessage other = bye.copy();
        other.setHeader("From", "<sip:caller01@cc.test>;tag=someone-else");
        assertFalse(d.matches(other));
    }

    @Test
    void digestMatchesRfc2617Example() {
        String ha1 = DigestAuth.ha1("Mufasa", "testrealm@host.com", "Circle Of Life");
        String resp = DigestAuth.response(ha1, "dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b", "auth",
                "GET", "/dir/index.html");
        assertEquals("6629fae49393a05397450978507c4ef1", resp);
    }

    @Test
    void digestChallengeAndVerification() {
        ManualTimerService clock = new ManualTimerService();
        DigestAuth.Server server = new DigestAuth.Server("cc.test", 60_000, clock::now);
        Map<String, String> passwords = Map.of("2001", "pw-2001");
        String challenge = server.challenge();
        String good = DigestAuth.authorization(challenge, "REGISTER", "sip:cc.test", "2001", "pw-2001", "c1", 1);
        assertEquals("2001", server.verify(good, "REGISTER", passwords));
        String wrong = DigestAuth.authorization(challenge, "REGISTER", "sip:cc.test", "2001", "guess", "c1", 1);
        assertNull(server.verify(wrong, "REGISTER", passwords));
        assertNull(server.verify(good, "INVITE", passwords), "method is part of the digest");
        String forged = good.replaceAll("nonce=\"[0-9a-f]+\"", "nonce=\"00\"");
        assertNull(server.verify(forged, "REGISTER", passwords), "unknown nonce");
        clock.advance(60_001);
        assertNull(server.verify(good, "REGISTER", passwords), "expired nonce");
    }
}
