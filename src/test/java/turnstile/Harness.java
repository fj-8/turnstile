package turnstile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal test harness: no dependencies, exit code 0 only when everything passed.
 *
 * <p>A case is a lambda registered with {@link #test}; there is no annotation
 * scan and no runner, because the JDK ships neither and adding a third-party test
 * framework would be the only dependency in the project. Cases execute in the
 * order written, which keeps the log readable and the seed-free concurrency tests
 * reproducible.
 *
 * <p>A failed {@code check} aborts its own case only, so one broken invariant
 * does not hide the other twenty, and every message carries both the expected and
 * the actual value — the single most useful thing a harness can do for someone
 * reading a CI log without a debugger.
 */
final class Harness {

    /** A test body that may throw anything. */
    interface Case {
        void run() throws Exception;
    }

    private static final class Aborted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Aborted(String message) {
            super(message);
        }
    }

    private static final List<String> results = new ArrayList<>();
    private static int passed;
    private static int failed;

    private Harness() {
    }

    static void test(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok   " + name);
            results.add("ok " + name);
        } catch (Aborted aborted) {
            failed++;
            System.out.println("  FAIL " + name + "\n       " + aborted.getMessage());
            results.add("FAIL " + name + ": " + aborted.getMessage());
        } catch (Throwable other) {
            failed++;
            System.out.println("  FAIL " + name + "\n       unexpected "
                    + other.getClass().getName() + ": " + other.getMessage());
            results.add("FAIL " + name + ": " + other);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new Aborted(message);
        }
    }

    static void checkEquals(String what, long expected, long actual) {
        if (expected != actual) {
            throw new Aborted(what + ": expected " + expected + ", got " + actual);
        }
    }

    /**
     * Reference-valued comparison. It has its own name rather than an overload of
     * {@link #checkEquals(String, long, long)}: with both present, a call like
     * {@code checkEquals("x", 0, queue.poll())} is ambiguous, because the literal
     * widens to {@code long} while the boxed return only boxes to {@code Object}.
     */
    static void checkSame(String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new Aborted(what + ": expected " + expected + ", got " + actual);
        }
    }

    /** Asserts {@code body} throws {@code kind} (or a subclass) and returns it. */
    static Throwable checkThrows(String what, Class<? extends Throwable> kind, Case body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (kind.isInstance(thrown)) {
                return thrown;
            }
            throw new Aborted(what + ": expected " + kind.getName() + ", got "
                    + thrown.getClass().getName() + " (" + thrown.getMessage() + ")");
        }
        throw new Aborted(what + ": expected " + kind.getName() + ", nothing was thrown");
    }

    /**
     * Halts the JVM if a case hangs, so a deadlock shows up as a failure instead
     * of a CI job that a human has to cancel. Daemon thread: it never keeps a
     * healthy run alive.
     */
    static void watchdog(long minutes) {
        Thread sentry = new Thread(() -> {
            try {
                Thread.sleep(minutes * 60_000L);
            } catch (InterruptedException clean) {
                return;
            }
            System.out.println("  TIMEOUT after " + minutes
                    + " minutes: a case is blocked. Completed so far: " + results);
            Runtime.getRuntime().halt(2);
        }, "harness-watchdog");
        sentry.setDaemon(true);
        sentry.start();
    }

    static int report(String suite) {
        System.out.printf("%n%s: %d/%d passed%n", suite, passed, passed + failed);
        return failed == 0 ? 0 : 1;
    }
}
