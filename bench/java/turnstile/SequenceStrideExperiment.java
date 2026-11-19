package turnstile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * An experiment, not part of the shipped API: does the queue get faster if each
 * slot's sequence word owns a cache line?
 *
 * <p>{@code Turnstile} keeps its sequences in one packed {@code long[]}, so eight
 * neighbouring slots share a 64-byte line and two producers writing consecutive
 * slots write the same line. The standard answer in the literature is to pad every
 * slot, at eight words per slot instead of one. This program runs the same ring
 * with the stride as a parameter — 1 is the shipped layout, 8 is the padded one —
 * over the same workload, and prints the ratio.
 *
 * <p>Run it with {@code java turnstile.SequenceStrideExperiment}. The result on
 * the machine named in the transcript is in the README, and the reason the padding
 * is not shipped is there too: the gain is not stable enough to be worth eight
 * times the memory in a structure whose whole argument is a fixed, small footprint.
 */
public final class SequenceStrideExperiment {

    private static final int ELEMENTS = 2_000_000;
    private static final int REPS = 5;
    private static final Integer[] POOL = new Integer[4096];

    static {
        for (int index = 0; index < POOL.length; index++) {
            POOL[index] = Integer.valueOf(index + 1);
        }
    }

    private interface Push {
        void push(int value);
    }

    private interface Take {
        int take();
    }

    private SequenceStrideExperiment() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("java.version          = " + System.getProperty("java.version"));
        System.out.println("os.name               = " + System.getProperty("os.name"));
        System.out.println("availableProcessors   = " + Runtime.getRuntime().availableProcessors());
        System.out.printf(Locale.ROOT,
                "workload              = %,d elements, median of %d reps after 1 warm-up%n%n",
                ELEMENTS, REPS);
        System.out.printf(Locale.ROOT, "%-8s %-10s %14s %14s %10s%n",
                "capacity", "shape", "stride 1 ns", "stride 8 ns", "gain");
        for (int capacity : new int[] {64, 1024, 4096}) {
            for (int threads : new int[] {1, 2, 4, 8}) {
                long packed = measure(capacity, 1, threads);
                long padded = measure(capacity, 8, threads);
                System.out.printf(Locale.ROOT, "%-8d P:%d C:%d %14s %14s %9.2fx%n",
                        capacity, threads, threads, String.format(Locale.ROOT, "%,d", packed),
                        String.format(Locale.ROOT, "%,d", padded), (double) packed / padded);
            }
        }
        System.out.println();
        System.out.println("  Both columns are the same algorithm and the same code path; only the");
        System.out.println("  distance between sequence words differs. Memory for the sequences goes");
        System.out.println("  from capacity x 8 bytes to capacity x 64 bytes, which is the price.");
    }

    private static long measure(int capacity, int stride, int threads) throws Exception {
        List<Long> samples = new ArrayList<>();
        for (int rep = 0; rep <= REPS; rep++) {
            Ring ring = new Ring(capacity, stride);
            long started = System.nanoTime();
            run(ring, threads);
            long spent = System.nanoTime() - started;
            if (rep > 0) {
                samples.add(spent);
            }
        }
        Collections.sort(samples);
        return samples.get(samples.size() / 2) / ELEMENTS;
    }

    private static void run(Ring ring, int threads) throws Exception {
        int perProducer = ELEMENTS / threads;
        int total = perProducer * threads;
        AtomicInteger consumed = new AtomicInteger();
        Push push = value -> {
            while (!ring.offer(POOL[value & 4095])) {
                Thread.onSpinWait();
            }
        };
        Take take = () -> {
            Integer got = ring.poll();
            if (got == null) {
                Thread.onSpinWait();
                return -1;
            }
            return got;
        };
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            final int id = index;
            workers.add(new Thread(() -> {
                for (int value = 0; value < perProducer; value++) {
                    push.push(value + id);
                }
            }, "push-" + id));
        }
        for (int index = 0; index < threads; index++) {
            final int id = index;
            workers.add(new Thread(() -> {
                while (consumed.get() < total) {
                    if (take.take() > 0) {
                        consumed.incrementAndGet();
                    }
                }
            }, "take-" + id));
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(300_000);
            if (worker.isAlive()) {
                throw new IllegalStateException(worker.getName() + " never finished");
            }
        }
        if (consumed.get() != total) {
            throw new IllegalStateException("moved " + consumed.get() + " of " + total);
        }
    }

    /** The shipped algorithm with one added variable: how far apart the words sit. */
    private static final class Ring {
        private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
        private static final VarHandle SEQ = MethodHandles.arrayElementVarHandle(long[].class);
        private static final VarHandle CURSOR = cursorHandle();

        private static VarHandle cursorHandle() {
            try {
                return MethodHandles.lookup().findVarHandle(Counter.class, "value", long.class);
            } catch (ReflectiveOperationException impossible) {
                throw new ExceptionInInitializerError(impossible);
            }
        }

        private final Object[] slots;
        private final long[] sequences;
        private final int mask;
        private final int capacity;
        private final int stride;
        private final Counter head = new Counter();
        private final Counter tail = new Counter();

        Ring(int capacity, int stride) {
            this.capacity = capacity;
            this.stride = stride;
            this.mask = capacity - 1;
            this.slots = new Object[capacity];
            this.sequences = new long[capacity * stride];
            for (int index = 0; index < capacity; index++) {
                sequences[index * stride] = index;
            }
        }

        boolean offer(Integer item) {
            long cursor = (long) CURSOR.getOpaque(head);
            for (;;) {
                int index = (int) (cursor & mask);
                long sequence = (long) SEQ.getAcquire(sequences, index * stride);
                long gap = sequence - cursor;
                if (gap == 0) {
                    if (CURSOR.compareAndSet(head, cursor, cursor + 1)) {
                        SLOT.setOpaque(slots, index, item);
                        SEQ.setRelease(sequences, index * stride, cursor + 1);
                        return true;
                    }
                    cursor = (long) CURSOR.getOpaque(head);
                } else if (gap < 0) {
                    return false;
                } else {
                    cursor = (long) CURSOR.getOpaque(head);
                }
            }
        }

        Integer poll() {
            long cursor = (long) CURSOR.getOpaque(tail);
            for (;;) {
                int index = (int) (cursor & mask);
                long sequence = (long) SEQ.getAcquire(sequences, index * stride);
                long gap = sequence - (cursor + 1);
                if (gap == 0) {
                    if (CURSOR.compareAndSet(tail, cursor, cursor + 1)) {
                        Object item = SLOT.getOpaque(slots, index);
                        SLOT.setOpaque(slots, index, null);
                        SEQ.setRelease(sequences, index * stride, cursor + capacity);
                        return (Integer) item;
                    }
                    cursor = (long) CURSOR.getOpaque(tail);
                } else if (gap < 0) {
                    return null;
                } else {
                    cursor = (long) CURSOR.getOpaque(tail);
                }
            }
        }
    }

    private static final class Counter {
        long p0, p1, p2, p3;
        long value;
        long q0, q1, q2, q3;
    }
}
