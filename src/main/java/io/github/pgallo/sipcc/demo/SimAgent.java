package io.github.pgallo.sipcc.demo;

import io.github.pgallo.sipcc.cc.ContactCenter;
import io.github.pgallo.sipcc.platform.sip.SipPlatform;
import io.github.pgallo.sipcc.sip.SipUri;
import io.github.pgallo.sipcc.time.TimerService;
import io.github.pgallo.sipcc.ua.Call;
import io.github.pgallo.sipcc.ua.UserAgent;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simulated agent phone: registers, reports READY, answers after a short ring, talks for an
 * exponentially distributed time and then hangs up or transfers (blind via REFER, or attended:
 * consultation call first, then REFER with Replaces).
 */
public final class SimAgent implements UserAgent.Listener {

    public static final AtomicInteger BLIND = new AtomicInteger();
    public static final AtomicInteger ATTENDED = new AtomicInteger();

    /** Marks INVITEs that result from an agent transfer, so a call is transferred at most once. */
    static final String TRANSFERRED = "X-CC-Transferred";

    private final UserAgent ua;
    private final ContactCenter cc;
    private final TimerService sim;
    private final Random rnd;
    private final Profile p;

    /** Behaviour parameters, in simulated milliseconds. */
    public record Profile(long ringMinMs, int ringJitterMs, long talkMinMs, long talkMeanMs, double blindP,
                          double attendedP) {
        public static final Profile DEMO = new Profile(500, 1_500, 3_000, 20_000, 0.2, 0.15);
        public static final Profile LOAD = new Profile(0, 1, 0, 1, 0, 0);
    }

    public SimAgent(UserAgent ua, ContactCenter cc, TimerService sim, Random rnd, Profile profile) {
        this.ua = ua;
        this.cc = cc;
        this.sim = sim;
        this.rnd = rnd;
        this.p = profile;
        ua.setListener(this);
    }

    public UserAgent ua() {
        return ua;
    }

    public void start() {
        ua.register(3600, status -> {
            if (status == 200) {
                ua.sendMessage("sip:" + Scenario.SERVICE + "@" + Scenario.DOMAIN, "READY");
            }
        });
    }

    @Override
    public void onIncoming(Call call) {
        sim.schedule(p.ringMinMs() + rnd.nextInt(p.ringJitterMs()), call::answer);
    }

    @Override
    public void onEstablished(Call call) {
        if (call.isOutgoing()) {
            Call original = call.attr("consultFor");
            if (original != null) {
                sim.schedule(2_000, () -> completeAttended(original, call));
            }
            return;
        }
        if (call.header("X-CC-Consult") != null) {
            return; // we are being consulted; the transferor completes or abandons the transfer
        }
        long talk = Math.max(p.talkMinMs(), (long) (-Math.log(1 - rnd.nextDouble()) * p.talkMeanMs()));
        sim.schedule(talk, () -> endOfTalk(call));
    }

    private void endOfTalk(Call call) {
        if (!call.isEstablished()) {
            return;
        }
        boolean alreadyTransferred = call.header(TRANSFERRED) != null;
        double r = rnd.nextDouble();
        if (!alreadyTransferred && r < p.blindP()) {
            blindTransfer(call);
        } else if (!alreadyTransferred && r < p.blindP() + p.attendedP()) {
            attendedTransfer(call);
        } else {
            call.hangup();
        }
    }

    private void blindTransfer(Call call) {
        String target = cc.reserveTransferTarget(ua.user());
        if (target == null) {
            call.hangup();
            return;
        }
        BLIND.incrementAndGet();
        call.attr("target", target);
        call.refer(targetUri(target).withHeader(SipPlatform.CORRELATION_HEADER, ccId(call)).withHeader(TRANSFERRED, "blind")
                .toString());
    }

    private void attendedTransfer(Call call) {
        String target = cc.reserveTransferTarget(ua.user());
        if (target == null) {
            call.hangup();
            return;
        }
        ATTENDED.incrementAndGet();
        call.attr("target", target);
        Call consult = ua.invite(targetUri(target).toString(),
                Map.of(SipPlatform.CORRELATION_HEADER, ccId(call), "X-CC-Consult", "1"));
        consult.attr("consultFor", call);
    }

    private void completeAttended(Call original, Call consult) {
        if (!original.isEstablished() || !consult.isEstablished()) {
            consult.hangup();
            return;
        }
        String target = original.attr("target");
        original.refer(targetUri(target).withHeader("Replaces", consult.replacesValue())
                .withHeader(SipPlatform.CORRELATION_HEADER, ccId(original)).withHeader(TRANSFERRED, "attended").toString());
    }

    @Override
    public void onReferResult(Call call, int status) {
        if (status < 200 || status >= 300) {
            String target = call.attr("target");
            if (target != null) {
                cc.releaseReservation(target);
            }
        }
        call.hangup(); // as transferor we leave the call either way
    }

    @Override
    public void onFailed(Call call, int status) {
        Call original = call.attr("consultFor");
        if (original != null) {
            cc.releaseReservation(original.attr("target"));
            original.hangup();
        }
    }

    private static SipUri targetUri(String ext) {
        return SipUri.parse("sip:" + ext + "@" + Scenario.DOMAIN);
    }

    private static String ccId(Call call) {
        return call.header(SipPlatform.CORRELATION_HEADER);
    }
}
