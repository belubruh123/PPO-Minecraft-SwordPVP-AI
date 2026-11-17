package dev.swordai.core.school;

import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Human;
import dev.swordai.core.pvp.Keys;
import dev.swordai.core.pvp.PvpPlayer;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.pvp.ToyFight;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.BackNudge;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run 125: the combo-hold drill (ComboDrill): its start state, the comboed bot's calibration against the real rounds, the reward. */
class ComboDrillTest {
	private static final int B = ComboDrill.BODY;

	// ---- the start state ----------------------------------------------------------------------------------------------------------

	@Test
	void startIsTheTickAfterItsSprintHit() {
		Random r = new Random(11);
		int resets = 0, botReady = 0, n = 2000;
		for (int i = 0; i < n; i++) {
			double[] s = ComboDrill.startState(r);
			assertEquals(2 * B, s.length);
			double reach = ComboDrill.stunReach(s);
			assertTrue(reach >= ComboDrill.STUN_LEAST - 1e-9 && reach <= ComboDrill.STUN_MOST + 1e-9, "the stun from the edge of reach: " + reach);
			// Both on the floor, facing each other along z: the agent at -z looking +z (yaw 0), the bot at +z looking -z (yaw 180).
			assertEquals(0.0, s[ComboDrill.Y]);
			assertEquals(0.0, s[B + ComboDrill.Y]);
			assertTrue(s[ComboDrill.Z] < 0 && s[B + ComboDrill.Z] > 0);
			assertEquals(0.0, s[ComboDrill.YAW]);
			assertEquals(180.0, s[B + ComboDrill.YAW]);
			// The AI: its sword just swung (charge low), not sprinting (the hit ended it), moving on toward them.
			int at = (int) s[ComboDrill.TICKER];
			assertTrue(at >= 1 && at <= 2, "agent sword ticks " + at);
			assertTrue((at + 0.5f) / Body.SWORD_TICKS < 0.25f, "the AI's sword is low at the start");
			assertEquals(0.0, s[ComboDrill.SPRINT]);
			assertTrue(s[ComboDrill.VZ] > 0.05 && s[ComboDrill.VZ] < 0.2);
			// The bot: knocked away (+z) and up, hurt and grace ticks running.
			assertTrue(s[B + ComboDrill.VZ] > 0.4, "knocked away: " + s[B + ComboDrill.VZ]);
			assertTrue(s[B + ComboDrill.VY] >= 0.4 - 1e-9);
			assertEquals(9.0, s[B + ComboDrill.HURT]);
			assertEquals(19.0, s[B + ComboDrill.INVUL]);
			assertEquals(20.0, s[B + ComboDrill.HEALTH]);
			resets += ComboDrill.stunReset(s) ? 1 : 0;
			botReady += s[B + ComboDrill.TICKER] >= 100 ? 1 : 0;
			if (ComboDrill.stunReset(s)) {
				assertTrue(s[B + ComboDrill.VZ] < 0.55, "a jump reset takes less knockback");
			} else {
				assertTrue(s[B + ComboDrill.VZ] > 0.66 && s[B + ComboDrill.VZ] <= 0.7 + 1e-9, "the game's sprint hit: " + s[B + ComboDrill.VZ]);
			}
		}
		double resetShare = resets / (double) n, readyShare = botReady / (double) n;
		System.out.printf(Locale.ROOT, "run 125 drill start: %d starts, jump-reset stuns %.0f%% (real 91/272 = 33%%), bot's sword back %.0f%%%n", n,
				100 * resetShare, 100 * readyShare);
		assertEquals(ComboDrill.START_RESET_SHARE, resetShare, 0.04);
		assertEquals(ComboDrill.BOT_READY_SHARE, readyShare, 0.04);
		// The same seed, the same start.
		assertEquals(ComboDrill.startState(new Random(5))[B + ComboDrill.VZ], ComboDrill.startState(new Random(5))[B + ComboDrill.VZ]);
	}

