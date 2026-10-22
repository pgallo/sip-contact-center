package io.github.pgallo.sipcc;

import java.util.function.BooleanSupplier;

/** Polls a condition for tests that run over real sockets. */
final class Await {

    private Await() {}

    static void until(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
