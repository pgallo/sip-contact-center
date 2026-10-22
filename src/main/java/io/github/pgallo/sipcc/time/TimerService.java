package io.github.pgallo.sipcc.time;

/**
 * Clock plus one-shot timers. All protocol and contact-center timing goes through this interface,
 * so tests can drive timers with {@link ManualTimerService} instead of sleeping.
 */
public interface TimerService {

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }

    /** Milliseconds on this service's time base. */
    long now();

    Cancellable schedule(long delayMs, Runnable task);

    /** Runs the task on the service's execution context as soon as possible. */
    void execute(Runnable task);
}
