package dev.swordai.core.school;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Bout;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Human;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Agent 49: the bots as they were and with a person's limits (Human#humanize), each against the school's teacher (the AI's stand-in)
 * in the ToyFight, with the views late as in the arena (the bot's Style#lag, the AI's 2 ticks) and the knockback late (Style#ping):
 * the numbers measured on the user's real rounds (13-15 Nov, 122 rounds, the opponent's side), on the same definitions.
 */
class HumanBotsReport {
	private static final int FIGHTS = 25;
	private static final int TICKS = 600;
	/** The real rounds' numbers (human_stats.py, 16 Nov): whiff share within 3.6, distance at their hits p50, sword under 0.9 at their
	 * swings, ticks between close swings p50, the AI's sword ready at their hits, their runs of 3+ hits. */
	static final double[] HUMAN = {0.27, 1.53, 0.30, 14, 0.36, 0.025};

	static final class Tally {
		int swings, closeSwings, closeWhiffs, early, hits, readyAtHit, runs, runs3, aiFirst, botFirst;
		final List<Double> dist = new ArrayList<>();
		final List<Integer> gaps = new ArrayList<>();
		float dealt, taken;
		int fights, aiWon;

		double[] numbers() {
			return new double[] {closeWhiffs / (double) Math.max(1, closeSwings), median(dist), early / (double) Math.max(1, swings),
					medianI(gaps), readyAtHit / (double) Math.max(1, hits), runs3 / (double) Math.max(1, runs)};
		}
	}

	private static double median(List<Double> v) {
		if (v.isEmpty()) {
			return Double.NaN;
		}
		List<Double> c = new ArrayList<>(v);
		Collections.sort(c);
		return c.get(c.size() / 2);
	}

	private static double medianI(List<Integer> v) {
		if (v.isEmpty()) {
			return Double.NaN;
		}
		List<Integer> c = new ArrayList<>(v);
		Collections.sort(c);
		return c.get(c.size() / 2);
	}

	private static Technique[] all() {
		List<Technique> t = new ArrayList<>();
		for (int f = 0; f < SchoolStages.FAMILIES; f++) {
			Collections.addAll(t, SchoolStages.STAGES[f].techniques);
		}
		return t.toArray(new Technique[0]);
	}

	static Tally fight(int bot, int mode) {
		Tally y = new Tally();
		Random r = new Random(31 + bot);
		Technique[] taught = all();
		for (int n = 0; n < FIGHTS; n++) {
			Style me = Teacher.style(taught);
			me.reaction = 2;
			Style them = bot == Specialists.MIXER ? Specialists.mixer(r) : Specialists.draw(bot, r);
			long seed = 5000L + 97L * bot + n;
			if (mode > 0) {
				them = Human.humanize(them, seed);
				if (mode == 2) {
					them.humanAdapts = false;
				} else if (mode == 3) {
					them.distBias = them.distSd = them.earlyClick = them.lateClick = 0f;
				} else if (mode == 4) {
					them.humanAdapts = false;
					them.earlyClick = them.lateClick = 0f;
				} else if (mode == 5) {
					them.humanAdapts = false;
					them.distBias = them.distSd = 0f;
				}
			}
			Bout b = new Bout(me, them, 3.0 + 5.0 * new Random(seed).nextDouble(), seed);
			b.fight.seenLag[0] = 2;
			b.fight.seenLag[1] = Math.max(0, them.lag);
			b.fight.knockLag[0] = 1;
			b.fight.knockLag[1] = Math.max(1, them.ping);
			int lastSwing = -1000;
			int lastHit = -1000;
			int run = 0;
			for (int t = 0; t < TICKS; t++) {
				float botCharge = b.fight.body[1].charge;
				float aiCharge = b.fight.body[0].charge;
				double dist = b.fight.view[0].reach;
				b.step();
				Events be = b.fight.events[1];
				Events ae = b.fight.events[0];
				if (be.iSwung) {
					y.swings++;
					y.early += botCharge < Body.CHARGED ? 1 : 0;
					if (dist <= 3.6) {
						y.closeSwings++;
						y.closeWhiffs += be.iHit == Events.NONE ? 1 : 0;
						if (t - lastSwing < 30) {
							y.gaps.add(t - lastSwing);
						}
						lastSwing = t;
					}
				}
				boolean botHit = be.iHit != Events.NONE;
				boolean aiHit = ae.iHit != Events.NONE;
				if (botHit || aiHit) {
					if (t - lastHit > 20) {
						if (run > 0) {
							y.runs++;
							y.runs3 += run >= 3 ? 1 : 0;
						}
						run = 0;
						y.botFirst += botHit && !aiHit ? 1 : 0;
						y.aiFirst += aiHit && !botHit ? 1 : 0;
					}
					lastHit = t;
				}
				if (aiHit && run > 0) {
					y.runs++;
					y.runs3 += run >= 3 ? 1 : 0;
					run = 0;
				}
				if (botHit) {
					y.hits++;
					y.dist.add(dist);
					y.readyAtHit += aiCharge >= Body.CHARGED ? 1 : 0;
					run++;
				}
			}
			if (run > 0) {
				y.runs++;
				y.runs3 += run >= 3 ? 1 : 0;
			}
			y.dealt += b.fight.dealt[1];
			y.taken += b.fight.dealt[0];
			y.fights++;
			y.aiWon += b.fight.dealt[0] > b.fight.dealt[1] ? 1 : 0;
		}
		return y;
	}

	@Test
	void theBotsMoveTowardThePeople() {
		StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
				"%nbots off -> human (Human.humanize), against the teacher in the ToyFight; real rounds: whiff %.2f, hit dist %.2f, early clicks %.2f, "
						+ "swing gap %.0f, AI ready at their hit %.2f, runs 3+ %.3f%n",
				HUMAN[0], HUMAN[1], HUMAN[2], HUMAN[3], HUMAN[4], HUMAN[5]));
		out.append(String.format(Locale.ROOT, "%-18s %-11s %-11s %-11s %-9s %-11s %-13s %-11s %s%n", "bot", "whiff", "hit dist", "early", "gap",
				"AI ready", "runs3+", "bot first", "bot net a fight"));
		double[] sumOff = new double[6];
		double[] sumOn = new double[6];
		double netOff = 0, netOn = 0;
		int aiWonOff = 0, aiWonOn = 0, nf = 0;
		int bots = 0;
		for (int bot = 0; bot <= Specialists.COUNT; bot++) {
			Tally off = fight(bot, 0);
			Tally on = fight(bot, 1);
			double[] a = off.numbers();
			double[] c = on.numbers();
			if (off.hits < 20 || on.hits < 20) {
				continue;
			}
			bots++;
			for (int i = 0; i < 6; i++) {
				sumOff[i] += a[i];
				sumOn[i] += c[i];
			}
			double no = (off.dealt - off.taken) / FIGHTS;
			double nn = (on.dealt - on.taken) / FIGHTS;
			netOff += no;
			netOn += nn;
			aiWonOff += off.aiWon;
			aiWonOn += on.aiWon;
			nf += off.fights;
			out.append(String.format(Locale.ROOT, "%-18s %.2f->%.2f %.2f->%.2f %.2f->%.2f %2.0f->%-4.0f %.2f->%.2f %.3f->%.3f %.2f->%.2f %+.1f->%+.1f%n",
					bot == Specialists.MIXER ? "mixer" : Specialists.NAMES[bot], a[0], c[0], a[1], c[1], a[2], c[2], a[3], c[3], a[4], c[4], a[5], c[5],
					off.botFirst / (double) Math.max(1, off.botFirst + off.aiFirst), on.botFirst / (double) Math.max(1, on.botFirst + on.aiFirst), no, nn));
		}
		for (int i = 0; i < 6; i++) {
			sumOff[i] /= bots;
			sumOn[i] /= bots;
		}
		out.append(String.format(Locale.ROOT, "%-18s %.2f->%.2f %.2f->%.2f %.2f->%.2f %2.0f->%-4.0f %.2f->%.2f %.3f->%.3f %11s %+.1f->%+.1f%n", "MEAN",
				sumOff[0], sumOn[0], sumOff[1], sumOn[1], sumOff[2], sumOn[2], sumOff[3], sumOn[3], sumOff[4], sumOn[4], sumOff[5], sumOn[5], "",
				netOff / bots, netOn / bots));
		out.append(String.format(Locale.ROOT, "the teacher (the AI's stand-in) won %.0f%% of the fights against the bots as they were, %.0f%% against the human ones "
				+ "(the AI against real players 13-15 Nov: 79 of 104 decided rounds, 76%%; against the bots in training 35-39%%)%n",
				100.0 * aiWonOff / nf, 100.0 * aiWonOn / nf));
		for (int mode = 2; mode <= 5; mode++) {
			double[] m = new double[6];
			double net = 0;
			int nb = 0;
			for (int bot = 0; bot <= Specialists.COUNT; bot++) {
				Tally t = fight(bot, mode);
				if (t.hits < 20) {
					continue;
				}
				nb++;
				double[] v = t.numbers();
				for (int i = 0; i < 6; i++) {
					m[i] += v[i];
				}
				net += (t.dealt - t.taken) / FIGHTS;
			}
			out.append(String.format(Locale.ROOT, "%-18s %.2f %.2f %.2f %.0f %.2f %.3f net %+.1f%n", new String[] {"", "", "limits only", "adaptation only", "distance only", "clicks only"}[mode],
					m[0] / nb, m[1] / nb, m[2] / nb, m[3] / nb, m[4] / nb, m[5] / nb, net / nb));
		}
		System.out.println(out);
		// Each limit moves the bots' number toward the people's.
		assertTrue(Math.abs(sumOn[0] - HUMAN[0]) <= Math.abs(sumOff[0] - HUMAN[0]) + 0.01, "whiffs not farther from the people's\n" + out);
		assertTrue(Math.abs(sumOn[1] - HUMAN[1]) < Math.abs(sumOff[1] - HUMAN[1]), "hit distance toward the people's\n" + out);
		assertTrue(Math.abs(sumOn[2] - HUMAN[2]) < Math.abs(sumOff[2] - HUMAN[2]), "early clicks toward the people's\n" + out);
		assertTrue(Math.abs(sumOn[3] - HUMAN[3]) <= Math.abs(sumOff[3] - HUMAN[3]) + 1, "the swing gap not farther from the people's\n" + out);
		// The AI's stand-in beats the human bots about as often as the AI beats real players (76%), not every time.
		double wonOn = aiWonOn / (double) nf;
		assertTrue(wonOn > aiWonOff / (double) nf && wonOn < 0.9, "the teacher's wins toward the AI's against people\n" + out);
	}
}
