package io.github.pgallo.sipcc.demo;

import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.time.TimerService.Cancellable;
import io.github.pgallo.sipcc.ua.Call;
import io.github.pgallo.sipcc.ua.UserAgent;

import java.util.Map;

/**
 * A simulated caller phone: dials the service number, keys an IVR path, waits in queue with a
 * given patience, follows the REFER to the agent and stays until the agent side hangs up.
 */
public final class SimCaller implements UserAgent.Listener {

    private final UserAgent ua;
    private final TimerService sim;
    private final long dtmfGapMs;
    private Call ivrCall;
    private boolean busy;
    private boolean connected;
    private String digits;
    private long patienceMs;
    private Cancellable patience;
    private Runnable onDone = () -> { };

    public SimCaller(UserAgent ua, TimerService sim, long dtmfGapMs) {
        this.ua = ua;
        this.sim = sim;
        this.dtmfGapMs = dtmfGapMs;
        ua.setListener(this);
    }

    public boolean busy() {
        return busy;
    }

    public void call(String digits, long patienceMs, Runnable onDone) {
        this.busy = true;
        this.connected = false;
        this.digits = digits;
        this.patienceMs = patienceMs;
        this.onDone = onDone;
        this.ivrCall = ua.invite("sip:" + Scenario.SERVICE + "@" + Scenario.DOMAIN, Map.of());
    }

    @Override
    public void onEstablished(Call call) {
        if (call == ivrCall) {
            Call c = call;
            for (int i = 0; i < digits.length(); i++) {
                char d = digits.charAt(i);
                sim.schedule(dtmfGapMs * (i + 1), () -> c.sendDtmf(d));
            }
            patience = sim.schedule(patienceMs, () -> {
                if (!connected && c.isEstablished()) {
                    c.hangup(); // abandon
                }
            });
        } else {
            connected = true;
            if (patience != null) {
                patience.cancel();
            }
        }
    }

    @Override
    public void onEnded(Call call) {
        checkDone();
    }

    @Override
    public void onFailed(Call call, int status) {
        checkDone();
    }

    private void checkDone() {
        if (busy && ua.activeCalls() == 0) {
            busy = false;
            if (patience != null) {
                patience.cancel();
            }
            onDone.run();
        }
    }
}
