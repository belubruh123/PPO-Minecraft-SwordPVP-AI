import io


def load(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    return s.replace('\r\n', '\n'), nl


def save(p, s, nl):
    io.open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', nl))


def rep(s, a, b, n=1):
    assert s.count(a) == n, (s.count(a), a[:90])
    return s.replace(a, b)


root = 'D:/swordai/'

# ---- RewardWeights
p = root + 'core/src/main/java/dev/swordai/core/curriculum/RewardWeights.java'
s, nl = load(p)
s = rep(s, '''	public float comboedHit = 0f;
''', '''	public float comboedHit = 0f;
	/**
	 * Extra cost, once, of the third hit of a combo it is taking, when it was free to do something
	 * about it (see {@code Tally.threeHitCombos}). Two hits in a row is being caught; the third is not
	 * having got out. That is the mark it is held to: out of a combo by its second hit, by a hit of
	 * its own or by getting clear.
	 */
	public float comboThird = 0f;
''')
save(p, s, nl)

# ---- RewardModel
p = root + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java'
s, nl = load(p)
s = rep(s, '''				if (before < 3 && takenStreak >= 3) {
					tally.threeHitCombos++;
				}
''', '''				if (before < 3 && takenStreak >= 3) {
					tally.threeHitCombos++;
					r -= w.comboThird;
				}
''')
save(p, s, nl)

# ---- Config version
p = root + 'core/src/main/java/dev/swordai/core/config/Config.java'
s, nl = load(p)
s = rep(s, 'CURRENT_VERSION = 42;', 'CURRENT_VERSION = 43;')
save(p, s, nl)

# ---- Stages
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Stages.java'
s, nl = load(p)
# combo escape stage: the third hit costs
s = rep(s, '''		// And getting all the way out, then back in with a hit, is worth more than hitting back from inside.
		st.reward.comboEscape = 1.0f;
''', '''		// And getting all the way out, then back in with a hit, is worth more than hitting back from inside.
		st.reward.comboEscape = 1.0f;
		// The mark it is held to: out by the opponent's second hit. A third costs once more, on top.
		st.reward.comboThird = 1.5f;
''')
# crits: keep the lesson of the stage before at full pay, push for S again at the start, drills that begin one or two hits in
s = rep(s, '''		// Fewer drills: getting out of combos is kept up, but the time goes to the new lesson.
		st.drillShare = 0.3f;
		st.drillMinHits = 1;
		st.reward.comboBack = 0.03f;
		st.reward.comboEscape = 0.8f;
''', '''		// Getting out of combos is kept up here, and at what the stage before paid for it. (Up to the
		// first runs of 0.14.0: fewer drills that began up to four hits in, S paid 0.03 a tick and not
		// pushed for. Put on the screen against a copy of itself that had a combo going, the fighter
		// held S on none of the ticks that decide it and was out by the second hit one time in three.
		// What the stage before had taught was gone: of this stage's opponents one does not combo and
		// one cannot be backed away from, so nothing here had kept it up.)
		// The drills begin one or two hits into the copy's combo: whether a third follows is then the
		// fighter's doing, and that is what is counted (Tally.outByTwoRate).
		st.drillShare = 0.35f;
		st.drillMinHits = 1;
		st.drillHits = 2;
		st.reward.comboBack = 0.07f;
		st.reward.comboEscape = 1.0f;
		st.reward.comboThird = 1.5f;
''')
s = rep(s, '''		// This stage's push was for the jump; the one for S was the stage before's.
		st.backNudgeRate = 0f;
		st.backNudgeHoldSteps = 0;
		st.backNudgeSteps = 0;
''', '''		// The push for S, as in the stage before and for the same reason: a key it has stopped pressing
		// is not found to help, however well it is paid. Over before the stage can be passed.
		st.backNudgeRate = 0.6f;
		st.backNudgeHoldSteps = 25_000;
		st.backNudgeSteps = 50_000;
''')
s = rep(s, '''		// (39: the drill and the opponents. 41: every other fight against an opponent on a slow connection.)
		st.lessonChangedIn = 41;''', '''		// (39: the drill and the opponents. 41: every other fight against an opponent on a slow connection.
		// 43: getting out of a combo by its second hit is taught here again.)
		st.lessonChangedIn = 43;''')
# duel
s = rep(s, '''		st.reward.comboBack = 0.02f;
''', '''		st.reward.comboBack = 0.04f;
		// Out by the second hit, as in the stages before; see RewardWeights.comboThird.
		st.reward.comboThird = 0.6f;
''')
save(p, s, nl)
print('ok')
