package dev.swordai.core;

import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.report.UpdateRecord;
import dev.swordai.core.rl.UpdateStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportTest {
	@Test
	void historyWrittenBeforeTheNewColumnsIsStillRead() {
		Tally t = new Tally();
		t.steps = 512;
		t.onTargetTicks = 256;
		t.mixTicks = 100;
		t.mixFollowed = 80;
		t.plannedClicks = 10;
		t.timedClicks = 9;
		t.clicks = 12;
		t.spamClicks = 3;
		t.rechargeTicks = 200;
		t.rechargeCalmTicks = 100;
		t.readyTicks = 300;
		t.readyOnTargetTicks = 225;
		t.hitsDealt = 40;
		t.critHits = 4;
		t.punishCrits = 3;
		t.critsTaken = 9;
		t.punishChances = 6;
		t.jumpsEnded = 40;
		t.jumpCrits = 10;
		t.uppercuts = 2;
		t.sweepHits = 8;
		t.combosTaken = 10;
		t.comboBreaks = 4;
		t.longCombosTaken = 6;
		t.wins = 6;
		t.losses = 2;
		t.draws = 2;
		t.closeHits = 10;
		t.closeSprintHits = 7;
		t.comboEscapes = 3;
		t.hitsToAnswer = 20;
		t.answers = 13;
		t.comboedHits = 20;
		t.comboedHitsFollowed = 5;
		t.backTicks = 100;
		t.backHeld = 60;
		t.jumps = 45;
		UpdateStats stats = new UpdateStats();
		UpdateRecord r = UpdateRecord.of(1L, 9000L, 17L, 6, 0f, t, stats);
		r.speed = 7.5f;
		r.headless = true;
		r.opponent = UpdateRecord.OPPONENT_FIGHT;
		String line = r.toCsv();
		assertEquals(UpdateRecord.CSV_HEADER.split(",").length, line.split(",").length);

		UpdateRecord back = UpdateRecord.fromCsv(line);
		assertNotNull(back);
		assertEquals(0.8f, back.mixStrafe, 1e-4f);
		assertEquals(0.9f, back.mixTiming, 1e-4f);

		assertEquals(0.25f, back.spamRate, 1e-4f);
		assertEquals(0.5f, back.rechargeCalm, 1e-4f);
		assertEquals(0.75f, back.readyOnTarget, 1e-4f);

		assertEquals(0.1f, back.critShare, 1e-4f);
		assertEquals(0.5f, back.punishRate, 1e-4f);

		assertEquals(0.05f, back.uppercutShare, 1e-4f);
		assertEquals(0.2f, back.sweepShare, 1e-4f);
		assertEquals(0.4f, back.breakRate, 1e-4f);
		assertEquals(0.5f, back.escapeRate, 1e-4f);
		assertEquals(0.7f, back.closeSprint, 1e-4f);
		assertEquals(0.2f, back.drawRate, 1e-4f);
		assertEquals(t.combosTaken, back.combosTaken);
		assertTrue(back.combosTaken > 0);
		assertEquals(7.5f, back.speed, 1e-4f);
		assertTrue(back.headless);
		assertEquals(0.65f, back.answerRate, 1e-4f);
		assertEquals(UpdateRecord.OPPONENT_FIGHT, back.opponent);
		assertEquals(0.6f, back.backRate, 1e-4f);
		assertEquals(0.75f, back.outRate, 1e-4f);
		assertEquals(t.jumpsPerMinute(), back.jumpsPerMinute, 1e-2f);
		assertTrue(back.jumpsPerMinute > 0f);
		assertEquals(0.25f, back.jumpCritRate, 1e-4f);

		// A row as 0.12.3, 0.7.0, 0.6.1, 0.5.2, 0.5.0, 0.4.8, 0.4.6, 0.4.5, 0.4.2, 0.4.1, 0.4.0, 0.2.5, 0.2.2, 0.2.1, 0.2.0 and 0.1.4
		// wrote it: one, two, four, five, six, eight, nine, ten, eleven, twelve, thirteen, fifteen, seventeen, nineteen,
		// twenty and twenty-two columns fewer.
		String header = UpdateRecord.CSV_HEADER;
		for (int missing : new int[] {1, 2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 15, 17, 19, 20, 22}) {
			String old = line;
			String oldHeader = header;
			for (int i = 0; i < missing; i++) {
				old = old.substring(0, old.lastIndexOf(','));
				oldHeader = oldHeader.substring(0, oldHeader.lastIndexOf(','));
			}
			assertTrue(UpdateRecord.readableHeader(oldHeader), oldHeader);
			UpdateRecord upgraded = UpdateRecord.fromCsv(old);
			assertNotNull(upgraded);
			assertEquals(9000L, upgraded.totalSteps);
			assertEquals(0.5f, upgraded.onTarget, 1e-4f);
			assertEquals(missing < 22 ? 0.8f : 0f, upgraded.mixStrafe, 1e-4f);
			assertEquals(missing < 20 ? 0.25f : 0f, upgraded.spamRate, 1e-4f);
			assertEquals(missing < 19 ? 0.5f : 0f, upgraded.rechargeCalm, 1e-4f);
			assertEquals(missing < 16 ? 0.5f : 0f, upgraded.punishRate, 1e-4f);
			assertEquals(missing < 14 ? 0.2f : 0f, upgraded.sweepShare, 1e-4f);
			assertEquals(missing < 13 ? 0.4f : 0f, upgraded.breakRate, 1e-4f);
			assertEquals(missing < 12 ? 0.5f : 0f, upgraded.escapeRate, 1e-4f);
			assertEquals(missing < 11 ? 0.7f : 0f, upgraded.closeSprint, 1e-4f);
			assertEquals(missing < 10 ? 0.2f : 0f, upgraded.drawRate, 1e-4f);
			assertEquals(missing < 9 ? t.combosTaken : 0L, upgraded.combosTaken);
			// A row from before there was a speed was gathered at normal speed, on the screen.
			assertEquals(missing < 8 ? 7.5f : 1f, upgraded.speed, 1e-4f);
			assertEquals(missing < 7, upgraded.headless);
			assertEquals(missing < 6 ? 0.65f : 0f, upgraded.answerRate, 1e-4f);
			// And a row from before it could learn from people was fought against the stage's own opponent.
			assertEquals(missing < 5 ? UpdateRecord.OPPONENT_FIGHT : UpdateRecord.OPPONENT_STAGE, upgraded.opponent);
			assertEquals(missing < 4 ? 0.6f : 0f, upgraded.backRate, 1e-4f, "nothing was known of this before 0.7.0");
			assertEquals(missing < 3 ? 0.75f : 0f, upgraded.outRate, 1e-4f);
			assertEquals(missing < 2 ? t.jumpsPerMinute() : 0f, upgraded.jumpsPerMinute, 1e-2f, "nor of this before 0.8.0");
			assertEquals(0f, upgraded.jumpCritRate, 1e-4f, "nor of what came of its jumps before 0.13.0");
		}
		assertTrue(UpdateRecord.readableHeader(header));
		assertFalse(UpdateRecord.readableHeader("epoch_ms,total_steps"), "too old to read");
		assertFalse(UpdateRecord.readableHeader(header.replace("on_head", "head")), "different columns");

		assertNull(UpdateRecord.fromCsv(header));
		assertNull(UpdateRecord.fromCsv("1,2,3"));
	}
}
