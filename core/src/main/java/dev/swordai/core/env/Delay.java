package dev.swordai.core.env;

/**
 * Hands things on a fixed number of ticks late: what one fighter gets to know of the other on a
 * server, where everything has gone through the network first.
 *
 * @param <T> what is handed on; never changed after it has been put in
 */
public final class Delay<T> {
	private final Object[] ring;
	private int next;
	private int filled;
	private int ticks;

	/** @param maxTicks the longest delay this can be set to */
	public Delay(int maxTicks) {
		ring = new Object[Math.max(0, maxTicks) + 1];
	}

	public void setTicks(int ticks) {
		this.ticks = Math.max(0, Math.min(ring.length - 1, ticks));
	}

	public int ticks() {
		return ticks;
	}

	/** Forgets what went before: after a new start nothing from the old place may arrive late. */
	public void clear() {
		next = 0;
		filled = 0;
	}

	/**
	 * One tick: takes what is true now and returns what was true {@link #ticks} ticks ago, or the
	 * oldest thing known while less than that much has gone by since the last {@link #clear}.
	 */
	@SuppressWarnings("unchecked")
	public T pass(T now) {
		ring[next] = now;
		next = (next + 1) % ring.length;
		if (filled < ring.length) {
			filled++;
		}
		int back = Math.min(ticks, filled - 1);
		return (T) ring[((next - 1 - back) % ring.length + ring.length) % ring.length];
	}
}
