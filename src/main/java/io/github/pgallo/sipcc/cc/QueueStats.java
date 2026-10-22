package io.github.pgallo.sipcc.cc;

/**
 * Per-queue counters and the usual contact-center indicators.
 *
 * Service level = calls answered within the threshold / (answered + abandoned after the threshold).
 * Calls abandoned before the threshold are excluded: the queue never had a fair chance at them.
 * ASA (average speed of answer) = total queue wait of answered calls / answered.
 * Abandon rate = abandoned / offered.
 */
public final class QueueStats {

    private final QueueConfig queue;
    private int offered;
    private int answered;
    private int answeredWithinSl;
    private int abandoned;
    private int abandonedAfterSl;
    private int overflowed;
    private long totalAnswerWaitMs;

    public QueueStats(QueueConfig queue) {
        this.queue = queue;
    }

    void offered() {
        offered++;
    }

    void answered(long waitMs) {
        answered++;
        totalAnswerWaitMs += waitMs;
        if (waitMs <= queue.serviceLevelMs()) {
            answeredWithinSl++;
        }
    }

    void abandoned(long waitMs) {
        abandoned++;
        if (waitMs > queue.serviceLevelMs()) {
            abandonedAfterSl++;
        }
    }

    void overflowed() {
        overflowed++;
    }

    public String queue() {
        return queue.name();
    }

    public int offeredCount() {
        return offered;
    }

    public int answeredCount() {
        return answered;
    }

    public int abandonedCount() {
        return abandoned;
    }

    public int overflowCount() {
        return overflowed;
    }

    public double serviceLevel() {
        int denom = answered + abandonedAfterSl;
        return denom == 0 ? 1.0 : (double) answeredWithinSl / denom;
    }

    public double asaMs() {
        return answered == 0 ? 0 : (double) totalAnswerWaitMs / answered;
    }

    public double abandonRate() {
        return offered == 0 ? 0 : (double) abandoned / offered;
    }
}
