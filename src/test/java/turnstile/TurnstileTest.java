package turnstile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The suite: {@code java turnstile.TurnstileTest}, exit code 0 when everything passes.
 *
 * <p>The interesting cases are the concurrent ones, and they are written to be
 * falsifiable rather than to look impressive: a round of {@code P} producers and
 * {@code C} consumers ends only when every encoded value has been delivered
 * exactly once, and every consumer additionally insists that each producer's
 * values reached it in increasing order. A lost element, a duplicate or a
 * reordered pair fails the run instead of silently shortening it.
 */
public final class TurnstileTest {

    /** Nanoseconds in a millisecond: every elapsed value below is divided by this. */
    private static final long MS = 1_000_000L;

    /** A hung case becomes a failure instead of a CI job someone has to cancel. */
    private static final long WATCHDOG_MINUTES = Long.getLong("turnstile.watchdog.minutes", 5L);

    public static void main(String[] args) {
        Harness.watchdog(WATCHDOG_MINUTES);
        Harness.test("round trip keeps FIFO order", TurnstileTest::roundTrip);
        Harness.test("capacity rounds up to a power of two", TurnstileTest::capacityRounding);
        Harness.test("an illegal capacity is refused before allocating", TurnstileTest::illegalCapacity);
        Harness.test("a full ring refuses instead of growing or blocking", TurnstileTest::fullRingIsBounded);
        Harness.test("null elements and null units are rejected", TurnstileTest::nullsRejected);
        Harness.test("offer timeout expires on a ring nobody drains", TurnstileTest::offerTimeoutExpires);
        Harness.test("poll timeout expires on an empty ring", TurnstileTest::pollTimeoutExpires);
        Harness.test("poll timeout wakes when an element arrives", TurnstileTest::pollWakesOnArrival);
        Harness.test("an interrupted wait throws and clears its status", TurnstileTest::interruptedPoll);
        Harness.test("an interrupted offer wait throws too", TurnstileTest::interruptedOffer);
        Harness.test("drainTo moves a bounded batch", TurnstileTest::drainBatch);
        Harness.test("a drained slot stops retaining its element", TurnstileTest::slotsAreCleared);
        Harness.test("S:1 C:1 keeps exact order over 200k elements", TurnstileTest::singleProducerSingleConsumerOrder);
        Harness.test("P:4 C:4 delivers 200k exactly once, in order", TurnstileTest::manyToMany);
        Harness.test("every capacity from 1 to 1025 round-trips a live round", TurnstileTest::capacitySweep);
        Harness.test("timed offers all land while a consumer drains", TurnstileTest::timedOffersUnderContention);
        Harness.test("size stays inside [0, capacity] under traffic", TurnstileTest::sizeBounds);
        Harness.test("the documentation quotes this checkout", TurnstileTest::documentationClaims);
        System.exit(Harness.report("turnstile tests"));
    }

    // ---------------------------------------------------------------- basics

    private static void roundTrip() {
        Turnstile<String> queue = new Turnstile<>(4);
        Harness.check(queue.offer("a") && queue.offer("b") && queue.offer("c"),
                "three offers into a ring of four");
        Harness.checkSame("first out", "a", queue.poll());
        Harness.checkSame("second out", "b", queue.poll());
        Harness.checkSame("third out", "c", queue.poll());
        Harness.check(queue.poll() == null, "poll on an empty ring returns null");
        Harness.checkEquals("capacity", 4, queue.capacity());
        Harness.checkEquals("size when empty", 0, queue.size());
        Harness.check(queue.isEmpty(), "isEmpty agrees with size");
    }

    private static void capacityRounding() {
        // A request of one becomes two: see the constructor's note on why the
        // turn marker needs the slot index to move between laps.
        int[][] cases = {{1, 2}, {2, 2}, {3, 4}, {5, 8}, {9, 16}, {1023, 1024}, {1025, 2048}};
        for (int[] one : cases) {
            Harness.checkEquals("capacity(" + one[0] + ")", one[1], new Turnstile<>(one[0]).capacity());
        }
    }

