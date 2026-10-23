package dev.swordai.core.env;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelayTest {
	@Test
	void handsThingsOnSoManyTicksLate() {
		Delay<Integer> d = new Delay<>(8);
		// No delay: what goes in comes out.
		for (int i = 0; i < 5; i++) {
			assertEquals(i, d.pass(i));
		}
		d.clear();
		d.setTicks(3);
		// Until three ticks have gone by, the oldest thing known is all there is.
		assertEquals(10, d.pass(10));
		assertEquals(10, d.pass(11));
		assertEquals(10, d.pass(12));
		assertEquals(10, d.pass(13));
		assertEquals(11, d.pass(14));
		for (int i = 15; i < 60; i++) {
			assertEquals(i - 3, d.pass(i));
		}
		// A new start: nothing from before it arrives late.
		d.clear();
		assertEquals(100, d.pass(100));
		assertEquals(100, d.pass(101));

		// Never longer than it was built for, never less than nothing.
		d.setTicks(50);
		assertEquals(8, d.ticks());
		d.setTicks(-2);
		assertEquals(0, d.ticks());
		assertEquals(7, d.pass(7));
	}
}
