package dev.swordai.core.pvp;

import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.swordai.core.school.Teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 107 (agent 47): the school teacher's crit reads (Teacher#CRIT_READS). C1 ("when you expect a double crit, don't go for the punish
 * crit. Sprint-hit them") opens on their crit against one expected to double crit, and in the game the client's look caught 19% of the
 * bots' crits: their crit by the game (Events#theyCritGame) counts too. T1 (after your first crit, jump and crit again; "only works if
 * the opponent tries to punish-crit you") reads the punish-crit chances only where they came down in reach (Style#punishChanceInReach);
 * the bait (T1a) keeps its read. Every bot reads as before.
 */
class CritReadsTest {
	/** The other side: a crit from a jump in place, and on landing at once another (a double crit), as in PvpPlayerTest's C1 test. */
	static final PvpPlayerTest.Script DOUBLE_CRITTER = (tick, v, k) -> {
		k.forward = v.flat > 1.2 ? 1 : 0;
		k.jump = v.me.onGround;
		k.attack = v.me.falling() && v.inReach() && v.me.charge >= 0.92f;
	};

	/**
	 * Side 0 (style {@code s}) against the double critter, every one of their crits shown to side 0 as a plain hit (the client's look),
	 * with the game's crit flag on it or not. Returns side 0's ticks in C1.
	 */
	static int c1Ticks(Style s, boolean gameFlag) {
		Bout b = new Bout(s, PvpPlayerTest.plain(), 6.0, 5L);
		Keys swingNow = new Keys();
		swingNow.attack = true;
		b.fight.step(swingNow, new Keys());
		b.fight.body[1].x = b.fight.body[0].x + 1.6;
		Events seen = new Events();
		int c1 = 0;
		for (int n = 0; n < 160; n++) {
			seen.copyFrom(b.fight.events[0]);
			if (seen.theyHit == Events.CRIT) {
				seen.theyHit = Events.PLAIN;
				seen.theyCritGame = gameFlag;
			}
			b.player[0].tick(b.fight.view[0], seen, b.keys[0]);
			b.keys[1].clear();
			DOUBLE_CRITTER.keys(b.fight.tick, b.fight.view[1], b.keys[1]);
			b.fight.step(b.keys[0], b.keys[1]);
			c1 += b.keys[0].technique == Technique.C1 ? 1 : 0;
		}
		return c1;
	}

	@Test
	void aDoubleCritThatLooksLikePlainHitsOpensC1OnlyWithTheGamesCrit() {
		// No belief: the double crit is judged from what is seen this fight (their jump again after their crit), as the teacher's is. It
		// never swings (a swing needs more than a full sword), so their double crit runs on: crit, jump again, crit.
		Style s = PvpPlayerTest.with(Technique.C1, 1f);
		s.swingCharge = 1.5f;
		assertEquals(0, c1Ticks(s, false), "crits never seen as crits: no C1");
		assertTrue(c1Ticks(s, true) > 0, "the game's crits read: C1 against the double critter");
	}

	@Test
	void theGamesCritFlagIsClearedCopiedAndNotMirrored() {
		Events e = new Events();
		e.theyHit = Events.PLAIN;
		e.theyCritGame = true;
		Events c = new Events();
		c.copyFrom(e);
		assertTrue(c.theyCritGame);
		Events m = new Events();
		m.mirrorOf(e);
		assertFalse(m.theyCritGame);
		e.clear();
		assertFalse(e.theyCritGame);
	}

	@Test
	void onlyTheTeacherReadsChancesInReach() {
		Random r = new Random(3);
		for (int bot = 0; bot < Specialists.KINDS; bot++) {
			assertFalse(Specialists.draw(bot, r).punishChanceInReach, Specialists.NAMES[bot]);
			assertFalse(Specialists.varied(Math.min(bot, Specialists.COUNT - 1), r).punishChanceInReach, Specialists.NAMES[bot]);
		}
		assertFalse(Specialists.mixer(r).punishChanceInReach || Specialists.critSpammer(r).punishChanceInReach
				|| Specialists.comboable(r).punishChanceInReach || Specialists.comboBreaker(r).punishChanceInReach);
		Style t = Teacher.style(new Technique[] {Technique.T1, Technique.C1});
		assertEquals(Teacher.CRIT_READS, t.punishChanceInReach);
		assertEquals(Teacher.CRIT_READS, t.copy().punishChanceInReach);
	}

	@Test
	void theTeacherTickLeavesTheCallersEventsAsTheyAre() {
		Teacher teacher = new Teacher();
		teacher.start(new Technique[] {Technique.B1, Technique.C1}, 0.9f, 1L);
		Bout b = new Bout(PvpPlayerTest.plain(), PvpPlayerTest.plain(), 4.0, 2L);
		Events e = new Events();
		e.theyHit = Events.PLAIN;
		teacher.tick(b.fight.view[0], e, true, true);
		assertFalse(e.theyCritGame, "the caller's events (the network's own inputs) unchanged");
		assertEquals(Events.PLAIN, e.theyHit);
	}

	/** Starts of {@code t} by side 0 (the teacher) over {@code fights} toy fights against bot {@code bot}, chances in reach on or off. */
	static int starts(int bot, Technique t, boolean inReach, int fights) {
		Technique[] family = {Technique.B1, Technique.T1, Technique.T1A, Technique.T1C, Technique.T2, Technique.T4, Technique.T6, Technique.T7,
				Technique.C1, Technique.C2, Technique.C6, Technique.K2, Technique.K3};
		int n = 0;
		for (int f = 0; f < fights; f++) {
			Random r = new Random(500L + f);
			Style s = Specialists.draw(bot, r);
			Style teacher = Teacher.style(family);
			teacher.punishChanceInReach = inReach;
			Bout b = new Bout(teacher, s, 5.0, 91L + f);
			Technique last = Technique.BASE;
			for (int k = 0; k < 1200; k++) {
				Technique now = b.step().keys[0].technique;
				n += now == t && last != t ? 1 : 0;
				last = now;
			}
		}
		return n;
	}

	@Test
	void againstThePunishCritterTheDoubleCritOpensMoreAndTheBaitAsBefore() {
		int t1Off = starts(Specialists.PUNISH_CRITTER, Technique.T1, false, 24);
		int t1On = starts(Specialists.PUNISH_CRITTER, Technique.T1, true, 24);
		int t1aOff = starts(Specialists.PUNISH_CRITTER, Technique.T1A, false, 24);
		int t1aOn = starts(Specialists.PUNISH_CRITTER, Technique.T1A, true, 24);
		String all = "T1 " + t1Off + " -> " + t1On + ", T1a " + t1aOff + " -> " + t1aOn;
		System.out.println("run 107, the teacher against the punish critter (24 toy fights): " + all);
		assertTrue(t1On > t1Off, all);
		// T1a keeps its read: only the fights' own course (a double crit more here and there) moves its count.
		assertTrue(Math.abs(t1aOn - t1aOff) <= Math.max(6, 0.2 * t1aOff), all);
	}
}
