package dev.swordai.core.pvp;

import dev.swordai.core.school.SchoolStages;
import dev.swordai.core.school.Teacher;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 122 (agent 56). The school's teacher: no crit chain right after its own hit (T1, T1b, T1c, T2, C6; SchoolStages#CRIT_CHAINS_TAUGHT,
 * Style#noCritChains), however it adapts; and the punish crit (B1) on every hit of theirs that throws it up with a punish crit possible
 * (Teacher#PUNISH_ALWAYS, Style#punishAlways), where before the jump reset (T9), the reverse deflection (T5) and C1 took most of them.
 * Every bot keeps its own.
 */
class PunishAlwaysTest {
	private static final Technique[] CHAINS = {Technique.T1, Technique.T1B, Technique.T1C, Technique.T2, Technique.C6};

	/** Every technique the teacher knows in the last stage (SchoolStages: all of them). */
	private static Technique[] all() {
		Technique[] t = new Technique[Technique.COUNT - 1];
		System.arraycopy(Technique.ALL, 1, t, 0, t.length);
		return t;
	}

	@Test
	void theTeacherStartsNoCritChainAgainstAnyBot() {
		if (SchoolStages.CRIT_CHAINS_TAUGHT) {
			return;
		}
		Style teacher = Teacher.style(all());
		for (Technique c : CHAINS) {
			assertEquals(0f, teacher.p(c), c.id);
		}
		assertTrue(teacher.noCritChains);
		assertTrue(teacher.p(Technique.T1A) > 0f, "the first jump-in crit (T1a) kept");
		assertTrue(teacher.p(Technique.B1) > 0f, "the punish crit kept");
		assertTrue(teacher.copy().noCritChains && teacher.copy().punishAlways == teacher.punishAlways, "copied");
		// The punish critter (T1's moment: "only works if the opponent tries to punish-crit you"), the double critter, the standing critter
		// and the jump resetter (C6's moment): the teacher adapts (its boosts raise a technique at 0), and never starts one.
		int[] bots = {Specialists.PUNISH_CRITTER, Specialists.DOUBLE_CRITTER, Specialists.STANDING_CRITTER, Specialists.JUMP_RESETTER,
				Specialists.CRIT_BREAKER, Specialists.MIXER};
		int ticks = 0;
		for (int bot : bots) {
			for (int f = 0; f < 6; f++) {
				Random r = new Random(700L + 31L * bot + f);
				Bout b = new Bout(Teacher.style(all()), Specialists.draw(bot, r), 4.0 + f % 3, 300L + f);
				b.run(1200);
				for (Technique c : CHAINS) {
					assertEquals(0, b.ticksOf(0, c), c.id + " against " + Specialists.NAMES[bot] + "\n" + b.summary());
				}
				ticks += b.ticks.size();
			}
		}
		assertTrue(ticks > 0);
	}

	/** Starts of B1 by side 0 over the toy fights, and its lifts with a punish crit possible (PvpPlayer#READ_LIFTS_PUNISHABLE). */
	private static int[] b1Starts(boolean always) {
		int[] out = new int[3];
		int[] bots = {Specialists.JUMP_RESETTER, Specialists.STUN_ESCAPER, Specialists.STRAIGHT_SPRINTER, Specialists.RHYTHM_COMBOER,
				Specialists.DEFLECTOR, Specialists.REVERSE_DEFLECTOR};
		for (int bot : bots) {
			for (long seed = 0; seed < 8; seed++) {
				Style s = Teacher.style(all());
				s.punishAlways = always;
				Style other = Specialists.draw(bot, new Random(seed));
				Bout b = new Bout(s, other, 3.0, 40L + seed);
				Technique last = Technique.BASE;
				for (int k = 0; k < 600; k++) {
					Technique now = b.step().keys[0].technique;
					out[0] += now == Technique.B1 && last != Technique.B1 ? 1 : 0;
					last = now;
				}
				long[] reads = new long[PvpPlayer.READS];
				b.player[0].drainReads(reads);
				out[1] += (int) reads[PvpPlayer.READ_LIFTS_PUNISHABLE];
				out[2] += (int) reads[PvpPlayer.READ_B1_ALWAYS];
			}
		}
		return out;
	}

	@Test
	void everyPunishableLiftIsAnsweredByThePunishCrit() {
		int[] off = b1Starts(false);
		int[] on = b1Starts(true);
		String all = "B1 starts " + off[0] + " -> " + on[0] + "; punishable lifts " + on[1] + ", B1 started on them " + on[2];
		System.out.println("run 122, the teacher's punish crit on every punishable lift (toy fights): " + all);
		assertEquals(0, off[1], "counted only with the style on: " + all);
		assertTrue(on[1] > 0, "punishable lifts came: " + all);
		assertEquals(on[1], on[2], "B1 on every one of them (the teacher's B1 is certain): " + all);
		assertTrue(on[0] > off[0], all);
	}

	@Test
	void punishableAlwaysIsTheSwordHalfBackAndThemInTheGamesReach() {
		Duel d = new Duel();
		d.me.charge = 0.6f;
		d.reach = 2.9;
		assertTrue(PvpPlayer.punishableAlways(d), "at the edge of reach, where 2.6 left it out");
		d.reach = 3.1;
		assertTrue(!PvpPlayer.punishableAlways(d), "out of reach");
		d.reach = 2.0;
		d.me.charge = 0.4f;
		assertTrue(!PvpPlayer.punishableAlways(d), "the sword not back by the way down");
	}

	@Test
	void noBotPunishesAlwaysOrDropsItsCritChains() {
		Random r = new Random(9);
		for (int bot = 0; bot < Specialists.KINDS; bot++) {
			Style s = Specialists.draw(bot, r);
			assertTrue(!s.punishAlways && !s.noCritChains, Specialists.NAMES[bot]);
		}
		assertTrue(!Specialists.critSpammer(r).punishAlways && !Specialists.critSpammer(r).noCritChains);
	}
}
