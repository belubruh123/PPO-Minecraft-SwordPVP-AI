package dev.swordai.core.school;

import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceTest {
	private static final int T10 = Technique.T10.ordinal();

	/** The teacher's target: W held and a jump, on the W/S and jump heads. */
	private static float[] target() {
		float[] t = new float[ActionSpace.TOTAL_LOGITS];
		int fwd = ActionSpace.OFFSETS[ActionSpace.FORWARD];
		t[fwd + 1] = 1f;
		t[ActionSpace.OFFSETS[ActionSpace.JUMP] + 1] = 1f;
		return t;
	}

	/** The share of taught keys pressed, each tick of a moment; and a done moment counted once, for the technique reward. */
	@Test
	void matchPerTickAndMomentsDone() {
		Performance p = new Performance(null);
		int heads = 1 << ActionSpace.FORWARD | 1 << ActionSpace.JUMP;
		float[] target = target();
		int[] both = new int[ActionSpace.HEADS];
		both[ActionSpace.FORWARD] = 1;
		both[ActionSpace.JUMP] = 1;
		int[] half = new int[ActionSpace.HEADS];
		half[ActionSpace.FORWARD] = 1;
		p.tick(T10, heads, target, both, ActionSpace.ALL_HEADS, 0f, 0f);
		assertEquals(1f, p.lastMatch);
		assertEquals(T10, p.lastTechnique);
		p.tick(T10, heads, target, half, ActionSpace.ALL_HEADS, 0f, 0f);
		assertEquals(0.5f, p.lastMatch);
		for (int i = 0; i < 4; i++) {
			p.tick(T10, heads, target, both, ActionSpace.ALL_HEADS, 0f, 0f);
		}
		// The moment ends; its damage counts for a while yet, then it is judged: 5 of 6 ticks all pressed, done.
		p.tick(-1, 0, target, both, ActionSpace.ALL_HEADS, 0f, 0f);
		assertEquals(-1f, p.lastMatch);
		for (int i = 0; i < Performance.AFTER + 1; i++) {
			p.tick(-1, 0, target, both, ActionSpace.ALL_HEADS, 0f, 0f);
		}
		assertEquals(1, p.takeDone(T10));
		assertEquals(0, p.takeDone(T10), "taken once");
		// No damage either way: it paid (not a loss), it won nothing.
		assertEquals(1, p.takePaid(T10));
		assertEquals(0, p.takeWon(T10));
		// A moment done with a hit landed and none taken: paid and won; one with only a hit taken: neither.
		for (int i = 0; i < 4; i++) {
			p.tick(T10, heads, target, both, ActionSpace.ALL_HEADS, i == 2 ? 5f : 0f, 0f);
		}
		for (int i = 0; i < Performance.AFTER + 2; i++) {
			p.tick(-1, 0, target, both, ActionSpace.ALL_HEADS, 0f, 0f);
		}
		for (int i = 0; i < 4; i++) {
			p.tick(T10, heads, target, both, ActionSpace.ALL_HEADS, 0f, i == 1 ? 5f : 0f);
		}
		for (int i = 0; i < Performance.AFTER + 2; i++) {
			p.tick(-1, 0, target, both, ActionSpace.ALL_HEADS, 0f, 0f);
		}
		assertEquals(2, p.takeDone(T10));
		assertEquals(1, p.takePaid(T10));
		assertEquals(1, p.takeWon(T10));
	}
}
