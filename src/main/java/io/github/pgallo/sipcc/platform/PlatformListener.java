package io.github.pgallo.sipcc.platform;

/** Events a telephony platform reports to the contact-center core. */
public interface PlatformListener {

    enum AgentCommand { LOGIN, READY, NOT_READY, LOGOUT }

    void incomingCall(String callId, String caller, String dialed);

    void dtmf(String callId, char digit);

    /** An agent is now in conversation on this call (first answer, transfer target or consultation). */
    void agentConnected(String callId, String agent);

    /** The agent no longer has any leg on this call. */
    void agentDisconnected(String callId, String agent);

    /** A {@link TelephonyPlatform#connectToAgent} attempt did not reach the agent. */
    void connectFailed(String callId, String agent, String reason);

    /** No leg of the call remains. */
    void callEnded(String callId);

    void agentCommand(String agent, AgentCommand command);
}
