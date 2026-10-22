package io.github.pgallo.sipcc.platform.manager;

import io.github.pgallo.sipcc.platform.PlatformListener;
import io.github.pgallo.sipcc.platform.PlatformListener.AgentCommand;
import io.github.pgallo.sipcc.platform.TelephonyPlatform;
import io.github.pgallo.sipcc.time.TimerService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter for PBXs that expose a line-oriented TCP manager interface (the style used by
 * open-source PBXs): log in, subscribe to the event stream, and drive calls with actions.
 *
 * Mapping:
 * <pre>
 *   Newchannel (Direction: inbound)  -> incomingCall      Redirect     <- connectToAgent
 *   DTMF                             -> dtmf              Playback     <- playPrompt
 *   Bridge                           -> agentConnected    MusicOnHold  <- startHold
 *   Hangup (agent channel)           -> agentDisconnected Hangup       <- hangup
 *   Hangup (caller channel)          -> callEnded         Originate    <- originate
 *   AgentLogin / AgentLogoff / AgentPaused -> agentCommand
 * </pre>
 * Calls are identified by the PBX's CallRef (the caller channel's unique id, or the variable passed
 * with Originate). Events are re-dispatched onto the core's executor so the core stays single-threaded.
 */
public final class ManagerPlatform implements TelephonyPlatform {

    private record AgentLeg(String callRef, String agent) {}

    private final InetSocketAddress address;
    private final String user;
    private final String secret;
    private final TimerService executor;
    private final Map<String, String> callerChannels = new ConcurrentHashMap<>();
    private final Map<String, AgentLeg> agentChannels = new ConcurrentHashMap<>();
    private final AtomicLong originateSeq = new AtomicLong();
    private ManagerConnection connection;
    private PlatformListener listener;

    public ManagerPlatform(InetSocketAddress address, String user, String secret, TimerService executor) {
        this.address = address;
        this.user = user;
        this.secret = secret;
        this.executor = executor;
    }

    @Override
    public String name() {
        return "manager";
    }

    @Override
    public void start(PlatformListener listener) throws IOException {
        this.listener = listener;
        connection = ManagerConnection.connect(address, e -> executor.execute(() -> onEvent(e)), Duration.ofSeconds(5));
        connection.login(user, secret, Duration.ofSeconds(5));
    }

    public String banner() {
        return connection.banner();
    }

    void onEvent(ManagerMessage e) {
        String ref = e.get("CallRef");
        switch (e.get("Event")) {
            case "Newchannel" -> {
                if ("inbound".equals(e.get("Direction"))) {
                    callerChannels.put(ref, e.get("Channel"));
                    listener.incomingCall(ref, e.get("CallerIDNum"), e.get("Exten"));
                } else if ("outbound".equals(e.get("Direction"))) {
                    callerChannels.put(ref, e.get("Channel"));
                }
            }
            case "DTMF" -> listener.dtmf(ref, e.get("Digit").charAt(0));
            case "Bridge" -> {
                agentChannels.put(e.get("Channel2"), new AgentLeg(ref, e.get("Agent")));
                listener.agentConnected(ref, e.get("Agent"));
            }
            case "Hangup" -> {
                AgentLeg leg = agentChannels.remove(e.get("Channel"));
                if (leg != null) {
                    listener.agentDisconnected(leg.callRef(), leg.agent());
                } else if (callerChannels.remove(ref) != null) {
                    listener.callEnded(ref);
                }
            }
            case "AgentLogin" -> listener.agentCommand(e.get("Agent"), AgentCommand.LOGIN);
            case "AgentLogoff" -> listener.agentCommand(e.get("Agent"), AgentCommand.LOGOUT);
            case "AgentPaused" -> listener.agentCommand(e.get("Agent"),
                    "1".equals(e.get("Paused")) ? AgentCommand.NOT_READY : AgentCommand.READY);
            default -> { }
        }
    }

    @Override
    public void playPrompt(String callId, String prompt) {
        String ch = callerChannels.get(callId);
        if (ch != null) {
            connection.send(ManagerMessage.action("Playback").put("Channel", ch).put("File", prompt));
        }
    }

    @Override
    public void startHold(String callId) {
        String ch = callerChannels.get(callId);
        if (ch != null) {
            connection.send(ManagerMessage.action("MusicOnHold").put("Channel", ch).put("State", "on"));
        }
    }

    @Override
    public void connectToAgent(String callId, String agentExtension) {
        String ch = callerChannels.get(callId);
        if (ch == null) {
            executor.execute(() -> listener.connectFailed(callId, agentExtension, "no caller channel"));
            return;
        }
        connection.send(ManagerMessage.action("Redirect").put("Channel", ch).put("Exten", agentExtension)
                        .put("Context", "agents"))
                .whenComplete((resp, err) -> {
                    if (err != null || !resp.isSuccess()) {
                        String why = err != null ? err.getMessage() : resp.get("Message");
                        executor.execute(() -> listener.connectFailed(callId, agentExtension, why));
                    }
                });
    }

    @Override
    public void hangup(String callId) {
        String ch = callerChannels.get(callId);
        if (ch != null) {
            connection.send(ManagerMessage.action("Hangup").put("Channel", ch));
        }
    }

    @Override
    public String originate(String agentExtension, String destination) {
        String ref = "O" + originateSeq.incrementAndGet();
        connection.send(ManagerMessage.action("Originate").put("Channel", "Agent/" + agentExtension)
                        .put("Exten", destination).put("Variable", "CALLREF=" + ref))
                .whenComplete((resp, err) -> {
                    if (err != null || !resp.isSuccess()) {
                        String why = err != null ? err.getMessage() : resp.get("Message");
                        executor.execute(() -> listener.connectFailed(ref, agentExtension, why));
                    }
                });
        return ref;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
    }
}
