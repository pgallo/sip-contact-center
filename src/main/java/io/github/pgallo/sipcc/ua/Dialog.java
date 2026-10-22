package io.github.pgallo.sipcc.ua;

import io.github.pgallo.sipcc.sip.NameAddr;
import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipUri;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dialog state of RFC 3261 section 12: identifiers, sequence numbers, remote target and route set.
 * Only loose routing (;lr) is supported, so the Request-URI of in-dialog requests is always the
 * remote target and the route set travels in Route headers.
 */
public final class Dialog {

    public record Id(String callId, String localTag, String remoteTag) {}

    private final Id id;
    private final NameAddr local;
    private final NameAddr remote;
    private final List<String> routeSet;
    private final String localContact;
    private SipUri remoteTarget;
    private long localSeq;
    private long remoteSeq;

    private Dialog(Id id, NameAddr local, NameAddr remote, SipUri remoteTarget, List<String> routeSet,
                   long localSeq, long remoteSeq, String localContact) {
        this.id = id;
        this.local = local;
        this.remote = remote;
        this.remoteTarget = remoteTarget;
        this.routeSet = List.copyOf(routeSet);
        this.localSeq = localSeq;
        this.remoteSeq = remoteSeq;
        this.localContact = localContact;
    }

    /** UAC side, from the request and its 2xx: the route set is the Record-Route list reversed (12.1.2). */
    public static Dialog asUac(SipMessage request, SipMessage response, String localContact) {
        List<String> routes = new ArrayList<>(response.headerValues("Record-Route"));
        Collections.reverse(routes);
        NameAddr to = response.to();
        return new Dialog(new Id(request.callId(), request.from().tag(), to.tag()), request.from(), to,
                NameAddr.parse(response.header("Contact")).uri(), routes, request.cseq().seq(), -1, localContact);
    }

    /** UAS side, from the request: the route set is the Record-Route list in order (12.1.1). */
    public static Dialog asUas(SipMessage request, String localTag, String localContact) {
        NameAddr from = request.from();
        return new Dialog(new Id(request.callId(), localTag, from.tag()), request.to().withTag(localTag), from,
                NameAddr.parse(request.header("Contact")).uri(), request.headerValues("Record-Route"),
                1000, request.cseq().seq(), localContact);
    }

    public Id id() {
        return id;
    }

    public NameAddr remote() {
        return remote;
    }

    public NameAddr local() {
        return local;
    }

    public SipUri remoteTarget() {
        return remoteTarget;
    }

    public List<String> routeSet() {
        return routeSet;
    }

    public long localSeq() {
        return localSeq;
    }

    public SipMessage newRequest(String method) {
        return build(method, ++localSeq);
    }

    /** ACK for a 2xx: an in-dialog request reusing the INVITE's CSeq number (13.2.2.4). */
    public SipMessage ack(long inviteSeq) {
        return build("ACK", inviteSeq);
    }

    private SipMessage build(String method, long seq) {
        SipMessage m = SipMessage.request(method, remoteTarget.toString());
        for (String r : routeSet) {
            m.addHeader("Route", r);
        }
        m.addHeader("Max-Forwards", "70");
        m.addHeader("From", local.toString());
        m.addHeader("To", remote.toString());
        m.addHeader("Call-ID", id.callId());
        m.addHeader("CSeq", seq + " " + method);
        m.addHeader("Contact", "<" + localContact + ">");
        return m;
    }

    /** Next hop for in-dialog requests: the first route if any, otherwise the remote target. */
    public InetSocketAddress nextHop() {
        if (!routeSet.isEmpty()) {
            return NameAddr.parse(routeSet.get(0)).uri().address();
        }
        return remoteTarget.address();
    }

    /** True if a request belongs to this dialog (Call-ID, To tag = local tag, From tag = remote tag). */
    public boolean matches(SipMessage request) {
        return id.callId().equals(request.callId()) && id.localTag().equals(request.to().tag())
                && id.remoteTag().equals(request.from().tag());
    }

    /** Enforces increasing remote CSeq (12.2.2); returns false if the request is out of order. */
    public boolean acceptRemoteSeq(long seq) {
        if (remoteSeq >= 0 && seq <= remoteSeq) {
            return false;
        }
        remoteSeq = seq;
        return true;
    }

    public void updateRemoteTarget(SipUri target) {
        this.remoteTarget = target;
    }
}
