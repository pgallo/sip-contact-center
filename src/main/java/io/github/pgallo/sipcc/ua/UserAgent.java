package io.github.pgallo.sipcc.ua;

import io.github.pgallo.sipcc.sip.DigestAuth;
import io.github.pgallo.sipcc.sip.NameAddr;
import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipUri;
import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.txn.ClientTransaction;
import io.github.pgallo.sipcc.txn.ServerTransaction;
import io.github.pgallo.sipcc.txn.SipTimers;
import io.github.pgallo.sipcc.txn.TransactionLayer;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * A SIP user agent: registration with digest authentication, INVITE dialogs as UAC and UAS
 * (with 2xx retransmission until ACK), BYE, DTMF over INFO, REFER and NOTIFY (RFC 3515) for
 * blind and attended transfer, and INVITE with Replaces (RFC 3891) to complete attended transfers.
 * Out-of-dialog requests go to an outbound proxy.
 */
public final class UserAgent implements TransactionLayer.User {

    public interface Listener {
        default void onIncoming(Call call) {
            call.answer();
        }

        default void onEstablished(Call call) {}

        default void onEnded(Call call) {}

        default void onFailed(Call call, int status) {}

        default void onDtmf(Call call, char digit) {}

        /** Final outcome of a REFER we sent, from the NOTIFY sipfrag (or the REFER's own error). */
        default void onReferResult(Call call, int status) {}

        /** We received REFER and started {@code newCall}; {@code from} is null for an out-of-dialog REFER. */
        default void onTransferRequested(Call from, Call newCall) {}

        default void onMessage(String fromUser, String text) {}
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String user;
    private final String domain;
    private final String password;
    private final TransactionLayer layer;
    private final TimerService timer;
    private final SipTimers timers;
    private final InetSocketAddress outboundProxy;
    private final String contact;
    private final Map<String, Call> calls = new HashMap<>();
    private final String regCallId = random(8) + "@reg";
    private final String regTag = random(4);
    private long regSeq;
    private int nonceCount;
    private Listener listener = new Listener() {};

