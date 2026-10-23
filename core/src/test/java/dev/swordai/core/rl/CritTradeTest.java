package dev.swordai.core.rl;

import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The swing held for an opponent coming in through the air, and the punish crit after its crit ({@link CritTrade}); and the clash jump. */
class CritTradeTest {
	private static final int ALL = ActionSpace.ALL_HEADS;

	/**
	 * The fighter on the ground (or not), the opponent this far off straight ahead, going up at {@code oppUp} and coming in
	 * at {@code toward} a tick, with its sword at {@code oppCharge}.
	 */
	private static float[] vec(boolean ground, float distance, float charge, float oppUp, float toward, float oppCharge) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		float height = ViewGeometry.apparentSize(1.8f, distance);
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.1f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = -0.9f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_LEFT] = -0.17f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_RIGHT] = 0.17f * height;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = ground ? 1f : 0f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = charge;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = oppUp * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -toward * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = oppCharge;
		return v;
	}

	@Test
	void anOpponentComingInThroughTheAirWithItsSwordReadyIsNotSwungAtUpOrDown() {
		CritTrade trade = new CritTrade(4);
		// On its way up, at the top and on its way down, 3 to 2.6 blocks off: held each time.
		assertTrue(trade.holds(vec(true, 3.0f, 1f, 0.25f, 0.3f, 1f)));
		assertTrue(trade.incoming());
		assertTrue(trade.holds(vec(true, 2.8f, 1f, 0.0f, 0.3f, 1f)), "the top of its jump still counts as in the air");
		assertTrue(trade.holds(vec(true, 2.6f, 1f, -0.2f, 0.3f, 1f)));
		assertTrue(trade.heldWithin(0));
	}

	@Test
	void onItsWayUpItIsHeldForOnlyWhenItIsComingIn() {
		CritTrade in = new CritTrade(4);
		assertTrue(in.holds(vec(true, 3.0f, 1f, 0.3f, 0.3f, 1f)), "jumping in");
		assertTrue(in.jumpingIn());
		CritTrade hop = new CritTrade(4);
		assertFalse(hop.holds(vec(true, 3.0f, 1f, 0.3f, 0f, 1f)), "a hop in place");
		assertFalse(hop.jumpingIn());
		CritTrade topOnly = new CritTrade(4);
		topOnly.holdOnRising(false);
		assertFalse(topOnly.holds(vec(true, 3.0f, 1f, 0.3f, 0.3f, 1f)), "held from the top of the jump only");
		assertFalse(topOnly.jumpingIn(), "and a hit on its way up is not taken points for either");
	}

	@Test
	void aJumpInPlaceOrWithAHitItTookIsNoJumpIn() {
		CritTrade trade = new CritTrade(4);
		// At the top of a jump in place, and on its way down knocked back by the fighter's hit.
		assertFalse(trade.holds(vec(true, 2.8f, 1f, 0f, 0f, 1f)));
		assertFalse(trade.holds(vec(true, 2.8f, 1f, -0.2f, -0.2f, 1f)));
		assertFalse(trade.incoming());
		// Its hit from there is no jumper's crit: answered as any hit.
		float[] hit = vec(true, 2.6f, 1f, -0.2f, -0.1f, 0.1f);
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		trade.holds(hit);
		assertFalse(trade.jumperHit());
	}

	@Test
	void onlyAJumpSeenTakingOffAtItIsAJumpIn() {
		// Knocked up and away by the fighter's hit, then coming back in on the way down: its hit from there is no jumper's crit.
		CritTrade knocked = new CritTrade(4);
		knocked.holds(vec(true, 3.0f, 1f, 0.3f, -0.4f, 1f));
		assertFalse(knocked.holds(vec(true, 2.8f, 1f, -0.2f, 0.15f, 1f)));
		assertFalse(knocked.incoming());
		float[] hit = vec(true, 2.6f, 1f, -0.1f, 0.15f, 0.1f);
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		knocked.holds(hit);
		assertFalse(knocked.jumperHit());
		// Stepping down a block while walking in: falling, but never seen going up.
		assertFalse(new CritTrade(4).holds(vec(true, 2.6f, 1f, -0.2f, 0.2f, 1f)));
		// A take-off at it long enough ago is a jump that is over.
		CritTrade old = new CritTrade(4);
		old.holds(vec(true, 4f, 1f, 0.3f, 0.3f, 1f));
		for (int i = 0; i < CritTrade.JUMP_TICKS; i++) {
			old.holds(vec(true, 4f, 1f, 0f, 0.1f, 1f));
		}
		assertFalse(old.holds(vec(true, 2.6f, 1f, -0.2f, 0.2f, 1f)));
	}

	@Test
	void notHeldForAnOpponentOnTheGroundOrWithItsSwordSpentOrFarOrGoingAway() {
		assertFalse(new CritTrade(4).holds(vec(true, 2.6f, 1f, 0f, 0.3f, 1f)), "on the ground");
		assertFalse(new CritTrade(4).holds(vec(true, 2.6f, 1f, -0.2f, 0.3f, 0.3f)), "its sword spent");
		CritTrade far = new CritTrade(4);
		assertFalse(far.holds(vec(true, 5f, 1f, 0.3f, 0.4f, 1f)), "beyond reach");
		assertTrue(far.incoming(), "but coming in: no running at it");
		assertFalse(new CritTrade(4).holds(vec(true, 2.6f, 1f, -0.2f, -0.3f, 1f)), "going away");
		CritTrade after = new CritTrade(4);
		after.holds(vec(true, 2.8f, 1f, 0.3f, 0.3f, 1f));
		after.holds(vec(true, 2.6f, 1f, -0.2f, 0.3f, 1f));
		for (int i = 0; i < 3; i++) {
			after.holds(vec(true, 2.6f, 1f, 0f, 0f, 0.2f));
		}
		assertFalse(after.heldWithin(2));
		assertTrue(after.heldWithin(3));
	}

	@Test
	void itsOwnCritComesFirstWhereItIsComingDownWithItsSwordBack() {
		float[] v = vec(false, 2.6f, 1f, -0.2f, 0.3f, 1f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = -0.2f * BodyState.VELOCITY_SCALE;
		// The opponent's own speed up is relative plus the fighter's: keep it coming down too.
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0f;
		assertFalse(new CritTrade(4).holds(v));
	}

	@Test
	void knockingItAwayIsOnlyForWhenThatIsCertain() {
		// Nearly at the top, close: it comes down in reach whatever the hit does.
		assertFalse(CritTrade.knockAwayCertain(2.8f, 0.3f, 0.1f, true, 4));
		// Just off the ground at the edge of reach, a sprint hit with no delay: carried out of reach.
		assertTrue(CritTrade.knockAwayCertain(3.0f, 0.3f, 0.42f, true, 0));
		// The same with the server's four ticks: it comes on into reach first, so not certain.
		assertFalse(CritTrade.knockAwayCertain(3.0f, 0.3f, 0.42f, true, 4));
		// And only where it is certain is the swing not held back.
		CritTrade quick = new CritTrade(0);
		assertFalse(quick.holds(sprinting(vec(true, 3.0f, 1f, 0.42f, 0.3f, 1f))));
		assertTrue(quick.knockAway());
		assertTrue(new CritTrade(4).holds(sprinting(vec(true, 3.0f, 1f, 0.42f, 0.3f, 1f))));
	}

	private static float[] sprinting(float[] v) {
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 1f;
		return v;
	}

	@Test
	void thePunishCritIsMadeAtOnce() {
		float[] ground = vec(true, 2.5f, 1f, 0f, 0f, 0.1f);
		assertEquals(CritTrade.NONE, new CritTrade(4).punish(ground));
		float[] up = vec(false, 2.5f, 1f, 0f, 0f, 0.1f);
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		assertEquals(CritTrade.LET_GO, CritTrade.punishInAir(sprinting(up.clone())), "the sprint let go of on the tick of the hit");
		assertEquals(CritTrade.WALK, CritTrade.punishInAir(up), "on the way up, W without it");
		float[] down = vec(false, 2.5f, 1f, 0f, 0f, 0.1f);
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = -0.05f * BodyState.VELOCITY_SCALE;
		assertEquals(CritTrade.SWING, CritTrade.punishInAir(down), "the first tick of the way down");
	}

	@Test
	void afterAJumpersCritTheSwingIsKeptOnTheGroundUntilTheKnockbackLiftsIt() {
		CritTrade trade = new CritTrade(4);
		// The jumper taking off at it, coming down on it, then the tick it learns of the hit.
		trade.holds(vec(true, 3.0f, 1f, 0.3f, 0.3f, 1f));
		trade.holds(vec(true, 2.6f, 1f, -0.2f, 0.3f, 1f));
		float[] hit = vec(true, 2.6f, 1f, 0f, 0.1f, 0.1f);
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f;
		hit[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = 1f;
		trade.holds(hit);
		assertTrue(trade.jumperHit());
		assertEquals(CritTrade.WAIT, trade.punish(hit), "still on the ground: wait for the lift");
		float[] later = hit.clone();
		later[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = 1f - CritTrade.GROUND_WAIT_TICKS / BodyState.EVENT_FADE_TICKS;
		trade.holds(later);
		assertEquals(CritTrade.NONE, trade.punish(later), "never lifted: the answer rule has it");
		// A hit from an opponent on the ground is no jumper's crit.
		CritTrade flat = new CritTrade(4);
		for (int i = 0; i < 5; i++) {
			flat.holds(vec(true, 2.6f, 1f, 0f, 0.1f, 1f));
		}
		flat.holds(hit);
		assertFalse(flat.jumperHit());
		assertEquals(CritTrade.NONE, flat.punish(hit));
	}

	@Test
	void anOpponentJumpingInAtItIsJumpedWithAndCritBackOnTheWayDown() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.clashJump(true);
		int plan = rule.plan(vec(true, 3.8f, 1f, 0.3f, 0.35f, 1f), ALL, false, false, 0);
		assertEquals(JumpIn.CLASH, rule.tookOff());
		assertTrue((plan & JumpIn.JUMP) != 0 && (plan & JumpIn.LET_GO) != 0 && (plan & JumpIn.NO_SWING) != 0);
		// On the way up: W without the sprint, no swing.
		float[] up = vec(false, 2.6f, 1f, 0.1f, 0.3f, 1f);
		up[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		plan = rule.plan(up, ALL, true, false, 0);
		assertTrue((plan & JumpIn.WALK) != 0 && (plan & JumpIn.NO_SWING) != 0);
		// On the way down with the opponent under the crosshair: the crit.
		float[] down = vec(false, 2.2f, 1f, 0f, 0.3f, 1f);
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = -0.1f * BodyState.VELOCITY_SCALE;
		plan = rule.plan(down, ALL, true, false, 0);
		assertTrue((plan & JumpIn.SWING) != 0);
	}

	@Test
	void noClashJumpWithItsOwnSwordFarFromBackOrAtAnOpponentThatIsNotComing() {
		JumpIn rule = new JumpIn(false, false, 0f, 1);
		rule.clashJump(true);
		assertEquals(0, rule.plan(vec(true, 3.8f, 0.3f, 0.3f, 0.35f, 1f), ALL, false, false, 0), "its own sword not back");
		assertEquals(0, rule.plan(vec(true, 3.8f, 1f, 0f, 0.35f, 1f), ALL, false, false, 0), "on the ground");
		assertEquals(0, rule.plan(vec(true, 3.8f, 1f, 0.3f, -0.2f, 1f), ALL, false, false, 0), "going away");
		assertEquals(0, rule.plan(vec(true, 3.8f, 1f, 0.3f, 0.35f, 0.3f), ALL, false, false, 0), "its sword spent");
		assertEquals(0, rule.plan(vec(true, 5.5f, 1f, 0.3f, 0.35f, 1f), ALL, false, false, 0), "too far");
		JumpIn off = new JumpIn(false, false, 0f, 1);
		assertEquals(0, off.plan(vec(true, 3.8f, 1f, 0.3f, 0.35f, 1f), ALL, false, false, 0), "the rule off");
	}
}
