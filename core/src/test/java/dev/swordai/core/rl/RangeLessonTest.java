package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The range lesson of the crit stage, played by the real network and the real learner on a stand-in
 * for the drill: an opponent that is within reach for a while and then out of it, shown only as how
 * large it is on screen. Whether the crit model comes to leave the jump alone at the far one, and how
 * long that takes, is what this is for: in 0.13.0 it was reasoned out and not tried, and it did not.
 */
class RangeLessonTest {
	private static final int ROLLOUT = 1800;
	private static final float ENTROPY = 0.3f;
	private static final int HOP_TICKS = 80;
	private static final int AIR_TICKS = 12;
	private static final int CRIT_TICK = 7;
	private static final int JUMP = ActionSpace.mask(ActionSpace.JUMP);

	/** What a critical hit earns, with the hit it is made of. */
	private static final float CRIT = 3.5f;
	/** A sword's reach, between the two fighters' middles. */
	private static final float REACH = 3.3f;

	/** The drill as the fighter meets it: what it is shown, and what comes of a jump. */
	private static final class Drill {
		final Random rng;
		final boolean farToo;
		/** What a jump that comes to nothing costs. */
		final float wasted;
		/**
		 * Share of the placements in which the fighter is not held but moving as it does in a fight:
		 * running, keys down, and its crosshair anywhere on the opponent rather than on its head.
		 */
		float movingShare;
		boolean moving;
		/** Where "farther off" begins in the held drill: 2.4 blocks in the lesson, out of reach once it only goes on. */
		float farFrom = 2.4f;
		/**
		 * Share of the placements that stand in for the stage's own fights: the fighter is moving, the
		 * opponent is anywhere from in its face to across the arena, and does not wait.
		 */
		float fightShare;
		boolean fight;
		/** In such a fight: how often a jump made with the opponent in reach comes to a critical hit. Mostly the opponent has stepped away. */
		float fightLands = 0.25f;
		/** 0.13.2: in a fight, a jump made in reach that the opponent stepped away from is charged like one made out of reach. */
		boolean chargeSteppedAway;
		/** How far off the opponent is, between the two fighters' middles. */
		float distance;
		boolean jumpInFight;
		/** Where the opponent was when it took off: see {@link #fightGround}. */
		int jumpBand;
		/** Fights only, by where the opponent was: in its face, just beyond that, at sprint-hit range, out of reach. */
		final long[] fightGround = new long[4];
		final long[] fightJumps = new long[4];
		float aim;
		float velForward;
		float velRight;
		int strafe;
		boolean far;
		float size;
		int sinceHop;
		/** Ticks into a jump, or -1 on the ground. */
		int air = -1;
		boolean jumpWasFar;
		long farGround;
		long farJumps;
		long nearGround;
		long nearJumps;

		Drill(long seed, boolean farToo, float wasted) {
			this.rng = new Random(seed);
			this.farToo = farToo;
			this.wasted = wasted;
			hop();
		}

		void hop() {
			far = farToo && rng.nextBoolean();
			// How tall the opponent stands on screen, in half frame heights: 1.8 blocks seen from 1.2 to
			// 1.8 blocks (right in its face, where a critical hit is the thing to make) or from 2.4 to
			// 6.5 (sprint-hit range and beyond, where the jump is to be left alone).
			distance = far ? farFrom + (6.5f - farFrom) * rng.nextFloat() : 1.2f + 0.6f * rng.nextFloat();
			fight = rng.nextFloat() < fightShare;
			if (fight) {
				distance = 1f + 5f * rng.nextFloat();
				far = distance > REACH;
			}
			size = 1.8f / distance;
			sinceHop = 0;
			moving = fight || rng.nextFloat() < movingShare;
			aim = moving ? 0.8f * rng.nextFloat() : 0f;
			velForward = moving ? 1.2f + 0.4f * (float) rng.nextGaussian() : 0f;
			velRight = moving ? 0.5f * (float) rng.nextGaussian() : 0f;
			strafe = moving ? rng.nextInt(3) : 0;
		}

