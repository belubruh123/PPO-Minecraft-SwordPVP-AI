import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
R = 'D:/swordai/core/src/main/java/dev/swordai/core/'
e = Ed(R + 'rl/AimRule.java')
e.rep("""	static final float SIDE_SPEED = 0.04f;
""", """	static final float SIDE_SPEED = 0.04f;
	/** With {@link #level}: the aim stays this share of the opponent's height inside its top and bottom. */
	static final float LEVEL_MARGIN = 0.1f;
""")
e.rep("""	private int yawBin;
	private int pitchBin;
""", """	private int yawBin;
	private int pitchBin;
	/** See {@link #level}. */
	private boolean level;
""")
e.rep("""	public void reset() {
		blindDir = 0;
	}
""", """	public void reset() {
		blindDir = 0;
	}

	/**
	 * Whether the camera is brought level with the fighter's own eye where the opponent's body spans that height, and
	 * onto the nearest part of it where it does not, instead of onto the middle of it; see {@code Config.aimLevel}. The
	 * game's reach is measured along the crosshair: from the eye down to the middle of an opponent on the same ground at
	 * the edge of the reach that ray is some 0.07 blocks longer than the level one, which is the margin by which the
	 * scripted opponents, aiming level, out-reached the fighter in the traces of 22 October.
	 */
	public void level(boolean on) {
		level = on;
	}
""")
e.rep("""			// The picture's y is up, the pitch is down.
			float pitchErr = -degrees(vec[at + ObsSpec.ST_ANY_Y]);
			if (far) {""", """			// The picture's y is up, the pitch is down.
			float pitchErr = -degrees(vec[at + ObsSpec.ST_ANY_Y]);
			if (level && !far) {
				// Level with its own eye (the horizon is as far above the crosshair as the camera looks down), kept within the opponent.
				float top = degrees(vec[at + ObsSpec.ST_TOP]);
				float bottom = degrees(vec[at + ObsSpec.ST_BOTTOM]);
				float margin = LEVEL_MARGIN * Math.max(0f, top - bottom);
				float horizon = vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_PITCH] * 90f;
				pitchErr = -Math.max(bottom + margin, Math.min(top - margin, horizon));
			}
			if (far) {""")
e.save()
e = Ed(R + 'config/Config.java')
e.rep("""	public boolean edgeCritRule = false;
""", """	public boolean edgeCritRule = false;
	/**
	 * The aim rule ({@code AimRule}) brings the camera level with the fighter's own eye on the opponent (where its body spans
	 * that height) instead of onto the middle of it: the shortest ray to it, so the most reach. Since 22 October.
	 */
	public boolean aimLevel = false;
""")
e.save()
e = Ed(R + 'rl/Fighter.java')
e.rep("""		this.aimRule = (afterHit || farRest || cfg.lostRule) && camera ? new AimRule(cfg.aimResponse) : null;""",
      """		this.aimRule = (afterHit || farRest || cfg.lostRule) && camera ? new AimRule(cfg.aimResponse) : null;
		if (aimRule != null) {
			aimRule.level(cfg.aimLevel);
		}""")
e.save()
e = Ed('D:/swordai/src/client/java/dev/swordai/client/Trainer.java')
e.rep("""				aimRule = new AimRule(cfg.aimResponse);""", """				aimRule = new AimRule(cfg.aimResponse);
				aimRule.level(cfg.aimLevel);""")
e.save()
