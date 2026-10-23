"""19 Oct morning: crit back in the opponent's cooldown (JumpIn.COUNTER), meet a jump-crit that is coming even
while being knocked back (MeetJump), and the share of rehearsal sent to the hard kinds (Config.selfCritShare)."""
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
    '\tpublic boolean punishInCombo = false;\n',
    '\tpublic boolean punishInCombo = false;\n'
    '\t/**\n'
    '\t * Crit back: with the opponent\'s sword on its way back (it has just swung, hit or miss) and its own\n'
    '\t * sword nearly back, an opponent at the edge of the reach is jumped at and hit on the way down, a\n'
    '\t * critical hit in the opponent\'s cooldown; see {@code JumpIn.COUNTER}. Since 19 October: players\n'
    '\t * who space it crit it and sprint-hit it, and it never critted back.\n'
    '\t */\n'
    '\tpublic boolean counterCritRule = true;\n'
    '\t/**\n'
    '\t * An opponent jumping in for a crit is met (see {@code MeetJump}) also when it is the opponent alone\n'
    '\t * that is coming, the fighter still being carried back by a hit: before 19 October only when the two\n'
    '\t * were closing fast, which a hit taken a moment before ruled out on half the jumps.\n'
    '\t */\n'
    '\tpublic boolean meetWhenComing = true;\n'
    '\t/**\n'
    '\t * In self-play, the share of the scripted fights that goes to the stage with the opponents it\n'
    '\t * still loses to (the crit stage and its kinds: spacer, timer, jumper, punisher...). The rest is\n'
    '\t * shared by the earlier stages, whose opponents it beats four rounds in five. 0.5 up to 19 October.\n'
    '\t */\n'
    '\tpublic float selfCritShare = 0.5f;\n')])

edit('core/src/main/java/dev/swordai/core/curriculum/Curriculum.java', [(
    '\tpublic int pickEpisodeStage(Random rng, float rehearsalProbability) {\n',
    '\tpublic int pickEpisodeStage(Random rng, float rehearsalProbability) {\n'
    '\t\treturn pickEpisodeStage(rng, rehearsalProbability, SELF_CRIT_SHARE);\n'
    '\t}\n\n'
    '\t/** @param critShare in self-play, the share of the scripted fights that goes to the crit stage */\n'
    '\tpublic int pickEpisodeStage(Random rng, float rehearsalProbability, float critShare) {\n'), (
    '\t\tif (critJumper >= 0 && rng.nextFloat() < SELF_CRIT_SHARE) {\n',
    '\t\tif (critJumper >= 0 && rng.nextFloat() < critShare) {\n')])

