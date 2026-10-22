package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.sip.NameAddr;
import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipParseException;
import io.github.pgallo.sipcc.sip.SipParser;
import io.github.pgallo.sipcc.sip.SipUri;
import io.github.pgallo.sipcc.sip.Via;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SipParserTest {

    static final String INVITE = String.join("\r\n",
            "INVITE sip:2001@cc.test SIP/2.0",
            "Via: SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bKaaa;rport, SIP/2.0/UDP 127.0.0.1:5080;branch=z9hG4bKbbb",
            "Max-Forwards: 69",
            "Record-Route: <sip:127.0.0.1:5060;lr>",
            "From: \"Caller One\" <sip:caller01@cc.test>;tag=f1",
            "To: <sip:2001@cc.test>",
            "Call-ID: abc123@host",
            "CSeq: 1 INVITE",
            "Contact: <sip:caller01@127.0.0.1:5080>",
            "Content-Type: application/sdp",
            "Content-Length: 5",
            "",
            "v=0\r\n");

    @Test
    void parsesRequestAndRoundTrips() {
        SipMessage m = SipParser.parse(INVITE);
        assertEquals("INVITE", m.method());
        assertEquals("sip:2001@cc.test", m.requestUri());
        assertEquals(2, m.headerValues("Via").size());
        assertEquals("z9hG4bKaaa", m.topVia().branch());
        assertEquals("Caller One", m.from().display());
        assertEquals("f1", m.from().tag());
        assertNull(m.to().tag());
        assertEquals(1L, m.cseq().seq());
        assertEquals("v=0\r\n", m.bodyText());

        SipMessage again = SipParser.parse(m.toString());
        assertEquals(m.headers(), again.headers());
        assertEquals(m.toString(), again.toString());
    }

    @Test
    void parsesResponseWithCompactHeadersAndFolding() {
        String text = String.join("\r\n",
                "SIP/2.0 180 Ringing",
                "v: SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bKaaa",
                "f: <sip:caller01@cc.test>;tag=f1",
                "t: <sip:2001@cc.test>",
                "  ;tag=t9",
                "i: abc123@host",
                "CSeq: 1 INVITE",
                "m: <sip:2001@127.0.0.1:5090>",
                "l: 0",
                "", "");
        SipMessage m = SipParser.parse(text);
        assertEquals(180, m.status());
        assertEquals("Ringing", m.reason());
        assertEquals("t9", m.to().tag());
        assertEquals("abc123@host", m.callId());
        assertEquals("<sip:2001@127.0.0.1:5090>", m.header("Contact"));
        assertTrue(m.toString().contains("Call-ID: abc123@host"));
    }

    @Test
    void uriHeadersAreEncodedAndDecoded() {
        SipUri uri = SipUri.parse("sip:2003@cc.test").withHeader("Replaces", "id@h;to-tag=a;from-tag=b")
                .withHeader("X-CC-Call", "C0001");
        String text = uri.toString();
        assertEquals("sip:2003@cc.test?Replaces=id%40h%3Bto-tag%3Da%3Bfrom-tag%3Db&X-CC-Call=C0001", text);
        SipUri back = SipUri.parse(text);
        assertEquals("id@h;to-tag=a;from-tag=b", back.headers().get("Replaces"));
        assertEquals("C0001", back.headers().get("X-CC-Call"));

        NameAddr na = NameAddr.parse("<" + text + ">;tag=x");
        assertEquals("x", na.tag());
        assertEquals(uri, na.uri());
    }

    @Test
    void viaResponseAddressHonoursReceivedAndRport() {
        Via v = Via.parse("SIP/2.0/UDP 10.0.0.9:5070;branch=z9hG4bK1;rport=40000;received=127.0.0.1");
        assertEquals(40000, v.responseAddress().getPort());
        assertEquals("127.0.0.1", v.responseAddress().getHostString());
    }

    @Test
    void toleratesLeadingKeepAliveAndBareLf() {
        String text = "\r\n\r\n" + INVITE.replace("\r\n", "\n").replace("Content-Length: 5", "Content-Length: 4");
        SipMessage m = SipParser.parse(text.getBytes(StandardCharsets.UTF_8), text.length());
        assertEquals("abc123@host", m.callId());
    }

    @Test
    void rejectsMalformedInput() {
        List<String> bad = List.of(
                "",
                "INVITE sip:2001@cc.test SIP/2.0\r\n",                               // no blank line
                INVITE.replace("CSeq: 1 INVITE\r\n", ""),                             // missing CSeq
                INVITE.replace("CSeq: 1 INVITE", "CSeq: 1 BYE"),                      // method mismatch
                INVITE.replace("CSeq: 1 INVITE", "CSeq: x INVITE"),                   // bad number
                INVITE.replace("Content-Length: 5", "Content-Length: 50"),            // truncated body
                INVITE.replace("INVITE sip:2001@cc.test SIP/2.0", "INVITE sip:2001@cc.test SIP/3.0"),
                INVITE.replace("INVITE sip:2001@cc.test", "INVITE http://cc.test"),   // scheme
                INVITE.replace("From: \"Caller One\"", "From \"Caller One\""),        // no colon
                INVITE.replace("To: <sip:2001@cc.test>", "To: <sip:2001@cc.test"),    // unterminated <
                "SIP/2.0 99 Too Low\r\nVia: SIP/2.0/UDP h;branch=z9hG4bK1\r\n\r\n",
                INVITE.replace("Max-Forwards: 69", "Max-Forwards: many"));
        for (String b : bad) {
            assertThrows(SipParseException.class, () -> SipParser.parse(b));
        }
    }
}
