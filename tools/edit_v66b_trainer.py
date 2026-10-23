ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, n in pairs:
        assert s.count(a) == n, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [
('''		if (straight) {
			keys &= ~ActionSpace.mask(ActionSpace.STRAFE);
			straightTicks++;
		}
''', '''		if (straight) {
			keys &= ~ActionSpace.mask(ActionSpace.STRAFE);
			straightTicks++;
		}
		// Nor a swing at an opponent plainly out of reach; see SwingGate.outOfReach.
		if (cfg.whiffGuard && !episodeLockedDrill && SwingGate.outOfReach(obs.vec)) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
''', 1),
# The hard opponents: the shares weighted by the rounds lost to each kind lately.
('''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare, stage.peopleShare, stage.spacerShare, stage.whifferShare};
			float upTo = 0f;''', '''					stage.jumperShare, stage.punisherShare, stage.backerShare, stage.timerShare, stage.peopleShare, stage.spacerShare, stage.whifferShare};
			if (cfg.hardOpponents) {
				hardWeights(shares);
			}
			float upTo = 0f;''', 1),
('''		logFights();
		tally.clear();''', '''		logFights();
		noteKindResults();
		tally.clear();''', 1),
('''	private final int[] walkedIn = new int[4];
''', '''	private final int[] walkedIn = new int[4];
	/**
	 * The share of rounds it has been losing to each kind of scripted opponent lately (by kind, smoothed
	 * over updates; a half until there is anything to go by). See {@code Config.hardOpponents}.
	 */
	private final float[] kindLoss = initialKindLoss();
	/** No kind comes up less than this share of what the stage gives it, however it fares against it. */
	static final float HARD_FLOOR = 0.35f;
	static final float HARD_CAP = 2f;
	private static final String[] KIND_NAMES = {"plain", "trader", "runner", "circler", "corner", "no-knockback", "odd", "jumper",
			"punisher", "backer", "timer", "people", "spacer", "whiffer"};
''', 1),
('''	void beginEpisode(Truth truth) {
		spaceLastReach = 99f;''', '''	private static float[] initialKindLoss() {
		float[] f = new float[KIND_WHIFFER + 1];
		java.util.Arrays.fill(f, 0.5f);
		return f;
	}

	/** How much more often a kind it loses this share of its rounds to comes up: twice at all lost, a third at none. */
	static float hardWeight(float loss) {
		return Math.max(HARD_FLOOR, Math.min(HARD_CAP, loss / 0.5f));
	}

	/** The stage's shares of the scripted kinds, weighted by the rounds lost to each lately, with the same total. */
	private void hardWeights(float[] shares) {
		float before = 0f;
		float after = 0f;
		for (int i = 0; i < shares.length; i++) {
			before += shares[i];
			shares[i] *= hardWeight(kindLoss[i + 1]);
			after += shares[i];
		}
		if (after > 0f) {
			for (int i = 0; i < shares.length; i++) {
				shares[i] *= before / after;
			}
		}
	}

	private Tally kindTally(int kind) {
		return switch (kind) {
			case KIND_TRADER -> traderTally;
			case KIND_RUNNER -> runnerTally;
			case KIND_CIRCLER -> circlerTally;
			case KIND_CORNER -> cornerTally;
			case KIND_NO_KNOCK -> noKnockTally;
			case KIND_ODD -> oddTally;
			case KIND_JUMPER -> jumperTally;
			case KIND_PUNISHER -> punisherTally;
			case KIND_BACKER -> backerTally;
			case KIND_TIMER -> timerTally;
			case KIND_PERSON -> personTally;
			case KIND_SPACER -> spacerTally;
			case KIND_WHIFFER -> whifferTally;
			default -> null;
		};
	}

	/** At each update, before the tallies are cleared: the rounds lost to each kind go into {@link #kindLoss}. */
	private void noteKindResults() {
		StringBuilder line = new StringBuilder();
		for (int k = KIND_TRADER; k <= KIND_WHIFFER; k++) {
			Tally t = kindTally(k);
			long won = t.roundsWon + (k == KIND_SPACER ? standerTally.roundsWon : 0);
			long lost = t.roundsLost + (k == KIND_SPACER ? standerTally.roundsLost : 0);
			if (won + lost >= 2) {
				kindLoss[k] = 0.85f * kindLoss[k] + 0.15f * lost / (float) (won + lost);
			}
			line.append(String.format(Locale.ROOT, " %s %.2f", KIND_NAMES[k], kindLoss[k]));
		}
		if (cfg.hardOpponents) {
			SwordAi.LOGGER.info("rounds lost lately, by kind (each comes up that much more or less often):{}", line);
		}
	}

	void beginEpisode(Truth truth) {
		spaceLastReach = 99f;''', 1),
])

# ---- tests ------------------------------------------------------------------------------------
open(ROOT + 'core/src/test/java/dev/swordai/core/rl/SwingGateTest.java', 'w', encoding='utf-8', newline='').write('''package dev.swordai.core.rl;

import dev.swordai.core.vision.ViewGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No swing before the sword is back, nor at an opponent plainly out of reach ({@link SwingGate}). */
class SwingGateTest {
	/** The opponent this far away, straight ahead. */
	private static float[] at(float distance) {
		float[] v = new float[ObsSpec.VEC];
		v[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 1f;
		float height = ViewGeometry.apparentSize(1.8f, distance);
		v[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 0.1f * height;
		v[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] = -0.9f * height;
		return v;
	}

	@Test
	void theSwordHasToBeBack() {
		int all = ActionSpace.mask(ActionSpace.ATTACK, ActionSpace.FORWARD);
		assertEquals(ActionSpace.mask(ActionSpace.FORWARD), SwingGate.keys(all, 0.9f, 0.96f));
		assertEquals(all, SwingGate.keys(all, 1f, 0.96f));
	}

	@Test
	void noSwingAtAnOpponentPlainlyOutOfReach() {
		assertFalse(SwingGate.outOfReach(at(2.5f)), "in reach");
		assertFalse(SwingGate.outOfReach(at(3.2f)), "at the edge of reach it may still come in");
		assertTrue(SwingGate.outOfReach(at(4.6f)), "knocked back out of reach: a swing at nothing");
		assertTrue(SwingGate.outOfReach(at(6f)));
		float[] gone = at(2.5f);
		gone[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] = 0f;
		assertTrue(SwingGate.outOfReach(gone), "nowhere on the screen");
		// In its face only part of it is on the screen: short there, but not far.
		float[] cut = at(5f);
		cut[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] = 1f;
		assertFalse(SwingGate.outOfReach(cut));
	}
}
''')
print('night ok')