	@Test
	void victimIsAPersonBeingComboedWithTheDocumentsCounters() {
		Style s = ComboDrill.victim(new Random(3));
		assertTrue(s.comboDrill);
		assertTrue(s.p(Technique.T9) >= 0.35f, "jump resets its hits (T9/C5)");
		assertTrue(s.flyOut > 0f, "flies out (C3)");
		assertTrue(s.strafe > 0f);
		assertEquals(0f, s.critOpen);
		assertEquals(0f, s.hitSelect);
		for (Technique t : Technique.ALL) {
			if (t != Technique.T9) {
				assertEquals(0f, s.p(t), "no other technique: " + t.id);
			}
		}
		// Humanized at TechniqueBot#start (the drill flag kept by the copy).
		assertTrue(Human.applies(s));
		Style h = Human.humanize(s, 9L);
		assertTrue(h.human && h.comboDrill);
	}

	// ---- the reward and the chain -------------------------------------------------------------------------------------------------

	@Test
	void eachFurtherHitPaysMoreAndTheirHitEndsIt() {
		assertEquals(0f, ComboDrill.hitReward(1));
		assertTrue(ComboDrill.hitReward(3) > ComboDrill.hitReward(2), "the 3rd more than the 2nd");
		assertTrue(ComboDrill.hitReward(4) > ComboDrill.hitReward(3));
		assertEquals(ComboDrill.HIT_MOST, ComboDrill.hitReward(10));
		assertEquals(ComboDrill.HIT_MOST, ComboDrill.hitReward(50), "capped: numerically stable");
		ComboDrill.Chain c = new ComboDrill.Chain();
		float sum = 0f;
		for (int t = 0; t < 12; t++) {
			sum += c.tick(t == 11, false, 2.8);
		}
		assertEquals(2, c.length());
		assertEquals(ComboDrill.hitReward(2), sum, 1e-6);
		sum = 0f;
		for (int t = 0; t < 12; t++) {
			sum += c.tick(t == 11, false, 2.8);
		}
		assertEquals(3, c.length());
		assertEquals(ComboDrill.hitReward(3), sum, 1e-6);
		assertFalse(c.over());
		// Their hit (a trade too) breaks it: the cost, the end, no value after.
		assertEquals(-ComboDrill.BREAK_COST, c.tick(true, true, 1.5), 1e-6);
		assertTrue(c.over() && c.terminal());
		assertEquals(ComboDrill.BROKEN, c.end());
		assertEquals(3, c.length(), "the trade's hit is not counted");
		assertEquals(0f, c.tick(true, false, 2.0), "nothing after the end");
		// Every reward of a drill is well inside the reward scaler's clip (10).
		assertTrue(ComboDrill.HIT_MOST + ComboDrill.BREAK_COST + ComboDrill.DROP_COST < 3f);
	}

	@Test
	void chainIsDroppedOutOfRangeOrWithoutAHitAndTimeIsNotTerminal() {
		ComboDrill.Chain out = new ComboDrill.Chain();
		float r = 0f;
		for (int t = 0; t < ComboDrill.OUT_TICKS; t++) {
			r += out.tick(false, false, ComboDrill.RANGE + 0.5);
		}
		assertTrue(out.over() && out.terminal());
		assertEquals(ComboDrill.OUT, out.end());
		assertEquals(-ComboDrill.DROP_COST, r, 1e-6);
		// Back in range resets the count.
		ComboDrill.Chain back = new ComboDrill.Chain();
		for (int t = 0; t < 30; t++) {
			back.tick(t == 15, false, t % 10 == 9 ? 3.0 : ComboDrill.RANGE + 0.5);
		}
		assertFalse(back.over());
		ComboDrill.Chain stall = new ComboDrill.Chain();
		for (int t = 0; t < ComboDrill.GAP_TICKS; t++) {
			stall.tick(false, false, 3.0);
		}
		assertEquals(ComboDrill.STALL, stall.end());
		ComboDrill.Chain time = new ComboDrill.Chain();
		for (int t = 0; t < ComboDrill.MOST_TICKS; t++) {
			time.tick(t % 15 == 14, false, 3.0);
		}
		assertEquals(ComboDrill.TIME, time.end());
		assertFalse(time.terminal(), "time up is bootstrapped");
	}

