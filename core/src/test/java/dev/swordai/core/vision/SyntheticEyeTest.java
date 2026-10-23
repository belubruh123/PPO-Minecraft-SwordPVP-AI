package dev.swordai.core.vision;

import dev.swordai.core.rl.ObsSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticEyeTest {
	private static final double EYE = 1.62;

	/** One model unit in blocks. */
	private static final float UNIT = 0.9375f / 16f;
	/** The arms sway all the time; at this age they hang straight down. */
	private static final float ARMS_STRAIGHT = (float) (Math.PI / 0.09);

	private static MaskFrame look(float yaw, float pitch, double x, double y, double z, float bodyYaw) {
		MaskFrame frame = new MaskFrame();
		new SyntheticEye().look(0, EYE, 0, yaw, pitch, x, y, z, bodyYaw, bodyYaw, 0f, frame);
		return frame;
	}

	private static PlayerPose facingCamera(double distance) {
		PlayerPose pose = new PlayerPose().standing(0, 0, distance, 180f, 180f, 0f);
		pose.age = ARMS_STRAIGHT;
		pose.holdsItem = false;
		return pose;
	}

	private static MaskFrame look(PlayerPose pose) {
		MaskFrame frame = new MaskFrame();
		new SyntheticEye().look(0, EYE, 0, 0f, 0f, pose, frame);
		return frame;
	}

	@Test
	void anOpponentStraightAheadIsDrawnWhereTheGameWouldDrawIt() {
		// Yaw 0 looks along +Z; the opponent faces back at the camera, arms straight down.
		MaskFrame f = look(facingCamera(4));
		float[] s = f.stats;
		assertTrue(f.enemyVisible());
		assertEquals(1f, s[ObsSpec.ST_HEAD_VISIBLE]);
		assertEquals(0f, s[ObsSpec.ST_HEAD_X], 0.01f);
		assertEquals(0f, s[ObsSpec.ST_ANY_X], 0.01f);

		// The model's origin is 1.501 blocks above the feet; the hat reaches 8.5 units above it and the
		// trouser legs 24.25 below, all at 15/16 scale.
		float depth = 4f - 2.25f * UNIT;
		float headDepth = 4f - 4.5f * UNIT;
		float topOfHat = (1.501f + 8.5f / 16f) * 0.9375f;
		float soles = (1.501f - 24.25f / 16f) * 0.9375f;
		assertEquals(ViewGeometry.apparentSize((float) (topOfHat - EYE), headDepth), s[ObsSpec.ST_TOP], 0.015f);
		assertEquals(ViewGeometry.apparentSize((float) (soles - EYE), depth), s[ObsSpec.ST_BOTTOM], 0.015f);
		// Body and arms with their sleeves are 16.5 model units across.
		float halfWidth = ViewGeometry.apparentSize(8.25f * UNIT, depth);
		assertEquals(halfWidth, s[ObsSpec.ST_RIGHT], 0.015f);
		assertEquals(-halfWidth, s[ObsSpec.ST_LEFT], 0.015f);
		// The head sits a little above eye level.
		float headMiddle = (1.501f + 4f / 16f) * 0.9375f;
		assertEquals(ViewGeometry.apparentSize((float) (headMiddle - EYE), headDepth), s[ObsSpec.ST_HEAD_Y], 0.02f);
		assertTrue(f.headSamples > 100 && f.bodySamples > f.headSamples);
	}

	@Test
	void itMovesLikeTheGamesModel() {
		MaskFrame still = look(facingCamera(4));
		float stillHeight = still.stats[ObsSpec.ST_TOP] - still.stats[ObsSpec.ST_BOTTOM];

		// At a run the legs swing far forward and back: the feet come off the ground line, so the
		// figure is shorter on screen than the same player standing.
		PlayerPose running = facingCamera(4);
		running.walkSpeed = 1f;
		running.walkPos = 0f;
		MaskFrame stride = look(running);
		float strideHeight = stride.stats[ObsSpec.ST_TOP] - stride.stats[ObsSpec.ST_BOTTOM];
		assertTrue(strideHeight < stillHeight * 0.95f, stillHeight + " standing vs " + strideHeight + " in full stride");
		assertEquals(still.stats[ObsSpec.ST_TOP], stride.stats[ObsSpec.ST_TOP], 0.01f, "the head stays where it is");
		// A quarter of a stride later the legs pass each other and it is as tall as standing.
		running.walkPos = (float) (Math.PI / 2 / 0.6662);
		MaskFrame passing = look(running);
		assertEquals(stillHeight, passing.stats[ObsSpec.ST_TOP] - passing.stats[ObsSpec.ST_BOTTOM], 0.02f);

		// Crouching brings the head down.
		PlayerPose crouched = facingCamera(4);
		crouched.crouching = true;
		assertTrue(look(crouched).stats[ObsSpec.ST_TOP] < still.stats[ObsSpec.ST_TOP] - 0.03f);

		// Mid-swing the sword arm comes up and across; the outline changes but the player stays put.
		PlayerPose swinging = facingCamera(4);
		swinging.holdsItem = true;
		swinging.attackTime = 0.3f;
		MaskFrame swing = look(swinging);
		assertEquals(still.stats[ObsSpec.ST_HEAD_X], swing.stats[ObsSpec.ST_HEAD_X], 0.02f);
		assertTrue(swing.bodySamples != still.bodySamples);

		// The swaying arms make it a little wider than with the arms straight down.
		PlayerPose swayed = facingCamera(4);
		swayed.age = 0f;
		MaskFrame wide = look(swayed);
		assertTrue(wide.stats[ObsSpec.ST_RIGHT] > still.stats[ObsSpec.ST_RIGHT] + 0.003f);
	}

	@Test
	void theRightArmIsOnThePlayersRight() {
		// The opponent faces the camera, so its right arm is on the camera's left. Lifting that arm
		// forward (an item in hand) only changes that side of the picture.
		PlayerPose empty = facingCamera(3);
		PlayerPose armed = facingCamera(3);
		armed.holdsItem = true;
		MaskFrame a = look(empty);
		MaskFrame b = look(armed);
		int leftChanged = 0;
		int rightChanged = 0;
		for (int row = 0; row < ObsSpec.PERI_H; row++) {
			for (int col = 0; col < ObsSpec.PERI_W; col++) {
				int i = (row * ObsSpec.PERI_W + col) * ObsSpec.CHANNELS + ObsSpec.CH_BODY;
				if (a.peri[i] != b.peri[i]) {
					if (col < ObsSpec.PERI_W / 2) {
						leftChanged++;
					} else {
						rightChanged++;
					}
				}
			}
		}
		assertTrue(leftChanged > 0, "the raised arm shows");
		assertEquals(0, rightChanged, "the other side is untouched");

		// Turning its head to its own right moves the face towards the camera's left.
		PlayerPose turned = facingCamera(3);
		turned.headYaw = 180f + 60f;
		assertTrue(look(turned).stats[ObsSpec.ST_HEAD_X] <= a.stats[ObsSpec.ST_HEAD_X] + 0.005f);
		// Looking down tips the face down: the head's middle drops on screen.
		PlayerPose nodding = facingCamera(3);
		nodding.headPitch = 60f;
		assertTrue(look(nodding).stats[ObsSpec.ST_HEAD_Y] < a.stats[ObsSpec.ST_HEAD_Y]);
	}

	@Test
	void itAgreesWithTheCameraMathsAboutLeftRightAndBehind() {
		// With yaw 0 the camera's right is -X.
		MaskFrame right = look(0f, 0f, -2, 0, 4, 180f);
		assertTrue(right.stats[ObsSpec.ST_ANY_X] > 0.3f);
		MaskFrame left = look(0f, 0f, 2, 0, 4, 180f);
		assertTrue(left.stats[ObsSpec.ST_ANY_X] < -0.3f);
		assertEquals(right.stats[ObsSpec.ST_ANY_X], -left.stats[ObsSpec.ST_ANY_X], 0.01f);

		assertFalse(look(0f, 0f, 0, 0, -4, 0f).enemyVisible(), "behind the camera");
		assertFalse(look(90f, 0f, 0, 0, 4, 0f).enemyVisible(), "off to the side, outside the view");

		// Turning the camera onto it brings it to the centre, wherever it stands.
		float yaw = ViewGeometry.yawTo(-3, 3);
		MaskFrame turned = look(yaw, 0f, -3, 0, 3, 0f);
		assertEquals(0f, turned.stats[ObsSpec.ST_ANY_X], 0.02f);

		// Looking down moves it up the screen.
		MaskFrame down = look(0f, 20f, 0, 0, 4, 180f);
		assertTrue(down.stats[ObsSpec.ST_HEAD_Y] > 0.3f);
	}

	@Test
	void sideOnItIsNarrowerAndTheHeadStaysInFrontOfTheBodyBehindIt() {
		MaskFrame front = look(0f, 0f, 0, 0, 4, 180f);
		MaskFrame side = look(0f, 0f, 0, 0, 4, 90f);
		float frontWidth = front.stats[ObsSpec.ST_RIGHT] - front.stats[ObsSpec.ST_LEFT];
		float sideWidth = side.stats[ObsSpec.ST_RIGHT] - side.stats[ObsSpec.ST_LEFT];
		assertTrue(sideWidth < frontWidth * 0.65f, frontWidth + " vs " + sideWidth);

		// From straight above, the head covers the body's middle: the centre of the picture is head.
		MaskFrame above = new MaskFrame();
		new SyntheticEye().look(0, 6, 0, 0f, 90f, 0, 0, 0, 0f, 0f, 0f, above);
		int centre = (ObsSpec.FOVEA / 2 * ObsSpec.FOVEA + ObsSpec.FOVEA / 2) * ObsSpec.CHANNELS;
		assertEquals(255, above.fovea[centre + ObsSpec.CH_HEAD] & 0xFF);
		assertEquals(0, above.fovea[centre + ObsSpec.CH_BODY] & 0xFF);
		assertTrue(above.bodySamples > 0, "the shoulders still show around it");
	}

	@Test
	void standingInsideTheOpponentDoesNotBreakIt() {
		MaskFrame f = look(0f, 0f, 0, 0, 0.2, 0f);
		assertTrue(f.enemyVisible());
		assertTrue(f.stats[ObsSpec.ST_ANY_AREA] > 0.5f, "it fills most of the view");
	}

	@Test
	void atTheTrainedFieldOfViewItMatchesWhatTheRealGameDrew() {
		// Measured in the real game by the self-test, at FOV 70 in an 854x480 window: the opponent
		// 3 and 6 blocks straight ahead was 0.97 and 0.47 high, its head 4422 and 1024 pixels.
		try {
			ViewGeometry.setFov(70f);
			float toRealPixels = (480f / SyntheticEye.HEIGHT) * (480f / SyntheticEye.HEIGHT);
			MaskFrame near = look(0f, 0f, 0, 0, 3, 180f);
			MaskFrame far = look(0f, 0f, 0, 0, 6, 180f);
			assertEquals(0.97f, near.stats[ObsSpec.ST_TOP] - near.stats[ObsSpec.ST_BOTTOM], 0.03f);
			assertEquals(0.47f, far.stats[ObsSpec.ST_TOP] - far.stats[ObsSpec.ST_BOTTOM], 0.02f);
			assertEquals(4422f, near.headSamples * toRealPixels, 4422f * 0.03f);
			assertEquals(1024f, far.headSamples * toRealPixels, 1024f * 0.03f);
			// The body was measured with the bot wherever its last move had left it turned, so it only
			// has to be close.
			assertEquals(3485f, far.bodySamples * toRealPixels, 3485f * 0.10f);
			assertEquals(0f, near.stats[ObsSpec.ST_HEAD_Y], 0.02f);
		} finally {
			ViewGeometry.setFov(ObsSpec.FOV_DEG);
		}
	}
}
