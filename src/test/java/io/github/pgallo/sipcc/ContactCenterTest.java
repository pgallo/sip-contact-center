package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.cc.Agent;
import io.github.pgallo.sipcc.cc.Cdr;
import io.github.pgallo.sipcc.cc.ContactCenter;
import io.github.pgallo.sipcc.cc.IvrMenu;
import io.github.pgallo.sipcc.cc.QueueStats;
import io.github.pgallo.sipcc.demo.Scenario;
import io.github.pgallo.sipcc.platform.PlatformListener.AgentCommand;
import io.github.pgallo.sipcc.platform.TelephonyPlatform;
import io.github.pgallo.sipcc.time.ManualTimerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core against a recording platform and a manual clock: no sockets, fully deterministic. */
class ContactCenterTest {

    static final class RecordingPlatform implements TelephonyPlatform {
        final List<String> commands = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void start(io.github.pgallo.sipcc.platform.PlatformListener listener) {
        }

        @Override
        public void playPrompt(String callId, String prompt) {
            commands.add("prompt " + callId + " " + prompt);
        }

        @Override
        public void startHold(String callId) {
            commands.add("hold " + callId);
        }

        @Override
        public void connectToAgent(String callId, String agent) {
            commands.add("connect " + callId + " " + agent);
        }

        @Override
        public void hangup(String callId) {
            commands.add("hangup " + callId);
        }

        @Override
        public String originate(String agent, String destination) {
            commands.add("originate " + agent + " " + destination);
            return "O1";
        }

        @Override
        public void close() {
        }

        String last() {
            return commands.get(commands.size() - 1);
        }
    }

    private ManualTimerService clock;
    private RecordingPlatform platform;
    private ContactCenter cc;

    @BeforeEach
    void setUp() {
        clock = new ManualTimerService();
        platform = new RecordingPlatform();
        cc = new ContactCenter(clock, platform, IvrMenu.standard(), Scenario.agents(), Scenario.queues(), 5_000, 8_000);
    }

    private void ready(String ext) {
        cc.agentCommand(ext, AgentCommand.LOGIN);
        cc.agentCommand(ext, AgentCommand.READY);
    }

    @Test
    void ivrQueueAgentWrapUpAndCdr() {
        ready("2001");
        cc.incomingCall("C1", "caller01", "support");
        assertEquals("prompt C1 main-menu", platform.last());
        clock.advance(2_000);
        cc.dtmf("C1", '1');
        assertEquals(List.of("prompt C1 queue-billing", "hold C1", "connect C1 2001"),
                platform.commands.subList(1, 4));
        assertEquals(Agent.State.ON_CALL, cc.agent("2001").state());

        clock.advance(1_000);
        cc.agentConnected("C1", "2001");
        clock.advance(30_000);
        cc.agentDisconnected("C1", "2001");
        cc.callEnded("C1");
        assertEquals(Agent.State.WRAP_UP, cc.agent("2001").state());
        clock.advance(5_000);
        assertEquals(Agent.State.READY, cc.agent("2001").state());

        Cdr cdr = cc.cdrs().get(0);
        assertEquals(Cdr.Disposition.ANSWERED, cdr.disposition());
        assertEquals("1", cdr.ivrPath());
        assertEquals(1_000L, cdr.waitMs());
        assertEquals(30_000L, cdr.talkMs());
        assertEquals(List.of("2001"), cdr.agents());
        assertEquals(1, cc.agent("2001").handled());
    }

    @Test
    void ivrSubmenuInvalidDigitAndTimeout() {
        cc.incomingCall("C1", "caller01", "support");
        cc.dtmf("C1", '9');
        assertTrue(platform.commands.contains("prompt C1 invalid-choice"));
        cc.dtmf("C1", '2');
        assertEquals("prompt C1 tech-menu", platform.last());
        cc.dtmf("C1", '2');
        assertEquals(1, cc.waiting("outage"));

        cc.incomingCall("C2", "caller02", "support");
        clock.advance(8_000);
        assertEquals(1, cc.waiting("general"), "no input routes to the default queue");
    }

    @Test
    void abandonAndServiceLevelStatistics() {
        cc.incomingCall("C1", "caller01", "support");
        cc.dtmf("C1", '0');
        cc.incomingCall("C2", "caller02", "support");
        cc.dtmf("C2", '0');
        clock.advance(25_000);
        cc.callEnded("C1"); // abandoned after the 20 s threshold
        ready("2005");
        assertTrue(platform.commands.contains("connect C2 2005"));
        cc.agentConnected("C2", "2005");
        cc.agentDisconnected("C2", "2005");
        cc.callEnded("C2");

        QueueStats s = cc.stats().get("general");
        assertEquals(2, s.offeredCount());
        assertEquals(1, s.answeredCount());
        assertEquals(1, s.abandonedCount());
        assertEquals(0.0, s.serviceLevel(), 1e-9, "answered at 25 s, outside the 20 s target");
        assertEquals(25_000.0, s.asaMs(), 1e-9);
        assertEquals(0.5, s.abandonRate(), 1e-9);
        assertEquals(Cdr.Disposition.ABANDONED, cc.cdrs().get(0).disposition());
    }

    @Test
    void failedDeliveryMakesAgentNotReadyAndRequeuesTheCall() {
        ready("2002");
        clock.advance(1_000);
        ready("2003");
        cc.incomingCall("C1", "caller01", "support");
        cc.dtmf("C1", '2');
        cc.dtmf("C1", '1');
        assertEquals("connect C1 2002", platform.last(), "longest idle technical agent");
        cc.connectFailed("C1", "2002", "no answer");
        assertEquals(Agent.State.NOT_READY, cc.agent("2002").state());
        assertEquals("connect C1 2003", platform.last());
    }

    @Test
    void overflowTimerRoutesToGeneralistsAndCountsIt() {
        ready("2005"); // general 3, billing 1: below the billing minimum of 2
        cc.incomingCall("C1", "caller01", "support");
        cc.dtmf("C1", '1');
        assertEquals("hold C1", platform.last());
        clock.advance(30_000);
        assertEquals("connect C1 2005", platform.last());
        assertEquals(1, cc.stats().get("billing").overflowCount());
    }

    @Test
    void transferIsCountedOnTheSameCdr() {
        ready("2001");
        ready("2005");
        cc.incomingCall("C1", "caller01", "support");
        cc.dtmf("C1", '1');
        cc.agentConnected("C1", "2001");
        String target = cc.reserveTransferTarget("2001");
        assertEquals("2005", target);
        cc.agentConnected("C1", "2005");
        cc.agentDisconnected("C1", "2001");
        cc.agentDisconnected("C1", "2005");
        cc.callEnded("C1");
        Cdr cdr = cc.cdrs().get(0);
        assertEquals(List.of("2001", "2005"), cdr.agents());
        assertEquals(1, cdr.transfers());
    }
}
