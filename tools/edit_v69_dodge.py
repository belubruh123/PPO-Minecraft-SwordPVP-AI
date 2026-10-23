"""19 Oct 07:45: out of the way of a jump-crit coming in while its own sword is not back (MeetJump.dodges, Config.dodgeJumpRule)."""
import io

ROOT = 'D:/swordai/'


def edit(path, pairs):
    p = ROOT + path
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:80], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('edited', path)


edit('core/src/main/java/dev/swordai/core/config/Config.java', [(
    '\tpublic boolean meetWhenComing = true;\n',
    '\tpublic boolean meetWhenComing = true;\n'
    '\t/**\n'
    '\t * An opponent jumping in at it while its own sword is not back (it cannot meet that jump with a hit):\n'
    '\t * it holds S for that jump, so the crit comes down out of reach, and comes in again once its sword is\n'
    '\t * back; see {@code MeetJump.dodges}. Since 19 October: on half the jumps made at it its sword was not\n'
    '\t * back, and those were the crits it took.\n'
    '\t */\n'
    '\tpublic boolean dodgeJumpRule = true;\n')])

edit('core/src/main/java/dev/swordai/core/rl/MeetJump.java', [(
    '\tprivate MeetJump() {\n',
    '\t/** It gets out of the way of a jump made at it from no farther off than this. */\n'
    '\tstatic final float DODGE_WITHIN = 4.5f;\n\n'
    '\tprivate MeetJump() {\n'), (
    '\tprivate static boolean approaching(float[] vec, boolean coming) {\n',
    '\t/**\n'
    '\t * Whether this is a tick to get out of the way of an opponent coming at it through the air: on its way\n'
    '\t * up, coming, near, and the fighter\'s sword not back, so that it cannot be met ({@link #applies}). S is\n'
    '\t * held for it: a jump cannot change where it is going, and walking backwards for the six ticks of the\n'
    '\t * way up keeps the fighter as far off as the jump carries the opponent in, so the critical hit comes\n'
    '\t * down out of reach, or is not made. By the time the opponent can jump again the sword is back, and\n'
    '\t * that jump is met.\n'
    '\t *\n'
    '\t * <p>That is spacing as the players who space it do it: out of reach while the other sword is ready\n'
    '\t * and its own is not, in again once it is. Before 19 October nothing did this, and on half the jumps\n'
    '\t * made at it its sword was not back: the crit landed, and the sprint hit after it.\n'
    '\t */\n'
    '\tpublic static boolean dodges(float[] vec, boolean coming) {\n'
    '\t\tif (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) >= CHARGE) {\n'
    '\t\t\treturn false;\n'
    '\t\t}\n'
    '\t\tfloat up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;\n'
    '\t\treturn up >= RISING && approaching(vec, coming) && JumpIn.distance(vec) <= DODGE_WITHIN;\n'
    '\t}\n\n'
    '\tprivate static boolean approaching(float[] vec, boolean coming) {\n')])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [(
    '\tprivate final boolean meetWhenComing;\n',
    '\tprivate final boolean meetWhenComing;\n'
    '\tprivate final boolean dodgeJump;\n'), (
    '\t\tthis.meetWhenComing = cfg.meetWhenComing;\n',
    '\t\tthis.meetWhenComing = cfg.meetWhenComing;\n'
    '\t\tthis.dodgeJump = cfg.dodgeJumpRule && cfg.answerRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD);\n'), (
    '\t\tboolean busy = owes || backOut || reset != 0;\n',
    '\t\t// An opponent jumping in at it with its own sword not back: out of the way of that jump; see MeetJump.dodges.\n'
    '\t\tboolean dodge = dodgeJump && !meet && reset == 0 && !letGo && swingMinCharge > 0f && ActionSpace.unlocked(keys, ActionSpace.FORWARD)\n'
    '\t\t\t\t&& (jumpIn == null || jumpIn.kind() == JumpIn.NONE) && MeetJump.dodges(obs.vec, meetWhenComing);\n'
    '\t\tif (dodge) {\n'
    '\t\t\tkeys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);\n'
    '\t\t}\n'
    '\t\tboolean busy = owes || backOut || reset != 0 || dodge;\n'), (
    '\t\tif (meet) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = 1;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 1;\n'
    '\t\t}\n',
    '\t\tif (dodge) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = BackNudge.BACK;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n'
    '\t\tif (meet) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = 1;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 1;\n'
    '\t\t}\n'), (
    '| (straight ? 1 << 9 : 0);\n',
    '| (straight ? 1 << 9 : 0) | (dodge ? 1 << 10 : 0);\n')])

