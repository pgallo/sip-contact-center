package io.github.pgallo.sipcc.platform.sip;

import io.github.pgallo.sipcc.platform.PlatformListener;
import io.github.pgallo.sipcc.platform.PlatformListener.AgentCommand;
import io.github.pgallo.sipcc.platform.TelephonyPlatform;
import io.github.pgallo.sipcc.sip.NameAddr;
import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.SipUri;
import io.github.pgallo.sipcc.time.EventLoop;
import io.github.pgallo.sipcc.transport.UdpTransport;
import io.github.pgallo.sipcc.txn.ClientTransaction;
import io.github.pgallo.sipcc.txn.ServerTransaction;
import io.github.pgallo.sipcc.txn.SipTimers;
import io.github.pgallo.sipcc.txn.TransactionLayer;
import io.github.pgallo.sipcc.ua.Call;
import io.github.pgallo.sipcc.ua.UserAgent;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SIP-native platform adapter: one UDP endpoint that is at the same time
 * <ul>
 *   <li>a registrar for agents and callers (digest-authenticated),</li>
 *   <li>a transaction-stateful, record-routing proxy for calls between registered parties, and</li>
 *   <li>the IVR/queue endpoint ("service" user) that answers callers, collects DTMF and hands the
 *       caller to an agent with REFER.</li>
 * </ul>
 * Because every dialog that carries a contact-center call is record-routed through the proxy, the
 * platform sees each 2xx and BYE and turns them into per-agent connect/disconnect events. Legs are
 * correlated with the X-CC-Call header that travels inside Refer-To URIs.
 */
public final class SipPlatform implements TelephonyPlatform, TransactionLayer.User, UserAgent.Listener {

    public static final String CORRELATION_HEADER = "X-CC-Call";

    private record Leg(String ccId, Set<String> agents) {}

    private static final class CcCall {
        final String id;
        final Call ivrCall;
        String pendingAgent;

        CcCall(String id, Call ivrCall) {
            this.id = id;
            this.ivrCall = ivrCall;
        }
    }

    private final EventLoop loop;
    private final String domain;
    private final String serviceUser;
    private final Set<String> agents;
    private final UdpTransport transport;
    private final TransactionLayer layer;
    private final Registrar registrar;
    private final String host;
    private final int port;
    private final SipTimers timers;
    private final Map<String, Leg> legs = new HashMap<>();
    private final Map<String, CcCall> ccCalls = new HashMap<>();
    private final List<String> mediaLog = Collections.synchronizedList(new ArrayList<>());
    private UserAgent ivr;
    private PlatformListener listener;
    private int seq;
    private long proxied;

    public SipPlatform(EventLoop loop, int port, String domain, String serviceUser, Map<String, String> accounts,
                       Set<String> agents, SipTimers timers) {
        this.loop = loop;
        this.domain = domain;
        this.serviceUser = serviceUser;
        this.agents = Set.copyOf(agents);
        this.timers = timers;
        this.transport = new UdpTransport(loop, port);
        this.layer = new TransactionLayer(transport, loop, timers);
        this.host = transport.localAddress().getAddress().getHostAddress();
        this.port = transport.localAddress().getPort();
        this.registrar = new Registrar(domain, Map.copyOf(accounts), loop::now, this::bindingChanged);
    }

    @Override
    public String name() {
        return "sip";
    }

    public InetSocketAddress address() {
        return transport.localAddress();
    }

    public long proxiedRequests() {
        return proxied;
    }

    public List<String> mediaLog() {
        synchronized (mediaLog) {
            return List.copyOf(mediaLog);
        }
    }

    @Override
    public void start(PlatformListener listener) {
        this.listener = listener;
        loop.call(() -> {
            ivr = new UserAgent(serviceUser, domain, null, layer, loop, timers, transport.localAddress());
            ivr.setListener(this);
            layer.start(this);
            return null;
        });
    }

    // ---------------------------------------------------------------- dispatch

    @Override
    public void onRequest(ServerTransaction tx, SipMessage req) {
        if (routedToUs(req)) {
            proxy(tx, req, true);
            return;
        }
        if (req.method().equals("REGISTER")) {
            tx.sendResponse(registrar.handle(req));
            return;
        }
        SipUri ruri = SipUri.parse(req.requestUri());
        boolean local = ruri.host().equalsIgnoreCase(domain) || ruri.sameHostPort(host, port);
        boolean ivrDialog = ivr.calls().stream().anyMatch(c -> c.callId().equals(req.callId()));
        if ((local && serviceUser.equals(ruri.user())) || ivrDialog) {
            ivr.onRequest(tx, req);
        } else {
            proxy(tx, req, false);
        }
    }

