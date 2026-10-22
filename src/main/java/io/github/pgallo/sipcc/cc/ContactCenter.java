package io.github.pgallo.sipcc.cc;

import io.github.pgallo.sipcc.platform.PlatformListener;
import io.github.pgallo.sipcc.platform.TelephonyPlatform;
import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.time.TimerService.Cancellable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The contact-center core: IVR, queues, ACD, agent states, call detail records and statistics.
 * It depends only on {@link TelephonyPlatform} and reacts to {@link PlatformListener} events, so
 * the same logic runs over SIP or over a PBX manager interface. Methods are synchronized; in the
 * demo everything is called from one event loop anyway.
 */
public final class ContactCenter implements PlatformListener {

    private static final class Active {
        final String id;
        final Cdr.Direction direction;
        final String caller;
        final String dialed;
        final long arrivedAt;
        final StringBuilder path = new StringBuilder();
        final List<String> agents = new ArrayList<>();
        String node;
        String queue;
        long queuedAt = -1;
        long answeredAt = -1;
        int transfers;
        String offeredTo;
        boolean failed;
        Cancellable ivrTimer;
        Cancellable overflowTimer;

        Active(String id, Cdr.Direction direction, String caller, String dialed, long arrivedAt) {
            this.id = id;
            this.direction = direction;
            this.caller = caller;
            this.dialed = dialed;
            this.arrivedAt = arrivedAt;
        }
    }

    private final TimerService clock;
    private final TelephonyPlatform platform;
    private final IvrMenu ivr;
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, QueueConfig> queues = new LinkedHashMap<>();
    private final Map<String, QueueStats> stats = new LinkedHashMap<>();
    private final Acd acd = new Acd();
    private final Map<String, Active> calls = new HashMap<>();
    private final List<Cdr> cdrs = new ArrayList<>();
    private final long wrapUpMs;
    private final long ivrTimeoutMs;
    private Consumer<String> log = s -> { };

    public ContactCenter(TimerService clock, TelephonyPlatform platform, IvrMenu ivr, Collection<Agent> agents,
                         Collection<QueueConfig> queues, long wrapUpMs, long ivrTimeoutMs) {
        this.clock = clock;
        this.platform = platform;
        this.ivr = ivr;
        agents.forEach(a -> this.agents.put(a.extension(), a));
        queues.forEach(q -> {
            this.queues.put(q.name(), q);
            this.stats.put(q.name(), new QueueStats(q));
        });
        this.wrapUpMs = wrapUpMs;
        this.ivrTimeoutMs = ivrTimeoutMs;
    }

    public synchronized void setLog(Consumer<String> log) {
        this.log = log;
    }

    private void log(String msg) {
        log.accept(String.format("%7.1fs  %s", clock.now() / 1000.0, msg));
    }

    // ---------------------------------------------------------------- platform events

    @Override
    public synchronized void incomingCall(String callId, String caller, String dialed) {
        Active c = new Active(callId, Cdr.Direction.INBOUND, caller, dialed, clock.now());
        c.node = ivr.root();
        calls.put(callId, c);
        log(callId + " arrives from " + caller + ", IVR " + ivr.root());
        platform.playPrompt(callId, ivr.node(c.node).prompt());
        armIvrTimer(c);
    }

    private void armIvrTimer(Active c) {
        if (c.ivrTimer != null) {
            c.ivrTimer.cancel();
        }
        c.ivrTimer = clock.schedule(ivrTimeoutMs, () -> {
            synchronized (this) {
                if (calls.get(c.id) == c && c.queue == null) {
                    c.path.append('T');
                    enqueue(c, ivr.defaultQueue());
                }
            }
        });
    }

    @Override
    public synchronized void dtmf(String callId, char digit) {
        Active c = calls.get(callId);
        if (c == null || c.queue != null) {
            return;
        }
        c.path.append(digit);
        IvrMenu.Action action = ivr.select(c.node, digit);
        switch (action) {
            case null -> {
                platform.playPrompt(callId, "invalid-choice");
                platform.playPrompt(callId, ivr.node(c.node).prompt());
                armIvrTimer(c);
            }
            case IvrMenu.Goto g -> {
                c.node = g.node();
                platform.playPrompt(callId, ivr.node(c.node).prompt());
                armIvrTimer(c);
            }
            case IvrMenu.Repeat r -> {
                platform.playPrompt(callId, ivr.node(c.node).prompt());
                armIvrTimer(c);
            }
            case IvrMenu.Enqueue e -> enqueue(c, e.queue());
        }
    }

