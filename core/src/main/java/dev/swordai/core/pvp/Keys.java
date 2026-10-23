package dev.swordai.core.pvp;

/**
 * What a fighter does on one tick: the movement keys, the jump, the swing, where it looks, and which
 * technique (if any) chose them. Written by {@link PvpPlayer}; carried out by whatever drives the body.
 */
public final class Keys {
	/** +1 W, -1 S, 0 neither. */
	public int forward;
	/** -1 A (left), +1 D (right), 0 neither. */
	public int strafe;
	public boolean sprint;
	public boolean jump;
	public boolean attack;
	/** Look at the opponent (the driver aims, with its own turn rate and noise); otherwise at {@link #yaw}/{@link #pitch}. */
	public boolean lookAtThem = true;
	public float yaw;
	public float pitch;
	/** The technique in charge of these keys, or {@link Technique#BASE}. */
	public Technique technique = Technique.BASE;
	/**
	 * Which keys the technique chose itself, as {@code KEY_} bits; the rest are the ordinary fight's. A
	 * teacher only pulls the learner's keys the technique chose.
	 */
	public int taught;

	public static final int KEY_MOVE = 1;
	public static final int KEY_STRAFE = 2;
	public static final int KEY_SPRINT = 4;
	public static final int KEY_JUMP = 8;
	public static final int KEY_ATTACK = 16;

	public void clear() {
		forward = 0;
		strafe = 0;
		sprint = false;
		jump = false;
		attack = false;
		lookAtThem = true;
		yaw = 0f;
		pitch = 0f;
		technique = Technique.BASE;
		taught = 0;
	}

	public void copyFrom(Keys o) {
		forward = o.forward;
		strafe = o.strafe;
		sprint = o.sprint;
		jump = o.jump;
		attack = o.attack;
		lookAtThem = o.lookAtThem;
		yaw = o.yaw;
		pitch = o.pitch;
		technique = o.technique;
		taught = o.taught;
	}

	/** For traces: W/S, A/D, sprint, jump, swing, e.g. {@code "W.rJ."}. */
	public String brief() {
		return "" + (forward > 0 ? 'W' : forward < 0 ? 'S' : '.') + (strafe < 0 ? 'A' : strafe > 0 ? 'D' : '.') + (sprint ? 'r' : '.')
				+ (jump ? 'J' : '.') + (attack ? 'X' : '.');
	}
}
