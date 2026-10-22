package io.github.pgallo.sipcc.platform;

/**
 * Service provider interface between the contact-center core and a telephony platform.
 *
 * The core owns the business logic (IVR menus, queues, routing, agent states, records) and talks
 * to the platform only in terms of call ids and agent extensions. Two adapters implement it: a
 * SIP-native platform (the stack in this repository acting as registrar, proxy and IVR endpoint)
 * and a client for a line-oriented TCP manager interface of the kind open-source PBXs expose.
 *
 * Commands are asynchronous: failures come back as {@link PlatformListener#connectFailed} rather
 * than exceptions, because on every real platform the outcome is only known later.
 */
public interface TelephonyPlatform extends AutoCloseable {

    String name();

    void start(PlatformListener listener) throws Exception;

    /** Plays a named prompt to the caller (placeholder: no media is generated). */
    void playPrompt(String callId, String prompt);

    /** Puts the caller on music on hold while queued (placeholder). */
    void startHold(String callId);

    /** Delivers a queued call to an agent extension. */
    void connectToAgent(String callId, String agentExtension);

    /** Clears the caller's leg. */
    void hangup(String callId);

    /** Starts an outbound call from an agent to a destination; returns the call id used in later events. */
    String originate(String agentExtension, String destination);

    @Override
    void close();
}
