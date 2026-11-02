package dev.swordai.core.pvp;

import dev.swordai.core.school.Teacher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The teacher's opening jump-in crit (the user, 2 Nov: "99% of user starts with jump in crit"; take off so the landing is
 * 2.5-3 blocks from them, so that in their reach it is already falling and is not knocked away). Prints, by the take-off
 * distance, against people drawn as the school draws them: where it landed, how often it was hit while still rising, who
 * hit first, and the damage of the first 200 ticks; and the same for a teacher that does not open with a jump.
 */
class OpeningJumpInTest {
	static final int SEEDS = 200;

	@Test
	void openingJumpInsByTakeOff() {
		List<Technique> all = new ArrayList<>();
		for (Technique t : Technique.ALL) {
			if (t != Technique.BASE) {
				all.add(t);
			}
		}
		Technique[] family = all.toArray(new Technique[0]);
		StringBuilder out = new StringBuilder("teacher vs " + SEEDS + " mixers from 8 blocks: take-off (eye to box) | jumped "
				+ "| take-off median | landed median | hit rising | its hit first | crit first | net damage 200 ticks\n");
		// -2: the teacher with the jump-in that lands 2.5-3 out (Style#jumpInLands); -1: the teacher without it (out of reach it
		// opens with the three-block hit, T7); 0: without T7, T1a and T9c, no jump; above: without them, the jump-in crit taken
		// off at that distance.
		float[] froms = {-2f, -1f, 0f, 2.8f, 3.5f, 4.0f, 4.5f, 5.0f, 5.5f, 6.0f};
		for (float from : froms) {
			int jumped = 0, rising = 0, first = 0, critFirst = 0, enter = 0, fallingIn = 0;
			double net = 0;
			List<Double> offs = new ArrayList<>(), lands = new ArrayList<>();
			for (long seed = 1; seed <= SEEDS; seed++) {
				Style me = Teacher.style(family);
				me.jumpInLands = from == -2f;
				if (from >= 0f) {
					me.set(Technique.T7, 0f).set(Technique.T1A, 0f).set(Technique.T9C, 0f);
				}
				me.critOpen = from > 0f || from == -2f ? 1f : 0f;
				me.critFrom = from > 0f ? from : me.critFrom;
				Style them = Specialists.draw(Specialists.MIXER, new Random(seed * 7919));
				Bout b = new Bout(me, them, 8.0, seed);
				int takeOff = -1, firstHit = -1;
				boolean wasGround = true, landed = false, entered = false;
				double x0 = 0;
				for (int t = 0; t < 200; t++) {
					Bout.Tick k = b.step();
					Body a = k.body[0];
					if (takeOff < 0 && wasGround && !a.onGround && a.vy > 0 && firstHit < 0) {
						takeOff = t;
						offs.add(b.ticks.size() > 1 ? b.ticks.get(b.ticks.size() - 2).reach : k.reach);
						x0 = Math.hypot(a.x - b.fight.body[1].x, a.z - b.fight.body[1].z);
					} else if (takeOff >= 0 && !landed && a.onGround) {
						landed = true;
						if (firstHit < 0) {
							lands.add(k.reach);
						}
					}
					// In their reach (3 blocks, eye to box) for the first time since the take-off: already falling?
					if (takeOff >= 0 && !landed && !entered && k.reach <= Duel.REACH) {
						entered = true;
						enter++;
						fallingIn += a.vy < 0 ? 1 : 0;
					}
					if (firstHit < 0 && (k.events[0].iHit != Events.NONE || k.events[1].iHit != Events.NONE)) {
						firstHit = k.events[0].iHit != Events.NONE ? 0 : 1;
						if (firstHit == 0) {
							first++;
							critFirst += k.events[0].iHit == Events.CRIT ? 1 : 0;
						} else if (takeOff >= 0 && !landed && b.ticks.get(b.ticks.size() - 2).body[0].vy > 0) {
							rising++;
						}
					}
					wasGround = a.onGround;
				}
				jumped += takeOff >= 0 && (firstHit < 0 || takeOff <= b.ticks.size()) ? 1 : 0;
				net += b.fight.dealt[0] - b.fight.dealt[1];
			}
			out.append(String.format(Locale.ROOT, "%s %6d %10.2f %12.2f %9.0f%% %11.0f%% %11.0f%% %+12.1f   falling entering reach %d of %d%n",
					from > 0f ? String.format(Locale.ROOT, "%4.1f", from) : from == 0f ? "none" : from == -1f ? "T7  " : "land", jumped, median(offs), median(lands),
					100.0 * rising / SEEDS, 100.0 * first / SEEDS, 100.0 * critFirst / SEEDS, net / SEEDS, fallingIn, enter));
		}
		System.out.println(out);
	}