    public UserAgent(String user, String domain, String password, TransactionLayer layer, TimerService timer,
                     SipTimers timers, InetSocketAddress outboundProxy) {
        this.user = user;
        this.domain = domain;
        this.password = password;
        this.layer = layer;
        this.timer = timer;
        this.timers = timers;
        this.outboundProxy = outboundProxy;
        InetSocketAddress a = layer.localAddress();
        this.contact = "sip:" + user + "@" + a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    /** Starts the transaction layer with this agent as its only user. */
    public UserAgent start() {
        layer.start(this);
        return this;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String user() {
        return user;
    }

    public String aor() {
        return "sip:" + user + "@" + domain;
    }

    public String contact() {
        return contact;
    }

    public int activeCalls() {
        return calls.size();
    }

    public List<Call> calls() {
        return List.copyOf(calls.values());
    }

    // ---------------------------------------------------------------- registration

    public void register(int expires, IntConsumer done) {
        sendRegister(expires, null, done);
    }

    private void sendRegister(int expires, String authorization, IntConsumer done) {
        SipMessage req = SipMessage.request("REGISTER", "sip:" + domain);
        req.addHeader("Max-Forwards", "70");
        req.addHeader("From", "<" + aor() + ">;tag=" + regTag);
        req.addHeader("To", "<" + aor() + ">");
        req.addHeader("Call-ID", regCallId);
        req.addHeader("CSeq", (++regSeq) + " REGISTER");
        req.addHeader("Contact", "<" + contact + ">");
        req.addHeader("Expires", String.valueOf(expires));
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        layer.sendRequest(req, outboundProxy, new ClientTransaction.Listener() {
            @Override
            public void onResponse(ClientTransaction tx, SipMessage resp) {
                if (resp.status() < 200) {
                    return;
                }
                String challenge = resp.header("WWW-Authenticate");
                if (resp.status() == 401 && authorization == null && challenge != null && password != null) {
                    String auth = DigestAuth.authorization(challenge, "REGISTER", "sip:" + domain, user, password,
                            random(4), ++nonceCount);
                    sendRegister(expires, auth, done);
                } else {
                    done.accept(resp.status());
                }
            }

            @Override
            public void onTimeout(ClientTransaction tx) {
                done.accept(408);
            }
        });
    }

    /** Sends an out-of-dialog MESSAGE (used by agents to report their state). */
    public void sendMessage(String target, String text) {
        SipMessage req = SipMessage.request("MESSAGE", target);
        req.addHeader("Max-Forwards", "70");
        req.addHeader("From", "<" + aor() + ">;tag=" + random(4));
        req.addHeader("To", "<" + target + ">");
        req.addHeader("Call-ID", random(8) + "@" + user);
        req.addHeader("CSeq", "1 MESSAGE");
        req.setBody("text/plain", text);
        layer.sendRequest(req, outboundProxy, (tx, resp) -> { });
    }

    // ---------------------------------------------------------------- outgoing calls

    /** Places a call. Headers embedded in the target URI (RFC 3261 19.1.1) are copied into the INVITE. */
    public Call invite(String target, Map<String, String> extraHeaders) {
        SipUri uri = SipUri.parse(target);
        Map<String, String> headers = new LinkedHashMap<>(uri.headers());
        headers.putAll(extraHeaders);
        SipUri ruri = uri.withoutHeaders();
        String callId = random(8) + "@" + layer.localAddress().getPort();
        String tag = random(4);
        SipMessage req = SipMessage.request("INVITE", ruri.toString());
        req.addHeader("Max-Forwards", "70");
        req.addHeader("From", "<" + aor() + ">;tag=" + tag);
        req.addHeader("To", "<" + ruri + ">");
        req.addHeader("Call-ID", callId);
        req.addHeader("CSeq", "1 INVITE");
        req.addHeader("Contact", "<" + contact + ">");
        headers.forEach(req::addHeader);
        req.setBody("application/sdp", sdp());
        Call call = new Call(this, callId, true, req, tag);
        calls.put(callId, call);
        layer.sendRequest(req, outboundProxy, new ClientTransaction.Listener() {
            @Override
            public void onResponse(ClientTransaction tx, SipMessage resp) {
                onInviteResponse(call, resp);
            }

            @Override
            public void onTimeout(ClientTransaction tx) {
                fail(call, 408);
            }
        });
        return call;
    }

    private void onInviteResponse(Call call, SipMessage resp) {
        int code = resp.status();
        if (code < 200) {
            return;
        }
        if (code >= 300) {
            fail(call, code);
            return;
        }
        if (call.dialog == null) {
            call.dialog = Dialog.asUac(call.invite, resp, contact);
        }
        layer.sendStateless(call.dialog.ack(call.invite.cseq().seq()), call.dialog.nextHop());
        if (call.state == Call.State.ENDED) {
            if (!call.byeSent) { // hung up while ringing: no CANCEL support, so clear with BYE
                call.byeSent = true;
                sendInDialog(call, "BYE");
            }
        } else if (call.state != Call.State.ESTABLISHED) {
            call.state = Call.State.ESTABLISHED;
            listener.onEstablished(call);
            notifyReferrer(call, 200, "OK");
        }
    }

    private void fail(Call call, int code) {
        boolean wasActive = call.state != Call.State.ENDED;
        call.state = Call.State.ENDED;
        calls.remove(call.callId);
        if (wasActive) {
            listener.onFailed(call, code);
            notifyReferrer(call, code, code == 408 ? "Request Timeout" : "Failed");
        }
    }

    // ---------------------------------------------------------------- incoming requests

    @Override
    public void onRequest(ServerTransaction tx, SipMessage req) {
        String method = req.method();
        if (method.equals("INVITE") && req.to().tag() == null) {
            incomingInvite(tx, req);
            return;
        }
        if (method.equals("MESSAGE")) {
            tx.sendResponse(SipMessage.responseTo(req, 200, "OK"));
            listener.onMessage(req.from().uri().user(), req.bodyText());
            return;
        }
        if (method.equals("OPTIONS")) {
            tx.sendResponse(SipMessage.responseTo(req, 200, "OK"));
            return;
        }
        if (method.equals("CANCEL")) {
            cancel(tx, req);
            return;
        }
        if (method.equals("REFER") && req.to().tag() == null) {
            tx.sendResponse(SipMessage.responseTo(req, 202, "Accepted"));
            Call newCall = invite(NameAddr.parse(req.header("Refer-To")).uri().toString(), Map.of());
            listener.onTransferRequested(null, newCall);
            return;
        }
        Call call = calls.get(req.callId());
        if (call == null || call.dialog == null || !call.dialog.matches(req)) {
            tx.sendResponse(SipMessage.responseTo(req, 481, "Call/Transaction Does Not Exist"));
            return;
        }
        if (!call.dialog.acceptRemoteSeq(req.cseq().seq())) {
            tx.sendResponse(SipMessage.responseTo(req, 500, "CSeq Out Of Order"));
            return;
        }
        switch (method) {
            case "BYE" -> {
                tx.sendResponse(SipMessage.responseTo(req, 200, "OK"));
                end(call);
            }
            case "INFO" -> {
                tx.sendResponse(SipMessage.responseTo(req, 200, "OK"));
                char digit = parseDtmf(req.bodyText());
                if (digit != 0) {
                    listener.onDtmf(call, digit);
                }
            }
            case "REFER" -> incomingRefer(tx, req, call);
            case "NOTIFY" -> {
                tx.sendResponse(SipMessage.responseTo(req, 200, "OK"));
                int status = parseSipfrag(req.bodyText());
                if (status >= 200) {
                    listener.onReferResult(call, status);
                }
            }
            case "INVITE" -> { // re-INVITE: accept with the same session
                SipMessage ok = SipMessage.responseTo(req, 200, "OK");
                ok.addHeader("Contact", "<" + contact + ">");
                ok.setBody("application/sdp", sdp());
                tx.sendResponse(ok);
            }
            default -> tx.sendResponse(SipMessage.responseTo(req, 501, "Not Implemented"));
        }
    }

    private void incomingInvite(ServerTransaction tx, SipMessage req) {
        Call replaced = null;
        String replaces = req.header("Replaces");
        if (replaces != null) {
            replaced = findReplaced(replaces);
            if (replaced == null) {
                tx.sendResponse(SipMessage.responseTo(req, 481, "Call/Transaction Does Not Exist"));
                return;
            }
        }
        Call call = new Call(this, req.callId(), false, req, random(4));
        call.inviteTx = tx;
        calls.put(call.callId, call);
        if (replaced != null) {
            call.replaces = replaced;
            answer(call);
            return;
        }
        SipMessage ringing = SipMessage.responseTo(req, 180, "Ringing");
        ringing.setHeader("To", req.to().withTag(call.localTag).toString());
        ringing.addHeader("Contact", "<" + contact + ">");
        tx.sendResponse(ringing);
        listener.onIncoming(call);
    }

    /** Matches a Replaces value against our dialogs in either tag orientation. */
    private Call findReplaced(String replaces) {
        String[] parts = replaces.split(";");
        String callId = parts[0].trim();
        String toTag = null;
        String fromTag = null;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.startsWith("to-tag=")) {
                toTag = p.substring(7);
            } else if (p.startsWith("from-tag=")) {
                fromTag = p.substring(9);
            }
        }
        Call c = calls.get(callId);
        if (c == null || c.dialog == null || c.state != Call.State.ESTABLISHED) {
            return null;
        }
        Dialog.Id id = c.dialog.id();
        boolean direct = id.localTag().equals(toTag) && id.remoteTag().equals(fromTag);
        boolean swapped = id.localTag().equals(fromTag) && id.remoteTag().equals(toTag);
        return direct || swapped ? c : null;
    }

