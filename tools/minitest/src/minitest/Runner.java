package minitest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minimal offline test runner: finds *Test classes under the given class directories, runs
 * every @Test method on a fresh instance with @BeforeEach/@AfterEach, and exits non-zero on
 * any failure. Exists so the tests run on a machine with only a JDK and no artifact repository access.
 */
public final class Runner {

    public static void main(String[] args) throws Exception {
        List<String> classNames = new ArrayList<>();
        for (String dir : args) {
            Path root = Path.of(dir);
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith("Test.class") && !p.toString().contains("$"))
                        .sorted(Comparator.naturalOrder())
                        .forEach(p -> classNames.add(toClassName(root, p)));
            } catch (IOException e) {
                throw new IllegalStateException("cannot scan " + root, e);
            }
        }
        int passed = 0;
        List<String> failures = new ArrayList<>();
        long start = System.nanoTime();
        for (String name : classNames) {
            Class<?> cls = Class.forName(name);
            List<Method> tests = annotated(cls, Test.class);
            tests.sort(Comparator.comparing(Method::getName));
            System.out.println(cls.getSimpleName());
            for (Method test : tests) {
                long t0 = System.nanoTime();
                Throwable error = runOne(cls, test);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (error == null) {
                    passed++;
                    System.out.printf("  [PASS] %s (%d ms)%n", test.getName(), ms);
                } else {
                    failures.add(cls.getSimpleName() + "." + test.getName());
                    System.out.printf("  [FAIL] %s (%d ms)%n", test.getName(), ms);
                    error.printStackTrace(System.out);
                }
            }
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println();
        System.out.printf("Tests run: %d, Passed: %d, Failed: %d, Time: %d ms%n",
                passed + failures.size(), passed, failures.size(), totalMs);
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("FAILED " + f));
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    private static Throwable runOne(Class<?> cls, Method test) {
        Object instance;
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            return e;
        }
        Throwable error = null;
        try {
            for (Method m : annotated(cls, BeforeEach.class)) {
                invoke(m, instance);
            }
            invoke(test, instance);
        } catch (Throwable t) {
            error = t;
        } finally {
            for (Method m : annotated(cls, AfterEach.class)) {
                try {
                    invoke(m, instance);
                } catch (Throwable t) {
                    if (error == null) {
                        error = t;
                    }
                }
            }
        }
        return error;
    }

    private static void invoke(Method m, Object instance) throws Throwable {
        m.setAccessible(true);
        try {
            m.invoke(Modifier.isStatic(m.getModifiers()) ? null : instance);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static List<Method> annotated(Class<?> cls, Class<? extends java.lang.annotation.Annotation> a) {
        List<Method> out = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(a)) {
                out.add(m);
            }
        }
        return out;
    }

    private static String toClassName(Path root, Path file) {
        String rel = root.relativize(file).toString();
        return rel.substring(0, rel.length() - ".class".length()).replace('/', '.').replace('\\', '.');
    }
}