	/**
	 * The jump alone, against one that stands, walks in or sprints in from 9 blocks: by the take-off distance, the tick of the
	 * fall it came into reach on (negative: still rising) and the reach at the landing.
	 */
	@Test
	void whereTheJumpInLands() {
		List<Technique> all = new ArrayList<>();
		for (Technique t : Technique.ALL) {
			if (t != Technique.BASE && t != Technique.T7 && t != Technique.T1A && t != Technique.T9C) {
				all.add(t);
			}
		}
		StringBuilder out = new StringBuilder("jump-in vs a scripted one from 9 blocks: take-off | in reach at tick of the fall (- rising)"
				+ ", landed at; for still / walking / sprinting\n");
		for (float from = 3.0f; from <= 8.01f; from += 0.5f) {
			out.append(String.format(Locale.ROOT, "%4.1f", from));
			for (int how = 0; how < 3; how++) {
				Style me = Teacher.style(all.toArray(new Technique[0]));
				me.critOpen = 1f;
				me.critFrom = from;
				int move = how;
				Bout b = PvpPlayerTest.scriptedFrom(new Bout(me, PvpPlayerTest.plain(), 9.0, 3L), 60, (tick, v, k) -> {
					k.forward = move > 0 ? 1 : 0;
					k.sprint = move == 2;
				});
				int takeOff = -1, apex = -1, in = 0;
				double off = 0, land = Double.NaN;
				for (int i = 1; i < b.ticks.size(); i++) {
					Bout.Tick k = b.ticks.get(i);
					Body a = k.body[0];
					if (takeOff < 0 && b.ticks.get(i - 1).body[0].onGround && !a.onGround) {
						takeOff = i;
						off = b.ticks.get(i - 1).reach;
					} else if (takeOff >= 0 && apex < 0 && a.vy <= 0) {
						apex = i;
					}
					if (takeOff >= 0 && in == 0 && k.reach <= Duel.REACH) {
						in = apex < 0 ? -(i - takeOff) : i - apex + 1;
					}
					if (takeOff >= 0 && i > takeOff && a.onGround) {
						land = k.reach;
						break;
					}
				}
				out.append(String.format(Locale.ROOT, "   off %.2f in %+3d land %.2f", off, in, land));
			}
			out.append('\n');
		}
		System.out.println(out);
	}

	/** The teacher's jump-in (Style#jumpInLands) comes into reach falling at one who stands, walks in or sprints in. */
	@Test
	void theTeachersJumpInComesIntoReachFalling() {
		for (int how = 0; how < 3; how++) {
			Style me = Teacher.style(new Technique[] {Technique.B1, Technique.K2});
			me.critOpen = 1f;
			me.jumpInLands = true;
			int move = how;
			Bout b = PvpPlayerTest.scriptedFrom(new Bout(me, PvpPlayerTest.plain(), 9.0, 3L), 60, (tick, v, k) -> {
				k.forward = move > 0 ? 1 : 0;
				k.sprint = move == 2;
			});
			boolean tookOff = false;
			for (int i = 1; i < b.ticks.size(); i++) {
				Body a = b.ticks.get(i).body[0];
				tookOff |= b.ticks.get(i - 1).body[0].onGround && !a.onGround;
				if (b.ticks.get(i).reach <= Duel.REACH) {
					org.junit.jupiter.api.Assertions.assertTrue(tookOff && !a.onGround && a.vy < 0, "move " + how + "\n" + b.trace());
					break;
				}
			}
		}
	}

	static double median(List<Double> v) {
		if (v.isEmpty()) {
			return Double.NaN;
		}
		Collections.sort(v);
		return v.get(v.size() / 2);
	}
}