    private void incomingRefer(ServerTransaction tx, SipMessage req, Call call) {
        tx.sendResponse(SipMessage.responseTo(req, 202, "Accepted"));
        sendNotify(call, "SIP/2.0 100 Trying", false);
        SipUri target = NameAddr.parse(req.header("Refer-To")).uri();
        Map<String, String> extra = new LinkedHashMap<>();
        String referredBy = req.header("Referred-By");
        if (referredBy != null) {
            extra.put("Referred-By", referredBy);
        }
        Call newCall = invite(target.toString(), extra);
        newCall.referredBy = call;
        listener.onTransferRequested(call, newCall);
    }

    private void notifyReferrer(Call call, int code, String reason) {
        Call origin = call.referredBy;
        if (origin != null && origin.state == Call.State.ESTABLISHED) {
            sendNotify(origin, "SIP/2.0 " + code + " " + reason, true);
        }
    }

    private void sendNotify(Call call, String sipfrag, boolean terminated) {
        SipMessage n = call.dialog.newRequest("NOTIFY");
        n.addHeader("Event", "refer");
        n.addHeader("Subscription-State", terminated ? "terminated;reason=noresource" : "active;expires=60");
        n.setBody("message/sipfrag;version=2.0", sipfrag);
        layer.sendRequest(n, call.dialog.nextHop(), (t, r) -> { });
    }

    private void cancel(ServerTransaction tx, SipMessage req) {
        tx.sendResponse(SipMessage.responseTo(req, 200, "OK"));
        Call call = calls.get(req.callId());
        if (call != null && !call.outgoing && call.state == Call.State.RINGING) {
            call.inviteTx.sendResponse(SipMessage.responseTo(call.invite, 487, "Request Terminated"));
            end(call);
        }
    }

    @Override
    public void onAck(SipMessage ack) {
        Call call = calls.get(ack.callId());
        if (call == null || call.outgoing || call.state != Call.State.CONNECTING) {
            return;
        }
        call.okTimer.cancel();
        call.state = Call.State.ESTABLISHED;
        listener.onEstablished(call);
        if (call.replaces != null) {
            call.replaces.hangup();
        }
    }

