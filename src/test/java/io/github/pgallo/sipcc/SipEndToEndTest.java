package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.cc.Agent;
import io.github.pgallo.sipcc.cc.Cdr;
import io.github.pgallo.sipcc.cc.ContactCenter;
import io.github.pgallo.sipcc.cc.IvrMenu;
import io.github.pgallo.sipcc.demo.Scenario;
import io.github.pgallo.sipcc.platform.sip.SipPlatform;
import io.github.pgallo.sipcc.sip.SipUri;
import io.github.pgallo.sipcc.time.EventLoop;
import io.github.pgallo.sipcc.transport.UdpTransport;
import io.github.pgallo.sipcc.txn.SipTimers;
import io.github.pgallo.sipcc.txn.TransactionLayer;
import io.github.pgallo.sipcc.ua.Call;
import io.github.pgallo.sipcc.ua.UserAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real UDP on loopback: registrar with digest auth, IVR via INFO DTMF, queue, REFER to an agent
 * through the record-routing proxy, then a blind transfer (REFER) and an attended transfer
 * (consultation call plus REFER with Replaces), ending with one CDR covering all three agents.
 */
class SipEndToEndTest {

    /** A scripted phone that auto-answers and leaves the call once its REFER succeeded. */
    static final class Phone implements UserAgent.Listener {
        final List<Call> established = Collections.synchronizedList(new ArrayList<>());
        UserAgent ua;

        @Override
        public void onEstablished(Call call) {
            established.add(call);
        }

        @Override
        public void onEnded(Call call) {
            established.remove(call);
        }

        @Override
        public void onReferResult(Call call, int status) {
            call.hangup();
        }

        Call only() {
            synchronized (established) {
                assertEquals(1, established.size(), "calls on " + ua.user());
                return established.get(0);
            }
        }
    }

    private EventLoop loop;
    private SipPlatform platform;
    private ContactCenter cc;
    private final SipTimers timers = SipTimers.RFC3261;

    @BeforeEach
    void setUp() {
        loop = new EventLoop("e2e");
        Map<String, String> accounts = new HashMap<>();
        for (String ext : List.of("2001", "2002", "2003")) {
            accounts.put(ext, Scenario.password(ext));
        }
        platform = new SipPlatform(loop, 0, Scenario.DOMAIN, Scenario.SERVICE, accounts, accounts.keySet(), timers);
        List<Agent> agents = List.of(
                new Agent("2001", "Ana", Map.of("billing", 3)),
                new Agent("2002", "Ben", Map.of("billing", 2)),
                new Agent("2003", "Cleo", Map.of("billing", 2)));
        cc = new ContactCenter(loop, platform, IvrMenu.standard(), agents, Scenario.queues(), 50, 60_000);
        platform.start(cc);
    }

    @AfterEach
    void tearDown() {
        platform.close();
        loop.close();
    }

    private Phone phone(String user, String password) {
        Phone p = new Phone();
        p.ua = loop.call(() -> {
            UserAgent ua = new UserAgent(user, Scenario.DOMAIN, password,
                    new TransactionLayer(new UdpTransport(loop, 0), loop, timers), loop, timers, platform.address()).start();
            ua.setListener(p);
            return ua;
        });
        return p;
    }

    private void registerReady(Phone p) {
        AtomicInteger status = new AtomicInteger();
        loop.execute(() -> p.ua.register(300, status::set));
        Await.until("registered " + p.ua.user(), () -> status.get() == 200);
        loop.execute(() -> p.ua.sendMessage("sip:support@cc.test", "READY"));
        Await.until("ready " + p.ua.user(), () -> cc.agent(p.ua.user()).state() == Agent.State.READY);
    }

    @Test
    void wrongPasswordIsRejectedByTheRegistrar() {
        Phone p = phone("2001", "not-the-password");
        AtomicInteger status = new AtomicInteger();
        loop.execute(() -> p.ua.register(300, status::set));
        Await.until("register answered", () -> status.get() != 0);
        assertEquals(401, status.get());
        assertEquals(Agent.State.LOGGED_OUT, cc.agent("2001").state());
    }

