package dev.swordai.core.school;

import dev.swordai.core.pvp.Bout;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Not a check, a report: how the school's teacher itself trades damage with each bot in the {@link dev.swordai.core.pvp.ToyFight}
 * (its techniques of the families reached against the ordinary fight alone). If the teacher loses, copying it cannot win.
 */
class TeacherDuelReport {
	private static final int FIGHTS = 60;

	/** A few minutes of fights: only when asked for, with SWORDAI_REPORT set. */
	@BeforeEach
	void onlyWhenAsked() {
		Assumptions.assumeTrue(System.getenv("SWORDAI_REPORT") != null);
	}
	private static final int TICKS = 600;

	private static Technique[] upTo(int family) {
		List<Technique> t = new ArrayList<>();
		for (int f = 0; f <= family; f++) {
			Collections.addAll(t, SchoolStages.STAGES[f].techniques);
		}
		return t.toArray(new Technique[0]);
	}

	private static float[] trade(Technique[] taught, int bot) {
		Random r = new Random(11);
		float dealt = 0f;
		float taken = 0f;
		for (int n = 0; n < FIGHTS; n++) {
			Style me = Teacher.style(taught);
			Style them = bot == Specialists.MIXER ? Specialists.mixer(r) : Specialists.draw(bot, r);
			Bout b = new Bout(me, them, 3.0 + 5.0 * r.nextDouble(), 1000L + n);
			b.run(TICKS);
			dealt += b.fight.dealt[0];
			taken += b.fight.dealt[1];
		}
		float minutes = FIGHTS * TICKS / 1200f;
		return new float[] {dealt / minutes, taken / minutes};
	}

	@Test
	void report() {
		StringBuilder out = new StringBuilder("\nteacher vs bots, damage a minute dealt/taken (ToyFight)\n");
		out.append(String.format(Locale.ROOT, "%-20s %14s %14s %14s%n", "bot", "ordinary only", "T-A", "T-A+T-B"));
		for (int bot = 0; bot <= Specialists.COUNT; bot++) {
			float[] plain = trade(new Technique[0], bot);
			float[] a = trade(upTo(0), bot);
			float[] ab = trade(upTo(1), bot);
			String name = bot == Specialists.MIXER ? "mixer" : Specialists.NAMES[bot];
			out.append(String.format(Locale.ROOT, "%-20s %6.0f/%-7.0f %6.0f/%-7.0f %6.0f/%-7.0f%n", name, plain[0], plain[1], a[0], a[1],
					ab[0], ab[1]));
		}
		System.out.println(out);
	}

	/** Each technique alone on top of the ordinary fight: its damage a minute, net, summed over every bot. */
	@Test
	void eachTechnique() {
		StringBuilder out = new StringBuilder("\neach technique alone on the ordinary fight: net damage a minute, mean over the bots (and vs the mixer)\n");
		float base = 0f;
		for (int bot = 0; bot <= Specialists.COUNT; bot++) {
			float[] d = trade(new Technique[0], bot);
			base += (d[0] - d[1]) / (Specialists.COUNT + 1);
		}
		out.append(String.format(Locale.ROOT, "ordinary only %+.0f%n", base));
		for (Technique t : SchoolStages.STAGES[SchoolStages.STAGES.length - 1].techniques) {
			float net = 0f;
			float mixer = 0f;
			for (int bot = 0; bot <= Specialists.COUNT; bot++) {
				float[] d = trade(new Technique[] {t}, bot);
				net += (d[0] - d[1]) / (Specialists.COUNT + 1);
				if (bot == Specialists.MIXER) {
					mixer = d[0] - d[1];
				}
			}
			out.append(String.format(Locale.ROOT, "%-4s %+5.0f (%+.0f vs ordinary; mixer %+.0f)%n", t.id, net, net - base, mixer));
		}
		System.out.println(out);
	}
}