    @Override
    public void onAck(SipMessage ack) {
        if (!routedToUs(ack)) {
            ivr.onAck(ack);
            return;
        }
        SipMessage fwd = ack.copy();
        fwd.removeFirst("Route");
        layer.sendStateless(fwd, nextHop(fwd));
    }

    @Override
    public void onStrayResponse(SipMessage response) {
        layer.forwardResponse(response); // e.g. a 2xx retransmission after our client transaction ended
    }

    private boolean routedToUs(SipMessage req) {
        String route = req.header("Route");
        return route != null && NameAddr.parse(route).uri().sameHostPort(host, port);
    }

    // ---------------------------------------------------------------- proxy (RFC 3261 section 16)

    private void proxy(ServerTransaction tx, SipMessage req, boolean popRoute) {
        SipMessage fwd = req.copy();
        String mfHeader = fwd.header("Max-Forwards");
        int mf = mfHeader == null ? 70 : Integer.parseInt(mfHeader.trim());
        if (mf <= 0) {
            tx.sendResponse(SipMessage.responseTo(req, 483, "Too Many Hops"));
            return;
        }
        fwd.setHeader("Max-Forwards", String.valueOf(mf - 1));
        if (popRoute) {
            fwd.removeFirst("Route");
        }
        SipUri ruri = SipUri.parse(fwd.requestUri());
        if (ruri.host().equalsIgnoreCase(domain)) {
            var target = registrar.lookup(ruri.user());
            if (target.isEmpty()) {
                tx.sendResponse(SipMessage.responseTo(req, 480, "Temporarily Unavailable"));
                return;
            }
            fwd = fwd.withRequestUri(target.get().toString());
        }
        boolean initialInvite = fwd.method().equals("INVITE") && fwd.to().tag() == null;
        if (initialInvite) {
            fwd.addHeaderFirst("Record-Route", "<sip:" + host + ":" + port + ";lr>");
        }
        if (fwd.method().equals("BYE")) {
            removeLeg(fwd.callId());
        }
        proxied++;
        SipMessage forwarded = fwd;
        layer.sendRequest(fwd, nextHop(fwd), new ClientTransaction.Listener() {
            @Override
            public void onResponse(ClientTransaction ctx, SipMessage resp) {
                if (resp.status() == 100) {
                    return; // 100 is hop-by-hop
                }
                if (initialInvite && resp.status() >= 200 && resp.status() < 300) {
                    observeAnswer(forwarded, resp);
                }
                SipMessage up = resp.copy();
                up.removeFirst("Via");
                tx.sendResponse(up);
            }

            @Override
            public void onTimeout(ClientTransaction ctx) {
                tx.sendResponse(SipMessage.responseTo(req, 408, "Request Timeout"));
            }
        });
    }

    private static InetSocketAddress nextHop(SipMessage req) {
        String route = req.header("Route");
        return route != null ? NameAddr.parse(route).uri().address() : SipUri.parse(req.requestUri()).address();
    }

    // ---------------------------------------------------------------- leg tracking

    private void observeAnswer(SipMessage invite, SipMessage resp) {
        String cc = invite.header(CORRELATION_HEADER);
        if (cc == null || legs.containsKey(resp.callId())) {
            return;
        }
        Set<String> involved = new LinkedHashSet<>();
        for (String u : new String[] {invite.from().uri().user(), invite.to().uri().user()}) {
            if (agents.contains(u)) {
                involved.add(u);
            }
        }
        List<String> fresh = involved.stream().filter(a -> !agentOnCall(cc, a)).toList();
        legs.put(resp.callId(), new Leg(cc, involved));
        ccCalls.computeIfAbsent(cc, id -> new CcCall(id, null));
        fresh.forEach(a -> listener.agentConnected(cc, a));
    }

    private boolean agentOnCall(String cc, String agent) {
        return legs.values().stream().anyMatch(l -> l.ccId().equals(cc) && l.agents().contains(agent));
    }

    private void removeLeg(String sipCallId) {
        Leg leg = legs.remove(sipCallId);
        if (leg == null) {
            return;
        }
        for (String a : leg.agents()) {
            if (!agentOnCall(leg.ccId(), a)) {
                listener.agentDisconnected(leg.ccId(), a);
            }
        }
        if (legs.values().stream().noneMatch(l -> l.ccId().equals(leg.ccId()))) {
            ccCalls.remove(leg.ccId());
            listener.callEnded(leg.ccId());
        }
    }

