package dev.swordai.core.env;

import java.util.ArrayDeque;
import java.util.Random;
import java.util.function.Consumer;

/**
 * What comes over a connection to a server: everything a set number of ticks late, and over one
 * that stutters, now and then nothing at all for a few ticks and then all of it at once. The first
 * is why a player on a server is knocked up by a hit a moment after the hit; the second is why a
 * lagging player stands still and then jumps on.
 *
 * <p>Things come out in the order they went in.
 *
 * @param <T> what is sent; never changed after it has been put in
 */
public final class LagLink<T> {
	/** More than this many things are never kept waiting: the oldest is let go of. */
	private static final int MOST_WAITING = 64;

	private final ArrayDeque<T> waiting = new ArrayDeque<>();
	private final ArrayDeque<Long> due = new ArrayDeque<>();
	private long now;
	private int delay;
	private float stallChance;
	private int stallTicks;
	private int stallLeft;
	private boolean stalled;

	/**
	 * @param delayTicks  everything arrives this many ticks late
	 * @param stallChance on each tick, the chance that the connection stops handing things on
	 * @param stallTicks  such a stop lasts from one tick to this many
	 */
	public void set(int delayTicks, float stallChance, int stallTicks) {
		this.delay = Math.max(0, delayTicks);
		this.stallTicks = Math.max(1, stallTicks);
		this.stallChance = stallTicks <= 0 ? 0f : Math.max(0f, Math.min(1f, stallChance));
	}

	public int delay() {
		return delay;
	}

	/** True when nothing is ever held back: no delay and no stops. */
	public boolean straight() {
		return delay == 0 && stallChance <= 0f;
	}

	/** True while something sent now would have to wait. */
	public boolean holding() {
		return delay > 0 || stalled || !waiting.isEmpty();
	}

	public int waiting() {
		return waiting.size();
	}

	/** Forgets what is on its way: after a new start nothing from the old place may arrive late. */
	public void clear() {
		waiting.clear();
		due.clear();
		stallLeft = 0;
		stalled = false;
	}

	/** Puts something on its way. */
	public void send(T item) {
		if (waiting.size() >= MOST_WAITING) {
			waiting.removeFirst();
			due.removeFirst();
		}
		waiting.addLast(item);
		due.addLast(now + delay);
	}

	/**
	 * One tick: hands over everything that has arrived by now, oldest first, unless the connection
	 * has stopped. What was sent earlier in the same tick counts as sent this tick.
	 */
	public void tick(Random rng, Consumer<? super T> arrive) {
		if (stallLeft > 0) {
			stallLeft--;
			stalled = true;
		} else if (stallChance > 0f && rng.nextFloat() < stallChance) {
			// This tick, and up to that many more.
			stallLeft = rng.nextInt(stallTicks);
			stalled = true;
		} else {
			stalled = false;
			while (!due.isEmpty() && due.peekFirst() <= now) {
				due.removeFirst();
				arrive.accept(waiting.removeFirst());
			}
		}
		now++;
	}

	/** Hands over everything still on its way, at once: the connection is no longer in between. */
	public void flush(Consumer<? super T> arrive) {
		stallLeft = 0;
		stalled = false;
		while (!waiting.isEmpty()) {
			due.removeFirst();
			arrive.accept(waiting.removeFirst());
		}
	}
}