    private static void illegalCapacity() {
        Throwable zero = Harness.checkThrows("capacity 0", IllegalArgumentException.class,
                () -> new Turnstile<>(0));
        Harness.check(zero.getMessage().contains("0"),
                "the message names the rejected value: " + zero.getMessage());
        Harness.checkThrows("capacity -7", IllegalArgumentException.class, () -> new Turnstile<>(-7));
        // Above 2^30 the mask stops fitting the arithmetic; the check runs before
        // the arrays are sized, so rejecting must not allocate 8 GB.
        Harness.checkThrows("capacity 2^30+1", IllegalArgumentException.class,
                () -> new Turnstile<>((1 << 30) + 1));
        Harness.checkThrows("capacity Integer.MAX_VALUE", IllegalArgumentException.class,
                () -> new Turnstile<>(Integer.MAX_VALUE));
    }

    private static void fullRingIsBounded() {
        Turnstile<Integer> queue = new Turnstile<>(4);
        for (int i = 0; i < 4; i++) {
            Harness.check(queue.offer(i), "offer " + i + " while there is room");
        }
        long started = System.nanoTime();
        Harness.check(!queue.offer(99), "a fifth offer is refused");
        long spentMillis = (System.nanoTime() - started) / MS;
        Harness.check(spentMillis < 100,
                "the refusal returned at once instead of blocking (took " + spentMillis + " ms)");
        Harness.checkEquals("size at full", 4, queue.size());
        Harness.checkSame("value out", Integer.valueOf(0), queue.poll());
        Harness.check(queue.offer(99), "the freed slot is reusable");
        Harness.check(!queue.offer(98), "and the ring is full again");
        Harness.checkEquals("nothing leaked past capacity", 4, queue.size());
    }

    private static void nullsRejected() {
        Turnstile<String> queue = new Turnstile<>(2);
        Harness.checkThrows("offer(null)", NullPointerException.class, () -> queue.offer(null));
        Harness.checkThrows("offer(null, timeout)", NullPointerException.class,
                () -> queue.offer(null, 10, TimeUnit.MILLISECONDS));
        Harness.checkThrows("poll with a null unit", NullPointerException.class,
                () -> queue.poll(10, null));
        Harness.checkThrows("offer with a null unit", NullPointerException.class,
                () -> queue.offer("x", 10, null));
        Harness.checkThrows("drainTo(null)", NullPointerException.class,
                () -> queue.drainTo(null, 1));
        Harness.checkEquals("the refusals queued nothing", 0, queue.size());
    }

    // --------------------------------------------------------------- timeouts