edit('core/src/main/java/dev/swordai/core/rl/MeetJump.java', [(
    '\t/** No farther off than a running jump carries, as {@link JumpIn#distance} reads it. */\n',
    '\t/** Or the opponent itself is coming at it by this much a tick, whatever the fighter is doing. */\n'
    '\tstatic final float COMING = 0.08f;\n'
    '\t/** No farther off than a running jump carries, as {@link JumpIn#distance} reads it. */\n'), (
    '\tpublic static boolean applies(float[] vec) {\n'
    '\t\tif (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {\n'
    '\t\t\treturn false;\n'
    '\t\t}\n'
    '\t\t// The opponent\'s own speed up or down: what the fighter sees of it, less its own.\n'
    '\t\tfloat up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;\n'
    '\t\tfloat closing = -scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) / BodyState.VELOCITY_SCALE;\n'
    '\t\treturn up >= RISING && closing >= CLOSING && JumpIn.distance(vec) <= WITHIN;\n'
    '\t}\n',
    '\tpublic static boolean applies(float[] vec) {\n'
    '\t\treturn applies(vec, false);\n'
    '\t}\n\n'
    '\t/**\n'
    '\t * @param coming also when only the opponent is coming ({@link #COMING}): the fighter carried back by\n'
    '\t *               a hit it took a moment ago is not closing, and that is when a jump-crit comes in\n'
    '\t */\n'
    '\tpublic static boolean applies(float[] vec, boolean coming) {\n'
    '\t\tif (vec[ObsSpec.VEC_STATS + ObsSpec.ST_ANY_VISIBLE] < 0.5f || scalar(vec, ObsSpec.SC_ATTACK_CHARGE) < CHARGE) {\n'
    '\t\t\treturn false;\n'
    '\t\t}\n'
    '\t\t// The opponent\'s own speed up or down: what the fighter sees of it, less its own.\n'
    '\t\tfloat up = (scalar(vec, ObsSpec.SC_REL_VEL_UP) + scalar(vec, ObsSpec.SC_VEL_UP)) / BodyState.VELOCITY_SCALE;\n'
    '\t\treturn up >= RISING && approaching(vec, coming) && JumpIn.distance(vec) <= WITHIN;\n'
    '\t}\n\n'
    '\tprivate static boolean approaching(float[] vec, boolean coming) {\n'
    '\t\tfloat closing = -scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) / BodyState.VELOCITY_SCALE;\n'
    '\t\t// The opponent\'s own speed towards the fighter: the closing, less the fighter\'s own part of it.\n'
    '\t\tfloat toward = -(scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) + scalar(vec, ObsSpec.SC_VEL_FORWARD)) / BodyState.VELOCITY_SCALE;\n'
    '\t\treturn closing >= CLOSING || coming && toward >= COMING;\n'
    '\t}\n'), (
    '\tpublic static int how(float[] vec) {\n',
    '\tpublic static int how(float[] vec) {\n'
    '\t\treturn how(vec, false);\n'
    '\t}\n\n'
    '\t/** @param coming see {@link #applies(float[], boolean)} */\n'
    '\tpublic static int how(float[] vec, boolean coming) {\n'), (
    '\t\treturn -scalar(vec, ObsSpec.SC_REL_VEL_FORWARD) / BodyState.VELOCITY_SCALE >= CLOSING ? 4 : 3;\n',
    '\t\treturn approaching(vec, coming) ? 4 : 3;\n')])

