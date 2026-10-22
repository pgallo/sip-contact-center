package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipParser;
import io.github.pgallo.sipcc.time.ManualTimerService;
import io.github.pgallo.sipcc.txn.ClientTransaction;
import io.github.pgallo.sipcc.txn.ServerTransaction;
import io.github.pgallo.sipcc.txn.SipTimers;
import io.github.pgallo.sipcc.txn.TransactionLayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timers A, B, D, E, F, G, H, I, J, K driven by a manual clock with RFC 3261 default values. */
class TransactionTimerTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 5099);

    private ManualTimerService clock;
    private FakeTransport transport;
    private TransactionLayer layer;
    private final List<ServerTransaction> serverTxs = new ArrayList<>();
    private final List<SipMessage> responses = new ArrayList<>();
    private int timeouts;

    @BeforeEach
    void setUp() {
        clock = new ManualTimerService();
        transport = new FakeTransport(clock);
        layer = new TransactionLayer(transport, clock, SipTimers.RFC3261);
        layer.start(new TransactionLayer.User() {
            @Override
            public void onRequest(ServerTransaction tx, SipMessage request) {
                serverTxs.add(tx);
            }

            @Override
            public void onAck(SipMessage ack) {
            }
        });
    }

    private ClientTransaction send(String method) {
        SipMessage req = SipMessage.request(method, "sip:2001@127.0.0.1:5099");
        req.addHeader("From", "<sip:a@cc.test>;tag=1").addHeader("To", "<sip:2001@cc.test>")
                .addHeader("Call-ID", "t1").addHeader("CSeq", "1 " + method).addHeader("Max-Forwards", "70");
        return layer.sendRequest(req, PEER, new ClientTransaction.Listener() {
            @Override
            public void onResponse(ClientTransaction tx, SipMessage response) {
                responses.add(response);
            }

            @Override
            public void onTimeout(ClientTransaction tx) {
                timeouts++;
            }
        });
    }

    private void respond(ClientTransaction tx, int code) {
        SipMessage r = SipMessage.responseTo(tx.request(), code, "X");
        r.setHeader("To", "<sip:2001@cc.test>;tag=srv");
        layer.onInbound(SipParser.parse(r.toString()), PEER);
    }

    @Test
    void inviteClientRetransmitsOnTimerAAndTimesOutOnTimerB() {
        send("INVITE");
        clock.advance(31_999);
        assertEquals(List.of(0L, 500L, 1_500L, 3_500L, 7_500L, 15_500L, 31_500L), transport.times("INVITE"));
        assertEquals(0, timeouts);
        clock.advance(1);
        assertEquals(1, timeouts, "Timer B at 64*T1");
        clock.advance(100_000);
        assertEquals(7, transport.times("INVITE").size());
        assertEquals(0, layer.activeTransactions());
    }

    @Test
    void provisionalResponseStopsInviteRetransmissions() {
        ClientTransaction tx = send("INVITE");
        clock.advance(600);
        respond(tx, 180);
        clock.advance(20_000);
        assertEquals(2, transport.times("INVITE").size());
        assertEquals(ClientTransaction.State.PROCEEDING, tx.state());
    }

    @Test
    void nonSuccessFinalIsAckedAndAbsorbedUntilTimerD() {
        ClientTransaction tx = send("INVITE");
        respond(tx, 486);
        assertEquals(1, transport.times("ACK").size());
        SipMessage ack = transport.sent.get(transport.sent.size() - 1).message();
        assertEquals(tx.request().topVia().branch(), ack.topVia().branch());
        assertEquals("srv", ack.to().tag());
        respond(tx, 486); // retransmitted final response
        assertEquals(2, transport.times("ACK").size());
        assertEquals(1, responses.size(), "the TU sees the final response once");
        clock.advance(31_999);
        assertEquals(ClientTransaction.State.COMPLETED, tx.state());
        clock.advance(1);
        assertEquals(ClientTransaction.State.TERMINATED, tx.state());
    }

    @Test
    void nonInviteClientCapsTimerEAtT2AndTimesOutOnTimerF() {
        send("OPTIONS");
        clock.advance(32_000);
        List<Long> times = transport.times("OPTIONS");
        assertEquals(List.of(0L, 500L, 1_500L, 3_500L, 7_500L, 11_500L, 15_500L), times.subList(0, 7));
        for (int i = 4; i < times.size(); i++) {
            assertEquals(4_000L, times.get(i) - times.get(i - 1), "interval capped at T2");
        }
        assertEquals(1, timeouts);
    }

    @Test
    void nonInviteClientWaitsTimerKAfterFinal() {
        ClientTransaction tx = send("BYE");
        respond(tx, 200);
        assertEquals(ClientTransaction.State.COMPLETED, tx.state());
        clock.advance(4_999);
        assertEquals(ClientTransaction.State.COMPLETED, tx.state());
        clock.advance(1);
        assertEquals(ClientTransaction.State.TERMINATED, tx.state());
    }

    private SipMessage inbound(String method, String branch) {
        SipMessage m = SipParser.parse(String.join("\r\n",
                method + " sip:2001@127.0.0.1:5070 SIP/2.0",
                "Via: SIP/2.0/UDP 127.0.0.1:5099;branch=" + branch,
                "From: <sip:a@cc.test>;tag=1", "To: <sip:2001@cc.test>" + (method.equals("ACK") ? ";tag=srv" : ""),
                "Call-ID: s1", "CSeq: 1 " + method, "", ""));
        layer.onInbound(m, PEER);
        return m;
    }

    @Test
    void inviteServerRetransmitsOnTimerGUntilAckThenTimerI() {
        SipMessage invite = inbound("INVITE", "z9hG4bKs1");
        ServerTransaction tx = serverTxs.get(0);
        assertEquals(1, transport.times("100").size(), "100 Trying sent at once");
        tx.sendResponse(SipMessage.responseTo(invite, 486, "Busy Here"));
        clock.advance(3_600);
        assertEquals(List.of(0L, 500L, 1_500L, 3_500L), transport.times("486"));
        inbound("ACK", "z9hG4bKs1");
        assertEquals(ServerTransaction.State.CONFIRMED, tx.state());
        clock.advance(4_999);
        assertEquals(ServerTransaction.State.CONFIRMED, tx.state());
        clock.advance(1);
        assertEquals(ServerTransaction.State.TERMINATED, tx.state());
        assertEquals(4, transport.times("486").size());
    }

    @Test
    void inviteServerGivesUpOnTimerHWithoutAck() {
        SipMessage invite = inbound("INVITE", "z9hG4bKs2");
        ServerTransaction tx = serverTxs.get(0);
        boolean[] fired = {false};
        tx.onAckTimeout(() -> fired[0] = true);
        tx.sendResponse(SipMessage.responseTo(invite, 603, "Decline"));
        clock.advance(31_999);
        assertTrue(!fired[0]);
        List<Long> t = transport.times("603");
        assertEquals(4_000L, t.get(t.size() - 1) - t.get(t.size() - 2), "Timer G capped at T2");
        clock.advance(1);
        assertTrue(fired[0], "Timer H");
        assertEquals(ServerTransaction.State.TERMINATED, tx.state());
    }

    @Test
    void nonInviteServerAnswersRetransmissionsUntilTimerJ() {
        SipMessage options = inbound("OPTIONS", "z9hG4bKs3");
        ServerTransaction tx = serverTxs.get(0);
        tx.sendResponse(SipMessage.responseTo(options, 200, "OK"));
        inbound("OPTIONS", "z9hG4bKs3");
        assertEquals(1, serverTxs.size(), "retransmission absorbed by the transaction");
        assertEquals(2, transport.times("200").size());
        clock.advance(32_000);
        assertEquals(ServerTransaction.State.TERMINATED, tx.state());
        inbound("OPTIONS", "z9hG4bKs3");
        assertEquals(2, serverTxs.size(), "after Timer J a new transaction is created");
    }
}
