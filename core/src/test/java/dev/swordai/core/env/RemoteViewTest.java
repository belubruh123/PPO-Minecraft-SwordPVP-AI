package dev.swordai.core.env;

import dev.swordai.core.vision.PlayerPose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteViewTest {
	private static void tick(RemoteView view, double x, double z) {
		view.tick(x, 64, z, 0f, 0f, 0f, 0f, false, true, true);
	}

	@Test
	void itTrailsARunningPlayerAndMovesEvenly() {
		RemoteView view = new RemoteView();
		double speed = 0.28;
		double x = 0;
		tick(view, x, 0);
		double smallestStep = Double.MAX_VALUE;
		double largestStep = 0;
		for (int i = 0; i < 60; i++) {
			x += speed;
			tick(view, x, 0);
			if (i >= 20) {
				smallestStep = Math.min(smallestStep, view.movedX());
				largestStep = Math.max(largestStep, view.movedX());
			}
		}
		double behind = x - view.now().x;
		assertTrue(behind > speed && behind < speed * 4, "a few ticks behind: " + behind / speed);
		// The real player's position arrives every second tick; the copy still never stands or jumps.
		assertTrue(smallestStep > speed * 0.4 && largestStep < speed * 1.6, smallestStep + " .. " + largestStep);
		assertEquals(1f, view.now().walkSpeed, 0.05f, "its legs are in full swing");
	}

	@Test
	void itArrivesWhenThePlayerStops() {
		RemoteView view = new RemoteView();
		tick(view, 0, 0);
		for (int i = 0; i < 10; i++) {
			tick(view, 3, -2);
		}
		assertEquals(3.0, view.now().x, 1e-6);
		assertEquals(-2.0, view.now().z, 1e-6);
		for (int i = 0; i < 20; i++) {
			tick(view, 3, -2);
		}
		assertEquals(0.0, view.movedX(), 1e-9);
		assertTrue(view.now().walkSpeed < 0.01f, "and its legs come to rest");
	}

	@Test
	void aFrameBetweenTicksShowsItPartWayAndAfterAResetItIsSimplyThere() {
		RemoteView view = new RemoteView();
		tick(view, 0, 0);
		for (int i = 0; i < 6; i++) {
			tick(view, 6, 0);
		}
		tick(view, 0, 0);
		tick(view, 0, 0);
		PlayerPose half = new PlayerPose();
		view.pose(0.5f, half);
		assertEquals(view.now().x - view.movedX() * 0.5, half.x, 1e-9);
		PlayerPose whole = new PlayerPose();
		view.pose(1f, whole);
		assertEquals(view.now().x, whole.x, 1e-9);

		view.reset();
		tick(view, 10, 10);
		assertEquals(10.0, view.now().x, 1e-9);
		assertEquals(0.0, view.movedX(), 1e-9);
	}

	@Test
	void turningGoesTheShortWayAndASwingRunsOnToItsEnd() {
		RemoteView view = new RemoteView();
		view.tick(0, 64, 0, 170f, 170f, 0f, 0f, false, true, true);
		for (int i = 0; i < 8; i++) {
			view.tick(0, 64, 0, -170f, -170f, 0f, 5f / 6f, false, true, true);
		}
		float yaw = view.now().headYaw;
		assertTrue(Math.abs(yaw - 190f) < 0.01f || Math.abs(yaw + 170f) < 0.01f, "through 180, not back through 0: " + yaw);

		// The swing has ended (back to 0): between the two ticks it is drawn finishing, not rewinding.
		view.tick(0, 64, 0, -170f, -170f, 0f, 0f, false, true, true);
		PlayerPose mid = new PlayerPose();
		view.pose(0.5f, mid);
		assertTrue(mid.attackTime > 5f / 6f && mid.attackTime <= 1f, "" + mid.attackTime);
	}
}