	// ---- the light teacher --------------------------------------------------------------------------------------------------------

	private static int choice(float[] t, int head) {
		int off = ActionSpace.OFFSETS[head];
		int best = 0;
		for (int k = 1; k < ActionSpace.SIZES[head]; k++) {
			if (t[off + k] > t[off + best]) {
				best = k;
			}
		}
		return best;
	}

	@Test
	void teacherSpacesWithTheSwordComingBackAndNeverJumpsOrHoldsTheSwing() {
		float[] t = new float[ActionSpace.TOTAL_LOGITS];
		// The W-tap right after its hit (the tick it shows and the next): not W, even with them beyond reach.
		int heads = ComboDrill.target(t, 0, 0.1f, 3.5);
		assertTrue(t[ActionSpace.OFFSETS[ActionSpace.FORWARD] + 1] < 0.1f, "W let go after its hit");
		heads = ComboDrill.target(t, 1, 0.1f, 3.5);
		assertTrue(t[ActionSpace.OFFSETS[ActionSpace.FORWARD] + 1] < 0.1f, "W let go after its hit");
		assertEquals(0, heads & (1 << ActionSpace.ATTACK), "the swing is never the drill teacher's (never held)");
		assertEquals(0, heads & (1 << ActionSpace.STRAFE));
		assertEquals(0, choice(t, ActionSpace.JUMP));
		// The sword coming back, them nearer than 2.6: S, no sprint.
		ComboDrill.target(t, 5, 0.4f, 2.0);
		assertEquals(BackNudge.BACK, choice(t, ActionSpace.FORWARD));
		assertEquals(0, choice(t, ActionSpace.SPRINT));
		// They flew out: chase at a sprint.
		ComboDrill.target(t, 6, 0.5f, 4.2);
		assertEquals(1, choice(t, ActionSpace.FORWARD));
		assertEquals(1, choice(t, ActionSpace.SPRINT));
		// At the edge with the sword still coming back: not W (hold the gap).
		ComboDrill.target(t, 6, 0.5f, 2.8);
		assertTrue(t[ActionSpace.OFFSETS[ActionSpace.FORWARD] + 1] < 0.1f);
		// The sword (nearly) back: W and the sprint for the sprint hit, wherever they are.
		heads = ComboDrill.target(t, 11, 0.9f, 1.8);
		assertEquals(1, choice(t, ActionSpace.FORWARD));
		assertEquals(1, choice(t, ActionSpace.SPRINT));
		assertEquals(0, choice(t, ActionSpace.JUMP));
		assertEquals(0, heads & (1 << ActionSpace.ATTACK));
	}

	// ---- the comboed bot's calibration (the toy fight, the game's movement and hit rules) -----------------------------------------

	/**
	 * The real rounds' AI for the calibration: the installed model swung on 52-59% of its ready-in-reach ticks in the checks (run 123's
	 * counters), so this follower swings on {@link #REAL_SWING} of them.
	 */
	static final float REAL_SWING = 0.55f;

	/**
	 * A follow-up policy for the calibration: after its hit the W-tap, then with the sword coming back it keeps to its spacing (a habit
	 * drawn 1.5-3.0 per drill, a little off each hit, so that every band has hits), and with the sword (nearly) back W and the sprint, and
	 * the sprint hit with the sword back and them in reach on {@code swingP} of those ticks. No jumps.
	 */
	private static final class Follower {
		private final float swingP;
		private final Random r;
		/** This drill's spacing (its habit), and this hit's (the habit, a little off). */
		private final double habit;
		private double want;
		private int sinceHit;

