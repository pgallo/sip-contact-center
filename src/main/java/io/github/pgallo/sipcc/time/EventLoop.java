package io.github.pgallo.sipcc.time;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A single-threaded event loop. Every SIP node, the contact-center core and the simulated users
 * run their callbacks here, so protocol state needs no locks; socket reader threads only enqueue.
 * The loop itself runs on real time; {@link #scaled(double)} gives a view whose clock and timers
 * run faster, which the demo uses to compress minutes of contact-center activity into seconds.
 */
public final class EventLoop implements TimerService, AutoCloseable {

    private final ScheduledThreadPoolExecutor executor;
    private final long origin = System.nanoTime();
    private volatile Thread thread;

    public EventLoop(String name) {
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            thread = t;
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public long now() {
        return (System.nanoTime() - origin) / 1_000_000;
    }

    @Override
    public Cancellable schedule(long delayMs, Runnable task) {
        ScheduledFuture<?> f = executor.schedule(guard(task), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return () -> f.cancel(false);
    }

    @Override
    public void execute(Runnable task) {
        if (!executor.isShutdown()) {
            executor.execute(guard(task));
        }
    }

    public boolean inLoop() {
        return Thread.currentThread() == thread;
    }

    /** Runs a task on the loop and waits for its result; for tests and the demo driver. */
    public <T> T call(Callable<T> task) {
        try {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** A view of this loop whose clock runs {@code speed} times faster than real time. */
    public TimerService scaled(double speed) {
        EventLoop loop = this;
        return new TimerService() {
            @Override
            public long now() {
                return (long) ((System.nanoTime() - origin) / 1e6 * speed);
            }

            @Override
            public Cancellable schedule(long delayMs, Runnable task) {
                return loop.schedule((long) Math.ceil(delayMs / speed), task);
            }

            @Override
            public void execute(Runnable task) {
                loop.execute(task);
            }
        };
    }

    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                System.err.println("event loop task failed: " + e);
                e.printStackTrace();
            }
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
