package io.github.pgallo.sipcc.time;

import java.util.PriorityQueue;

/** Deterministic timer service for tests: time only moves when {@link #advance(long)} is called. */
public final class ManualTimerService implements TimerService {

    private record Entry(long due, long seq, Runnable task, boolean[] cancelled) {}

    private final PriorityQueue<Entry> queue = new PriorityQueue<>(
            (a, b) -> a.due != b.due ? Long.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
    private long now;
    private long seq;

    @Override
    public long now() {
        return now;
    }

    @Override
    public Cancellable schedule(long delayMs, Runnable task) {
        boolean[] cancelled = {false};
        queue.add(new Entry(now + Math.max(0, delayMs), seq++, task, cancelled));
        return () -> cancelled[0] = true;
    }

    @Override
    public void execute(Runnable task) {
        task.run();
    }

    /** Advances time, running every task that falls due in order (including ones scheduled meanwhile). */
    public void advance(long ms) {
        long target = now + ms;
        while (!queue.isEmpty() && queue.peek().due <= target) {
            Entry e = queue.poll();
            now = e.due;
            if (!e.cancelled[0]) {
                e.task.run();
            }
        }
        now = target;
    }

    public int pending() {
        return (int) queue.stream().filter(e -> !e.cancelled[0]).count();
    }
}