		Follower(Random r, float swingP) {
			this.r = r;
			this.swingP = swingP;
			habit = 1.5 + 1.5 * r.nextDouble();
			hit();
		}

		void hit() {
			sinceHit = 0;
			want = habit + 0.4 * (r.nextDouble() - 0.5);
		}

		void keys(Duel d, Keys k) {
			k.clear();
			sinceHit++;
			float c = d.me.charge;
			if (sinceHit <= 1) {
				k.forward = 0;
			} else if (c < ComboDrill.RUN_UP_CHARGE) {
				k.forward = d.reach < want - 0.2 ? -1 : d.reach > want + 0.3 ? 1 : 0;
				k.sprint = k.forward > 0;
			} else {
				k.forward = 1;
				k.sprint = true;
			}
			k.attack = c >= Body.CHARGED && d.reach <= Duel.REACH && (d.me.sprinting || k.forward > 0 && k.sprint) && r.nextFloat() < swingP;
		}
	}

	/** What the toy drills measured. */
	private static final class Calibration {
		final ComboDrill.Tally tally = new ComboDrill.Tally();
		/** The bot's take-offs within 2 ticks of feeling a hit, and the hits it felt. */
		int resets, felt;
		/** The bot's hits back within 5 ticks of the AI's hit, and all its hits back. */
		int within5, backs;
	}

	/** One drill in the toy: the stun (a real sprint hit from {@code stun} blocks), then {@link Follower} against the drill's bot. */
	private static void toyDrill(long seed, Calibration cal, float swingP) {
		Random r = new Random(seed);
		Style victim = Human.humanize(ComboDrill.victim(r), seed);
		PvpPlayer bot = new PvpPlayer();
		bot.start(victim, seed);
		bot.seenLag(victim.lag);
		ToyFight f = new ToyFight();
		f.knockLag[1] = Math.max(1, victim.ping);
		f.seenLag[1] = victim.lag;
		f.seenLag[0] = 1 + r.nextInt(4);
		double stun = ComboDrill.STUN_LEAST + (ComboDrill.STUN_MOST - ComboDrill.STUN_LEAST) * r.nextDouble();
		f.place(stun + 0.3);
		if (r.nextFloat() >= ComboDrill.BOT_READY_SHARE) {
			f.setTicker(1, 4 + r.nextInt(6));
		}
		Follower ai = new Follower(r, swingP);
		Keys ka = new Keys();
		Keys kb = new Keys();
		// The stun: in at a sprint, the swing on the first tick.
		ka.clear();
		ka.forward = 1;
		ka.sprint = true;
		ka.attack = true;
		bot.tick(f.view[1], f.events[1], kb);
		kb.attack = false;
		f.step(ka, kb);
		if (f.events[0].iHit != Events.SPRINT) {
			return;
		}
		ComboDrill.Chain chain = new ComboDrill.Chain();
		int sinceAiHit = 0;
		int jumpWindow = 0;
		cal.felt++;
		cal.tally.start(false);
		ai.hit();
		for (int t = 0; t < ComboDrill.MOST_TICKS && !chain.over(); t++) {
			bot.tick(f.view[1], f.events[1], kb);
			if (f.events[1].theyHit != Events.NONE) {
				jumpWindow = 3;
			}
			if (jumpWindow > 0) {
				jumpWindow--;
				if (kb.jump && f.body[1].onGround) {
					cal.resets++;
					jumpWindow = 0;
				}
			}
			ai.keys(f.view[0], ka);
			double reachAtSwing = f.view[0].reach;
			f.step(ka, kb);
			boolean dealt = f.events[0].iHit != Events.NONE;
			boolean taken = f.events[0].theyHit != Events.NONE;
			sinceAiHit++;
			if (taken) {
				cal.backs++;
				cal.within5 += sinceAiHit <= 5 ? 1 : 0;
				cal.tally.hitBack();
			} else if (dealt) {
				cal.tally.hit(reachAtSwing);
				cal.felt++;
				sinceAiHit = 0;
				ai.hit();
			}
			chain.tick(dealt, taken, f.view[0].reach);
		}
		cal.tally.end(chain);
	}