		void observe(Obs o) {
			java.util.Arrays.fill(o.vec, 0f);
			java.util.Arrays.fill(o.priv, 0f);
			for (int frame = 0; frame < ObsSpec.STAT_FRAMES; frame++) {
				int at = ObsSpec.VEC_STATS + frame * ObsSpec.STATS;
				float wobble = 0.01f * (float) rng.nextGaussian();
				// Where on the opponent the crosshair is: 0 on its head, 0.8 at its shins. Everything
				// measured from the crosshair moves with that.
				float up = (0.1f + aim) * size;
				float side = moving ? 0.1f * size * (float) Math.sin(sinceHop * 0.3f) : 0f;
				o.vec[at + ObsSpec.ST_HEAD_VISIBLE] = 1f;
				o.vec[at + ObsSpec.ST_ANY_VISIBLE] = 1f;
				o.vec[at + ObsSpec.ST_HEAD_X] = side;
				o.vec[at + ObsSpec.ST_HEAD_Y] = aim * size;
				o.vec[at + ObsSpec.ST_HEAD_AREA] = 0.22f * size;
				o.vec[at + ObsSpec.ST_ANY_X] = side;
				o.vec[at + ObsSpec.ST_ANY_Y] = up - 0.5f * size + wobble;
				o.vec[at + ObsSpec.ST_LEFT] = side - 0.17f * size;
				o.vec[at + ObsSpec.ST_RIGHT] = side + 0.17f * size;
				o.vec[at + ObsSpec.ST_TOP] = Math.min(1f, up + wobble);
				// From close up the opponent runs off the bottom of the picture.
				o.vec[at + ObsSpec.ST_BOTTOM] = Math.max(-1f, up - size + wobble);
				o.vec[at + ObsSpec.ST_ANY_AREA] = Math.min(1f, 0.55f * size);
				ObsSpec.deriveStats(java.util.Arrays.copyOfRange(o.vec, at, at + ObsSpec.STATS));
			}
			int sc = ObsSpec.VEC_SCALARS;
			boolean ground = air < 0;
			o.vec[sc + ObsSpec.SC_ON_GROUND] = ground ? 1f : 0f;
			o.vec[sc + ObsSpec.SC_HEIGHT] = ground ? 0f : 0.3f;
			o.vec[sc + ObsSpec.SC_VEL_UP] = ground ? 0f : air < CRIT_TICK ? 0.5f : -0.5f;
			o.vec[sc + ObsSpec.SC_ATTACK_CHARGE] = ground ? 1f : Math.min(1f, air / 11f);
			o.vec[sc + ObsSpec.SC_HEALTH] = 1f;
			o.vec[sc + ObsSpec.SC_PITCH] = 0.05f * (float) rng.nextGaussian() + 0.3f * aim;
			if (moving && ground) {
				// Running at the opponent with its keys down, the camera carrying some speed.
				o.vec[sc + ObsSpec.SC_VEL_FORWARD] = velForward;
				o.vec[sc + ObsSpec.SC_VEL_RIGHT] = velRight;
				o.vec[sc + ObsSpec.SC_SPRINTING] = 1f;
				o.vec[sc + ObsSpec.SC_REL_VEL_FORWARD] = -velForward + 0.3f * (float) rng.nextGaussian();
				o.vec[sc + ObsSpec.SC_TURN_RATE_YAW] = 0.3f * (float) rng.nextGaussian();
				o.vec[sc + ObsSpec.SC_TURN_RATE_PITCH] = 0.2f * (float) rng.nextGaussian();
				o.vec[sc + ObsSpec.SC_IDEA_STRAFE] = strafe == 0 ? 0f : strafe == 1 ? -1f : 1f;
				int[] keys = new int[ActionSpace.HEADS];
				keys[ActionSpace.YAW] = 5 + rng.nextInt(5);
				keys[ActionSpace.PITCH] = 4 + rng.nextInt(3);
				keys[ActionSpace.FORWARD] = 1;
				keys[ActionSpace.STRAFE] = strafe;
				keys[ActionSpace.SPRINT] = 1;
				ObsBuilder.writeLastAction(o.vec, keys);
			}
			ObsBuilder.writeHeadMask(o.vec, ActionSpace.ALL_HEADS);
			// The critic is told how far off the opponent is; the acting network never is.
			o.priv[ObsSpec.PV_REACH_DIST] = (1.8f / size - 0.3f) / 8f;
		}

