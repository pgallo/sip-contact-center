package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.cc.Agent;
import io.github.pgallo.sipcc.cc.Cdr;
import io.github.pgallo.sipcc.cc.ContactCenter;
import io.github.pgallo.sipcc.cc.IvrMenu;
import io.github.pgallo.sipcc.demo.Scenario;
import io.github.pgallo.sipcc.platform.manager.ManagerConnection;
import io.github.pgallo.sipcc.platform.manager.ManagerMessage;
import io.github.pgallo.sipcc.platform.manager.ManagerPlatform;
import io.github.pgallo.sipcc.platform.manager.SimulatedPbx;
import io.github.pgallo.sipcc.time.EventLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manager-interface adapter against the in-process simulated PBX over real TCP. */
class ManagerPlatformTest {

    private SimulatedPbx pbx;
    private EventLoop loop;

    @BeforeEach
    void setUp() throws IOException {
        pbx = new SimulatedPbx("ccmanager", "synthetic-secret", 20);
        loop = new EventLoop("test-loop");
    }

    @AfterEach
    void tearDown() {
        pbx.close();
        loop.close();
    }

    @Test
    void messageCodecRoundTripsAndRejectsLineBreaks() throws IOException {
        ManagerMessage m = ManagerMessage.action("Originate").put("Channel", "Agent/2001")
                .put("Variable", "A=1").put("Variable", "B=2: with colon");
        ManagerMessage back = ManagerMessage.read(new BufferedReader(new StringReader("\r\n" + m.encode())));
        assertEquals("Originate", back.get("action"));
        assertEquals(List.of("A=1", "B=2: with colon"), back.getAll("Variable"));
        assertThrows(IllegalArgumentException.class, () -> ManagerMessage.action("X").put("K", "a\r\nAction: Hangup"));
        assertThrows(IOException.class, () -> ManagerMessage.read(new BufferedReader(new StringReader("no colon\r\n\r\n"))));
    }

    @Test
    void loginIsRequiredAndWrongSecretIsRejected() throws Exception {
        try (ManagerConnection c = ManagerConnection.connect(pbx.address(), e -> { }, Duration.ofSeconds(2))) {
            assertEquals(SimulatedPbx.BANNER, c.banner());
            ManagerMessage denied = c.send(ManagerMessage.action("Ping")).get(2, TimeUnit.SECONDS);
            assertEquals("Permission denied", denied.get("Message"));
            assertThrows(IOException.class, () -> c.login("ccmanager", "wrong", Duration.ofSeconds(2)));
        }
    }

    @Test
    void responsesAreCorrelatedByActionId() throws Exception {
        try (ManagerConnection c = ManagerConnection.connect(pbx.address(), e -> { }, Duration.ofSeconds(2))) {
            c.login("ccmanager", "synthetic-secret", Duration.ofSeconds(2));
            List<CompletableFuture<ManagerMessage>> futures = new ArrayList<>();
            futures.add(c.send(ManagerMessage.action("Ping")));
            futures.add(c.send(ManagerMessage.action("Redirect").put("Channel", "Trunk/none").put("Exten", "2001")));
            futures.add(c.send(ManagerMessage.action("Bogus")));
            List<ManagerMessage> r = new ArrayList<>();
            for (var f : futures) {
                r.add(f.get(2, TimeUnit.SECONDS));
            }
            assertEquals("Pong", r.get(0).get("Message"));
            assertEquals("No such channel", r.get(1).get("Message"));
            assertEquals("Invalid/unknown command", r.get(2).get("Message"));
            assertFalse(r.get(1).isSuccess());
            assertEquals(0, c.pendingActions());
        }
    }

    @Test
    void inboundCallFlowsThroughIvrQueueAndAgent() throws Exception {
        ManagerPlatform platform = new ManagerPlatform(pbx.address(), "ccmanager", "synthetic-secret", loop);
        ContactCenter cc = new ContactCenter(loop, platform, IvrMenu.standard(), Scenario.agents(), Scenario.queues(),
                10, 60_000);
        platform.start(cc);
        pbx.agentLogin("2001");
        pbx.agentPaused("2001", false);
        Await.until("agent ready", () -> cc.agent("2001").state() == Agent.State.READY);

        String ref = pbx.incomingCall("+99900000001", "800");
        Await.until("call in IVR", () -> cc.activeCalls() == 1);
        pbx.dtmf(ref, '1');
        Await.until("agent on call", () -> cc.agent("2001").activeCalls().contains(ref));
        assertTrue(pbx.agentHangup("2001"));
        Await.until("cdr", () -> cc.cdrs().size() == 1);

        Cdr cdr = cc.cdrs().get(0);
        assertEquals(Cdr.Disposition.ANSWERED, cdr.disposition());
        assertEquals("billing", cdr.queue());
        assertEquals(List.of("2001"), cdr.agents());
        assertEquals(0, pbx.activeChannels());
        List<String> actions = pbx.actionLog();
        assertTrue(actions.contains("Playback Trunk/+99900000001-" + ref + " main-menu"), actions.toString());
        assertTrue(actions.contains("Redirect Trunk/+99900000001-" + ref + " -> 2001"), actions.toString());
        Await.until("wrap-up ends", () -> cc.agent("2001").state() == Agent.State.READY);
        platform.close();
    }

    @Test
    void failedRedirectAndOriginate() throws Exception {
        ManagerPlatform platform = new ManagerPlatform(pbx.address(), "ccmanager", "synthetic-secret", loop);
        ContactCenter cc = new ContactCenter(loop, platform, IvrMenu.standard(), Scenario.agents(), Scenario.queues(),
                10, 60_000);
        platform.start(cc);
        // 2005 is READY in the core but never logged in on the PBX: the Redirect is refused
        cc.agentCommand("2005", io.github.pgallo.sipcc.platform.PlatformListener.AgentCommand.LOGIN);
        cc.agentCommand("2005", io.github.pgallo.sipcc.platform.PlatformListener.AgentCommand.READY);
        String ref = pbx.incomingCall("+99900000002", "800");
        Await.until("call in IVR", () -> cc.activeCalls() == 1);
        pbx.dtmf(ref, '0');
        Await.until("agent marked not ready", () -> cc.agent("2005").state() == Agent.State.NOT_READY);
        assertEquals(1, cc.waiting("general"), "call back in queue");
        pbx.callerHangup(ref);
        Await.until("abandoned", () -> cc.cdrs().size() == 1);
        assertEquals(Cdr.Disposition.ABANDONED, cc.cdrs().get(0).disposition());

        pbx.agentLogin("2004");
        pbx.agentPaused("2004", false);
        Await.until("2004 ready", () -> cc.agent("2004").state() == Agent.State.READY);
        String out = loop.call(() -> cc.originate("2004", "+99900000099"));
        Await.until("outbound bridged", () -> cc.agent("2004").activeCalls().contains(out));
        pbx.agentHangup("2004");
        Await.until("outbound cdr", () -> cc.cdrs().size() == 2);
        Cdr cdr = cc.cdrs().get(1);
        assertEquals(Cdr.Direction.OUTBOUND, cdr.direction());
        assertEquals(Cdr.Disposition.ANSWERED, cdr.disposition());
        platform.close();
    }
}
