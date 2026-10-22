package org.junit.jupiter.api;

import java.util.Objects;

/**
 * Source-compatible subset of the assertion API of the test framework declared in pom.xml, used
 * only by the offline build (scripts/test.sh). The pom build compiles the same tests against the
 * real library.
 */
public final class Assertions {

    @FunctionalInterface
    public interface Executable {
        void execute() throws Throwable;
    }

    private Assertions() {}

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "expected true");
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, "expected false");
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            fail(message);
        }
    }

    public static void assertNull(Object actual) {
        if (actual != null) {
            fail("expected null but was " + actual);
        }
    }

    public static void assertNull(Object actual, String message) {
        if (actual != null) {
            fail(message + " ==> expected null but was " + actual);
        }
    }

    public static void assertNotNull(Object actual) {
        assertNotNull(actual, "expected non-null");
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            fail(message);
        }
    }

    public static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            fail("expected same instance " + expected + " but was " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail((message == null ? "" : message + " ==> ") + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals((Object) expected, (Object) actual, null);
    }

    public static void assertEquals(int expected, int actual) {
        assertEquals((Object) expected, (Object) actual, null);
    }

    public static void assertEquals(int expected, int actual, String message) {
        assertEquals((Object) expected, (Object) actual, message);
    }

    public static void assertEquals(double expected, double actual, double delta) {
        assertEquals(expected, actual, delta, null);
    }

    public static void assertEquals(double expected, double actual, double delta, String message) {
        if (Math.abs(expected - actual) > delta) {
            fail((message == null ? "" : message + " ==> ") + "expected <" + expected + "> but was <" + actual
                    + "> (delta " + delta + ")");
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        try {
            executable.execute();
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return expectedType.cast(t);
            }
            throw new AssertionError("expected " + expectedType.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + expectedType.getSimpleName() + " to be thrown");
    }

    public static <T> T assertInstanceOf(Class<T> expectedType, Object actual) {
        if (!expectedType.isInstance(actual)) {
            fail("expected instance of " + expectedType.getSimpleName() + " but was " + actual);
        }
        return expectedType.cast(actual);
    }
}