    private void enqueue(Active c, String queueName) {
        if (c.ivrTimer != null) {
            c.ivrTimer.cancel();
        }
        QueueConfig q = queues.get(queueName);
        c.queue = queueName;
        c.queuedAt = clock.now();
        stats.get(queueName).offered();
        platform.playPrompt(c.id, "queue-" + queueName);
        platform.startHold(c.id);
        acd.enqueue(c.id, q, c.queuedAt);
        log(c.id + " queued in " + queueName + " (IVR path " + c.path + ")");
        if (q.overflowAfterMs() > 0 && q.overflowSkill() != null) {
            c.overflowTimer = clock.schedule(q.overflowAfterMs(), () -> {
                synchronized (this) {
                    if (acd.isWaiting(c.id)) {
                        acd.markOverflow(c.id);
                        stats.get(queueName).overflowed();
                        log(c.id + " overflows from " + queueName + " to agents with skill " + q.overflowSkill());
                        dispatch();
                    }
                }
            });
        }
        dispatch();
    }

    private void dispatch() {
        for (Acd.Assignment a : acd.assign(agents.values())) {
            Agent agent = agents.get(a.agent());
            agent.transition(Agent.State.ON_CALL, clock.now());
            Active c = calls.get(a.callId());
            c.offeredTo = a.agent();
            log(a.callId() + " routed " + a.queue() + " -> " + a.agent() + " (" + agent.name()
                    + (a.overflow() ? ", overflow" : "") + ")");
            platform.connectToAgent(a.callId(), a.agent());
        }
    }

    @Override
    public synchronized void agentConnected(String callId, String ext) {
        Active c = calls.get(callId);
        Agent agent = agents.get(ext);
        if (c == null || agent == null) {
            return;
        }
        if (agent.state() != Agent.State.ON_CALL && agent.canTransition(Agent.State.ON_CALL)) {
            agent.transition(Agent.State.ON_CALL, clock.now());
        }
        agent.activeCalls().add(callId);
        if (ext.equals(c.offeredTo)) {
            c.offeredTo = null;
        }
        if (c.answeredAt < 0) {
            c.answeredAt = clock.now();
            if (c.queue != null) {
                stats.get(c.queue).answered(c.answeredAt - c.queuedAt);
            }
            log(callId + " answered by " + ext);
        } else if (!c.agents.contains(ext)) {
            c.transfers++;
            log(callId + " now also with " + ext + " (transfer " + c.transfers + ")");
        }
        if (!c.agents.contains(ext)) {
            c.agents.add(ext);
        }
    }

    @Override
    public synchronized void agentDisconnected(String callId, String ext) {
        Agent agent = agents.get(ext);
        if (agent == null) {
            return;
        }
        agent.activeCalls().remove(callId);
        if (agent.activeCalls().isEmpty() && agent.state() == Agent.State.ON_CALL) {
            agent.transition(Agent.State.WRAP_UP, clock.now());
            agent.countHandled();
            log(ext + " leaves " + callId + ", wrap-up");
            clock.schedule(wrapUpMs, () -> {
                synchronized (this) {
                    if (agent.state() == Agent.State.WRAP_UP) {
                        agent.transition(Agent.State.READY, clock.now());
                        dispatch();
                    }
                }
            });
        }
    }

    @Override
    public synchronized void connectFailed(String callId, String ext, String reason) {
        Agent agent = agents.get(ext);
        if (agent != null && agent.state() == Agent.State.ON_CALL && agent.activeCalls().isEmpty()) {
            agent.transition(Agent.State.NOT_READY, clock.now()); // redirect on no answer
        }
        Active c = calls.get(callId);
        log(callId + " could not reach " + ext + ": " + reason);
        if (c != null && ext.equals(c.offeredTo)) {
            c.offeredTo = null;
            if (c.direction == Cdr.Direction.OUTBOUND) {
                c.failed = true;
                endCall(callId);
            } else if (c.queue != null && c.answeredAt < 0) {
                acd.enqueue(callId, queues.get(c.queue), c.queuedAt); // keeps its original position
            }
        }
        dispatch();
    }

    @Override
    public synchronized void callEnded(String callId) {
        endCall(callId);
        dispatch();
    }

