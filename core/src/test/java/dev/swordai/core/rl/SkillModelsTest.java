package dev.swordai.core.rl;

import dev.swordai.core.nn.Param;
import dev.swordai.core.nn.ParamSet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillModelsTest {
	private static final int JUMP = ActionSpace.OFFSETS[ActionSpace.JUMP];
	private static final int FORWARD = ActionSpace.OFFSETS[ActionSpace.FORWARD];

	private static float[] vec(Random rng, boolean inCombo) {
		float[] v = new float[ObsSpec.VEC];
		for (int i = 0; i < v.length; i++) {
			v[i] = (float) rng.nextGaussian() * 0.5f;
		}
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW] = inCombo ? 2f / 3f : 0f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN] = inCombo ? 0.8f : 0f;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		return v;
	}

	/**
	 * Whatever the escape model and the crit model learn stays with them: after a step of learning
	 * about W/S in a combo and about the jump, the network presses every other key exactly as
	 * before, and W/S outside a combo exactly as before.
	 */
	@Test
	void whatTheTwoSmallModelsLearnChangesNothingElse() {
		ActorNet net = new ActorNet(5L, false, false);
		ActorNet.Workspace ws = net.newWorkspace();
		Random rng = new Random(2);
		float[] outside = vec(rng, false);
		float[] inside = vec(rng, true);
		byte[] peri = new byte[ObsSpec.PERI_SIZE];
		byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];

		net.forward(ws, peri, fovea, outside);
		float[] outsideBefore = ws.logits.clone();
		net.forward(ws, peri, fovea, inside);
		float[] insideBefore = ws.logits.clone();
		// A model that has learned nothing leaves the jump alone on most ticks and is undecided between W, nothing and S.
		assertTrue(insideBefore[JUMP] - insideBefore[JUMP + 1] > 2f);
		assertEquals(insideBefore[FORWARD], insideBefore[FORWARD + 2], 0.2f);

		// One step of learning, from a moment in a combo: S and the jump were good.
		ParamSet.zero(ws.grads);
		java.util.Arrays.fill(ws.dLogits, 0f);
		ws.dLogits[FORWARD + BackNudge.BACK] = -1f;
		ws.dLogits[FORWARD + 1] = 1f;
		ws.dLogits[JUMP + 1] = -1f;
		ws.dLogits[JUMP] = 1f;
		net.backward(ws, peri, fovea, inside);
		int touched = 0;
		for (Param p : net.params.params()) {
			float[] g = ws.grads[p.id];
			boolean any = false;
			for (int i = 0; i < g.length; i++) {
				p.w[i] -= 0.05f * g[i];
				any |= g[i] != 0f;
			}
			if (any) {
				touched++;
				assertTrue(p.name.startsWith("crit.") || p.name.startsWith("escape."), p.name + " was changed");
			}
		}
		assertEquals(8, touched, "both models, each two layers with weights and biases; what the crit model says in the air was not asked");

		net.forward(ws, peri, fovea, inside);
		assertTrue(ws.logits[FORWARD + BackNudge.BACK] > insideBefore[FORWARD + BackNudge.BACK], "in a combo it now leans to S");
		assertTrue(ws.logits[JUMP + 1] > insideBefore[JUMP + 1]);
		net.forward(ws, peri, fovea, outside);
		for (int i = 0; i < ws.logits.length; i++) {
			if (i != JUMP && i != JUMP + 1) {
				assertEquals(outsideBefore[i], ws.logits[i], 0f, "outside a combo, output " + i + " is what it was");
			}
		}

		// And the other way round: learning about W/S outside a combo is the trunk's, and the escape model hears nothing of it.
		ParamSet.zero(ws.grads);
		java.util.Arrays.fill(ws.dLogits, 0f);
		ws.dLogits[FORWARD + 1] = -1f;
		net.backward(ws, peri, fovea, outside);
		for (Param p : net.params.params()) {
			for (float g : ws.grads[p.id]) {
				if (g != 0f) {
					assertTrue(!p.name.startsWith("escape.") && !p.name.startsWith("crit."), p.name);
				}
			}
		}
	}

	/** Makes the trunk prefer swinging to not swinging by this much (or the other way, below zero), whatever it is shown. */
	private static void trunkSwing(ActorNet net, float by) {
		int attack = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		for (Param p : net.params.params()) {
			if (p.name.equals("heads.b")) {
				p.w[attack] = 0f;
				p.w[attack + 1] = by;
			} else if (p.name.equals("heads.w")) {
				for (int i = 0; i < p.w.length; i += ActionSpace.TOTAL_LOGITS) {
					p.w[i + attack] = 0f;
					p.w[i + attack + 1] = 0f;
				}
			}
		}
	}

	private static float swingOverHold(ActorNet net, ActorNet.Workspace ws, float[] vec) {
		int attack = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		net.forward(ws, new byte[ObsSpec.PERI_SIZE], new byte[ObsSpec.FOVEA_SIZE], vec);
		return ws.logits[attack + 1] - ws.logits[attack];
	}

	/**
	 * Whenever a critical hit can be in the making, the sprint key is the crit model's, and W/S too
	 * unless the escape model has those: in a jump of its own, and coming down after a hit has
	 * knocked it up. On the ground, and on the way up after a hit, those keys are the trunk's. The
	 * swing is the crit model's for as long as it is off the ground at all, also on the way up after
	 * a hit: that is where the swing is kept for the punish crit.
	 */
	@Test
	void whereACriticalHitCanBeMadeTheCritModelHasTheSprintAndTheWalkAndInTheAirTheSwing() {
		int sprint = ActionSpace.OFFSETS[ActionSpace.SPRINT];
		int attack = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		int up = ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP;
		int since = ObsSpec.VEC_SCALARS + ObsSpec.SC_SINCE_HIT_TAKEN;
		int row = ObsSpec.VEC_SCALARS + ObsSpec.SC_HITS_TAKEN_ROW;
		int sprinting = ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING;
		byte[] peri = new byte[ObsSpec.PERI_SIZE];
		byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
		float[] ground = vec(new Random(4), false);
		ground[sprinting] = 0f;
		float[] ownJump = ground.clone();
		ownJump[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		ownJump[up] = 0.3f;
		float[] knockedUp = ownJump.clone();
		knockedUp[since] = 0.8f;
		float[] comingDown = knockedUp.clone();
		comingDown[up] = -0.2f;
		float[] comingDownInACombo = comingDown.clone();
		comingDownInACombo[row] = 2f / 3f;
		assertTrue(JumpNudge.critMoment(ownJump) && JumpNudge.critMoment(comingDown) && JumpNudge.critMoment(comingDownInACombo));
		assertTrue(!JumpNudge.critMoment(ground) && !JumpNudge.critMoment(knockedUp));
		assertTrue(JumpNudge.inAir(knockedUp) && JumpNudge.inAir(ownJump) && !JumpNudge.inAir(ground));
		assertTrue(BackNudge.applies(comingDownInACombo) && !BackNudge.applies(comingDown));

		ActorNet net = new ActorNet(8L, false, false);
		ActorNet.Workspace ws = net.newWorkspace();
		// A trunk that sees a hit to be had, so that what the crit model makes of the swing shows.
		trunkSwing(net, 2f);
		float[][] moments = {ground, ownJump, knockedUp, comingDown, comingDownInACombo};
		float[][] before = new float[moments.length][];
		for (int m = 0; m < moments.length; m++) {
			net.forward(ws, peri, fovea, moments[m]);
			before[m] = ws.logits.clone();
		}
		// In its own jump: the sprint key up, and W left alone (pressed again it would start the sprint again).
		assertTrue(before[1][sprint] - before[1][sprint + 1] > 3.5f);
		assertTrue(before[1][FORWARD] - before[1][FORWARD + 1] > 2.5f);
		assertTrue(before[1][FORWARD + 2] < before[1][FORWARD + 1]);
		// On the ground the swing is the trunk's, as it is.
		assertEquals(2f, before[0][attack + 1] - before[0][attack], 1e-5f);

		// The swing in the air starts out as what a critical hit takes: on the way up, with the weapon
		// not back, or still sprinting, it is held; coming down with the weapon back and the sprint off,
		// it is made.
		int charge = ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE;
		float[] rising = ownJump.clone();
		rising[charge] = 1f;
		float[] fallingEarly = ownJump.clone();
		fallingEarly[up] = -0.2f;
		fallingEarly[charge] = 0.5f;
		float[] fallingReady = fallingEarly.clone();
		fallingReady[charge] = 0.92f;
		float[] fallingSprinting = fallingReady.clone();
		fallingSprinting[sprinting] = 1f;
		assertTrue(!JumpNudge.critSwingReady(rising) && !JumpNudge.critSwingReady(fallingEarly) && JumpNudge.critSwingReady(fallingReady));
		assertTrue(!JumpNudge.critSwingReady(fallingSprinting), "a sprinting swing is no critical hit, whatever else is right");
		assertTrue(swingOverHold(net, ws, rising) < -2.5f, "on the way up the swing is held");
		assertTrue(swingOverHold(net, ws, fallingEarly) < -2.5f, "and with the weapon not back");
		assertTrue(swingOverHold(net, ws, fallingSprinting) < -2.5f, "and while the sprint is still on");
		assertTrue(swingOverHold(net, ws, fallingReady) > 2.5f, "coming down with the weapon back and the sprint off it is made");
		// Knocked up by a hit and still on the way up, with the weapon back: the trunk on its own would
		// swing here, and the punish crit would be gone. The swing is kept for the way down.
		float[] knockedUpCharged = knockedUp.clone();
		knockedUpCharged[charge] = 1f;
		assertTrue(swingOverHold(net, ws, knockedUpCharged) < -2.5f);
		float[] comingDownCharged = comingDown.clone();
		comingDownCharged[charge] = 1f;
		assertTrue(swingOverHold(net, ws, comingDownCharged) > 2.5f, "and made on the way down: the punish crit");

		// What the crit model says in the air is changed, and nothing else: who hears of it?
		for (Param p : net.params.params()) {
			if (p.name.equals("crit.air.b") || p.name.startsWith("crit.air") && p.w.length == 7) {
				for (int i = 0; i < p.w.length; i++) {
					p.w[i] += 4f + i;
				}
			}
		}
		boolean[] sprintIsTheCritModels = {false, true, false, true, true};
		boolean[] swingIsTheCritModels = {false, true, true, true, true};
		boolean[] walkIsTheCritModels = {false, true, false, true, false};
		for (int m = 0; m < moments.length; m++) {
			net.forward(ws, peri, fovea, moments[m]);
			assertEquals(sprintIsTheCritModels[m], ws.logits[sprint] != before[m][sprint], "the sprint key, moment " + m);
			assertEquals(swingIsTheCritModels[m], ws.logits[attack + 1] != before[m][attack + 1], "the swing, moment " + m);
			assertEquals(walkIsTheCritModels[m], ws.logits[FORWARD + 2] != before[m][FORWARD + 2], "W/S, moment " + m);
			assertEquals(before[m][0], ws.logits[0], 0f, "the camera is never its business");
		}

		// Learning about those keys in such a moment: only the crit model changes.
		for (float[] moment : new float[][] {comingDown, knockedUp}) {
			net.forward(ws, peri, fovea, moment);
			ParamSet.zero(ws.grads);
			java.util.Arrays.fill(ws.dLogits, 0f);
			ws.dLogits[sprint] = moment == comingDown ? -1f : 0f;
			ws.dLogits[attack + 1] = -1f;
			ws.dLogits[FORWARD + 1] = moment == comingDown ? 1f : 0f;
			net.backward(ws, peri, fovea, moment);
			int touched = 0;
			for (Param p : net.params.params()) {
				boolean any = false;
				for (float g : ws.grads[p.id]) {
					any |= g != 0f;
				}
				if (any) {
					touched++;
					assertTrue(p.name.startsWith("crit."), p.name + " was changed");
				}
			}
			assertTrue(touched >= 4, "its own layers did learn: " + touched);
		}
	}

	/**
	 * A swing in the air takes two: the crit model's "now it would be critical" and the trunk's
	 * "there is a hit to be had". Where the trunk would rather not swing (the opponent is not under
	 * the crosshair, or too far off), the swing is held back, so that the weapon is not thrown away
	 * on the air in front of an opponent out of reach. The trunk can only ever hold it back.
	 */
	@Test
	void inTheAirASwingIsHeldBackWhereTheTrunkSeesNoHit() {
		float[] ready = vec(new Random(4), false);
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 0f;
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_VEL_UP] = -0.2f;
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 1f;
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_SPRINTING] = 0f;
		assertTrue(JumpNudge.critSwingReady(ready));
		ActorNet net = new ActorNet(8L, false, false);
		ActorNet.Workspace ws = net.newWorkspace();

		trunkSwing(net, 0f);
		float undecided = swingOverHold(net, ws, ready);
		assertTrue(undecided > 2.5f, "a trunk with nothing against the swing leaves it as the crit model has it");
		trunkSwing(net, 4f);
		assertEquals(undecided, swingOverHold(net, ws, ready), 1e-6f, "and one that is keen on it makes it no likelier");
		// The less the trunk sees a hit, the more the swing is held back, down to as unlikely as a swing
		// that could not be critical at all, and no further.
		trunkSwing(net, -1f);
		assertEquals(undecided - ActorNet.NO_HIT_GAIN, swingOverHold(net, ws, ready), 1e-5f);
		trunkSwing(net, -3f);
		float held = swingOverHold(net, ws, ready);
		assertEquals(undecided - ActorNet.NO_HIT_MOST, held, 1e-5f);
		assertTrue(held < -2.5f, "nineteen ticks in twenty it does not swing at an opponent the trunk cannot hit");
		trunkSwing(net, -8f);
		assertEquals(held, swingOverHold(net, ws, ready), 1e-6f);

		// The crit model can learn its way round the trunk here, and the trunk learns nothing from it.
		net.forward(ws, new byte[ObsSpec.PERI_SIZE], new byte[ObsSpec.FOVEA_SIZE], ready);
		ParamSet.zero(ws.grads);
		java.util.Arrays.fill(ws.dLogits, 0f);
		ws.dLogits[ActionSpace.OFFSETS[ActionSpace.ATTACK] + 1] = -1f;
		net.backward(ws, new byte[ObsSpec.PERI_SIZE], new byte[ObsSpec.FOVEA_SIZE], ready);
		for (Param p : net.params.params()) {
			float[] g = ws.grads[p.id];
			boolean any = false;
			for (int i = 0; i < g.length; i++) {
				p.w[i] -= 0.5f * g[i];
				any |= g[i] != 0f;
			}
			assertTrue(!any || p.name.startsWith("crit."), p.name + " was changed");
		}
		assertTrue(swingOverHold(net, ws, ready) > held + 0.3f);
	}

	/** The jump key starting over is the crit model starting over. */
	@Test
	void reopeningTheJumpStartsTheCritModelOver() {
		ActorNet net = new ActorNet(6L, false, false);
		ActorNet.Workspace ws = net.newWorkspace();
		float[] v = vec(new Random(3), false);
		for (Param p : net.params.params()) {
			if (p.name.startsWith("crit.out")) {
				java.util.Arrays.fill(p.w, 0.7f);
			}
		}
		net.forward(ws, new byte[ObsSpec.PERI_SIZE], new byte[ObsSpec.FOVEA_SIZE], v);
		assertNotEquals(2f, ws.logits[JUMP] - ws.logits[JUMP + 1], 1e-3f);
		net.reopenHead(ActionSpace.JUMP, 2f);
		net.forward(ws, new byte[ObsSpec.PERI_SIZE], new byte[ObsSpec.FOVEA_SIZE], v);
		assertEquals(2f, ws.logits[JUMP], 1e-6f);
		assertEquals(0f, ws.logits[JUMP + 1], 1e-6f);
		// What it does in the air starts over with it: W left alone, sprint key up, whatever it had learned.
		for (Param p : net.params.params()) {
			if (p.name.equals("crit.air.b")) {
				assertEquals(3f, p.w[0]);
				assertEquals(0f, p.w[1]);
				assertEquals(4f, p.w[3]);
				assertEquals(0f, p.w[4]);
			}
			if (p.name.equals("crit.air.w")) {
				for (float w : p.w) {
					assertEquals(0f, w);
				}
			}
		}
	}

	/** A network saved before it had a part loads as it was, and the part added since stays new. */
	@Test
	void aNetworkSavedBeforeAPartWasAddedLoadsAndKeepsThePartNew() throws Exception {
		ParamSet old = new ParamSet();
		Param a = old.add("a", 3);
		a.w[0] = 1f;
		a.w[2] = 3f;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			old.write(out);
		}
		ParamSet now = new ParamSet();
		Param a2 = now.add("a", 3);
		Param added = now.add("added", 2);
		added.w[1] = 9f;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			now.read(in);
		}
		assertEquals(1, now.missing());
		assertEquals(1f, a2.w[0]);
		assertEquals(3f, a2.w[2]);
		assertEquals(9f, added.w[1], "left as it was made");

		// The other way round, a file with more than the network has, is still refused.
		ByteArrayOutputStream more = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(more)) {
			now.write(out);
		}
		ParamSet smaller = new ParamSet();
		smaller.add("a", 3);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(more.toByteArray()))) {
			org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> smaller.read(in));
		}
	}
}
