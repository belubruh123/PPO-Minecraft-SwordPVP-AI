package dev.swordai.core.rl;

import dev.swordai.core.config.Config;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.vision.MaskFrame;
import dev.swordai.core.vision.SyntheticEye;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where backing out of a combo is a rule ({@code Config.backRule}), S is held for the fighter in the
 * ticks after each hit of a combo it is taking, and W/S is its own again the rest of the time.
 */
class BackRuleTest {
	private static final int FIGHT = ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH, ActionSpace.FORWARD,
			ActionSpace.STRAFE, ActionSpace.SPRINT, ActionSpace.ATTACK);

	private static BodyState body() {
		BodyState b = new BodyState();
		b.onGround = true;
		b.charge = 1f;
		b.health = 1f;
		return b;
	}

	private static MaskFrame seen(double blocks) {
		MaskFrame frame = new MaskFrame();
		new SyntheticEye().look(0, 1.62, 0, 0f, 0f, 0, 0, blocks, 180f, 180f, 0f, frame);
		return frame;
	}

	private static int backs(Fighter fighter, MaskFrame frame, int ticks) {
		int back = 0;
		for (int tick = 0; tick < ticks; tick++) {
			if (fighter.decide(frame, body())[ActionSpace.FORWARD] == BackNudge.BACK) {
				back++;
			}
		}
		return back;
	}

	@Test
	void comboedItBacksOutInTheTicksAfterEachHit() {
		Config cfg = new Config();
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			fighter.backOutRule(true);
			MaskFrame near = seen(3.0);
			// One hit is no combo: W and S are its own, and an untrained network presses S now and then.
			fighter.hitTaken();
			assertTrue(backs(fighter, near, 11) < 11);
			// The second in a row: S on every one of the ticks that decide it.
			fighter.hitTaken();
			assertEquals(11, backs(fighter, near, 11));
			// And the third the same.
			fighter.hitTaken();
			assertEquals(11, backs(fighter, near, 11));
			// Those ticks over, W and S are its own again: it is free to come back in.
			assertTrue(backs(fighter, near, 30) < 30);
			// It has hit back: no combo, no rule.
			fighter.hitTaken();
			fighter.decide(near, body());
			fighter.hitDealt();
			assertTrue(backs(fighter, near, 11) < 11);
		}
	}

	@Test
	void leftOffItIsTheNetworksOwn() {
		Config cfg = new Config();
		try (Learner learner = new Learner(cfg, 7, 1)) {
			Fighter fighter = new Fighter(learner, cfg, FIGHT, true, 21);
			MaskFrame near = seen(3.0);
			fighter.hitTaken();
			fighter.decide(near, body());
			fighter.hitTaken();
			assertTrue(backs(fighter, near, 11) < 11);
		}
	}
}
