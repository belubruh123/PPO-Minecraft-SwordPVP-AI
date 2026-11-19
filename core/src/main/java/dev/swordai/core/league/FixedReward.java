package dev.swordai.core.league;

/**
 * The fixed reward of SwordAI plus strictly policy-invariant potential-based shaping (Ng et al. 1999):
 * <ul>
 *   <li>Base reward: {@code +1} win, {@code -1} loss, {@code +0.02 * (damageDealt - damageTaken)}.</li>
 *   <li>Potential shaping: {@code gamma * phi(s') - phi(s)} with {@code phi(terminal) = 0},
 *       depending purely on game state (never on pressing keys). By Ng et al. (1999) Theorem 1,
 *       adding {@code gamma * phi(s') - phi(s)} cannot change the optimal policy.</li>
 * </ul>
 */
public final class FixedReward {
	public static final float WIN = 1.0f;
	public static final float LOSS = -1.0f;
	public static final float HP_SCALE = 0.02f;
	public static final float GAMMA = 0.99f;

	private static final float PHI_HP = 0.15f;
	private static final float PHI_COMBO = 0.04f;
	private static final float PHI_CHARGE = 0.04f;
	private static final float CLOSE_RANGE = 4.5f;

	private FixedReward() {
	}

	/** Base unshaped reward for one tick. */
	public static float base(float damageDealt, float damageTaken, boolean koDealt, boolean koTaken) {
		float r = HP_SCALE * (damageDealt - damageTaken);
		if (koDealt && !koTaken) {
			r += WIN;
		} else if (koTaken && !koDealt) {
			r += LOSS;
		}
		return r;
	}

	/**
	 * State potential {@code phi(s)} over purely observable fight state (health margin, active combo
	 * control, and close-range sword readiness). Returns {@code 0} on terminal states as required
	 * for policy invariance.
	 */
	public static float potential(float ownHpFrac, float oppHpFrac, int ownCombo, int oppCombo,
			float ownCharge, float oppCharge, float dist, boolean terminal) {
		if (terminal) {
			return 0f;
		}
		float comboDiff = Math.max(-4f, Math.min(4f, ownCombo - oppCombo));
		float chargeEdge = dist < CLOSE_RANGE ? (ownCharge - oppCharge) : 0f;
		return PHI_HP * (ownHpFrac - oppHpFrac) + PHI_COMBO * comboDiff + PHI_CHARGE * chargeEdge;
	}

	/** Base reward plus Ng et al. potential shaping {@code GAMMA * phiNext - phiPrev}. */
	public static float step(float damageDealt, float damageTaken, boolean koDealt, boolean koTaken,
			float phiPrev, float phiNext) {
		return base(damageDealt, damageTaken, koDealt, koTaken) + GAMMA * phiNext - phiPrev;
	}
}