    private static void offerTimeoutExpires() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(2);
        Harness.check(queue.offer(1) && queue.offer(2), "fill the ring of two");
        long started = System.nanoTime();
        Harness.check(!queue.offer(2, 30, TimeUnit.MILLISECONDS),
                "a 30 ms offer wait on a ring nobody drains gives up");
        long spent = (System.nanoTime() - started) / MS;
        Harness.check(spent >= 25, "it waited the whole timeout, not " + spent + " ms");
        Harness.check(spent < 5_000, "and gave up at " + spent + " ms, not long after");
        Harness.check(!queue.offer(3, -1, TimeUnit.SECONDS),
                "a negative timeout behaves like zero");
        Harness.checkEquals("size unchanged by the failed offers", 2, queue.size());
    }

    private static void pollTimeoutExpires() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(4);
        long started = System.nanoTime();
        Harness.check(queue.poll(30, TimeUnit.MILLISECONDS) == null,
                "a 30 ms poll on an empty ring returns null");
        long spent = (System.nanoTime() - started) / MS;
        Harness.check(spent >= 25, "it waited the whole timeout, not " + spent + " ms");
        Harness.check(spent < 5_000, "and returned at " + spent + " ms, not later");
        Harness.check(queue.poll(-1, TimeUnit.SECONDS) == null, "a negative timeout is zero");
    }

    private static void pollWakesOnArrival() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(4);
        Thread waiter = new Thread(() -> {
            try {
                Thread.sleep(20);
                queue.offer(7);
            } catch (InterruptedException late) {
                Thread.currentThread().interrupt();
            }
        }, "late-producer");
        waiter.start();
        long started = System.nanoTime();
        Integer arrived = queue.poll(2, TimeUnit.SECONDS);
        long spent = (System.nanoTime() - started) / MS;
        waiter.join();
        Harness.checkSame("the element that arrived while we waited", Integer.valueOf(7), arrived);
        Harness.check(spent < 1_000,
                "poll returned " + spent + " ms after entering, not when the 2 s timeout expired");
    }

    private static void interruptedPoll() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(4);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagStillSet = new AtomicBoolean();
        Thread waiting = new Thread(() -> {
            try {
                queue.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException expected) {
                interruptFlagStillSet.set(Thread.currentThread().isInterrupted());
                thrown.set(expected);
            }
        }, "interrupted-consumer");
        waiting.start();
        Thread.sleep(30);
        waiting.interrupt();
        waiting.join();
        Harness.checkSame("what the interrupted poll threw", InterruptedException.class,
                thrown.get().getClass());
        Harness.check(!interruptFlagStillSet.get(),
                "the interrupt status is cleared, so a caller can propagate the exception");
    }

    private static void interruptedOffer() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(2);
        int filled = 0;
        while (queue.offer(filled)) {
            filled++;
        }
        Harness.checkEquals("the ring is full", 2, filled);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiting = new Thread(() -> {
            try {
                queue.offer(2, 10, TimeUnit.SECONDS);
            } catch (InterruptedException expected) {
                thrown.set(expected);
            }
        }, "interrupted-producer");
        waiting.start();
        Thread.sleep(30);
        waiting.interrupt();
        waiting.join();
        Harness.checkSame("what the interrupted offer threw", InterruptedException.class,
                thrown.get().getClass());
        Harness.checkEquals("the interrupted offer queued nothing", filled, queue.size());
    }

    // ---------------------------------------------------------------- batches

    private static void drainBatch() {
        Turnstile<Integer> queue = new Turnstile<>(8);
        for (int i = 0; i < 8; i++) {
            queue.offer(i);
        }
        List<Integer> first = new ArrayList<>();
        Harness.checkEquals("drainTo respects maxElements", 3, queue.drainTo(first, 3));
        Harness.checkSame("batch content", Arrays.asList(0, 1, 2), first);
        List<Integer> rest = new ArrayList<>();
        Harness.checkEquals("drainTo takes what is there", 5, queue.drainTo(rest, 100));
        Harness.checkSame("rest content", Arrays.asList(3, 4, 5, 6, 7), rest);
        List<Integer> nothing = new ArrayList<>();
        Harness.checkEquals("drainTo on an empty ring", 0, queue.drainTo(nothing, 4));
        Harness.checkEquals("and it added nothing", 0, nothing.size());
        Harness.checkEquals("size after draining", 0, queue.size());
    }

    private static void slotsAreCleared() {
        Turnstile<byte[]> queue = new Turnstile<>(4);
        Harness.checkEquals("nothing retained at birth", 0, queue.retainedElements());
        for (int cycle = 0; cycle < 100; cycle++) {
            for (int i = 0; i < 4; i++) {
                queue.offer(new byte[cycle]);
            }
            Harness.checkEquals("a full ring retains exactly its capacity", 4, queue.retainedElements());
            for (int i = 0; i < 4; i++) {
                queue.poll();
            }
            Harness.checkEquals("a drained ring retains nothing", 0, queue.retainedElements());
        }
        for (int i = 0; i < 3; i++) {
            queue.offer(new byte[1]);
        }
        Harness.checkEquals("a half-full ring retains its elements", 3, queue.retainedElements());
        queue.poll();
        queue.poll();
        Harness.checkEquals("and gives them back", 1, queue.retainedElements());
    }

    // ------------------------------------------------------------ concurrency

    /**
     * Runs one live round and asserts the three properties that matter: every
     * encoded value is delivered exactly once, each consumer sees each producer's
     * values in increasing order, and the ring is empty afterwards.
     */
    private static void runRound(int producers, int consumers, int perProducer, int requestedCapacity)
            throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(requestedCapacity);
        int total = producers * perProducer;
        AtomicIntegerArray deliveries = new AtomicIntegerArray(total);
        AtomicInteger received = new AtomicInteger();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        for (int index = 0; index < producers; index++) {
            final int producerId = index;
            Thread producer = new Thread(() -> {
                for (int seq = 0; seq < perProducer; seq++) {
                    int value = producerId * perProducer + seq;
                    while (!queue.offer(value)) {
                        Thread.onSpinWait();
                    }
                }
            }, "producer-" + producerId);
            threads.add(producer);
        }

        for (int index = 0; index < consumers; index++) {
            final int consumerId = index;
            Thread consumer = new Thread(() -> {
                int[] lastFrom = new int[producers];
                Arrays.fill(lastFrom, -1);
                try {
                    while (received.get() < total) {
                        Integer value = queue.poll();
                        if (value == null) {
                            Thread.onSpinWait();
                            continue;
                        }
                        int producerId = value / perProducer;
                        int seq = value % perProducer;
                        if (seq <= lastFrom[producerId]) {
                            throw new IllegalStateException("producer " + producerId + " reached consumer "
                                    + consumerId + " out of order: index " + seq + " after "
                                    + lastFrom[producerId]);
                        }
                        lastFrom[producerId] = seq;
                        deliveries.incrementAndGet(producerId * perProducer + seq);
                        received.incrementAndGet();
                    }
                } catch (Throwable broken) {
                    problem.compareAndSet(null, broken);
                }
            }, "consumer-" + consumerId);
            threads.add(consumer);
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        Throwable broken = problem.get();
        Harness.check(broken == null, "a worker failed: " + broken);
        Harness.checkEquals("elements received", total, received.get());
        int once = 0;
        int lost = 0;
        int duplicated = 0;
        for (int index = 0; index < total; index++) {
            switch (deliveries.get(index)) {
                case 1:
                    once++;
                    break;
                case 0:
                    lost++;
                    break;
                default:
                    duplicated++;
                    break;
            }
        }
        Harness.check(lost == 0, lost + " elements were lost");
        Harness.check(duplicated == 0, duplicated + " elements were delivered more than once");
        Harness.checkEquals("delivered exactly once", total, once);
        Harness.checkEquals("the ring is empty afterwards", 0, queue.size());
        Harness.checkEquals("and retains nothing", 0, queue.retainedElements());
    }

    private static void singleProducerSingleConsumerOrder() throws Exception {
        int count = 200_000;
        Turnstile<Integer> queue = new Turnstile<>(64);
        int[] receivedInOrder = new int[count];
        AtomicInteger received = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();

        Thread producer = new Thread(() -> {
            for (int value = 0; value < count; value++) {
                while (!queue.offer(value)) {
                    Thread.onSpinWait();
                }
            }
            delivered.set(count);
        }, "producer");
        Thread consumer = new Thread(() -> {
            int seen = 0;
            while (seen < count) {
                Integer value = queue.poll();
                if (value == null) {
                    Thread.onSpinWait();
                    continue;
                }
                receivedInOrder[seen++] = value;
            }
            received.set(seen);
        }, "consumer");
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        Harness.checkEquals("count consumed", count, received.get());
        Harness.checkEquals("count produced", count, delivered.get());
        int firstWrong = -1;
        for (int index = 0; index < count; index++) {
            if (receivedInOrder[index] != index) {
                firstWrong = index;
                break;
            }
        }
        Harness.check(firstWrong < 0, firstWrong < 0 ? "in order"
                : "the first out-of-order arrival is slot " + firstWrong + ", holding "
                        + receivedInOrder[firstWrong]);
    }

    private static void manyToMany() throws Exception {
        long started = System.nanoTime();
        runRound(4, 4, 50_000, 1024);
        long spentMillis = (System.nanoTime() - started) / MS;
        Harness.check(spentMillis < 120_000, "200k elements through 4x4 threads in " + spentMillis + " ms");
    }

    private static void capacitySweep() throws Exception {
        int[] requests = {1, 2, 3, 4, 7, 64, 257, 1025};
        for (int request : requests) {
            Turnstile<Integer> probe = new Turnstile<>(request);
            Harness.checkEquals("effective capacity for a request of " + request,
                    Math.max(2, Integer.highestOneBit(request - 1) << 1), probe.capacity());
            runRound(2, 2, 2_000, request);
        }
    }

    private static void timedOffersUnderContention() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(8);
        int producers = 2;
        int perProducer = 20_000;
        int total = producers * perProducer;
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        for (int index = 0; index < producers; index++) {
            final int producerId = index;
            Thread producer = new Thread(() -> {
                try {
                    for (int seq = 0; seq < perProducer; seq++) {
                        if (queue.offer(producerId * perProducer + seq, 5, TimeUnit.SECONDS)) {
                            accepted.incrementAndGet();
                        } else {
                            timedOut.incrementAndGet();
                        }
                    }
                } catch (InterruptedException expected) {
                    problem.compareAndSet(null, expected);
                } catch (Throwable broken) {
                    problem.compareAndSet(null, broken);
                }
            }, "timed-producer-" + producerId);
            threads.add(producer);
        }
        Thread consumer = new Thread(() -> {
            while (received.get() < total) {
                try {
                    if (queue.poll(100, TimeUnit.MILLISECONDS) != null) {
                        received.incrementAndGet();
                    }
                } catch (InterruptedException expected) {
                    problem.compareAndSet(null, expected);
                    return;
                }
            }
        }, "timed-consumer");
        threads.add(consumer);

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        Harness.check(problem.get() == null, "a worker failed: " + problem.get());
        Harness.checkEquals("timedOut", 0, timedOut.get());
        Harness.checkEquals("every producer's 5 s budget was enough", total, accepted.get());
        Harness.checkEquals("and the consumer saw all of them", total, received.get());
    }

    private static void sizeBounds() throws Exception {
        Turnstile<Integer> queue = new Turnstile<>(16);
        int capacity = queue.capacity();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean sampled = new AtomicBoolean();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        AtomicInteger offered = new AtomicInteger();
        AtomicInteger polled = new AtomicInteger();
        int[] extremes = new int[2];
        // The ring is full before anything moves, and the consumer stays gated
        // until the sampler has taken its first reading. Without both halves the
        // case is a race three spinning threads can lose: one producer and one
        // consumer with an unbounded backlog leave the queue empty almost all of
        // the time, so `size()` legitimately reads zero half a million times in a
        // row. That is what failed the second CI run on ubuntu/JDK 21, and the
        // assertion was the defect, not the queue.
        for (int i = 0; i < capacity; i++) {
            queue.offer(i);
        }

        Thread producer = new Thread(() -> {
            int value = capacity;
            while (!stop.get()) {
                if (queue.offer(value++)) {
                    offered.incrementAndGet();
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "sizer-producer");
        Thread consumer = new Thread(() -> {
            while (!stop.get()) {
                if (!sampled.get()) {
                    Thread.onSpinWait();
                    continue;
                }
                if (queue.poll() != null) {
                    polled.incrementAndGet();
                }
            }
        }, "sizer-consumer");
        Thread sampler = new Thread(() -> {
            int min = capacity;
            int max = 0;
            try {
                for (int round = 0; round < 500_000; round++) {
                    int size = queue.size();
                    if (size < 0 || size > capacity) {
                        throw new IllegalStateException("size() reported " + size
                                + " for a ring of capacity " + capacity);
                    }
                    min = Math.min(min, size);
                    max = Math.max(max, size);
                    if (round == 0) {
                        sampled.set(true);
                    }
                }
            } catch (Throwable broken) {
                problem.compareAndSet(null, broken);
                sampled.set(true); // never hold the consumer for a failed case
            }
            extremes[0] = min;
            extremes[1] = max;
            stop.set(true);
        }, "sizer-sampler");

        producer.start();
        consumer.start();
        sampler.start();
        sampler.join();
        stop.set(true);
        producer.join();
        consumer.join();

        Harness.check(problem.get() == null, "size() left its bounds: " + problem.get());
        Harness.check(extremes[1] == capacity,
                "the sampler saw the full ring it started with, largest reading " + extremes[1]);
        Harness.check(offered.get() >= 1 && polled.get() >= 1,
                "traffic ran while it sampled: " + offered.get() + " offered, " + polled.get()
                        + " polled, readings spanned " + extremes[0] + ".." + extremes[1]);
        Harness.check(!queue.isEmpty() || queue.size() == 0, "isEmpty and size agree at rest");
    }

    /**
     * The documentation quotes this checkout: how many cases the suite has, how
     * long the three files whose sizes are used as arguments are, and which
     * transcripts are claimed to be committed next to them. One of those numbers
     * went stale the week this case did not exist — a Javadoc fix added two lines
     * to the queue and the README kept reporting the old count, on a machine where
     * nothing else reads the README.
     */
    private static void documentationClaims() throws Exception {
        Path readme = Path.of("README.md");
        Path contributing = Path.of("CONTRIBUTING.md");
        Path algorithms = Path.of("docs", "ALGORITHMS.md");
        Path queue = Path.of("src", "main", "java", "turnstile", "Turnstile.java");
        Path harness = Path.of("src", "test", "java", "turnstile", "Harness.java");
        Path suite = Path.of("src", "test", "java", "turnstile", "TurnstileTest.java");
        Path script = Path.of("build.sh");
        for (Path path : List.of(readme, contributing, algorithms, queue, harness, suite, script)) {
            Harness.check(Files.isRegularFile(path),
                    "this case reads the checkout, so run the suite from the repository root; no "
                            + path + " under " + Path.of("").toAbsolutePath());
        }

        String documentation = Files.readString(readme) + "\n" + Files.readString(contributing)
                + "\n" + Files.readString(algorithms);
        String readMe = Files.readString(readme);

        // Built in two pieces so that this method does not itself contain the
        // string it counts occurrences of.
        int cases = occurrences(Files.readString(suite), "Harness." + "test(");
        Harness.check(cases > 0, "the suite registers its own cases, found " + cases);
        List<String> quoted = new ArrayList<>();
        quoted.addAll(matches(documentation, "(\\d+) cases"));
        quoted.addAll(matches(documentation, "(\\d+)-case suite"));
        quoted.addAll(matches(documentation, "badge/tests-(\\d+)%20passing"));
        Harness.check(!quoted.isEmpty(), "the docs quote the suite size somewhere");
        for (String size : quoted) {
            Harness.checkEquals("the suite has " + cases + " registrations, a document says",
                    cases, Integer.parseInt(size));
        }

        Harness.check(readMe.contains("the queue, " + lineCount(queue) + " lines"),
                "README's line count for Turnstile.java, which is " + lineCount(queue)
                        + " lines long, is quoted as: " + excerpt(readMe, "the queue, "));
        Harness.check(readMe.contains("test framework is " + lineCount(harness) + " lines"),
                "README's line count for Harness.java, which is " + lineCount(harness)
                        + " lines long, is quoted as: " + excerpt(readMe, "test framework is "));
        Harness.check(Files.readString(contributing).contains("`Harness.java` is "
                        + lineCount(harness) + " lines"),
                "CONTRIBUTING quotes the same Harness.java length");
        Harness.check(readMe.contains("`build.sh` is " + lineCount(script) + " lines of POSIX shell"),
                "README's line count for build.sh, which is " + lineCount(script)
                        + " lines long, is quoted as: " + excerpt(readMe, "`build.sh` is "));

        List<String> transcripts = matches(readMe, "\\]\\((bench/[A-Za-z0-9_.-]+\\.txt)\\)");
        Harness.check(transcripts.size() == 5,
                "five transcripts are claimed to be committed; the README links " + transcripts);
        for (String transcript : transcripts) {
            Harness.check(Files.isRegularFile(Path.of(transcript)),
                    "the README links a transcript that is not in the checkout: " + transcript);
        }
    }

    /** Every capture group 1 of {@code regex} in {@code text}. */
    private static List<String> matches(String text, String regex) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** The same thing {@code wc -l} counts: the line separators present. */
    private static int lineCount(Path path) throws IOException {
        return occurrences(Files.readString(path), "\n");
    }

    /** The quoted number next to a claim, so a failure says what to edit. */
    private static String excerpt(String text, String before) {
        int at = text.indexOf(before);
        if (at < 0) {
            return "the phrase " + before + " does not appear at all";
        }
        int end = Math.min(text.length(), at + before.length() + 12);
        return text.substring(at, end).replace('\n', ' ');
    }
}