	private static Calibration calibrate(int drills, long seed, float swingP) {
		Calibration cal = new Calibration();
		for (int i = 0; i < drills; i++) {
			toyDrill(seed + 7919L * i, cal, swingP);
		}
		return cal;
	}

	private static double rate(ComboDrill.Tally t, int band) {
		return t.followed(band) == 0 ? Double.NaN : t.hitBacks(band) / (double) t.followed(band);
	}

	@Test
	void comboedBotHitsBackCloseFollowUpsAndMostlyNotEdgeOnes() {
		Calibration cal = calibrate(3000, 125L, REAL_SWING);
		ComboDrill.Tally t = cal.tally;
		double near = rate(t, 0), mid = rate(t, 1), edge = rate(t, 2);
		System.out.printf(Locale.ROOT, "run 125 calibration (toy, %d drills, the stun, then a follower spacing 1.5-3.0 and swinging on 55%% of its ready ticks"
				+ " as the installed model does): the bot's hit back next after the"
				+ " AI's sprint hit <2.0 %.0f%% (%d/%d; real 72%% of 110), 2.0-2.6 %.0f%% (%d/%d; real 54%% of 103), 2.6+ %.0f%% (%d/%d; real 15%% of 26);"
				+ " after the stun %d of %d; its hits back within 5 ticks of the AI's hit %.0f%% (real 172/272 = 63%%); its take-offs within 2 ticks of a"
				+ " hit felt %.0f%% (real jump resets 91/272 = 33%%)%n  %s%n", t.drills(), 100 * near, t.hitBacks(0), t.followed(0), 100 * mid,
				t.hitBacks(1), t.followed(1), 100 * edge, t.hitBacks(2), t.followed(2), t.stunHitBacks(), t.stuns(),
				cal.backs == 0 ? 0.0 : 100.0 * cal.within5 / cal.backs, cal.felt == 0 ? 0.0 : 100.0 * cal.resets / cal.felt, t.line());
		assertTrue(t.followed(0) >= 100 && t.followed(1) >= 100 && t.followed(2) >= 100, "every band measured");
		assertTrue(near > mid && mid > edge, "the nearer the follow-up, the likelier their hit back");
		// Within about two standard errors of the real rounds' numbers (110, 103 and 26 hits).
		assertEquals(0.72, near, 0.10, "under 2.0 (real 72%)");
		assertEquals(0.54, mid, 0.13, "2.0-2.6 (real 54%)");
		assertEquals(0.15, edge, 0.15, "2.6+ (real 15%)");
	}

	@Test
	void aFollowUpThatNeverHoldsTheSwingHoldsLongerChains() {
		Calibration held = calibrate(1500, 9125L, REAL_SWING);
		Calibration never = calibrate(1500, 9125L, 1f);
		System.out.printf(Locale.ROOT, "run 125 chains in the toy drill: swinging on 55%% of the ready ticks: mean %.2f, 3+ %d, 5+ %d, 10+ %d of %d; swinging on"
				+ " every ready tick: mean %.2f, 3+ %d, 5+ %d, 10+ %d of %d; hit back after <2.0 / 2.0-2.6 / 2.6+: %.0f%% %.0f%% %.0f%%%n",
				held.tally.meanLength(), held.tally.chains(3), held.tally.chains(5), held.tally.chains(10), held.tally.drills(), never.tally.meanLength(),
				never.tally.chains(3), never.tally.chains(5), never.tally.chains(10), never.tally.drills(), 100 * rate(never.tally, 0),
				100 * rate(never.tally, 1), 100 * rate(never.tally, 2));
		assertTrue(never.tally.meanLength() > held.tally.meanLength(), "never holding the ready swing keeps the chain longer");
		assertTrue(never.tally.chains(10) > 0, "10+ chains can be held against the drill's bot");
	}
}