    private void bindingChanged(String user, boolean bound) {
        if (agents.contains(user)) {
            listener.agentCommand(user, bound ? AgentCommand.LOGIN : AgentCommand.LOGOUT);
        }
    }

    // ---------------------------------------------------------------- IVR endpoint (UserAgent.Listener)

    @Override
    public void onIncoming(Call call) {
        String cc = String.format("C%04d", ++seq);
        call.attr("cc", cc);
        ccCalls.put(cc, new CcCall(cc, call));
        legs.put(call.callId(), new Leg(cc, Set.of()));
        call.answer();
        listener.incomingCall(cc, call.remoteUser(), call.dialedUser());
    }

    @Override
    public void onDtmf(Call call, char digit) {
        listener.dtmf(call.attr("cc"), digit);
    }

    @Override
    public void onEnded(Call call) {
        removeLeg(call.callId());
    }

    @Override
    public void onFailed(Call call, int status) {
        removeLeg(call.callId());
    }

    @Override
    public void onReferResult(Call call, int status) {
        String cc = call.attr("cc");
        CcCall c = ccCalls.get(cc);
        String agent = c == null ? null : c.pendingAgent;
        if (c != null) {
            c.pendingAgent = null;
        }
        if (status >= 200 && status < 300) {
            call.hangup(); // the transferor clears its leg once the transfer succeeded (RFC 5589)
        } else if (agent != null) {
            listener.connectFailed(cc, agent, "SIP " + status);
        }
    }

    @Override
    public void onMessage(String fromUser, String text) {
        if (!agents.contains(fromUser)) {
            return;
        }
        switch (text.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "READY" -> listener.agentCommand(fromUser, AgentCommand.READY);
            case "NOT_READY" -> listener.agentCommand(fromUser, AgentCommand.NOT_READY);
            case "LOGOUT" -> listener.agentCommand(fromUser, AgentCommand.LOGOUT);
            default -> { }
        }
    }

    // ---------------------------------------------------------------- TelephonyPlatform commands

    @Override
    public void playPrompt(String callId, String prompt) {
        mediaLog.add(callId + " prompt " + prompt);
    }

    @Override
    public void startHold(String callId) {
        mediaLog.add(callId + " hold");
    }

    @Override
    public void connectToAgent(String callId, String agentExtension) {
        loop.execute(() -> {
            CcCall c = ccCalls.get(callId);
            if (c == null || c.ivrCall == null || !c.ivrCall.isEstablished()) {
                listener.connectFailed(callId, agentExtension, "caller leg not available");
                return;
            }
            c.pendingAgent = agentExtension;
            SipUri target = SipUri.parse("sip:" + agentExtension + "@" + domain).withHeader(CORRELATION_HEADER, callId);
            c.ivrCall.refer(target.toString());
        });
    }

    @Override
    public void hangup(String callId) {
        loop.execute(() -> {
            CcCall c = ccCalls.get(callId);
            if (c != null && c.ivrCall != null) {
                c.ivrCall.hangup();
            }
        });
    }

    /** Click-to-dial: an out-of-dialog REFER asks the agent's phone to call the destination. */
    @Override
    public String originate(String agentExtension, String destination) {
        String cc;
        synchronized (this) {
            cc = String.format("O%04d", ++seq);
        }
        loop.execute(() -> {
            var contact = registrar.lookup(agentExtension);
            if (contact.isEmpty()) {
                listener.connectFailed(cc, agentExtension, "agent not registered");
                return;
            }
            SipMessage refer = SipMessage.request("REFER", contact.get().toString());
            refer.addHeader("Max-Forwards", "70");
            refer.addHeader("From", "<sip:" + serviceUser + "@" + domain + ">;tag=" + cc);
            refer.addHeader("To", "<sip:" + agentExtension + "@" + domain + ">");
            refer.addHeader("Call-ID", cc + "@" + host);
            refer.addHeader("CSeq", "1 REFER");
            refer.addHeader("Contact", "<sip:" + serviceUser + "@" + host + ":" + port + ">");
            refer.addHeader("Refer-To", "<" + SipUri.parse(destination).withHeader(CORRELATION_HEADER, cc) + ">");
            layer.sendRequest(refer, contact.get().address(), (tx, resp) -> {
                if (resp.status() >= 300) {
                    listener.connectFailed(cc, agentExtension, "SIP " + resp.status());
                }
            });
        });
        return cc;
    }

    @Override
    public void close() {
        transport.close();
    }
}
