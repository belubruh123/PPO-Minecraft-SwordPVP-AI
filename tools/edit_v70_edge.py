"""19 Oct 09:20: hold at the edge of the reach while the opponent's sword is ready (EdgeHold, Config.edgeHoldRule)."""
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
    '\tpublic boolean dodgeJumpRule = false;\n',
    '\tpublic boolean dodgeJumpRule = false;\n'
    '\t/**\n'
    '\t * Spacing in: with the opponent just out of reach and its sword ready, it does not walk into that\n'
    '\t * reach but stands at the edge and lets the opponent come, going in once that sword has been spent\n'
    '\t * (or after a second of waiting); see {@code EdgeHold}. Since 19 October, on trial in training.\n'
    '\t */\n'
    '\tpublic boolean edgeHoldRule = false;\n')])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [(
    '\tprivate final boolean dodgeJump;\n',
    '\tprivate final boolean dodgeJump;\n'
    '\tprivate final EdgeHold edgeHold;\n'), (
    '\t\tthis.meetWhenComing = cfg.meetWhenComing;\n',
    '\t\tthis.meetWhenComing = cfg.meetWhenComing;\n'
    '\t\tthis.edgeHold = cfg.edgeHoldRule && ActionSpace.unlocked(headMask, ActionSpace.FORWARD) && ActionSpace.unlocked(headMask, ActionSpace.SPRINT)\n'
    '\t\t\t\t? new EdgeHold() : null;\n'), (
    '\t\tboolean busy = owes || backOut || reset != 0 || dodge;\n',
    '\t\t// Just out of reach of an opponent whose sword is ready, it does not walk in; see EdgeHold.\n'
    '\t\tboolean held = edgeHold != null && edgeHold.plan(obs.vec);\n'
    '\t\tboolean hold = held && !owes && !backOut && reset == 0 && !dodge && !meet && !letGo\n'
    '\t\t\t\t&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)\n'
    '\t\t\t\t&& (jumpIn == null || jumpIn.kind() == JumpIn.NONE);\n'
    '\t\tif (hold) {\n'
    '\t\t\tkeys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);\n'
    '\t\t}\n'
    '\t\tboolean busy = owes || backOut || reset != 0 || dodge || hold;\n'), (
    '\t\tif (dodge) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = BackNudge.BACK;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n',
    '\t\tif (dodge) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = BackNudge.BACK;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n'
    '\t\tif (hold) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = 0;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n'), (
    '| (dodge ? 1 << 10 : 0);\n',
    '| (dodge ? 1 << 10 : 0) | (hold ? 1 << 11 : 0);\n'), (
    '\t\tif (stepBack != null) {\n'
    '\t\t\t// The opponent of the next round is the one of the last.\n'
    '\t\t\tstepBack.newRound();\n'
    '\t\t}\n',
    '\t\tif (stepBack != null) {\n'
    '\t\t\t// The opponent of the next round is the one of the last.\n'
    '\t\t\tstepBack.newRound();\n'
    '\t\t}\n'
    '\t\tif (edgeHold != null) {\n'
    '\t\t\tedgeHold.reset();\n'
    '\t\t}\n')])

