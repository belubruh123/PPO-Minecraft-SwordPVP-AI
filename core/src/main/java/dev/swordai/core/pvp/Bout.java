package dev.swordai.core.pvp;

import java.util.ArrayList;
import java.util.List;

/**
 * Two {@link PvpPlayer}s fighting in a {@link ToyFight}, tick by tick, with a record of every tick:
 * for the tick tests of the techniques, and for a quick look at how a bot fights.
 */
public final class Bout {
	public final ToyFight fight = new ToyFight();
	public final PvpPlayer[] player = {new PvpPlayer(), new PvpPlayer()};
	public final Keys[] keys = {new Keys(), new Keys()};
	/** Per tick: each side's keys and technique, its body after the move, and what happened. */
	public final List<Tick> ticks = new ArrayList<>();

	public static final class Tick {
		public final int t;
		public final Keys[] keys = {new Keys(), new Keys()};
		public final Body[] body = {new Body(), new Body()};
		public final Events[] events = {new Events(), new Events()};
		public double reach;

		Tick(int t) {
			this.t = t;
		}
	}

	public Bout(Style a, Style b, double distance, long seed) {
		fight.place(distance);
		player[0].start(a, seed);
		player[1].start(b, seed + 1);
	}

	public void run(int ticks) {
		for (int n = 0; n < ticks; n++) {
			step();
		}
	}

	public Tick step() {
		for (int i = 0; i < 2; i++) {
			player[i].tick(fight.view[i], fight.events[i], keys[i]);
		}
		fight.step(keys[0], keys[1]);
		Tick t = new Tick(fight.tick);
		for (int i = 0; i < 2; i++) {
			t.keys[i].copyFrom(keys[i]);
			t.body[i].copyFrom(fight.body[i]);
			t.events[i].copyFrom(fight.events[i]);
		}
		t.reach = fight.view[0].reach;
		ticks.add(t);
		return t;
	}

	/** How many ticks side {@code i} spent with {@code t} in charge. */
	public int ticksOf(int i, Technique t) {
		int n = 0;
		for (Tick k : ticks) {
			if (k.keys[i].technique == t) {
				n++;
			}
		}
		return n;
	}

	/** Hits side {@code i} landed of a kind ({@link Events}). */
	public int hits(int i, int kind) {
		return fight.hits[i][kind];
	}

	/** The fight as text, one line a tick: for a test that fails. */
	public String trace() {
		StringBuilder b = new StringBuilder();
		for (Tick t : ticks) {
			b.append(String.format(java.util.Locale.ROOT, "%4d reach %.2f | %s %-5s %s y%.2f%s | %s %-5s %s y%.2f%s%n", t.t, t.reach,
					t.keys[0].brief(), t.keys[0].technique.id, t.body[0].onGround ? "g" : "a", t.body[0].y, hit(t.events[0]),
					t.keys[1].brief(), t.keys[1].technique.id, t.body[1].onGround ? "g" : "a", t.body[1].y, hit(t.events[1])));
		}
		return b.toString();
	}

	/** Ticks each technique was in charge, per side, and the hits by kind: one line for the logs. */
	public String summary() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 2; i++) {
			b.append(i == 0 ? "A " : " | B ").append(player[i].style().name).append(':');
			for (Technique t : Technique.ALL) {
				int n = ticksOf(i, t);
				if (n > 0 && t != Technique.BASE) {
					b.append(' ').append(t.id).append('=').append(n);
				}
			}
			b.append(String.format(java.util.Locale.ROOT, " hits s%d c%d p%d dealt %.0f", hits(i, Events.SPRINT), hits(i, Events.CRIT),
					hits(i, Events.PLAIN), fight.dealt[i]));
		}
		return b.toString();
	}

	private static String hit(Events e) {
		return e.iHit == Events.SPRINT ? " HIT-sprint" : e.iHit == Events.CRIT ? " HIT-crit" : e.iHit == Events.PLAIN ? " HIT" : "";
	}
}
