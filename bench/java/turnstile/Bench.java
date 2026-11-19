package turnstile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The measurement harness: {@code java turnstile.Bench}, no dependencies.
 *
 * <p>No JMH is used, and that costs precision. These are wall-clock measurements
 * inside one JVM — a discarded warm-up and the median of {@value #REPS} timed
 * reps, every queue driven through the same loop shape in the same process — so
 * the comparison inside a table is sound while an absolute number quoted to a
 * stranger is not. Each row transfers the same {@value #ELEMENTS} elements.
 *
 * <p>Three design notes decide what the numbers mean. Producers push references
 * from a pre-built pool, so no row pays for boxing two million integers and none
 * gets to skip allocating an element either. The throughput loops spin with
 * {@link Thread#onSpinWait()} for every queue alike, which is what a busy
 * pipeline does and keeps the comparison about the queue rather than the wait
 * policy; the wait-policy table measures the parking path instead, and says so.
 * And the sum of everything consumed is printed as a checksum, because work that
 * nobody observes is work a compiler may remove.
 *
 * <p>{@code ConcurrentLinkedQueue} is a reference row, not a competitor: it is
 * unbounded, never refuses an offer, and so never applies the back-pressure the
 * other two exist to provide.
 */
public final class Bench {

    private static final int ELEMENTS = 2_000_000;
    private static final int REPS = 5;
    private static final int CAPACITY = 1024;

    /** Every element that came out of a queue, summed: proof the transfers were real. */
    private static final AtomicLong CHECKSUM = new AtomicLong();

    private static final Integer[] POOL = new Integer[4096];
    private static final int POOL_MASK = POOL.length - 1;

    static {
        for (int index = 0; index < POOL.length; index++) {
            POOL[index] = Integer.valueOf(index + 1);
        }
    }

    /** One element into the queue, waiting by spinning until there is room. */
    private interface Push {
        void push(int value);
    }

    /**
     * One element out of the queue.
     *
     * @return the value taken, always positive, or {@code -1} when nothing was
     *         available at this instant
     */
    private interface Take {
        int take();
    }

    /** A queue under test: it builds the producing and the consuming side. */
    private interface UnderTest {
        String name();

        Push newPush();

        Take newTake();
    }

    private Bench() {
    }

    public static void main(String[] args) throws Exception {
        print("java.version", System.getProperty("java.version"));
        print("java.vendor", System.getProperty("java.vendor"));
        print("os.name", System.getProperty("os.name"));
        print("availableProcessors", "" + Runtime.getRuntime().availableProcessors());
        print("workload", ELEMENTS + " elements per transfer, capacity " + CAPACITY
                + ", median of " + REPS + " reps after 1 warm-up");
        System.out.println();

        throughput();
        depth();
        waitPolicy();
        handoffLatency();
        falseSharing();

        print("checksum", String.format(Locale.ROOT, "%,d", CHECKSUM.get())
                + " (sum of every element consumed, plus the padding control's counters)");
        System.gc();
        print("heap at exit", String.format(Locale.ROOT, "%,d MiB used of %,d MiB max",
                usedMebibytes(), Runtime.getRuntime().maxMemory() >> 20));
    }

    // ------------------------------------------------------------- throughput

    /**
     * The matrix. The first column is always turnstile, so every other cell is a
     * ratio against the thing under test rather than a number to compare by eye.
     */
    private static void throughput() throws Exception {
        List<UnderTest> subjects = new ArrayList<>();
        subjects.add(turnstileSpi(CAPACITY));
        subjects.add(arrayBlockingSpi());
        subjects.add(concurrentLinkedSpi());
        int[][] shapes = {{1, 1}, {2, 2}, {4, 4}, {8, 8}, {1, 4}, {4, 1}};

        System.out.println("throughput, ns per element (lower is better), reference = turnstile");
        System.out.printf(Locale.ROOT, "%-10s", "shape");
        for (UnderTest subject : subjects) {
            System.out.printf(Locale.ROOT, "%23s", subject.name());
        }
        System.out.println();
        for (int[] shape : shapes) {
            System.out.printf(Locale.ROOT, "%-10s", "P:" + shape[0] + " C:" + shape[1]);
            long reference = 0;
            for (int index = 0; index < subjects.size(); index++) {
                long nanos = transfer(subjects.get(index), shape[0], shape[1]);
                if (index == 0) {
                    reference = nanos;
                }
                System.out.printf(Locale.ROOT, "%10s %-12s", format(nanos),
                        index == 0 ? "" : ratio(nanos, reference));
            }
            System.out.println();
        }
        System.out.println("  CLQ* is unbounded: it never refuses an offer, so it is not applying");
        System.out.println("  back-pressure and is not a like-for-like queue. It also allocates a");
        System.out.println("  node per element; turnstile allocates nothing after construction.");
        System.out.println();
    }

    /**
     * How much of the multi-threaded cost is the depth of the ring: the same
     * workload at increasing capacities, where a deeper ring means the threads
     * touch more distinct sequence words and fewer of them stay resident.
     */
    private static void depth() throws Exception {
        int[] depths = {2, 16, 64, 256, 1024, 4096};
        System.out.println("ring depth, P:4 C:4, ns per element");
        System.out.printf(Locale.ROOT, "%-10s %14s %14s %14s%n",
                "capacity", "turnstile", "ABQ", "ABQ vs turnstile");
        for (int depth : depths) {
            long turnstile = transfer(turnstileSpi(depth), 4, 4);
            long abq = transfer(spinning(new ArrayBlockingQueue<>(depth), "ABQ"), 4, 4);
            System.out.printf(Locale.ROOT, "%-10s %14s %14s %14s%n", format(depth),
                    format(turnstile), format(abq), ratio(abq, turnstile));
        }
        System.out.println("  A capacity-2 turnstile is the two-slot floor the constructor needs; the");
        System.out.println("  ABQ column is the same request given to a lock-based queue.");
        System.out.println();
    }

    private static void waitPolicy() throws Exception {
        System.out.println("wait policy, P:2 C:2, same workload");
        long spinning = transfer(turnstileSpi(CAPACITY), 2, 2);
        long parking = transfer(turnstileTimed(), 2, 2);
        long abqParking = transfer(arrayBlockingTimed(), 2, 2);
        System.out.printf(Locale.ROOT, "%-34s %10s ns/element%n", "turnstile, spin", format(spinning));
        System.out.printf(Locale.ROOT, "%-34s %10s ns/element  %s%n", "turnstile, offer/poll timeout",
                format(parking), ratio(parking, spinning));
        System.out.printf(Locale.ROOT, "%-34s %10s ns/element  %s%n",
                "ArrayBlockingQueue, timed offer/poll", format(abqParking), ratio(abqParking, parking));
        System.out.println("  An empty or full turnstile is discovered by the next poll, so a waiter");
        System.out.println("  pays a park and a timer round-trip, while ArrayBlockingQueue is told");
        System.out.println("  about the event by a condition signal. Waiting on a queue is ABQ's job");
        System.out.println("  and is not what this class is for: see the README's \"when not to use\".");
        System.out.println();
    }

    // -------------------------------------------------------------- handoffs

    /**
     * Two threads that each push one element and then take one, forever. Nothing
     * is pipelined: a take cannot succeed until the other side has pushed, so
     * every transfer costs a wake-up and the number is a latency, not a
     * throughput. Both threads run the same body, and one element crossing the
     * queue is one hop, which makes the total two hops per iteration count.
     */
    private static void handoffLatency() throws Exception {
        int hops = 200_000;
        System.out.println("handoff latency, ns per hop (two threads alternately pushing and taking,");
        System.out.println("   two hops per iteration, " + hops + " iterations per thread)");
        System.out.printf(Locale.ROOT, "%-34s %10s%n", "queue", "ns/hop");
        for (UnderTest subject : List.of(turnstileSpi(CAPACITY), turnstileTimed(), arrayBlockingSpi())) {
            System.out.printf(Locale.ROOT, "%-34s %10s%n", subject.name(),
                    format(pingPong(subject, hops)));
        }
        System.out.println();
    }

    private static long pingPong(UnderTest subject, int hops) throws Exception {
        List<Long> samples = new ArrayList<>();
        for (int rep = 0; rep <= REPS; rep++) {
            Push push = subject.newPush();
            Take take = subject.newTake();
            Runnable body = () -> {
                long sink = 0;
                for (int hop = 0; hop < hops; hop++) {
                    push.push(hop);
                    int value = take.take();
                    while (value < 0) {
                        value = take.take();
                    }
                    sink += value;
                }
                CHECKSUM.addAndGet(sink);
            };
            Thread first = new Thread(body, "ping");
            Thread second = new Thread(body, "pong");
            long started = System.nanoTime();
            first.start();
            second.start();
            first.join();
            second.join();
            long spent = System.nanoTime() - started;
            if (rep > 0) {
                samples.add(spent);
            }
        }
        return median(samples) / (2L * hops);
    }

    // ----------------------------------------------------------- false sharing

    /**
     * Two threads, each the only writer of its own counter, with the counters one
     * word apart or sixteen: same cache line versus a line of its own. This is
     * the effect the dead fields in {@code Turnstile.Counter} are meant to have,
     * measured rather than assumed.
     */
    private static void falseSharing() throws Exception {
        int ops = 1_000_000;
        long[] backing = new long[64];
        List<Long> adjacent = new ArrayList<>();
        List<Long> separated = new ArrayList<>();
        for (int rep = 0; rep <= REPS; rep++) {
            long started = System.nanoTime();
            hammer(backing, 0, 1, ops);
            long near = System.nanoTime() - started;
            started = System.nanoTime();
            hammer(backing, 0, 16, ops);
            long far = System.nanoTime() - started;
            if (rep > 0) {
                adjacent.add(near);
                separated.add(far);
            }
        }
        long near = median(adjacent) / ops;
        long far = median(separated) / ops;
        System.out.println("false sharing, 2 threads x " + ops + " compare-and-set on their own word");
        System.out.printf(Locale.ROOT, "%-34s %10s ns/op%n", "counters one word apart", format(near));
        System.out.printf(Locale.ROOT, "%-34s %10s ns/op  %s%n", "counters 128 bytes apart",
                format(far), ratio(far, near));
        System.out.println("  A long[] is 8 bytes per element, so index 0 and 1 share a line while");
        System.out.println("  index 0 and 16 do not. The ratio is what the padding in Counter buys.");
        System.out.println();
    }

    private static void hammer(long[] backing, int left, int right, int ops) throws Exception {
        Thread a = new Thread(new Incrementer(backing, left, ops), "hammer-left");
        Thread b = new Thread(new Incrementer(backing, right, ops), "hammer-right");
        a.start();
        b.start();
        a.join();
        b.join();
    }

    private static final class Incrementer implements Runnable {
        private static final VarHandle WORD = MethodHandles.arrayElementVarHandle(long[].class);

        private final long[] backing;
        private final int index;
        private final int times;

        Incrementer(long[] backing, int index, int times) {
            this.backing = backing;
            this.index = index;
            this.times = times;
        }

        @Override
        public void run() {
            for (int done = 0; done < times; done++) {
                long cursor = (long) WORD.getOpaque(backing, index);
                while (!WORD.compareAndSet(backing, index, cursor, cursor + 1)) {
                    cursor = (long) WORD.getOpaque(backing, index);
                }
            }
        }
    }

    // ----------------------------------------------------------------- driver

    /** Runs the timed reps for one configuration; median nanoseconds per element. */
    private static long transfer(UnderTest subject, int producers, int consumers) throws Exception {
        List<Long> samples = new ArrayList<>();
        for (int rep = 0; rep <= REPS; rep++) {
            long started = System.nanoTime();
            int moved = runOnce(subject, producers, consumers);
            long spent = System.nanoTime() - started;
            if (moved != ELEMENTS) {
                throw new IllegalStateException(subject.name() + " moved " + moved
                        + " of " + ELEMENTS + " elements");
            }
            if (rep > 0) {
                samples.add(spent);
            }
        }
        return median(samples) / ELEMENTS;
    }

    private static int runOnce(UnderTest subject, int producers, int consumers) throws Exception {
        int perProducer = ELEMENTS / producers;
        int total = perProducer * producers;
        AtomicInteger consumed = new AtomicInteger();
        long[] sinks = new long[consumers];
        List<Thread> threads = new ArrayList<>();

        for (int index = 0; index < producers; index++) {
            final int id = index;
            Push push = subject.newPush();
            threads.add(new Thread(() -> {
                for (int value = 0; value < perProducer; value++) {
                    push.push(value + id);
                }
            }, "bench-producer-" + id));
        }
        for (int index = 0; index < consumers; index++) {
            final int id = index;
            Take take = subject.newTake();
            threads.add(new Thread(() -> {
                long sink = 0;
                while (consumed.get() < total) {
                    int value = take.take();
                    if (value > 0) {
                        sink += value;
                        consumed.incrementAndGet();
                    }
                }
                sinks[id] = sink;
            }, "bench-consumer-" + id));
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        long sink = 0;
        for (long value : sinks) {
            sink += value;
        }
        CHECKSUM.addAndGet(sink);
        return consumed.get();
    }

    // --------------------------------------------------------------- subjects

    private static UnderTest turnstileSpi(int depth) {
        Turnstile<Integer> queue = new Turnstile<>(depth);
        return new UnderTest() {
            @Override
            public String name() {
                return "turnstile";
            }

            @Override
            public Push newPush() {
                return value -> {
                    while (!queue.offer(POOL[value & POOL_MASK])) {
                        Thread.onSpinWait();
                    }
                };
            }

            @Override
            public Take newTake() {
                return () -> {
                    Integer taken = queue.poll();
                    if (taken == null) {
                        Thread.onSpinWait();
                        return -1;
                    }
                    return taken;
                };
            }
        };
    }

    /** The same queue, driven through the API that parks instead of spinning. */
    private static UnderTest turnstileTimed() {
        Turnstile<Integer> queue = new Turnstile<>(CAPACITY);
        return new UnderTest() {
            @Override
            public String name() {
                return "turnstile-timed";
            }

            @Override
            public Push newPush() {
                return value -> {
                    try {
                        if (!queue.offer(POOL[value & POOL_MASK], 1, TimeUnit.MINUTES)) {
                            throw new IllegalStateException("offer gave up");
                        }
                    } catch (InterruptedException broken) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(broken);
                    }
                };
            }

            @Override
            public Take newTake() {
                return () -> {
                    try {
                        Integer taken = queue.poll(5, TimeUnit.MILLISECONDS);
                        return taken == null ? -1 : taken;
                    } catch (InterruptedException broken) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(broken);
                    }
                };
            }
        };
    }

    private static UnderTest arrayBlockingSpi() {
        return spinning(new ArrayBlockingQueue<>(CAPACITY), "ABQ");
    }

    private static UnderTest arrayBlockingTimed() {
        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(CAPACITY);
        return new UnderTest() {
            @Override
            public String name() {
                return "ABQ-timed";
            }

            @Override
            public Push newPush() {
                return value -> {
                    try {
                        if (!queue.offer(POOL[value & POOL_MASK], 1, TimeUnit.MINUTES)) {
                            throw new IllegalStateException("offer gave up");
                        }
                    } catch (InterruptedException broken) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(broken);
                    }
                };
            }

            @Override
            public Take newTake() {
                return () -> {
                    try {
                        Integer taken = queue.poll(5, TimeUnit.MILLISECONDS);
                        return taken == null ? -1 : taken;
                    } catch (InterruptedException broken) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(broken);
                    }
                };
            }
        };
    }

    private static UnderTest concurrentLinkedSpi() {
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        return new UnderTest() {
            @Override
            public String name() {
                return "CLQ*";
            }

            @Override
            public Push newPush() {
                return value -> queue.offer(POOL[value & POOL_MASK]);
            }

            @Override
            public Take newTake() {
                return () -> {
                    Integer taken = queue.poll();
                    if (taken == null) {
                        Thread.onSpinWait();
                        return -1;
                    }
                    return taken;
                };
            }
        };
    }

    /** Spin-driven offer/poll, which is how every row in the throughput table runs. */
    private static UnderTest spinning(BlockingQueue<Integer> queue, String name) {
        return new UnderTest() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Push newPush() {
                return value -> {
                    while (!queue.offer(POOL[value & POOL_MASK])) {
                        Thread.onSpinWait();
                    }
                };
            }

            @Override
            public Take newTake() {
                return () -> {
                    Integer taken = queue.poll();
                    if (taken == null) {
                        Thread.onSpinWait();
                        return -1;
                    }
                    return taken;
                };
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    private static void print(String key, String value) {
        System.out.println(String.format(Locale.ROOT, "%-20s = %s", key, value));
    }

    private static long median(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static String format(long nanos) {
        return String.format(Locale.ROOT, "%,d", nanos);
    }

    private static String ratio(long candidate, long reference) {
        if (reference == 0) {
            return "-";
        }
        double times = (double) candidate / (double) reference;
        if (Math.abs(times - 1.0) < 0.005) {
            return "the same";
        }
        return times > 1.0
                ? String.format(Locale.ROOT, "%.2fx slower", times)
                : String.format(Locale.ROOT, "%.2fx faster", 1.0 / times);
    }

    private static long usedMebibytes() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) >> 20;
    }
}