edit('src/client/java/dev/swordai/client/Trainer.java', [(
    '\t\tint step = cfg.stepBackRule && !episodeLockedDrill && stage.unlocked(ActionSpace.JUMP) ? stepBack.plan(obs.vec) : 0;\n',
    '\t\tint step = cfg.stepBackRule && !episodeLockedDrill && stage.unlocked(ActionSpace.JUMP) ? stepBack.plan(obs.vec) : 0;\n'
    '\t\t// An opponent jumping in at it with its own sword not back: out of the way of that jump; see MeetJump.dodges.\n'
    '\t\tboolean dodge = cfg.dodgeJumpRule && cfg.answerRule && !episodeLockedDrill && !meet && reset == 0 && !letGo && swingMinCharge() > 0f\n'
    '\t\t\t\t&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && (jumpIn == null || jumpIn.kind() == JumpIn.NONE)\n'
    '\t\t\t\t&& MeetJump.dodges(obs.vec, cfg.meetWhenComing);\n'
    '\t\tif (dodge) {\n'
    '\t\t\tkeys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);\n'
    '\t\t\tdodgeTicks++;\n'
    '\t\t}\n'), (
    'jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0 || meet, dealtRow);',
    'jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0 || meet || dodge, dealtRow);'), (
    '\t\tif (owes || backOut || reset != 0 || meet || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE\n',
    '\t\tif (owes || backOut || reset != 0 || meet || dodge || jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE\n'), (
    '\t\tif (meet) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = 1;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 1;\n'
    '\t\t\tif (owes && answer) {\n',
    '\t\tif (dodge) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = BackNudge.BACK;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n'
    '\t\tif (meet) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = 1;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 1;\n'
    '\t\t\tif (owes && answer) {\n'), (
    'sword not back {}, not closing {}) | opponent under the crosshair',
    'sword not back {}, not closing {}), stepped out of one with its sword not back on {} ticks | opponent under the crosshair'), (
    'meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3], pickTicks,',
    'meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3], dodgeTicks, pickTicks,'), (
    '\t\tmeetTicks = 0;\n',
    '\t\tmeetTicks = 0;\n'
    '\t\tdodgeTicks = 0;\n'), (
    '\tprivate int meetSwings;\n',
    '\tprivate int meetSwings;\n'
    '\t/** Ticks it held S out of the way of a jump made at it, its sword not back; see MeetJump.dodges. */\n'
    '\tprivate int dodgeTicks;\n')])

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = io.open(p, encoding='utf-8').read()
i = s.rstrip().rfind('}')
add = '''
	/** A jump made at it with its own sword not back: it gets out of the way instead of meeting it. */
	@Test
	void aJumpCritComingInWithItsSwordNotBackIsSteppedOutOf() {
		float[] v = vec(true, 3.6f, 0.4f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.25f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.applies(v, true));
		assertTrue(MeetJump.dodges(v, true));
		// With the sword back it is met instead; one coming down, going away or far off is no jump at it.
		float[] ready = v.clone();
		ready[ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] = 1f;
		assertFalse(MeetJump.dodges(ready, true));
		assertTrue(MeetJump.applies(ready, true));
		float[] down = v.clone();
		down[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = -0.2f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.dodges(down, true));
		float[] away = v.clone();
		away[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = 0.4f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.dodges(away, true));
		float[] far = vec(true, 7f, 0.4f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_UP] = 0.3f * BodyState.VELOCITY_SCALE;
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_REL_VEL_FORWARD] = -0.25f * BodyState.VELOCITY_SCALE;
		assertFalse(MeetJump.dodges(far, true));
	}
'''
s = s[:i] + add + s[i:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('edited JumpInTest')
