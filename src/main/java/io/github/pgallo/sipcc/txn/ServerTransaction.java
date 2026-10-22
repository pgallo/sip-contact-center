package io.github.pgallo.sipcc.txn;

import io.github.pgallo.sipcc.sip.SipMessage;
import io.github.pgallo.sipcc.time.TimerService.Cancellable;

import java.net.InetSocketAddress;

/**
 * Server transaction state machines of RFC 3261 section 17.2 (with the RFC 6026 Accepted state).
 *
 * INVITE:     Proceeding -(2xx)-> Accepted -(Timer L)-> Terminated
 *             Proceeding -(300-699)-> Completed -(ACK)-> Confirmed -(Timer I = T4)-> Terminated
 *             Completed retransmits the final response on Timer G and gives up on Timer H (64*T1).
 * non-INVITE: Trying -(1xx)-> Proceeding -(final)-> Completed -(Timer J = 64*T1)-> Terminated
 * In every state a retransmitted request is answered with the last response sent.
 */
public final class ServerTransaction {

    public enum State { TRYING, PROCEEDING, ACCEPTED, COMPLETED, CONFIRMED, TERMINATED }

    private final TransactionLayer layer;
    private final String key;
    private final SipMessage request;
    private final InetSocketAddress responseAddress;
    private final boolean invite;
    private State state;
    private SipMessage lastResponse;
    private long interval;
    private Cancellable gTimer;
    private Cancellable hTimer;
    private Runnable onAckTimeout = () -> { };

    ServerTransaction(TransactionLayer layer, String key, SipMessage request, InetSocketAddress responseAddress) {
        this.layer = layer;
        this.key = key;
        this.request = request;
        this.responseAddress = responseAddress;
        this.invite = request.method().equals("INVITE");
        this.state = invite ? State.PROCEEDING : State.TRYING;
    }

    public SipMessage request() {
        return request;
    }

    public State state() {
        return state;
    }

    public InetSocketAddress responseAddress() {
        return responseAddress;
    }

    /** Called if Timer H fires: the ACK for a non-2xx final response never arrived. */
    public void onAckTimeout(Runnable r) {
        this.onAckTimeout = r;
    }

    public void sendResponse(SipMessage resp) {
        int code = resp.status();
        switch (state) {
            case TRYING, PROCEEDING -> { }
            case ACCEPTED -> {
                if (code >= 200 && code < 300) {
                    layer.transport().send(resp, responseAddress); // the TU's own 2xx retransmission
                }
                return;
            }
            default -> {
                return;
            }
        }
        lastResponse = resp;
        layer.transport().send(resp, responseAddress);
        if (code < 200) {
            state = State.PROCEEDING;
        } else if (invite && code < 300) {
            state = State.ACCEPTED;
            layer.timer().schedule(layer.timers().timeout64T1(), this::terminate); // Timer L
        } else if (invite) {
            state = State.COMPLETED;
            interval = layer.timers().t1();
            gTimer = layer.timer().schedule(interval, this::timerG);
            hTimer = layer.timer().schedule(layer.timers().timeout64T1(), () -> {
                if (state == State.COMPLETED) {
                    terminate();
                    onAckTimeout.run();
                }
            });
        } else {
            state = State.COMPLETED;
            layer.timer().schedule(layer.timers().timeout64T1(), this::terminate); // Timer J
        }
    }

    private void timerG() {
        if (state != State.COMPLETED) {
            return;
        }
        layer.transport().send(lastResponse, responseAddress);
        interval = Math.min(2 * interval, layer.timers().t2());
        gTimer = layer.timer().schedule(interval, this::timerG);
    }

    void onRequest(SipMessage req) {
        if (req.method().equals("ACK")) {
            if (invite && state == State.COMPLETED) {
                state = State.CONFIRMED;
                gTimer.cancel();
                hTimer.cancel();
                layer.timer().schedule(layer.timers().t4(), this::terminate); // Timer I
            }
            return;
        }
        if (lastResponse != null && state != State.CONFIRMED && state != State.TERMINATED) {
            layer.transport().send(lastResponse, responseAddress);
        }
    }

    private void terminate() {
        if (state != State.TERMINATED) {
            state = State.TERMINATED;
            if (gTimer != null) {
                gTimer.cancel();
            }
            layer.remove(key);
        }
    }
}
