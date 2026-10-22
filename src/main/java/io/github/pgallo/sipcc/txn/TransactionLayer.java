package io.github.pgallo.sipcc.txn;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.sip.Via;
import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.transport.Transport;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Matches messages to transactions (RFC 3261 sections 17.1.3 and 17.2.3: top Via branch plus
 * method, with ACK mapped to INVITE) and hands everything else to the transaction user.
 * Runs entirely on one event loop.
 */
public final class TransactionLayer {

    public interface User {
        void onRequest(ServerTransaction tx, SipMessage request);

        /** ACK for a 2xx (a separate transaction per RFC 3261) or any ACK that matched no transaction. */
        void onAck(SipMessage ack);

        /** A response that matched no client transaction, typically a late 2xx retransmission. */
        default void onStrayResponse(SipMessage response) {}
    }

    private final Transport transport;
    private final TimerService timer;
    private final SipTimers timers;
    private final Map<String, Object> transactions = new HashMap<>();
    private User user;

    public TransactionLayer(Transport transport, TimerService timer, SipTimers timers) {
        this.transport = transport;
        this.timer = timer;
        this.timers = timers;
    }

    public void start(User user) {
        this.user = user;
        transport.start(this::onInbound);
    }

    Transport transport() {
        return transport;
    }

    TimerService timer() {
        return timer;
    }

    SipTimers timers() {
        return timers;
    }

    public InetSocketAddress localAddress() {
        return transport.localAddress();
    }

    public int activeTransactions() {
        return transactions.size();
    }

    public void onInbound(SipMessage msg, InetSocketAddress source) {
        if (msg.isRequest()) {
            Via via = msg.topVia();
            if (via.params().containsKey("rport")) { // RFC 3581: stamp the observed source address
                msg.replaceTopVia(via.withParam("received", source.getAddress().getHostAddress())
                        .withParam("rport", String.valueOf(source.getPort())));
                via = msg.topVia();
            }
            String method = msg.method().equals("ACK") ? "INVITE" : msg.method();
            String key = "S|" + via.branch() + "|" + method;
            Object existing = transactions.get(key);
            if (existing instanceof ServerTransaction tx) {
                tx.onRequest(msg);
                return;
            }
            if (msg.method().equals("ACK")) {
                user.onAck(msg);
                return;
            }
            ServerTransaction tx = new ServerTransaction(this, key, msg, via.responseAddress());
            transactions.put(key, tx);
            if (tx.request().method().equals("INVITE")) {
                tx.sendResponse(SipMessage.responseTo(msg, 100, "Trying"));
            }
            user.onRequest(tx, msg);
        } else {
            String key = "C|" + msg.topVia().branch() + "|" + msg.cseq().method();
            if (transactions.get(key) instanceof ClientTransaction tx) {
                tx.onResponse(msg);
            } else {
                user.onStrayResponse(msg);
            }
        }
    }

    /** Pushes a Via with a fresh branch and starts a client transaction. */
    public ClientTransaction sendRequest(SipMessage request, InetSocketAddress destination,
                                         ClientTransaction.Listener listener) {
        String branch = Via.newBranch();
        request.addHeaderFirst("Via", localVia(branch).toString());
        String key = "C|" + branch + "|" + request.method();
        ClientTransaction tx = new ClientTransaction(this, key, request, destination, listener);
        transactions.put(key, tx);
        tx.start();
        return tx;
    }

    /** Sends a request outside any transaction (ACK for 2xx), pushing a Via with a fresh branch. */
    public void sendStateless(SipMessage request, InetSocketAddress destination) {
        request.addHeaderFirst("Via", localVia(Via.newBranch()).toString());
        transport.send(request, destination);
    }

    /** Proxy helper: pops our Via and sends the response to the next Via (RFC 3261 16.7 step 9). */
    public void forwardResponse(SipMessage response) {
        SipMessage r = response.copy();
        r.removeFirst("Via");
        Via next = r.topVia();
        if (next != null) {
            transport.send(r, next.responseAddress());
        }
    }

    public void sendRaw(SipMessage message, InetSocketAddress destination) {
        transport.send(message, destination);
    }

    private Via localVia(String branch) {
        InetSocketAddress a = transport.localAddress();
        return Via.of(a.getAddress().getHostAddress(), a.getPort(), branch);
    }

    void remove(String key) {
        transactions.remove(key);
    }
}
