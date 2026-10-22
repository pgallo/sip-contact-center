package io.github.pgallo.sipcc.cc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Automatic call distribution. Waiting calls are served in order of queue priority, then wait
 * time; for each call the eligible READY agent that has been idle longest wins, with skill level
 * as tie-breaker. A call that has waited past its queue's overflow threshold also becomes
 * eligible for agents holding the overflow skill.
 */
public final class Acd {

    public record Assignment(String callId, String agent, String queue, boolean overflow) {}

    private static final class Waiting {
        final String callId;
        final QueueConfig queue;
        final long since;
        boolean overflowed;

        Waiting(String callId, QueueConfig queue, long since) {
            this.callId = callId;
            this.queue = queue;
            this.since = since;
        }
    }

    private final List<Waiting> waiting = new ArrayList<>();

    public void enqueue(String callId, QueueConfig queue, long since) {
        waiting.add(new Waiting(callId, queue, since));
    }

    public boolean remove(String callId) {
        return waiting.removeIf(w -> w.callId.equals(callId));
    }

    public void markOverflow(String callId) {
        waiting.stream().filter(w -> w.callId.equals(callId)).forEach(w -> w.overflowed = true);
    }

    public boolean isWaiting(String callId) {
        return waiting.stream().anyMatch(w -> w.callId.equals(callId));
    }

    public int waitingIn(String queue) {
        return (int) waiting.stream().filter(w -> w.queue.name().equals(queue)).count();
    }

    public long longestWait(String queue, long now) {
        return waiting.stream().filter(w -> w.queue.name().equals(queue)).mapToLong(w -> now - w.since).max().orElse(0);
    }

    public int size() {
        return waiting.size();
    }

    /** Matches waiting calls to READY agents; matched calls leave the queue. Agents' states are not changed here. */
    public List<Assignment> assign(Collection<Agent> agents) {
        List<Waiting> order = new ArrayList<>(waiting);
        order.sort(Comparator.comparingInt((Waiting w) -> -w.queue.priority()).thenComparingLong(w -> w.since));
        Set<String> taken = new HashSet<>();
        List<Assignment> out = new ArrayList<>();
        for (Waiting w : order) {
            Optional<Agent> best = agents.stream()
                    .filter(a -> a.state() == Agent.State.READY && !taken.contains(a.extension()))
                    .filter(a -> primary(a, w) || overflow(a, w))
                    .min(Comparator.comparingLong(Agent::idleSince)
                            .thenComparing(a -> -a.skill(w.queue.skill()))
                            .thenComparing(Agent::extension));
            if (best.isPresent()) {
                Agent a = best.get();
                taken.add(a.extension());
                waiting.remove(w);
                out.add(new Assignment(w.callId, a.extension(), w.queue.name(), !primary(a, w)));
            }
        }
        return out;
    }

    private static boolean primary(Agent a, Waiting w) {
        return a.skill(w.queue.skill()) >= w.queue.minLevel();
    }

    private static boolean overflow(Agent a, Waiting w) {
        return w.overflowed && w.queue.overflowSkill() != null && a.skill(w.queue.overflowSkill()) >= 1;
    }
}