		/** One tick. Returns what it was worth. */
		float step(int[] action, boolean chargeWasted) {
			float r = 0f;
			if (air < 0) {
				boolean judged = sinceHop > 8;
				int band = distance <= 1.8f ? 0 : distance <= 2.4f ? 1 : distance <= REACH ? 2 : 3;
				if (judged && fight) {
					fightGround[band]++;
				} else if (judged) {
					if (far) {
						farGround++;
					} else {
						nearGround++;
					}
				}
				if (action[ActionSpace.JUMP] == 1) {
					air = 0;
					jumpWasFar = far;
					jumpInFight = fight;
					jumpBand = band;
					if (judged && fight) {
						fightJumps[band]++;
					} else if (judged) {
						if (far) {
							farJumps++;
						} else {
							nearJumps++;
						}
					}
				}
			} else {
				air++;
				if (air == CRIT_TICK && !jumpWasFar && rng.nextFloat() < (jumpInFight ? fightLands : 0.9f)) {
					// In reach, coming down with the sword back: the swing lands and is critical.
					r += CRIT;
					air = AIR_TICKS + 100;
				}
				if (air == AIR_TICKS) {
					// Back on the ground with nothing done. In the held drill it is charged if it was made at
					// the opponent farther off. In a fight, as it is now: if it was made from anywhere but in
					// the opponent's face. As 0.13.2 had it: if the opponent was out of reach, or had
					// stepped out of reach by now, from wherever it was made.
					boolean charged = !jumpInFight ? jumpWasFar
							: chargeSteppedAway ? jumpWasFar || rng.nextFloat() < 0.7f
							: jumpBand > 0;
					if (chargeWasted && charged) {
						r -= wasted;
					}
					air = -1;
				} else if (air >= AIR_TICKS + 100 + (AIR_TICKS - CRIT_TICK)) {
					air = -1;
				}
			}
			// It is put somewhere else every four seconds, but not in the middle of a jump.
			sinceHop++;
			if (air < 0 && sinceHop >= HOP_TICKS) {
				hop();
			}
			return r;
		}

		int keys() {
			return air < 0 ? JUMP : 0;
		}

		void clearCounts() {
			farGround = 0;
			farJumps = 0;
			nearGround = 0;
			nearJumps = 0;
			java.util.Arrays.fill(fightGround, 0L);
			java.util.Arrays.fill(fightJumps, 0L);
		}
	}

	private static void play(Learner learner, Drill drill, Obs[] obs, boolean chargeWasted) {
		int[] action = new int[ActionSpace.HEADS];
		while (!learner.rolloutReady(ROLLOUT)) {
			learner.act(obs[0], drill.keys(), false, true, action);
			float reward = drill.step(action, chargeWasted);
			drill.observe(obs[1]);
			learner.complete(reward, false, false, obs[1]);
			Obs swap = obs[0];
			obs[0] = obs[1];
			obs[1] = swap;
		}
	}

