package io.github.pgallo.sipcc.txn;

/**
 * Base timer values of RFC 3261 section 17 and Table 4. Every derived timer is a function of
 * these, so tests can shrink them uniformly. Timer D is kept separate because the RFC fixes it at
 * "at least 32 s" rather than as a multiple of T1.
 */
public record SipTimers(long t1, long t2, long t4, long timerD) {

    public static final SipTimers RFC3261 = new SipTimers(500, 4_000, 5_000, 32_000);

    /** Timers A..K scaled down for loopback simulations, preserving the ratios between them. */
    public static SipTimers scaled(double factor) {
        return new SipTimers(Math.max(1, (long) (500 * factor)), Math.max(2, (long) (4_000 * factor)),
                Math.max(2, (long) (5_000 * factor)), Math.max(2, (long) (32_000 * factor)));
    }

    /** Timers B, F, H, J, L and M. */
    public long timeout64T1() {
        return 64 * t1;
    }
}