edit('src/client/java/dev/swordai/client/Trainer.java', [(
    '\tprivate final StepBack stepBack = new StepBack();\n',
    '\tprivate final StepBack stepBack = new StepBack();\n'
    '\tprivate final dev.swordai.core.rl.EdgeHold edgeHold = new dev.swordai.core.rl.EdgeHold();\n'
    '\t/** Ticks it stood at the edge of the reach of an opponent whose sword was ready; see EdgeHold. */\n'
    '\tprivate int edgeTicks;\n'), (
    '\t\tif (dodge) {\n'
    '\t\t\tkeys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);\n'
    '\t\t\tdodgeTicks++;\n'
    '\t\t}\n',
    '\t\tif (dodge) {\n'
    '\t\t\tkeys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);\n'
    '\t\t\tdodgeTicks++;\n'
    '\t\t}\n'
    '\t\t// Just out of reach of an opponent whose sword is ready, it does not walk in; see EdgeHold.\n'
    '\t\tboolean held = cfg.edgeHoldRule && !episodeLockedDrill && edgeHold.plan(obs.vec);\n'
    '\t\tboolean hold = held && !owes && !backOut && reset == 0 && !dodge && !meet && !letGo\n'
    '\t\t\t\t&& ActionSpace.unlocked(keys, ActionSpace.FORWARD) && ActionSpace.unlocked(keys, ActionSpace.SPRINT)\n'
    '\t\t\t\t&& (jumpIn == null || jumpIn.kind() == JumpIn.NONE);\n'
    '\t\tif (hold) {\n'
    '\t\t\tkeys &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);\n'
    '\t\t\tedgeTicks++;\n'
    '\t\t}\n'), (
    'owes || backOut || reset != 0 || meet || dodge, dealtRow);',
    'owes || backOut || reset != 0 || meet || dodge || hold, dealtRow);'), (
    '\t\tif (owes || backOut || reset != 0 || meet || dodge || jump != 0',
    '\t\tif (owes || backOut || reset != 0 || meet || dodge || hold || jump != 0'), (
    '\t\tif (dodge) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = BackNudge.BACK;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n',
    '\t\tif (dodge) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = BackNudge.BACK;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n'
    '\t\tif (hold) {\n'
    '\t\t\taction[ActionSpace.FORWARD] = 0;\n'
    '\t\t\taction[ActionSpace.SPRINT] = 0;\n'
    '\t\t}\n'), (
    'stepped out of one with its sword not back on {} ticks | opponent under the crosshair',
    'stepped out of one with its sword not back on {} ticks, held at the edge of the reach of a ready sword on {} ticks | opponent under the crosshair'), (
    'meetHow[3], dodgeTicks, pickTicks,',
    'meetHow[3], dodgeTicks, edgeTicks, pickTicks,'), (
    '\t\tdodgeTicks = 0;\n',
    '\t\tdodgeTicks = 0;\n'
    '\t\tedgeTicks = 0;\n'), (
    '\t\t\tjumpIn = jumpInVsOthers;\n'
    '\t\t\tjumpIn.reset();\n',
    '\t\t\tjumpIn = jumpInVsOthers;\n'
    '\t\t\tjumpIn.reset();\n'
    '\t\t}\n'
    '\t\tedgeHold.reset();\n'
    '\t\tif (false) {\n')])

p = ROOT + 'core/src/test/java/dev/swordai/core/rl/JumpInTest.java'
s = io.open(p, encoding='utf-8').read()
i = s.rstrip().rfind('}')
add = '''
	/** Just out of reach of a ready sword it does not walk in; it does once that sword is spent, or after a second. */
	@Test
	void itHoldsAtTheEdgeOfAReadySwordAndGoesInOnceItIsSpent() {
		float[] v = vec(true, 3.5f, 1f);
		v[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		EdgeHold rule = new EdgeHold();
		assertTrue(rule.plan(v));
		float[] spent = v.clone();
		spent[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 0.3f;
		assertFalse(rule.plan(spent), "the opponent has swung: in");
		// In reach already, far off, or in its own combo: not held.
		float[] near = vec(true, 2.5f, 1f);
		near[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		assertFalse(EdgeHold.holds(near));
		float[] far = vec(true, 6f, 1f);
		far[ObsSpec.VEC_SCALARS + ObsSpec.SC_OPP_CHARGE] = 1f;
		assertFalse(EdgeHold.holds(far));
		float[] combo = after(v.clone(), 5, 100);
		assertFalse(EdgeHold.holds(combo));
		// It waits a second at most, then goes in.
		EdgeHold waits = new EdgeHold();
		int heldTicks = 0;
		for (int tick = 0; tick < 40; tick++) {
			if (waits.plan(v)) {
				heldTicks++;
			}
		}
		assertEquals(EdgeHold.MOST_TICKS, heldTicks);
	}
'''
s = s[:i] + add + s[i:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('edited JumpInTest')