edit('core/src/main/java/dev/swordai/core/rl/JumpIn.java', [(
    '\tpublic static final int HOP = 5;\n',
    '\tpublic static final int HOP = 5;\n'
    '\t/**\n'
    '\t * Crit back: the opponent has just swung (hit or miss) and its sword is on its way back, the\n'
    '\t * fighter\'s is nearly back, and the opponent is at the edge of the reach. A running jump at it,\n'
    '\t * and the critical hit on the way down lands before that sword is back; see {@link #counterCrit}.\n'
    '\t */\n'
    '\tpublic static final int COUNTER = 6;\n'), (
    '\tstatic final int HOP_HEADING_TICKS = 2;\n',
    '\tstatic final int HOP_HEADING_TICKS = 2;\n'
    '\t/** Crit back only with the opponent\'s sword this far back at most: swung five ticks ago or less. */\n'
    '\tstatic final float COUNTER_OPP_MOST = 0.4f;\n'
    '\t/** Its own sword this far back at least: all the way back by the way down, seven ticks on. */\n'
    '\tstatic final float COUNTER_CHARGE = 0.55f;\n'
    '\t/** Not from nearer than this: there the hit to make is the one in reach now. */\n'
    '\tstatic final float COUNTER_FROM = 2.6f;\n'), (
    '\tprivate boolean hops;\n',
    '\tprivate boolean hops;\n'
    '\t/** See {@link #counterCrit}. */\n'
    '\tprivate boolean counter;\n'
    '\tprivate int counterOff;\n'
    '\tprivate int counterBurns;\n'), (
    '\tpublic void approachHops(boolean on) {\n'
    '\t\thops = on;\n'
    '\t}\n',
    '\tpublic void approachHops(boolean on) {\n'
    '\t\thops = on;\n'
    '\t}\n\n'
    '\t/**\n'
    '\t * Whether it crits back ({@link #COUNTER}): with the opponent\'s sword just spent and its own nearly\n'
    '\t * back, a running jump at an opponent at the edge of the reach and the critical hit on the way down.\n'
    '\t * Players who space it crit it from the edge and sprint-hit it, and before 19 October nothing it\n'
    '\t * did came back at them in their cooldown with a crit. Hit in the air of one, it leaves them alone\n'
    '\t * for a while, as with the chase.\n'
    '\t */\n'
    '\tpublic void counterCrit(boolean on) {\n'
    '\t\tcounter = on;\n'
    '\t}\n'), (
    '\t\thopOff = 0;\n'
    '\t\thopBurns = 0;\n'
    '\t\tunhitTicks = FACE_UNHIT_TICKS;\n',
    '\t\thopOff = 0;\n'
    '\t\thopBurns = 0;\n'
    '\t\tcounterOff = 0;\n'
    '\t\tcounterBurns = 0;\n'
    '\t\tunhitTicks = FACE_UNHIT_TICKS;\n'), (
    '\t\tint hopBurnsWere = hopBurns;\n'
    '\t\treset();\n',
    '\t\tint hopBurnsWere = hopBurns;\n'
    '\t\tint counterWas = counterOff;\n'
    '\t\tint counterBurnsWere = counterBurns;\n'
    '\t\treset();\n'
    '\t\tcounterOff = counterWas;\n'
    '\t\tcounterBurns = counterBurnsWere;\n'), (
    '\t\tif (hopOff > 0 && --hopOff == 0 && hopBurns >= HOP_BURNS_LONG) {\n'
    '\t\t\thopBurns = 0;\n'
    '\t\t}\n',
    '\t\tif (hopOff > 0 && --hopOff == 0 && hopBurns >= HOP_BURNS_LONG) {\n'
    '\t\t\thopBurns = 0;\n'
    '\t\t}\n'
    '\t\tif (counterOff > 0 && --counterOff == 0 && counterBurns >= 2) {\n'
    '\t\t\tcounterBurns = 0;\n'
    '\t\t}\n'), (
    '\t\t\t\t} else if (kind == HOP) {\n'
    '\t\t\t\t\thopOff = ++hopBurns >= HOP_BURNS_LONG ? HOP_OFF_LONG_TICKS : HOP_OFF_TICKS;\n',
    '\t\t\t\t} else if (kind == HOP) {\n'
    '\t\t\t\t\thopOff = ++hopBurns >= HOP_BURNS_LONG ? HOP_OFF_LONG_TICKS : HOP_OFF_TICKS;\n'
    '\t\t\t\t} else if (kind == COUNTER) {\n'
    '\t\t\t\t\tcounterOff = ++counterBurns >= 2 ? OFF_FOR_GOOD_TICKS : CHASE_OFF_TICKS;\n'), (
    '\t\tif (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE\n',
    '\t\t// Crit back: the opponent\'s sword has just been spent, its own is nearly back.\n'
    '\t\tif (counter && counterOff <= 0 && scalar(vec, ObsSpec.SC_OPP_CHARGE) <= COUNTER_OPP_MOST && charge >= COUNTER_CHARGE\n'
    '\t\t\t\t&& side <= SIDE_MOST && d >= COUNTER_FROM\n'
    '\t\t\t\t&& d <= Math.min(CHASE_TO_MOST, CHASE_TO + CHASE_TICKS_CLOSING * Math.max(0f, closing))) {\n'
    '\t\t\tstart(COUNTER);\n'
    '\t\t\treturn JUMP | RUN | NO_SWING;\n'
    '\t\t}\n'
    '\t\tif (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE\n')])

edit('core/src/main/java/dev/swordai/core/curriculum/Tally.java', [(
    '\tpublic long chaseRuleJumps;\n'
    '\tpublic long chaseRuleCrits;\n',
    '\tpublic long chaseRuleJumps;\n'
    '\tpublic long chaseRuleCrits;\n'
    '\t/** Crit-back jumps ({@code JumpIn.COUNTER}) and the critical hits they ended in. */\n'
    '\tpublic long counterRuleJumps;\n'
    '\tpublic long counterRuleCrits;\n')])
p = ROOT + 'core/src/main/java/dev/swordai/core/curriculum/Tally.java'
s = io.open(p, encoding='utf-8').read()
old = '\t\tchaseRuleCrits += t.chaseRuleCrits;\n'
assert s.count(old) == 2
s = s.replace(old, old + '\t\tcounterRuleJumps += t.counterRuleJumps;\n\t\tcounterRuleCrits += t.counterRuleCrits;\n')
old = '\t\tchaseRuleCrits = 0;\n'
assert s.count(old) == 1
s = s.replace(old, old + '\t\tcounterRuleJumps = 0;\n\t\tcounterRuleCrits = 0;\n')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('edited Tally adds/clear')

