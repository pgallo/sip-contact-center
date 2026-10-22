package io.github.pgallo.sipcc.txn;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.time.TimerService.Cancellable;

import java.net.InetSocketAddress;

/**
 * Client transaction state machines of RFC 3261 section 17.1, with the Accepted state of
 * RFC 6026 for INVITE so that 2xx retransmissions still reach the transaction user.
 *
 * INVITE:     Calling -(1xx)-> Proceeding -(2xx)-> Accepted -(Timer M)-> Terminated
 *                     \-(300-699, send ACK)-> Completed -(Timer D)-> Terminated
 *             Timer A retransmits in Calling (T1, 2T1, 4T1...), Timer B (64*T1) times out.
 * non-INVITE: Trying -(1xx)-> Proceeding -(final)-> Completed -(Timer K = T4)-> Terminated
 *             Timer E retransmits (doubling, capped at T2), Timer F (64*T1) times out.
 */
public final class ClientTransaction {

    public enum State { CALLING, TRYING, PROCEEDING, ACCEPTED, COMPLETED, TERMINATED }

    public interface Listener {
        void onResponse(ClientTransaction tx, SipMessage response);

        default void onTimeout(ClientTransaction tx) {}
    }

    private final TransactionLayer layer;
    private final SipMessage request;
    private final InetSocketAddress destination;
    private final Listener listener;
    private final boolean invite;
    private final String key;
    private State state;
    private long interval;
    private Cancellable retransmitTimer;
    private Cancellable timeoutTimer;
    private SipMessage ack;

    ClientTransaction(TransactionLayer layer, String key, SipMessage request, InetSocketAddress destination,
                      Listener listener) {
        this.layer = layer;
        this.key = key;
        this.request = request;
        this.destination = destination;
        this.listener = listener;
        this.invite = request.method().equals("INVITE");
    }

    void start() {
        state = invite ? State.CALLING : State.TRYING;
        layer.transport().send(request, destination);
        interval = layer.timers().t1();
        retransmitTimer = layer.timer().schedule(interval, this::retransmit);
        timeoutTimer = layer.timer().schedule(layer.timers().timeout64T1(), this::timeout);
    }

    public State state() {
        return state;
    }

    public SipMessage request() {
        return request;
    }

    public InetSocketAddress destination() {
        return destination;
    }

    private void retransmit() {
        SipTimers t = layer.timers();
        if (invite && state == State.CALLING) {
            layer.transport().send(request, destination);
            interval *= 2; // Timer A doubles without a cap
        } else if (!invite && (state == State.TRYING || state == State.PROCEEDING)) {
            layer.transport().send(request, destination);
            interval = state == State.PROCEEDING ? t.t2() : Math.min(2 * interval, t.t2()); // Timer E
        } else {
            return;
        }
        retransmitTimer = layer.timer().schedule(interval, this::retransmit);
    }

    private void timeout() {
        if (state == State.CALLING || state == State.TRYING || state == State.PROCEEDING) {
            terminate();
            listener.onTimeout(this); // Timer B or Timer F
        }
    }

    void onResponse(SipMessage resp) {
        int code = resp.status();
        if (invite) {
            switch (state) {
                case CALLING, PROCEEDING -> {
                    if (code < 200) {
                        state = State.PROCEEDING;
                        cancel(retransmitTimer);
                    } else if (code < 300) {
                        state = State.ACCEPTED;
                        cancelAll();
                        layer.timer().schedule(layer.timers().timeout64T1(), this::terminate); // Timer M
                    } else {
                        state = State.COMPLETED;
                        cancelAll();
                        ack = buildAck(resp);
                        layer.transport().send(ack, destination);
                        layer.timer().schedule(layer.timers().timerD(), this::terminate);
                    }
                    listener.onResponse(this, resp);
                }
                case ACCEPTED -> {
                    if (code >= 200 && code < 300) {
                        listener.onResponse(this, resp); // retransmitted 2xx: the TU re-sends its ACK
                    }
                }
                case COMPLETED -> {
                    if (code >= 300) {
                        layer.transport().send(ack, destination); // absorb the retransmitted final response
                    }
                }
                default -> { }
            }
        } else {
            if (state == State.TRYING || state == State.PROCEEDING) {
                if (code < 200) {
                    state = State.PROCEEDING;
                } else {
                    state = State.COMPLETED;
                    cancelAll();
                    layer.timer().schedule(layer.timers().t4(), this::terminate); // Timer K
                }
                listener.onResponse(this, resp);
            }
        }
    }

    /** ACK for a non-2xx final response: same branch, same Request-URI, To tag from the response (17.1.1.3). */
    private SipMessage buildAck(SipMessage resp) {
        SipMessage a = SipMessage.request("ACK", request.requestUri());
        a.addHeader("Via", request.header("Via"));
        for (String r : request.headerValues("Route")) {
            a.addHeader("Route", r);
        }
        a.addHeader("Max-Forwards", "70");
        a.addHeader("From", request.header("From"));
        a.addHeader("To", resp.header("To"));
        a.addHeader("Call-ID", request.callId());
        a.addHeader("CSeq", request.cseq().seq() + " ACK");
        return a;
    }

    private void terminate() {
        if (state != State.TERMINATED) {
            state = State.TERMINATED;
            cancelAll();
            layer.remove(key);
        }
    }

    private void cancelAll() {
        cancel(retransmitTimer);
        cancel(timeoutTimer);
    }

    private static void cancel(Cancellable c) {
        if (c != null) {
            c.cancel();
        }
    }
}
