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

# ---- Tally
p = root + 'core/src/main/java/dev/swordai/core/curriculum/Tally.java'
s, nl = load(p)
s = rep(s, '''	public long comboedHits;
	public long comboedHitsFollowed;
	/** Ticks just after such a hit,''', '''	public long comboedHits;
	public long comboedHitsFollowed;
	/**
	 * The opponent's combos that got to two hits in a row with the agent free to do something about
	 * the next, and how many of those got a third. A combo it is handed three hits into (a drill)
	 * is in neither.
	 */
	public long twoHitCombos;
	public long threeHitCombos;
	/** Ticks just after such a hit,''')
s = rep(s, '''		comboedHits += t.comboedHits;
		comboedHitsFollowed += t.comboedHitsFollowed;
''', '''		comboedHits += t.comboedHits;
		comboedHitsFollowed += t.comboedHitsFollowed;
		twoHitCombos += t.twoHitCombos;
		threeHitCombos += t.threeHitCombos;
''', 2)
s = rep(s, '''		comboedHits = 0;
		comboedHitsFollowed = 0;
''', '''		comboedHits = 0;
		comboedHitsFollowed = 0;
		twoHitCombos = 0;
		threeHitCombos = 0;
''')
s = rep(s, '''	/** Share of the ticks just after a hit of the opponent's combo, with the opponent still near, that it held S. */''', '''	/**
	 * Share of the opponent's combos that stopped at two hits: it was out, by a hit of its own or by
	 * getting clear, before a third landed.
	 */
	public float outByTwoRate() {
		return twoHitCombos <= 0 ? 0f : 1f - Math.min(1f, ratio(threeHitCombos, twoHitCombos));
	}

	/** Share of the ticks just after a hit of the opponent's combo, with the opponent still near, that it held S. */''')
save(p, s, nl)

# ---- RewardModel
p = root + 'core/src/main/java/dev/swordai/core/curriculum/RewardModel.java'
s, nl = load(p)
s = rep(s, '''	private int takenStreak;
''', '''	private int takenStreak;
	/** The run of hits it is taking is one it was handed three or more hits into: not counted as a combo it could have got out of at two. */
	private boolean handedLong;
''')
s = rep(s, '''		takenStreak = hits;
		ticksSinceTaken = 0;
		ticksSinceContact = 0;
		sinceHurt = 0;
		answerOwed = true;
		tally.hitsToAnswer++;
''', '''		takenStreak = hits;
		// Two hits into it, the combo is at two as it gets its keys: whether a third follows is its doing.
		handedLong = hits >= 3;
		if (hits == 2) {
			tally.twoHitCombos++;
		}
		ticksSinceTaken = 0;
		ticksSinceContact = 0;
		sinceHurt = 0;
		answerOwed = true;
		tally.hitsToAnswer++;
''')
s = rep(s, '''			int before = takenStreak;
			takenStreak += f.hitsTaken;
''', '''			int before = takenStreak;
			takenStreak += f.hitsTaken;
			if (!handedLong && !f.cannotAvoid) {
				if (before < 2 && takenStreak >= 2) {
					tally.twoHitCombos++;
				}
				if (before < 3 && takenStreak >= 3) {
					tally.threeHitCombos++;
				}
			}
''')
# the run ends: by its own hit, by the timeout, at a reset
s = rep(s, '''				tally.sweepHits += f.hitsDealt;
			}
			takenStreak = 0;
''', '''				tally.sweepHits += f.hitsDealt;
			}
			takenStreak = 0;
			handedLong = false;
''')
s = rep(s, '''				escapeHeld = w.comboEscape;
				escapeArmed = false;
			}
			takenStreak = 0;
''', '''				escapeHeld = w.comboEscape;
				escapeArmed = false;
			}
			takenStreak = 0;
			handedLong = false;
''')
s = rep(s, '''		ticksSinceHit = Integer.MAX_VALUE / 2;
		takenStreak = 0;
''', '''		ticksSinceHit = Integer.MAX_VALUE / 2;
		takenStreak = 0;
		handedLong = false;
''')
save(p, s, nl)

# ---- Trainer: log and status
p = root + 'src/client/java/dev/swordai/client/Trainer.java'
s, nl = load(p)
s = rep(s, '''						+ "sweeps %d, early %d, swings %d, air swings %d (%d at nothing), hits answered %d/%d",''', '''						+ "sweeps %d, early %d, swings %d, air swings %d (%d at nothing), hits answered %d/%d, combos on it %d of which a third hit %d",''')
s = rep(s, '''				t.sweepHits, t.spamClicks, t.clicks, t.airSwings, t.airWhiffs, t.answers, t.hitsToAnswer);''', '''				t.sweepHits, t.spamClicks, t.clicks, t.airSwings, t.airWhiffs, t.answers, t.hitsToAnswer, t.twoHitCombos, t.threeHitCombos);''')
save(p, s, nl)
print('ok')