edit('core/src/main/java/dev/swordai/core/curriculum/RewardModel.java', [(
    '\t\t\t} else if (jumpRule == JumpIn.FACE) {\n'
    '\t\t\t\ttally.faceRuleJumps++;\n',
    '\t\t\t} else if (jumpRule == JumpIn.COUNTER) {\n'
    '\t\t\t\ttally.counterRuleJumps++;\n'
    '\t\t\t} else if (jumpRule == JumpIn.FACE) {\n'
    '\t\t\t\ttally.faceRuleJumps++;\n'), (
    '\t\t\t\t\t} else if (jumpRule == JumpIn.FACE) {\n'
    '\t\t\t\t\t\ttally.faceRuleCrits++;\n',
    '\t\t\t\t\t} else if (jumpRule == JumpIn.COUNTER) {\n'
    '\t\t\t\t\t\ttally.counterRuleCrits++;\n'
    '\t\t\t\t\t} else if (jumpRule == JumpIn.FACE) {\n'
    '\t\t\t\t\t\ttally.faceRuleCrits++;\n')])

edit('core/src/main/java/dev/swordai/core/rl/Fighter.java', [(
    '\t\tthis.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f)\n',
    '\t\tthis.jumpIn = jumps && (cfg.chaseRule || cfg.faceRule || cfg.uppercutChance > 0f || cfg.counterCritRule)\n'), (
    '\t\t\tjumpIn.approachHops(cfg.hopRule);\n',
    '\t\t\tjumpIn.approachHops(cfg.hopRule);\n'
    '\t\t\tjumpIn.counterCrit(cfg.counterCritRule);\n'), (
    'MeetJump.applies(obs.vec) && (jumpIn == null',
    'MeetJump.applies(obs.vec, meetWhenComing) && (jumpIn == null'), (
    '\t\tthis.punishInCombo = cfg.punishInCombo;\n',
    '\t\tthis.punishInCombo = cfg.punishInCombo;\n'
    '\t\tthis.meetWhenComing = cfg.meetWhenComing;\n'), (
    '\tprivate final boolean punishInCombo;\n',
    '\tprivate final boolean punishInCombo;\n'
    '\tprivate final boolean meetWhenComing;\n')])

edit('src/client/java/dev/swordai/client/Trainer.java', [(
    '\t\tmade.approachHops(cfg.hopRule);\n',
    '\t\tmade.approachHops(cfg.hopRule);\n'
    '\t\tmade.counterCrit(cfg.counterCritRule);\n'), (
    'meetHow[MeetJump.how(obs.vec)]++;',
    'meetHow[MeetJump.how(obs.vec, cfg.meetWhenComing)]++;'), (
    '&& MeetJump.applies(obs.vec) && (jumpIn == null',
    '&& MeetJump.applies(obs.vec, cfg.meetWhenComing) && (jumpIn == null'), (
    'curriculum.pickEpisodeStage(rng, cfg.rehearsalProbability)',
    'curriculum.pickEpisodeStage(rng, cfg.rehearsalProbability, cfg.selfCritShare)'), (
    '+ "resets %d, step back ticks %d, hit after its own hit %d, crits taken %d, own combos %d of %d hits, hops %d, damage %.0f dealt / %.0f taken, rounds %d won %d lost",\n',
    '+ "resets %d, step back ticks %d, hit after its own hit %d, crits taken %d, own combos %d of %d hits, hops %d, damage %.0f dealt / %.0f taken, rounds %d won %d lost, "\n'
    '\t\t\t\t\t\t+ "crit-back jumps %d (crit %d)",\n'), (
    't.damageDealt, t.damageTaken, t.roundsWon, t.roundsLost);\n',
    't.damageDealt, t.damageTaken, t.roundsWon, t.roundsLost,\n'
    '\t\t\t\tt.counterRuleJumps, t.counterRuleCrits);\n')])