	/**
	 * Returns the number of updates of the range drill it took until, over three updates running, it
	 * jumped on no more than one in a hundred of the ticks it stood with the opponent out of reach
	 * while still jumping at the near one; or -1 if it never did.
	 *
	 * @param timingFirst updates of the timing drill's stand-in played first, in which the opponent is
	 *                    always in reach and a jump is never charged
	 */
	private static int updatesToLearnTheRange(long seed, int timingFirst, int most, float wasted, float lrTimes, boolean print) {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		cfg.actorLr *= lrTimes;
		cfg.maxActorLr *= lrTimes;
		try (Learner learner = new Learner(cfg, seed, 1)) {
			learner.reopenHead(ActionSpace.JUMP, 3f);
			Obs[] obs = {new Obs(), new Obs()};
			Drill timing = new Drill(seed + 1, false, wasted);
			timing.observe(obs[0]);
			for (int u = 0; u < timingFirst; u++) {
				play(learner, timing, obs, false);
				learner.update(u >= cfg.criticWarmupUpdates, ENTROPY);
				timing.clearCounts();
			}
			Drill range = new Drill(seed + 2, true, wasted);
			range.observe(obs[0]);
			learner.interrupt();
			int good = 0;
			for (int u = 0; u < most; u++) {
				play(learner, range, obs, true);
				learner.update(u >= cfg.criticWarmupUpdates, ENTROPY);
				float farRate = range.farJumps / (float) Math.max(1, range.farGround);
				float nearRate = range.nearJumps / (float) Math.max(1, range.nearGround);
				if (print && (u % 4 == 3 || u < 2)) {
					System.out.printf("  range %3d: far jump %.4f (%d of %d), near jump %.3f, lr %.5f%n", u, farRate, range.farJumps, range.farGround, nearRate, learner.actorLr());
				}
				good = range.farGround >= 100 && farRate <= 0.01f && nearRate >= 0.3f ? good + 1 : 0;
				range.clearCounts();
				if (good >= 3) {
					return u + 1;
				}
			}
			return -1;
		}
	}

	/**
	 * Taught when to jump and when not together, from a jump key with no habit yet, it has both within
	 * about fifteen updates: it jumps at the opponent in its face and on no more than one tick in a
	 * hundred at the one farther off. (Four runs: 13 to 18 updates. With "near" meaning anywhere within
	 * reach, as in 0.13.1, it took 26 to 33.)
	 *
	 * <p>The other order does not work, and is what 0.13.0 did: with the timing learned first on an
	 * opponent always in reach ({@code timingFirst} 12 here), it jumps on nine ticks in ten wherever
	 * it stands, and sixty updates of the range drill (108,000 steps) leave it there, with a jump for
	 * nothing costing 2.0 and also at four times the learning rate. Holding back for one tick changes
	 * nothing when the next tick jumps anyway, so there is nothing for it to learn from. That run
	 * takes a minute and a half and is not repeated here.
	 */
	@Test
	void itComesToLeaveTheJumpAloneWhereTheOpponentIsNotInItsFace() {
		int updates = updatesToLearnTheRange(11L, 0, 30, 2.0f, 1f, false);
		assertTrue(updates > 0, "after 30 updates it still jumped at the opponent farther off, or had stopped jumping at the one in its face");
	}

