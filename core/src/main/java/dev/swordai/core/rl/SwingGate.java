package dev.swordai.core.rl;

/**
 * No swing before the weapon is back. Whatever the network says, the attack key is not its to press
 * until the weapon has recharged this far; it then decides whether to swing, as before.
 *
 * <p>A swing made early is never the better swing: it does a fraction of the damage, it cannot knock
 * back or be critical (the game asks for more than nine tenths of the charge for either), and it
 * starts the weapon over, so the proper hit that was a few ticks away is gone as well. In training
 * the network had learned that from what an early swing cost it, and made one swing in ten early.
 * But what it had really learned was a rhythm that held in the arena: the opponent flies off when it
 * is hit and is back in reach about when the weapon is. Against a player on a server with 200 ms
 * each way, the opponent is seen to be knocked back nine or ten ticks after the click and stands in
 * reach, under the crosshair, the whole time the weapon recharges. There it swung again and again:
 * six to seven swings in ten were early (0.13.2), nearly every hit was a weak one, and it lost to a
 * beginner. A rule the game itself never bends is better kept than learned.
 *
 * <p>The line is drawn a tick later than the game needs. On a server the swings arrive a tick apart
 * more or less than they were made, and a swing made on the first tick the client sees the weapon
 * back can reach the server on a tick it does not: no knockback, no critical hit. Twelve ticks
 * after the last swing (0.96 of a sword's 12.5) leaves that tick to spare, at the cost of one tick
 * of rhythm.
 */
public final class SwingGate {
	/** The default for {@code Config.ruleSwingCharge}: a sword twelve ticks after its last swing. Up to 18 October, the network's line too. */
	public static final float MIN_CHARGE = 0.95f;
	/**
	 * The default for {@code Config.swingMinCharge}, since 18 October: eleven ticks after the last
	 * swing, 0.88 on its own screen, which the game makes 0.92 when the swing is made (it adds half a
	 * tick): more than the nine tenths it asks for knockback and a critical hit, and nine tenths of
	 * the damage. The user's words: a hit with the sword 90% back "is considered ok in game". A
	 * player who has found out that it swings the moment its sword is all the way back, and never
	 * before, can time his own hit to come down a tick before that; the tick is the network's to
	 * use or not. Swings a rule makes for it still wait for {@link #MIN_CHARGE}.
	 */
	public static final float EARLY_CHARGE = 0.88f;

	/** An opponent read as farther off than this (blocks, eye to its box) is out of reach. */
	public static final float WHIFF_FROM = 3.5f;

	private SwingGate() {
	}

	/**
	 * The opponent is plainly out of reach, or nowhere on the screen: a swing now hits nothing, starts
	 * the sword over, and hands the opponent a free hit in its cooldown. The user, 18 October, of 0.15.0:
	 * it "swing in air and gets MANY free hit" (14 of its 18 misses were from beyond 3 blocks, most
	 * of them in the air after being knocked back). The distance is read off the opponent's height on
	 * screen, as the network sees it; not with the opponent cut off by the screen's edge, since then it
	 * is close and its height says nothing.
	 */
	public static boolean outOfReach(float[] vec) {
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f) {
			return true;
		}
		if (vec[ObsSpec.VEC_STATS + ObsSpec.ST_TOP] >= StraightIn.EDGE || vec[ObsSpec.VEC_STATS + ObsSpec.ST_BOTTOM] <= -StraightIn.EDGE) {
			return false;
		}
		return JumpIn.distance(vec) > WHIFF_FROM;
	}

	/**
	 * The keys the network has on this tick: {@code headMask}, less the attack key while the weapon
	 * is not back.
	 *
	 * @param charge    how far the weapon has recharged, 0 to 1, as the fighter's own game counts it
	 * @param minCharge the least charge a swing may be made with; zero or less leaves every swing to the network
	 */
	public static int keys(int headMask, float charge, float minCharge) {
		return minCharge > 0f && charge < minCharge ? headMask & ~(1 << ActionSpace.ATTACK) : headMask;
	}
}