    // ---------------------------------------------------------------- call control (via Call)

    void answer(Call call) {
        if (call.outgoing || call.state != Call.State.RINGING) {
            return;
        }
        call.dialog = Dialog.asUas(call.invite, call.localTag, contact);
        call.dialog.acceptRemoteSeq(call.invite.cseq().seq());
        SipMessage ok = SipMessage.responseTo(call.invite, 200, "OK");
        ok.setHeader("To", call.invite.to().withTag(call.localTag).toString());
        ok.addHeader("Contact", "<" + contact + ">");
        ok.setBody("application/sdp", sdp());
        call.okResponse = ok;
        call.state = Call.State.CONNECTING;
        call.inviteTx.sendResponse(ok);
        retransmitOk(call, timers.t1(), 0);
    }

    /** The UAS core retransmits 2xx until ACK (RFC 3261 13.3.1.4), T1 doubling to T2, for 64*T1. */
    private void retransmitOk(Call call, long interval, long elapsed) {
        call.okTimer = timer.schedule(interval, () -> {
            if (call.state != Call.State.CONNECTING) {
                return;
            }
            long total = elapsed + interval;
            if (total >= timers.timeout64T1()) {
                sendInDialog(call, "BYE");
                end(call);
                return;
            }
            call.inviteTx.sendResponse(call.okResponse);
            retransmitOk(call, Math.min(2 * interval, timers.t2()), total);
        });
    }

    void reject(Call call, int status, String reason) {
        if (!call.outgoing && call.state == Call.State.RINGING) {
            call.inviteTx.sendResponse(SipMessage.responseTo(call.invite, status, reason));
            call.state = Call.State.ENDED;
            calls.remove(call.callId);
        }
    }

    void hangup(Call call) {
        switch (call.state) {
            case ESTABLISHED, CONNECTING -> {
                if (call.okTimer != null) {
                    call.okTimer.cancel();
                }
                call.byeSent = true;
                sendInDialog(call, "BYE");
                end(call);
            }
            case CALLING -> end(call);
            case RINGING -> reject(call, 603, "Decline");
            default -> { }
        }
    }

    void sendDtmf(Call call, char digit) {
        if (call.state != Call.State.ESTABLISHED) {
            return;
        }
        SipMessage info = call.dialog.newRequest("INFO");
        info.setBody("application/dtmf-relay", "Signal=" + digit + "\r\nDuration=160\r\n");
        layer.sendRequest(info, call.dialog.nextHop(), (t, r) -> { });
    }

    void refer(Call call, String referTo) {
        if (call.state != Call.State.ESTABLISHED) {
            listener.onReferResult(call, 481);
            return;
        }
        SipMessage refer = call.dialog.newRequest("REFER");
        refer.addHeader("Refer-To", "<" + referTo + ">");
        refer.addHeader("Referred-By", "<" + aor() + ">");
        layer.sendRequest(refer, call.dialog.nextHop(), new ClientTransaction.Listener() {
            @Override
            public void onResponse(ClientTransaction tx, SipMessage resp) {
                if (resp.status() >= 300) {
                    listener.onReferResult(call, resp.status());
                }
            }

            @Override
            public void onTimeout(ClientTransaction tx) {
                listener.onReferResult(call, 408);
            }
        });
    }

    private void sendInDialog(Call call, String method) {
        layer.sendRequest(call.dialog.newRequest(method), call.dialog.nextHop(), (t, r) -> { });
    }

    private void end(Call call) {
        if (call.state == Call.State.ENDED) {
            return;
        }
        call.state = Call.State.ENDED;
        if (call.okTimer != null) {
            call.okTimer.cancel();
        }
        calls.remove(call.callId);
        listener.onEnded(call);
    }

    // ---------------------------------------------------------------- helpers

    static char parseDtmf(String body) {
        for (String line : body.split("\r?\n")) {
            if (line.startsWith("Signal=") && line.length() > 7) {
                char c = line.charAt(7);
                return "0123456789*#ABCD".indexOf(c) >= 0 ? c : 0;
            }
        }
        return 0;
    }

    static int parseSipfrag(String body) {
        String first = body.split("\r?\n", 2)[0];
        String[] p = first.split(" ");
        try {
            return p.length >= 2 && p[0].equals("SIP/2.0") ? Integer.parseInt(p[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String sdp() {
        String host = layer.localAddress().getAddress().getHostAddress();
        return "v=0\r\no=" + user + " 1 1 IN IP4 " + host + "\r\ns=-\r\nc=IN IP4 " + host
                + "\r\nt=0 0\r\nm=audio " + (layer.localAddress().getPort() + 2) + " RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n";
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
