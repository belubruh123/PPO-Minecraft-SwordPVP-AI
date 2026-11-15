package dev.swordai.core.pvp;

import java.util.Random;

/**
 * Agent 49 (16 Nov; the user, 15 Nov 22:50: "the bot is either too perfect so you need obscure way to win and thus making AI worse
 * ... to AI it thinks the way to beat ppl is how it beat bot"): a person's limits for the technique bots, drawn from the user's real
 * rounds (122 rounds of 13-15 Nov, the profile's fights folder) against the checks' per-hit log (hits.csv, the same definitions).
 *
 * <p>Where the bots were superhuman, each from {@link PvpPlayer}:
 * <ul>
 * <li>the distance: every swing came the tick the other's hitbox crossed its line ({@code d.reach <= REACH - closeIn}), read off exact
 * positions (late only by the view lag), and the S-tap's spacing too; real players misjudge it: 27% of their swings within 3.6 blocks
 * hit nothing (the bots 5%: "made to miss" 3788 of 77837 swings on 15 Nov), and their hits land at 1.53 blocks (bots 2.6-2.7);</li>
 * <li>the click: the swing on the very tick the sword is back to Style#swingCharge (0.9-1.0); real players click early (30% of their
 * swings with the sword under 0.9 back, p10 0.80) or late (ticks between their close swings p50 14, 39% 17+);</li>
 * <li>the reaction: one fixed number a fight, a tick of jitter (PvpPlayer#later); a person's varies from one reaction to the next;</li>
 * <li>adaptation: the specialists played their technique whatever happened (Style#adapts only for the mixer and the varied bots), so
 * a move that the AI punished every time came again every time, and the AI could win by one trick no person falls for twice.</li>
 * </ul>
 * With {@link #HUMAN_BOTS} on, a bot's style is {@link #humanize}d where its fight starts (TechniqueBot#start): its techniques and
 * their chances stay as they are (the document's moves); only these limits, and the adaptation, are added. The school's teacher never
 * goes through here.
 */
public final class Human {
	/** On: the arena's technique bots fight with a person's limits and adapt (TechniqueBot#start). Off: the bots exactly as before. */
	public static final boolean HUMAN_BOTS = true;

	/** A swing's misjudged distance is clipped to this, blocks either way. */
	static final double DIST_ERR_MOST = 1.4;
	/** Early clicks come at a charge between these. */
	static final float EARLY_LEAST = 0.70f;
	static final float EARLY_MOST = 0.90f;
	/** A late click is at most this many ticks after the sword is back. */
	static final int LATE_MOST = 8;
	/** The spread of one reaction around the fight's own (a log-normal factor: about 0.64x to 1.57x for p10 to p90). */
	static final double REACT_SPREAD = 0.35;
	/** The failure memory: each outcome fades the old ones by this; a technique failing this share of its recent tries is cut. */
	static final float MEMORY_FADE = 0.8f;
	static final float FAIL_SHARE = 0.6f;
	static final float MEMORY_LEAST = 2.4f;
	/** The least a technique that keeps failing is still tried at, of its chance. */
	static final float CUT_LEAST = 0.3f;
	/** Ticks after a plan's start in which the first hit either way decides it. */
	static final int OUTCOME_TICKS = 30;

	private Human() {
	}

	/** Whether a style gets the limits: not the school's teacher (never a TechniqueBot), not one already humanized. */
	public static boolean applies(Style s) {
		return HUMAN_BOTS && !s.human;
	}

	/**
	 * A copy of {@code s} with a person's limits drawn for this fight from {@code seed} (its own stream: the style's other draws and the
	 * player's own random stream are as they were). The comboable bots (Style#stunAfterHit, #backAfterHit) keep their scaffold: the
	 * limits, but no adaptation, which would give them the jump resets and counters they are built without.
	 */
	public static Style humanize(Style s, long seed) {
		Style h = s.copy();
		Random r = new Random(seed ^ 0x48554D414E5FL);
		h.human = true;
		// Plus: it takes them for farther than they are and walks in closer before it swings; minus: it swings from too far.
		h.distBias = -0.1f + 0.4f * r.nextFloat();
		h.distSd = 0.35f + 0.3f * r.nextFloat();
		h.earlyClick = 0.15f + 0.3f * r.nextFloat();
		h.lateClick = 0.5f + 1.5f * r.nextFloat();
		h.humanAdapts = s.stunAfterHit <= 0 && s.backAfterHit <= 0;
		return h;
	}

	/** One reaction, ticks: the fight's own ({@code base}) times a person's spread from one reaction to the next; at least one. */
	static int reaction(int base, Random r) {
		return Math.max(1, (int) Math.round(Math.max(1, base) * Math.exp(REACT_SPREAD * r.nextGaussian())));
	}

	/** This window's misjudged distance, blocks (added to the distance it sees). */
	static double distError(Style s, Random r) {
		return Math.max(-DIST_ERR_MOST, Math.min(DIST_ERR_MOST, s.distBias + s.distSd * r.nextGaussian()));
	}

	/** The charge this window's click comes at: early (under 0.9) at the style's share, else its own swing charge. */
	static float clickCharge(Style s, Random r) {
		return r.nextFloat() < s.earlyClick ? EARLY_LEAST + (EARLY_MOST - EARLY_LEAST) * r.nextFloat() : s.swingCharge;
	}

	/** Ticks this window's click comes after the sword is back to its click charge (0 for an early click). */
	static int lateTicks(Style s, float clickCharge, Random r) {
		if (clickCharge < s.swingCharge) {
			return 0;
		}
		return Math.min(LATE_MOST, (int) Math.floor(-Math.log(1.0 - r.nextDouble()) * s.lateClick));
	}
}
