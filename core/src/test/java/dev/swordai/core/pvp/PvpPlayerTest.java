package dev.swordai.core.pvp;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tick tests of the techniques, each against the steps of {@code docs/PVP-TECHNIQUES.md} (the IDs are
 * that file's), in the {@link ToyFight}'s physics.
 */
class PvpPlayerTest {
	/** A style that does only the ordinary fight, quick and exact, seeing everything at once. */
	static Style plain() {
		Style s = new Style();
		s.reaction = 2;
		s.timing = 1f;
		s.lag = 0;
		s.ping = 0;
		s.wtap = 1;
		s.stap = 0;
		s.strafe = 0f;
		s.swingCharge = 0.92f;
		s.spacing = 3.0f;
		return s;
	}

	static Style with(Technique t, float p) {
		return plain().set(t, p);
	}

	/** Keys for a fighter that only stands and does nothing. */
	static Keys idle() {
		return new Keys();
	}

	/** Runs side 0 as a player and side 1 by a script; returns the bout. */
	interface Script {
		void keys(int tick, Duel view, Keys k);
	}

	static Bout scripted(Style a, double distance, int ticks, Script b) {
		return scriptedFrom(new Bout(a, plain(), distance, 7L), ticks, b);
	}

	static Bout scriptedFrom(Bout bout, int ticks, Script b) {
		for (int n = 0; n < ticks; n++) {
			bout.player[0].tick(bout.fight.view[0], bout.fight.events[0], bout.keys[0]);
			bout.keys[1].clear();
			b.keys(bout.fight.tick, bout.fight.view[1], bout.keys[1]);
			bout.fight.step(bout.keys[0], bout.keys[1]);
			Bout.Tick t = new Bout.Tick(bout.fight.tick);
			for (int i = 0; i < 2; i++) {
				t.keys[i].copyFrom(bout.keys[i]);
				t.body[i].copyFrom(bout.fight.body[i]);
				t.events[i].copyFrom(bout.fight.events[i]);
			}
			t.reach = bout.fight.view[0].reach;
			bout.ticks.add(t);
		}
		return bout;
	}

	/** A sprinter that runs at the other and sprint-hits whenever its sword is back and it is in reach. */
	static final Script SPRINTER = (tick, v, k) -> {
		k.forward = 1;
		k.sprint = true;
		k.attack = v.inReach() && v.me.charge >= 0.92f;
	};

	// ---- T9: jump reset ------------------------------------------------------------------------------

	/** How far side 0 is pushed in the six ticks after the first hit it takes from a sprinter. */
	static double pushedAfterHit(Style s) {
		Bout b = scripted(s, 5.0, 60, SPRINTER);
		for (Bout.Tick t : b.ticks) {
			if (t.events[0].theyHit != Events.NONE) {
				int at = b.ticks.indexOf(t);
				return Math.abs(b.ticks.get(at + 6).body[0].x - t.body[0].x);
			}
		}
		throw new AssertionError("never hit\n" + b.trace());
	}

	@Test
	void aJumpResetPressedOnTheHitTakesLessKnockback() {
		double without = pushedAfterHit(plain());
		double with = pushedAfterHit(with(Technique.T9, 1f));
		assertTrue(with < 0.8 * without, "pushed " + with + " with the jump reset, " + without + " without");
	}

	@Test
	void theJumpResetIsPressedOnTheFirstTickAfterTheHit() {
		Bout b = scripted(with(Technique.T9, 1f), 5.0, 60, SPRINTER);
		for (int i = 0; i < b.ticks.size() - 1; i++) {
			if (b.ticks.get(i).events[0].theyHit != Events.NONE) {
				Keys next = b.ticks.get(i + 1).keys[0];
				assertTrue(next.jump && next.forward > 0 && next.sprint, b.trace());
				assertEquals(Technique.T9, next.technique);
				return;
			}
		}
		throw new AssertionError("never hit\n" + b.trace());
	}

	// ---- B1: punish crit -------------------------------------------------------------------------------

	@Test
	void aPunishCritIsMadeOnTheWayDownAfterBeingHitStanding() {
		Bout b = new Bout(with(Technique.B1, 1f), plain(), 4.0, 7L);
		// Its sword is down (it swung at nothing, out of reach): the other's plain hit comes first and lifts it.
		Keys swingNow = new Keys();
		swingNow.attack = true;
		b.fight.step(swingNow, new Keys());
		int[] swung = {0};
		Bout run = scriptedFrom(b, 40, (tick, v, k) -> {
			// It hits once (no sprint: little knockback) and keeps walking in.
			k.forward = v.flat > 0.8 ? 1 : 0;
			if (swung[0] == 0 && v.inReach() && v.me.charge >= 0.92f) {
				k.attack = true;
				swung[0] = tick;
			}
		});
		boolean crit = false;
		boolean swungRising = false;
		for (int i = 1; i < run.ticks.size(); i++) {
			Bout.Tick t = run.ticks.get(i);
			Bout.Tick before = run.ticks.get(i - 1);
			crit |= t.events[0].iHit == Events.CRIT;
			swungRising |= t.keys[0].attack && t.keys[0].technique == Technique.B1 && before.body[0].vy > 0;
		}
		assertTrue(crit, run.trace());
		assertTrue(!swungRising, "swung on the way up\n" + run.trace());
	}

	// ---- T1: double crit -------------------------------------------------------------------------------

	@Test
	void aDoubleCritJumpsAgainAtOnceAndCritsAgain() {
		Style s = with(Technique.T1A, 1f).set(Technique.T1, 1f);
		// The other is a punish critter that stands its ground: lifted by the first crit, it comes down onto the second.
		Bout b = scripted(s, 5.0, 80, (tick, v, k) -> k.attack = v.me.falling() && v.inReach() && v.me.charge >= 0.92f);
		int first = -1;
		int second = -1;
		boolean jumpedOnLanding = false;
		for (Bout.Tick t : b.ticks) {
			if (t.events[0].iHit == Events.CRIT) {
				if (first < 0) {
					first = t.t;
				} else if (second < 0) {
					second = t.t;
				}
			}
			jumpedOnLanding |= first > 0 && second < 0 && t.keys[0].technique == Technique.T1 && t.keys[0].jump;
		}
		assertTrue(first > 0, b.trace());
		assertTrue(jumpedOnLanding, "no jump again after the first crit\n" + b.trace());
		assertTrue(b.ticksOf(0, Technique.T1) > 0 && b.ticksOf(0, Technique.T1A) > 0, b.trace());
	}

	// ---- T2: standing crit -----------------------------------------------------------------------------

	@Test
	void aStandingCritJumpsStraightUpRightAfterTheSprintHit() {
		Bout b = scripted(with(Technique.T2, 1f), 5.0, 60, (tick, v, k) -> {
			// The other keeps coming in, as one does that took little knockback.
			k.forward = v.flat > 1.0 ? 1 : 0;
		});
		for (int i = 0; i < b.ticks.size() - 1; i++) {
			if (b.ticks.get(i).events[0].iHit == Events.SPRINT) {
				Keys next = b.ticks.get(i + 1).keys[0];
				assertTrue(next.jump && next.forward == 0 && !next.sprint, b.trace());
				assertEquals(Technique.T2, next.technique);
				return;
			}
		}
		throw new AssertionError("no sprint hit\n" + b.trace());
	}

	// ---- T4: deflection --------------------------------------------------------------------------------

	@Test
	void aDeflectionIsASprintHitRightAfterTheirSwing() {
		Style s = with(Technique.T4, 1f);
		s.reaction = 2;
		int[] swungAt = {-1};
		Bout b = scripted(s, 3.8, 40, (tick, v, k) -> {
			// It walks in and swings once, early, from just outside reach: a miss.
			k.forward = 1;
			if (swungAt[0] < 0 && v.theirReach <= 3.4) {
				k.attack = true;
				swungAt[0] = tick;
			}
		});
		int hitAt = -1;
		for (Bout.Tick t : b.ticks) {
			if (t.events[0].iHit == Events.SPRINT && hitAt < 0) {
				hitAt = t.t;
			}
		}
		assertTrue(hitAt > swungAt[0] && hitAt - swungAt[0] <= 8, "swing at " + swungAt[0] + ", deflection at " + hitAt + "\n" + b.trace());
		assertTrue(b.ticksOf(0, Technique.T4) > 0, b.trace());
	}

	// ---- T7: three-block hit -------------------------------------------------------------------------

	@Test
	void aThreeBlockHitIsMadeAtFullSprintFromTheEdgeOfReach() {
		Bout b = scripted(with(Technique.T7, 1f), 7.0, 60, (tick, v, k) -> {
		});
		for (Bout.Tick t : b.ticks) {
			if (t.events[0].iHit != Events.NONE) {
				assertEquals(Events.SPRINT, t.events[0].iHit, b.trace());
				assertTrue(b.ticksOf(0, Technique.T7) > 0, b.trace());
				// From near the edge: the reach before the hit's tick moved anyone.
				Bout.Tick before = b.ticks.get(b.ticks.indexOf(t) - 1);
				assertTrue(before.reach > 2.5, "hit from " + before.reach + "\n" + b.trace());
				return;
			}
		}
		throw new AssertionError("no hit\n" + b.trace());
	}

	// ---- T8: the rising jumper ----------------------------------------------------------------------

	@Test
	void aJumperIsHitWhileItRises() {
		Bout b = scripted(with(Technique.T8, 1f), 4.2, 30, (tick, v, k) -> {
			k.forward = 1;
			k.sprint = true;
			k.jump = tick == 1;
		});
		for (Bout.Tick t : b.ticks) {
			if (t.events[0].iHit != Events.NONE) {
				Bout.Tick before = b.ticks.get(b.ticks.indexOf(t) - 1);
				assertTrue(before.body[1].vy > 0 || !before.body[1].onGround && t.body[1].y > 0, b.trace());
				assertTrue(b.ticksOf(0, Technique.T8) > 0, b.trace());
				return;
			}
		}
		throw new AssertionError("no hit\n" + b.trace());
	}

	// ---- T11b: hops ------------------------------------------------------------------------------

	@Test
	void hopsLetGoOfWBeforeEachJumpAndSprintHitFalling() {
		Style s = with(Technique.T11B, 1f);
		Bout b = scripted(s, 5.0, 100, (tick, v, k) -> {
			// The other walks back in after each hit, as one does that stays in.
			k.forward = v.flat > 2.0 ? 1 : 0;
		});
		int hopJumps = 0;
		int fallingSprintHits = 0;
		for (int i = 1; i < b.ticks.size(); i++) {
			Bout.Tick t = b.ticks.get(i);
			if (t.keys[0].technique == Technique.T11B && t.keys[0].jump) {
				assertEquals(0, t.keys[0].forward, "W must be let go of on the hop's jump\n" + b.trace());
				hopJumps++;
			}
			Bout.Tick before = b.ticks.get(i - 1);
			if (t.events[0].iHit == Events.SPRINT && before.keys[0].technique == Technique.T11B && before.body[0].vy < 0) {
				fallingSprintHits++;
			}
		}
		assertTrue(hopJumps >= 2, b.trace());
		assertTrue(fallingSprintHits >= 1, b.trace());
	}

	// ---- T11a: lunge -----------------------------------------------------------------------------------

	@Test
	void aLungeChasesOneThatHoldsSAndHitsFalling() {
		Bout b = scripted(with(Technique.T11A, 1f), 3.5, 60, (tick, v, k) -> {
			k.forward = -1;
		});
		assertTrue(b.ticksOf(0, Technique.T11A) > 0, b.trace());
		for (Bout.Tick t : b.ticks) {
			if (t.events[0].iHit != Events.NONE) {
				Bout.Tick before = b.ticks.get(b.ticks.indexOf(t) - 1);
				assertTrue(!before.body[0].onGround, b.trace());
				return;
			}
		}
		throw new AssertionError("the lunge never landed\n" + b.trace());
	}

	// ---- C3: jump and hold S before the stun --------------------------------------------------------

	@Test
	void outBeforeTheStunWithAJumpAndS() {
		Style s = with(Technique.C3, 1f);
		Bout b = new Bout(s, plain(), 6.0, 3L);
		// Its own sword is down: it swung at nothing just before.
		Keys swingNow = new Keys();
		swingNow.attack = true;
		b.fight.step(swingNow, new Keys());
		b.run(40);
		assertTrue(b.ticksOf(0, Technique.C3) > 0, b.trace());
		boolean jumpedBack = false;
		for (Bout.Tick t : b.ticks) {
			jumpedBack |= t.keys[0].technique == Technique.C3 && t.keys[0].jump && t.keys[0].forward < 0;
		}
		assertTrue(jumpedBack, b.trace());
	}

	// ---- K2: the golden rule -----------------------------------------------------------------------------

	@Test
	void noKnockbackHitOnAFallingPlayer() {
		Style s = with(Technique.K2, 1f);
		// The other hops in place right in front of it, all the time.
		Bout b = scripted(s, 2.4, 80, (tick, v, k) -> k.jump = true);
		for (int i = 1; i < b.ticks.size(); i++) {
			Bout.Tick before = b.ticks.get(i - 1);
			if (b.ticks.get(i).keys[0].attack) {
				assertTrue(!(before.body[1].vy < 0 && !before.body[1].onGround), "swung at a falling player\n" + b.trace());
			}
		}
		assertTrue(b.ticksOf(0, Technique.K2) > 0, b.trace());
	}

	// ---- habits --------------------------------------------------------------------------------------

	@Test
	void jumpResetsAreSeenForWhatTheyAre() {
		float resetter = resetRate(with(Technique.T9, 1f));
		float none = resetRate(plain());
		assertTrue(resetter > 0.6f && none < 0.3f, "seen as jump resets: " + resetter + " of a resetter's, " + none + " of a plain fighter's");
	}

	/** Side 1 sprint-hits side 0 over and over; what side 1 makes of side 0's jump resets. */
	static float resetRate(Style s) {
		Habits h = new Habits();
		Bout b = scripted(s, 5.0, 600, SPRINTER);
		// Watch the recorded fight from side 1.
		Duel d = new Duel();
		Events e = new Events();
		for (Bout.Tick t : b.ticks) {
			d.me.copyFrom(t.body[1]);
			d.them.copyFrom(t.body[0]);
			d.measure();
			e.copyFrom(t.events[1]);
			h.observe(d, e);
		}
		return h.chances(Habits.JUMP_RESET) < 2f ? -1f : h.rate(Habits.JUMP_RESET);
	}

	@Test
	void theRealPlayerTurnsToTheCountersOfJumpResets() {
		Style mixer = plain();
		mixer.adapts = true;
		mixer.set(Technique.C7, 0.1f);
		Bout b = new Bout(mixer, with(Technique.T9, 1f), 5.0, 11L);
		b.run(1200);
		assertTrue(b.player[0].chance(Technique.C7) > 0.2f, "C7 at " + b.player[0].chance(Technique.C7) + "; " + b.player[0].habits().describe());
	}

	@Test
	void theSameSeedFightsTheSameFight() {
		Random r = new Random(5);
		Style a = Specialists.draw(Specialists.MIXER, r);
		Style c = Specialists.draw(Specialists.HOPPER, r);
		Bout one = new Bout(a, c, 4.0, 99L);
		Bout two = new Bout(a.copy(), c.copy(), 4.0, 99L);
		one.run(400);
		two.run(400);
		assertEquals(one.trace(), two.trace());
	}

	@Test
	void everySpecialistUsesItsTechnique() {
		Random r = new Random(1);
		// C1 and T5 need a crit landed on them and a deflection that did not stun: they have tests of their own below.
		Technique[] expected = {Technique.B1, Technique.T1, null, Technique.T2, Technique.T4, null, Technique.T6, null,
				Technique.T7, null, null, Technique.T9, null, Technique.C3, Technique.C4, Technique.T10, Technique.T11A, Technique.T11B,
				Technique.K3, null};
		// The opponent that gives each its moment: a crit-breaker needs crits to break, a reverse deflector a deflection.
		int[] against = {-1, -1, Specialists.DOUBLE_CRITTER, -1, -1, Specialists.DEFLECTOR, -1, -1, -1, -1, -1,
				-1, -1, -1, -1, -1, -1, -1, -1, -1};
		StringBuilder missing = new StringBuilder();
		for (int bot = 0; bot < Specialists.COUNT; bot++) {
			if (expected[bot] == null) {
				continue;
			}
			int used = 0;
			int ticks = 0;
			for (int seed = 0; seed < 4; seed++) {
				Style s = Specialists.draw(bot, r);
				Style other = against[bot] < 0 ? Specialists.mixer(r) : Specialists.draw(against[bot], r);
				Bout b = new Bout(other, s, 6.0, seed);
				b.run(1200);
				used += b.ticksOf(1, expected[bot]);
				ticks += 1200;
			}
			if (used == 0) {
				missing.append(Specialists.NAMES[bot]).append(" never used ").append(expected[bot].id).append("; ");
			}
		}
		assertEquals("", missing.toString());
	}

	// ---- C1: the sprint hit against a double crit -------------------------------------------------------

	@Test
	void againstADoubleCritterTheCritIsAnsweredWithASprintHitNotAPunishCrit() {
		Style s = with(Technique.C1, 1f).set(Technique.B1, 1f);
		s.believe[Habits.DOUBLE_CRIT] = 1f;
		Bout b = new Bout(s, plain(), 6.0, 5L);
		// Its sword is down (a swing at nothing), and the other starts right on top of it: the first crit comes first.
		Keys swingNow = new Keys();
		swingNow.attack = true;
		b.fight.step(swingNow, new Keys());
		b.fight.body[1].x = b.fight.body[0].x + 1.6;
		// The other: a crit from a jump in place, and on landing at once another (a double crit).
		Bout run = scriptedFrom(b, 60, (tick, v, k) -> {
			k.forward = v.flat > 1.2 ? 1 : 0;
			k.jump = v.me.onGround;
			k.attack = v.me.falling() && v.inReach() && v.me.charge >= 0.92f;
		});
		int critAt = -1;
		for (Bout.Tick t : run.ticks) {
			if (t.events[0].theyHit == Events.CRIT && critAt < 0) {
				critAt = t.t;
			}
			if (critAt >= 0 && t.events[0].iHit != Events.NONE) {
				assertEquals(Events.SPRINT, t.events[0].iHit, run.trace());
				assertTrue(run.ticksOf(0, Technique.C1) > 0 && run.ticksOf(0, Technique.B1) == 0, run.trace());
				return;
			}
		}
		throw new AssertionError("no answer to the crit (crit at " + critAt + ")\n" + run.trace());
	}

	// ---- T5: the reverse deflection ---------------------------------------------------------------------

	@Test
	void aDeflectionThatDidNotStunIsDeflectedBack() {
		Bout b = new Bout(with(Technique.T5, 1f), plain(), 4.6, 9L);
		// Its knockback comes late (a slow connection): the deflection does not send it out of reach in time.
		b.fight.knockLag[0] = 4;
		// Its swing, at nothing (out of reach): what the other deflects.
		Keys swingNow = new Keys();
		swingNow.attack = true;
		b.fight.step(swingNow, new Keys());
		Bout run = scriptedFrom(b, 40, (tick, v, k) -> {
			// It runs in and sprint-hits from close, a few ticks after that swing: a deflection that does not stun.
			k.forward = v.reach > 1.2 ? 1 : 0;
			k.sprint = true;
			k.attack = tick >= 6 && v.inReach() && v.me.charge >= 0.92f && v.me.sinceSwing > 100;
		});
		assertTrue(run.ticksOf(0, Technique.T5) > 0, run.trace());
		boolean back = false;
		boolean deflected = false;
		for (Bout.Tick t : run.ticks) {
			deflected |= t.events[0].theyHit == Events.SPRINT;
			back |= deflected && t.events[0].iHit == Events.SPRINT;
		}
		assertTrue(back, run.trace());
	}
}
