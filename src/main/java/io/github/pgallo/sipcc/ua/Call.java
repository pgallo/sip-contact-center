package io.github.pgallo.sipcc.ua;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.time.TimerService.Cancellable;
import io.github.pgallo.sipcc.txn.ServerTransaction;

import java.util.HashMap;
import java.util.Map;

/** One INVITE dialog seen from a {@link UserAgent}. All methods must be called on the agent's event loop. */
public final class Call {

    public enum State { CALLING, RINGING, CONNECTING, ESTABLISHED, ENDED }

    final UserAgent ua;
    final String callId;
    final boolean outgoing;
    final SipMessage invite;
    final String localTag;
    State state;
    Dialog dialog;
    ServerTransaction inviteTx;
    SipMessage okResponse;
    Cancellable okTimer;
    boolean byeSent;
    Call replaces;
    Call referredBy;
    private final Map<String, Object> attributes = new HashMap<>();

    Call(UserAgent ua, String callId, boolean outgoing, SipMessage invite, String localTag) {
        this.ua = ua;
        this.callId = callId;
        this.outgoing = outgoing;
        this.invite = invite;
        this.localTag = localTag;
        this.state = outgoing ? State.CALLING : State.RINGING;
    }

    public String callId() {
        return callId;
    }

    public boolean isOutgoing() {
        return outgoing;
    }

    public State state() {
        return state;
    }

    public boolean isEstablished() {
        return state == State.ESTABLISHED;
    }

    public Dialog dialog() {
        return dialog;
    }

    /** A header of the initial INVITE, e.g. a correlation header copied from a Refer-To URI. */
    public String header(String name) {
        return invite.header(name);
    }

    /** User part of the other party's address. */
    public String remoteUser() {
        return outgoing ? invite.to().uri().user() : invite.from().uri().user();
    }

    /** User part the call was placed to. */
    public String dialedUser() {
        return invite.to().uri().user();
    }

    @SuppressWarnings("unchecked")
    public <T> T attr(String key) {
        return (T) attributes.get(key);
    }

    public void attr(String key, Object value) {
        attributes.put(key, value);
    }

    public void answer() {
        ua.answer(this);
    }

    public void reject(int status, String reason) {
        ua.reject(this, status, reason);
    }

    public void hangup() {
        ua.hangup(this);
    }

    public void sendDtmf(char digit) {
        ua.sendDtmf(this, digit);
    }

    /** Sends REFER (RFC 3515); the result arrives via {@code Listener.onReferResult}. */
    public void refer(String referTo) {
        ua.refer(this, referTo);
    }

    /** Value for a Replaces header (RFC 3891) that targets this dialog from the remote party's side. */
    public String replacesValue() {
        return callId + ";to-tag=" + dialog.id().remoteTag() + ";from-tag=" + dialog.id().localTag();
    }

    @Override
    public String toString() {
        return (outgoing ? "out " : "in ") + remoteUser() + " [" + state + "]";
    }
}
