package io.github.pgallo.sipcc.cc;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** An agent with skills (level 1..5) and a validated state machine. */
public final class Agent {

    public enum State {
        LOGGED_OUT, READY, NOT_READY, ON_CALL, WRAP_UP;

        /** Allowed successors. ON_CALL may also be entered from NOT_READY/WRAP_UP when the platform reports an answer. */
        Set<State> next() {
            return switch (this) {
                case LOGGED_OUT -> EnumSet.of(NOT_READY);
                case NOT_READY -> EnumSet.of(READY, LOGGED_OUT, ON_CALL);
                case READY -> EnumSet.of(NOT_READY, ON_CALL, LOGGED_OUT);
                case ON_CALL -> EnumSet.of(WRAP_UP, NOT_READY, READY);
                case WRAP_UP -> EnumSet.of(READY, NOT_READY, LOGGED_OUT, ON_CALL);
            };
        }
    }

    private final String extension;
    private final String name;
    private final Map<String, Integer> skills;
    private final Set<String> activeCalls = new HashSet<>();
    private State state = State.LOGGED_OUT;
    private long stateSince;
    private long idleSince;
    private int handled;
    private long talkMs;
    private long onCallSince;

    public Agent(String extension, String name, Map<String, Integer> skills) {
        this.extension = extension;
        this.name = name;
        this.skills = Map.copyOf(skills);
    }

    public String extension() {
        return extension;
    }

    public String name() {
        return name;
    }

    public State state() {
        return state;
    }

    public long stateSince() {
        return stateSince;
    }

    /** When the agent last became READY; the longest-idle rule picks the smallest value. */
    public long idleSince() {
        return idleSince;
    }

    public int skill(String skill) {
        return skills.getOrDefault(skill, 0);
    }

    public Map<String, Integer> skills() {
        return skills;
    }

    public Set<String> activeCalls() {
        return activeCalls;
    }

    public int handled() {
        return handled;
    }

    public long talkMs() {
        return talkMs;
    }

    public boolean canTransition(State to) {
        return state == to || state.next().contains(to);
    }

    public void transition(State to, long now) {
        if (state == to) {
            return;
        }
        if (!state.next().contains(to)) {
            throw new IllegalStateException("agent " + extension + ": " + state + " -> " + to + " not allowed");
        }
        if (state == State.ON_CALL) {
            talkMs += now - onCallSince;
        }
        if (to == State.ON_CALL) {
            onCallSince = now;
        }
        if (to == State.READY) {
            idleSince = now;
        }
        state = to;
        stateSince = now;
    }

    void countHandled() {
        handled++;
    }

    @Override
    public String toString() {
        return extension + "(" + name + ", " + state + ")";
    }
}