    private void endCall(String callId) {
        Active c = calls.remove(callId);
        if (c == null) {
            return;
        }
        long now = clock.now();
        for (Cancellable t : new Cancellable[] {c.ivrTimer, c.overflowTimer}) {
            if (t != null) {
                t.cancel();
            }
        }
        acd.remove(callId);
        if (c.offeredTo != null) {
            Agent a = agents.get(c.offeredTo);
            if (a.state() == Agent.State.ON_CALL && a.activeCalls().isEmpty()) {
                a.transition(Agent.State.READY, now); // the caller left before the agent answered
            }
        }
        Cdr.Disposition d;
        if (c.answeredAt >= 0) {
            d = Cdr.Disposition.ANSWERED;
        } else if (c.failed) {
            d = Cdr.Disposition.FAILED;
        } else if (c.queue != null) {
            d = Cdr.Disposition.ABANDONED;
            stats.get(c.queue).abandoned(now - c.queuedAt);
        } else {
            d = Cdr.Disposition.IVR_ONLY;
        }
        Cdr cdr = new Cdr(c.id, c.direction, c.caller, c.dialed, c.path.toString(), c.queue, c.arrivedAt, c.queuedAt,
                c.answeredAt, now, List.copyOf(c.agents), c.transfers, d);
        cdrs.add(cdr);
        log(callId + " ended: " + d + (c.agents.isEmpty() ? "" : " agents " + c.agents));
    }

    @Override
    public synchronized void agentCommand(String ext, AgentCommand command) {
        Agent a = agents.get(ext);
        if (a == null) {
            return;
        }
        long now = clock.now();
        switch (command) {
            case LOGIN -> {
                if (a.state() == Agent.State.LOGGED_OUT) {
                    a.transition(Agent.State.NOT_READY, now);
                }
            }
            case READY -> {
                if (a.state() == Agent.State.NOT_READY || a.state() == Agent.State.WRAP_UP) {
                    a.transition(Agent.State.READY, now);
                }
            }
            case NOT_READY -> {
                if (a.state() == Agent.State.READY || a.state() == Agent.State.WRAP_UP) {
                    a.transition(Agent.State.NOT_READY, now);
                }
            }
            case LOGOUT -> {
                if (a.activeCalls().isEmpty() && a.canTransition(Agent.State.LOGGED_OUT)) {
                    a.transition(Agent.State.LOGGED_OUT, now);
                }
            }
        }
        log("agent " + ext + " " + command + ", state " + a.state());
        dispatch();
    }

    // ---------------------------------------------------------------- agent-side operations

    /** Starts an outbound call for a READY agent; returns the call id, or null if the agent is not READY. */
    public synchronized String originate(String ext, String destination) {
        Agent a = agents.get(ext);
        if (a == null || a.state() != Agent.State.READY) {
            return null;
        }
        a.transition(Agent.State.ON_CALL, clock.now());
        String id = platform.originate(ext, destination);
        Active c = new Active(id, Cdr.Direction.OUTBOUND, ext, destination, clock.now());
        c.offeredTo = ext;
        calls.put(id, c);
        log(id + " outbound " + ext + " -> " + destination);
        return id;
    }

    /** Reserves the longest-idle READY agent (other than {@code exclude}) as a transfer target. */
    public synchronized String reserveTransferTarget(String exclude) {
        return agents.values().stream()
                .filter(a -> a.state() == Agent.State.READY && !a.extension().equals(exclude))
                .min(Comparator.comparingLong(Agent::idleSince))
                .map(a -> {
                    a.transition(Agent.State.ON_CALL, clock.now());
                    return a.extension();
                })
                .orElse(null);
    }

    public synchronized void releaseReservation(String ext) {
        Agent a = agents.get(ext);
        if (a != null && a.state() == Agent.State.ON_CALL && a.activeCalls().isEmpty()) {
            a.transition(Agent.State.READY, clock.now());
            dispatch();
        }
    }

    // ---------------------------------------------------------------- reporting

    public synchronized List<Cdr> cdrs() {
        return List.copyOf(cdrs);
    }

    public synchronized Map<String, QueueStats> stats() {
        return Map.copyOf(stats);
    }

    public synchronized List<String> queueNames() {
        return List.copyOf(queues.keySet());
    }

    public synchronized Agent agent(String ext) {
        return agents.get(ext);
    }

    public synchronized List<Agent> agents() {
        return List.copyOf(agents.values());
    }

    public synchronized int activeCalls() {
        return calls.size();
    }

    public synchronized int waiting(String queue) {
        return acd.waitingIn(queue);
    }

    public synchronized long longestWait(String queue) {
        return acd.longestWait(queue, clock.now());
    }

    public long now() {
        return clock.now();
    }
}