    @Test
    void callThroughIvrQueueAgentBlindAndAttendedTransfer() {
        Phone a = phone("2001", Scenario.password("2001"));
        Phone b = phone("2002", Scenario.password("2002"));
        Phone c = phone("2003", Scenario.password("2003"));
        registerReady(a);
        registerReady(b);
        registerReady(c);
        Phone caller = phone("caller01", null);

        Call ivr = loop.call(() -> caller.ua.invite("sip:support@cc.test", Map.of()));
        Await.until("IVR answered", () -> caller.established.size() == 1);
        loop.execute(() -> ivr.sendDtmf('1'));

        // the IVR REFERs the caller to the longest-idle billing agent (2001), then drops its own leg
        Await.until("caller with 2001", () -> a.established.size() == 1 && caller.established.size() == 1
                && caller.established.get(0) != ivr);
        Call atA = a.only();
        assertEquals("C0001", atA.header(SipPlatform.CORRELATION_HEADER));
        assertFalse(loop.call(() -> atA.dialog().routeSet().isEmpty()), "proxy record-routed the dialog");
        assertEquals(Agent.State.ON_CALL, cc.agent("2001").state());

        // blind transfer 2001 -> 2002
        String t1 = cc.reserveTransferTarget("2001");
        assertEquals("2002", t1);
        loop.execute(() -> atA.refer(SipUri.parse("sip:2002@cc.test").withHeader(SipPlatform.CORRELATION_HEADER, "C0001")
                .toString()));
        Await.until("caller with 2002", () -> b.established.size() == 1 && a.established.isEmpty());
        Await.until("2001 wrap-up done", () -> cc.agent("2001").state() == Agent.State.READY);
        assertEquals("2002", caller.only().remoteUser());

        // attended transfer 2002 -> 2003: consultation first, then REFER with Replaces
        Call atB = b.only();
        String t2 = cc.reserveTransferTarget("2002");
        assertTrue(Set.of("2001", "2003").contains(t2));
        Phone target = t2.equals("2003") ? c : a;
        Call consult = loop.call(() -> b.ua.invite("sip:" + t2 + "@cc.test",
                Map.of(SipPlatform.CORRELATION_HEADER, "C0001", "X-CC-Consult", "1")));
        Await.until("consultation up", () -> target.established.size() == 1 && b.established.size() == 2);
        loop.execute(() -> atB.refer(SipUri.parse("sip:" + t2 + "@cc.test")
                .withHeader("Replaces", consult.replacesValue())
                .withHeader(SipPlatform.CORRELATION_HEADER, "C0001").toString()));
        Await.until("caller with " + t2, () -> b.established.isEmpty() && target.established.size() == 1
                && caller.established.size() == 1 && caller.established.get(0).remoteUser().equals(t2));
        Call atTarget = target.only();
        assertEquals("C0001", atTarget.header(SipPlatform.CORRELATION_HEADER));
        assertTrue(atTarget.header("Replaces") != null, "target accepted INVITE with Replaces");

        loop.execute(atTarget::hangup);
        Await.until("cdr written", () -> cc.cdrs().size() == 1);
        Cdr cdr = cc.cdrs().get(0);
        assertEquals(Cdr.Disposition.ANSWERED, cdr.disposition());
        assertEquals(List.of("2001", "2002", t2), cdr.agents());
        assertEquals(2, cdr.transfers());
        assertEquals("1", cdr.ivrPath());
        Await.until("caller idle", () -> loop.call(() -> caller.ua.activeCalls()) == 0);
        Await.until("all agents ready", () -> cc.agents().stream().allMatch(ag -> ag.state() == Agent.State.READY));
        assertTrue(platform.mediaLog().contains("C0001 prompt queue-billing"));
    }

    @Test
    void callerAbandonsWhileQueued() {
        Phone caller = phone("caller02", null);
        Call ivr = loop.call(() -> caller.ua.invite("sip:support@cc.test", Map.of()));
        Await.until("IVR answered", () -> caller.established.size() == 1);
        loop.execute(() -> ivr.sendDtmf('3'));
        Await.until("queued", () -> cc.waiting("sales") == 1);
        loop.execute(ivr::hangup);
        Await.until("cdr", () -> cc.cdrs().size() == 1);
        assertEquals(Cdr.Disposition.ABANDONED, cc.cdrs().get(0).disposition());
        assertEquals(1, cc.stats().get("sales").abandonedCount());
    }
}