	/**
	 * What becomes of the jump after its lesson, in a stand-in for the stage's own fights: the opponent
	 * is anywhere from in its face to across the arena, the fighter is moving, and of the jumps it
	 * makes with the opponent in reach only one in four comes to a critical hit, because the opponent
	 * does not wait.
	 *
	 * @param as0132 as 0.13.2 had it: nothing but such fights after the lesson, and a jump the
	 *               opponent stepped away from charged like one made out of reach, wherever it was
	 *               made from. Otherwise as it is now: a jump from in the opponent's face is never
	 *               charged, one from farther off is unless it lands, and one placement in nine is
	 *               the held drill, going on.
	 * @return how often it jumps, per tick on the ground, over the last five updates: held in its
	 *         face, held and out of reach (not in the 0.13.2 way, which has no held drill then), and
	 *         in fights in its face, just beyond that (up to 2.4 blocks), at sprint-hit range, and
	 *         out of reach
	 */
	private static float[] jumpAfterTheLesson(boolean as0132, boolean print) {
		Config cfg = new Config();
		cfg.usePeriphery = false;
		cfg.useFovea = false;
		try (Learner learner = new Learner(cfg, 11L, 1)) {
			learner.reopenHead(ActionSpace.JUMP, 3f);
			Obs[] obs = {new Obs(), new Obs()};
			Drill lesson = new Drill(13L, true, 2f);
			lesson.observe(obs[0]);
			for (int u = 0; u < 20; u++) {
				play(learner, lesson, obs, true);
				learner.update(u >= cfg.criticWarmupUpdates, ENTROPY);
				if (print && u == 19) {
					System.out.printf("  after the lesson: jumps on %.3f of ticks in its face, %.4f farther off%n",
							lesson.nearJumps / (float) Math.max(1, lesson.nearGround), lesson.farJumps / (float) Math.max(1, lesson.farGround));
				}
				lesson.clearCounts();
			}
			Drill stage = new Drill(17L, true, 2f);
			stage.fightShare = as0132 ? 1f : 8f / 9f;
			stage.chargeSteppedAway = as0132;
			stage.farFrom = 3.5f;
			stage.hop();
			stage.observe(obs[0]);
			learner.interrupt();
			long[] ground = new long[6];
			long[] jumps = new long[6];
			int updates = 30;
			for (int u = 0; u < updates; u++) {
				play(learner, stage, obs, true);
				learner.update(true, ENTROPY);
				if (u >= updates - 5) {
					ground[0] += stage.nearGround;
					jumps[0] += stage.nearJumps;
					ground[1] += stage.farGround;
					jumps[1] += stage.farJumps;
					for (int b = 0; b < 4; b++) {
						ground[2 + b] += stage.fightGround[b];
						jumps[2 + b] += stage.fightJumps[b];
					}
				}
				stage.clearCounts();
			}
			float[] rates = new float[6];
			for (int i = 0; i < 6; i++) {
				rates[i] = jumps[i] / (float) Math.max(1, ground[i]);
			}
			if (print) {
				System.out.printf("  %s, after 30 updates of fights: held in its face %.3f, held out of reach %.4f; in fights: in its face %.3f, just beyond %.3f, at sprint-hit range %.4f, out of reach %.4f%n",
						as0132 ? "as 0.13.2" : "as now", rates[0], rates[1], rates[2], rates[3], rates[4], rates[5]);
			}
			return rates;
		}
	}

	/**
	 * After the lesson the jump has to live through real fights, where most jumps come to nothing.
	 * As it is now, it does: in the stand-in it goes on jumping at the opponent in its face, held (on
	 * 91% of the ticks) and in a fight (35%), and leaves the jump alone at sprint-hit range (0.7%)
	 * and out of reach (0.06%). Just beyond the line, up to 2.4 blocks, it jumps on 6% of the ticks:
	 * how large the opponent is on screen does not draw a line that sharp.
	 *
	 * <p>As 0.13.2 had it, it did not, and that is what the report of that run showed (a jump a
	 * minute after the drills, then none). The same stand-in, run once and not repeated here because
	 * it takes another minute: after 30 updates of fights it pressed jump on 4% of the ticks the
	 * opponent was in its face, down from 75% at the end of the lesson. With that jump never charged
	 * and nothing else changed it stayed, but spread: it then also jumped on 8% of the ticks the
	 * opponent was at sprint-hit range, where in the stand-in nothing spoke against it. That is the
	 * range combos are made at, so a jump from there is charged unless it lands.
	 */
	@Test
	void theJumpItLearnedIsStillThereAfterFightsInWhichMostJumpsComeToNothing() {
		float[] now = jumpAfterTheLesson(false, true);
		assertTrue(now[0] >= 0.3f, "held, with the opponent in its face, it jumps on " + now[0] + " of the ticks");
		assertTrue(now[1] <= 0.01f, "held, with the opponent out of reach, it jumps on " + now[1] + " of the ticks");
		assertTrue(now[2] >= 0.3f, "in a fight, with the opponent in its face, it jumps on " + now[2] + " of the ticks");
		assertTrue(now[4] <= 0.02f, "in a fight, at sprint-hit range, it jumps on " + now[4] + " of the ticks");
		assertTrue(now[5] <= 0.01f, "in a fight, with the opponent out of reach, it jumps on " + now[5] + " of the ticks");
	}
}
