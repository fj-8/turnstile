package turnstile;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A bounded multiple-producer multiple-consumer queue over a fixed ring of slots.
 *
 * <p>The algorithm is the turn-based MPMC ring published by Dmitry Vyukov on
 * <em>1024cores.net</em>: every slot carries a sequence number that records whose
 * turn it is, so a producer claims its slot by advancing a shared cursor and then
 * hands the slot over by releasing that sequence. No locks, no allocation per
 * element, no unbounded growth — {@link #offer(Object)} returns {@code false}
 * when the ring is full instead of blocking or expanding.
 *
 * <p>This is an independent Java implementation of that published algorithm,
 * using {@link VarHandle} acquire/release and compare-and-set where the original
 * uses C++11 atomics. Why the plain-looking slot accesses are safe is worked out
 * in {@code docs/ALGORITHMS.md}; the short version is that the release store on a
 * sequence and the acquire load of the same sequence form the happens-before edge
 * that carries the element.
 *
 * <p>Capacity is rounded up to the next power of two, with a floor of two slots,
 * so the slot index is a mask instead of a remainder; {@link #capacity()} reports
 * the effective value.
 *
 * <p>{@code null} is not a legal element, which is what makes {@code null} from
 * {@link #poll()} mean "nothing was available" rather than "a null arrived".
 * Elements are held strongly; nothing is interned, copied or cloned.
 *
 * <p>Every method is safe to call concurrently from any number of threads.
 * {@link #size()} is approximate on purpose: an exact count would need a third
 * protocol over the two cursors, and any caller that must act on full or empty
 * can do so through {@code offer}/{@code poll}, which are exact.
 *
 * @param <E> the element type; elements must not be {@code null}
 */
public final class Turnstile<E> {

    /**
     * One cursor, spatially isolated. Java has no portable cache-line padding —
     * {@code @Contended} is an internal annotation — so the neighbours are dead
     * fields that make it likely the value sits on a line of its own. Whether
     * that buys anything is measured, not assumed; see the README.
     */
    private static final class Counter {
        long p0, p1, p2, p3;
        long value;
        long q0, q1, q2, q3;
    }

    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle SEQ = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle VALUE = counterValueHandle();

    /** findVarHandle throws checked exceptions, so the handle is built in a helper. */
    private static VarHandle counterValueHandle() {
        try {
            return MethodHandles.lookup().findVarHandle(Counter.class, "value", long.class);
        } catch (ReflectiveOperationException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static final int PARK_NANOS = 1_000;
    private static final int SPINS_BEFORE_PARK = 64;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object[] slots;
    private final long[] sequences;
    private final int mask;
    private final int capacity;
    private final Counter head = new Counter();
    private final Counter tail = new Counter();

    /**
     * @param requestedCapacity room for at least one element; rounded up to a
     *                          power of two, with a floor of two slots
     * @throws IllegalArgumentException if the request is not positive, or rounds
     *                                  above 2^30 where the index mask stops
     *                                  fitting in an int
     */
    public Turnstile(int requestedCapacity) {
        if (requestedCapacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + requestedCapacity);
        }
        // Widened before shifting: 2^30 << 1 is Integer.MIN_VALUE, and a negative
        // round must be reported as too large rather than quietly clamped to the floor.
        long rounded = (long) Integer.highestOneBit(requestedCapacity - 1) << 1;
        if (rounded > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity above 2^30 is not supported: "
                    + requestedCapacity + " would round to " + rounded);
        }
        if (rounded < 2) {
            // Two slots is the real floor, not a preference. A producer looks for
            // sequence == p at its position p, and the previous producer left
            // exactly that number behind when it published p-1. With a single slot
            // the index never moves, so the two readings collide and a full ring
            // accepts one more element. From two slots upwards index(p-1) and
            // index(p) differ, and the readings cannot.
            rounded = 2;
        }
        capacity = (int) rounded;
        mask = capacity - 1;
        slots = new Object[capacity];
        sequences = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            sequences[i] = i;
        }
    }

    /**
     * @return the effective capacity: the request rounded up to a power of two,
     *         with a floor of two slots
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Puts {@code item} at the tail if a slot is free at this instant.
     *
     * @param item the element to queue; not {@code null}
     * @return {@code true} when queued, {@code false} when the ring was full
     * @throws NullPointerException if {@code item} is {@code null}
     */
    public boolean offer(E item) {
        Objects.requireNonNull(item, "null element; poll() returns null for 'empty'");
        long cursor = (long) VALUE.getOpaque(head);
        for (;;) {
            int index = (int) (cursor & mask);
            long sequence = (long) SEQ.getAcquire(sequences, index);
            long gap = sequence - cursor;
            if (gap == 0) {
                if (VALUE.compareAndSet(head, cursor, cursor + 1)) {
                    SLOT.setOpaque(slots, index, item);
                    SEQ.setRelease(sequences, index, cursor + 1);
                    return true;
                }
                cursor = (long) VALUE.getOpaque(head);
            } else if (gap < 0) {
                return false;
            } else {
                cursor = (long) VALUE.getOpaque(head);
            }
        }
    }

    /**
     * {@link #offer(Object)} with a bound on how long to wait for room.
     *
     * <p>Spins for {@value #SPINS_BEFORE_PARK} rounds, then parks about a
     * microsecond per attempt: a full ring stops burning a core, a nearly-full one
     * is still served without scheduling latency.
     *
     * @param item    the element to queue; not {@code null}
     * @param timeout how long to wait for room; a negative value means no wait
     * @param unit    the unit of {@code timeout}; not {@code null}
     * @return {@code true} if queued before the timeout expired
     * @throws InterruptedException if interrupted while waiting; the interrupt
     *                              status is cleared, as is conventional for a
     *                              method that throws this
     */
    public boolean offer(E item, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long deadline = System.nanoTime() + unit.toNanos(Math.max(timeout, 0));
        int spins = 0;
        while (!offer(item)) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            if (spins++ < SPINS_BEFORE_PARK) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(Math.min(remaining, PARK_NANOS));
            }
        }
        return true;
    }

    /**
     * Removes the head element if there is one.
     *
     * @return the element, or {@code null} when the ring was empty
     */
    public E poll() {
        long cursor = (long) VALUE.getOpaque(tail);
        for (;;) {
            int index = (int) (cursor & mask);
            long sequence = (long) SEQ.getAcquire(sequences, index);
            long gap = sequence - (cursor + 1);
            if (gap == 0) {
                if (VALUE.compareAndSet(tail, cursor, cursor + 1)) {
                    Object item = SLOT.getOpaque(slots, index);
                    SLOT.setOpaque(slots, index, null);
                    SEQ.setRelease(sequences, index, cursor + capacity);
                    @SuppressWarnings("unchecked")
                    E taken = (E) item;
                    return taken;
                }
                cursor = (long) VALUE.getOpaque(tail);
            } else if (gap < 0) {
                return null;
            } else {
                cursor = (long) VALUE.getOpaque(tail);
            }
        }
    }

    /**
     * {@link #poll()} with a bound on how long to wait for an element.
     *
     * @param timeout how long to wait; a negative value means no wait
     * @param unit    the unit of {@code timeout}; not {@code null}
     * @return the element, or {@code null} if none arrived before the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long deadline = System.nanoTime() + unit.toNanos(Math.max(timeout, 0));
        int spins = 0;
        E item;
        while ((item = poll()) == null) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            if (spins++ < SPINS_BEFORE_PARK) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(Math.min(remaining, PARK_NANOS));
            }
        }
        return item;
    }

    /**
     * Moves up to {@code maxElements} elements into {@code target}.
     *
     * <p>Non-blocking and weakly consistent: it takes what is present when it
     * looks and reports how many, so a caller that cannot act on a partial batch
     * must keep calling, exactly as with
     * {@link java.util.concurrent.BlockingQueue#drainTo}.
     *
     * @param target      the collection to append to; not {@code null}
     * @param maxElements the most elements to move; a value of zero moves none
     * @return the number of elements transferred
     */
    public int drainTo(Collection<? super E> target, int maxElements) {
        Objects.requireNonNull(target, "target");
        int moved = 0;
        while (moved < maxElements) {
            E item = poll();
            if (item == null) {
                break;
            }
            target.add(item);
            moved++;
        }
        return moved;
    }

    /**
     * Elements queued at the instant of the call, as close as a lock-free
     * structure can say without a third cursor.
     *
     * @return an approximate count, always within {@code [0, capacity()]}
     */
    public int size() {
        long difference = (long) VALUE.getOpaque(head) - (long) VALUE.getOpaque(tail);
        if (difference < 0) {
            return 0;
        }
        return (int) Math.min(difference, capacity);
    }

    /**
     * The same caveat as {@link #size()}: a snapshot racing with live traffic.
     *
     * @return {@code true} if no element was queued when this was computed
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Slots that still hold a reference. Package-private, visible for tests: the
     * queue promises a polled slot stops retaining its element, and asserting that
     * through a {@code WeakReference} would depend on when the collector happens to
     * run. Only meaningful while nothing else is touching the queue.
     */
    int retainedElements() {
        int live = 0;
        for (int index = 0; index < capacity; index++) {
            if (SLOT.getOpaque(slots, index) != null) {
                live++;
            }
        }
        return live;
    }
}
