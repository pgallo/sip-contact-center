package io.github.pgallo.sipcc;

import io.github.pgallo.sipcc.cc.Acd;
import io.github.pgallo.sipcc.cc.Agent;
import io.github.pgallo.sipcc.cc.QueueConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingTest {

    private static final QueueConfig BILLING = QueueConfig.of("billing", "billing", 2, 1).withOverflow(30_000, "general");
    private static final QueueConfig OUTAGE = QueueConfig.of("outage", "technical", 1, 5);
    private static final QueueConfig TECH = QueueConfig.of("technical", "technical", 2, 1);

    private static Agent ready(String ext, Map<String, Integer> skills, long readyAt) {
        Agent a = new Agent(ext, ext, skills);
        a.transition(Agent.State.NOT_READY, 0);
        a.transition(Agent.State.READY, readyAt);
        return a;
    }

    @Test
    void longestIdleEligibleAgentWins() {
        Agent recent = ready("a1", Map.of("billing", 3), 500);
        Agent idle = ready("a2", Map.of("billing", 2), 100);
        Acd acd = new Acd();
        acd.enqueue("c1", BILLING, 1_000);
        List<Acd.Assignment> out = acd.assign(List.of(recent, idle));
        assertEquals(List.of(new Acd.Assignment("c1", "a2", "billing", false)), out);
        assertEquals(0, acd.size());
    }

    @Test
    void minimumSkillLevelIsEnforced() {
        Agent junior = ready("a1", Map.of("billing", 1), 0);
        Acd acd = new Acd();
        acd.enqueue("c1", BILLING, 0);
        assertTrue(acd.assign(List.of(junior)).isEmpty());
        assertEquals(1, acd.waitingIn("billing"));
    }

    @Test
    void higherPriorityQueueIsServedFirstThenLongestWait() {
        Agent tech = ready("a1", Map.of("technical", 3), 0);
        Acd acd = new Acd();
        acd.enqueue("old-normal", TECH, 0);
        acd.enqueue("new-urgent", OUTAGE, 5_000);
        assertEquals("new-urgent", acd.assign(List.of(tech)).get(0).callId());

        Agent tech2 = ready("a2", Map.of("technical", 3), 0);
        acd.enqueue("newer-normal", TECH, 6_000);
        assertEquals("old-normal", acd.assign(List.of(tech2)).get(0).callId());
    }

    @Test
    void overflowWidensTheAgentPool() {
        Agent generalist = ready("a1", Map.of("general", 2), 0);
        Acd acd = new Acd();
        acd.enqueue("c1", BILLING, 0);
        assertTrue(acd.assign(List.of(generalist)).isEmpty());
        acd.markOverflow("c1");
        assertEquals(List.of(new Acd.Assignment("c1", "a1", "billing", true)), acd.assign(List.of(generalist)));
    }

    @Test
    void notReadyAgentsAreSkipped() {
        Agent a = ready("a1", Map.of("billing", 3), 0);
        a.transition(Agent.State.ON_CALL, 10);
        Acd acd = new Acd();
        acd.enqueue("c1", BILLING, 0);
        assertTrue(acd.assign(List.of(a)).isEmpty());
    }

    @Test
    void agentStateMachineRejectsIllegalTransitionsAndTracksTalkTime() {
        Agent a = new Agent("a1", "A", Map.of());
        assertThrows(IllegalStateException.class, () -> a.transition(Agent.State.READY, 0));
        a.transition(Agent.State.NOT_READY, 0);
        a.transition(Agent.State.READY, 100);
        assertEquals(100L, a.idleSince());
        a.transition(Agent.State.ON_CALL, 200);
        assertThrows(IllegalStateException.class, () -> a.transition(Agent.State.LOGGED_OUT, 250));
        a.transition(Agent.State.WRAP_UP, 1_200);
        assertEquals(1_000L, a.talkMs());
        a.transition(Agent.State.READY, 1_500);
        a.transition(Agent.State.LOGGED_OUT, 2_000);
        assertEquals(Agent.State.LOGGED_OUT, a.state());
    }
}
