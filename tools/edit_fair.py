import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('core/src/main/java/dev/swordai/core/curriculum/StageSpec.java', [
    ('''	public float slowShare = 0f;
''',
     '''	public float slowShare = 0f;
	/**
	 * Against a copy of itself: the share of the fights that are set up on equal terms, and they are
	 * the ones that count as won or lost. Nothing is late for either of them, the copy is knocked back
	 * as far as the fighter is, what the fighter sees of it does not stutter, and they start facing
	 * each other. In every other fight of such a stage something is against the fighter and nothing
	 * against the copy: the copy's knockback comes late or carries it less far, which keeps it in
	 * reach; what the fighter sees of it comes in fits and starts; the copy starts with its eyes on
	 * the fighter and the fighter has to find it. Those are what it is to learn to deal with, and
	 * who wins them says what they are worth, not which of the two is the better. (Counted without
	 * this, a fighter put against a copy of itself made that very minute lost 16 of 19.)
	 */
	public float fairShare = 0f;
''', 1),
])

edit('core/src/main/java/dev/swordai/core/curriculum/Stages.java', [
    ('''	private static final float SLOW_SHARE = 0.5f;
''',
     '''	private static final float SLOW_SHARE = 0.5f;
	/** Against a copy of itself, one fight in five is on equal terms and counted; see {@link StageSpec#fairShare}. */
	private static final float FAIR_SHARE = 0.2f;
''', 1),
    ('''		st.slowShare = SLOW_SHARE;
		st.bot.minDistance = 6f;
		st.bot.maxDistance = 10f;
		st.bot.maxBearingDeg = 60f;
''',
     '''		st.slowShare = SLOW_SHARE;
		st.fairShare = FAIR_SHARE;
		st.bot.minDistance = 6f;
		st.bot.maxDistance = 10f;
		st.bot.maxBearingDeg = 60f;
''', 1),
    ('''		// One fight in ten is on equal terms and counted (see Trainer.onEqualTerms), and a fight is a
		// few hundred steps: over 30,000 steps, as up to the first runs of 0.14.0, there were often
		// fewer than the twenty it takes to say anything, and the stage could not be passed however
		// the fights went.
		st.promote.windowSteps = 90_000;
''',
     '''		// One fight in five is on equal terms and counted (see StageSpec.fairShare), and a fight is a
		// few hundred steps: over 30,000 steps, as up to the first runs of 0.14.0, there were often
		// fewer than the twenty it takes to say anything, and the stage could not be passed however
		// the fights went.
		st.promote.windowSteps = 60_000;
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	/**
	 * Whether the running fight is one on equal terms: the opponent's knockback is not late, and it is
	 * knocked back as far as the fighter is. Only such a fight counts as won or lost against a copy of
	 * itself. One whose knockback comes late stays in reach after it is hit and swings back, while the
	 * fighter is thrown clear by every hit it takes: measured against the copy it was a minute before,
	 * it landed 38 hits a minute and took 57 where the knockback came a quarter to half a second late.
	 * One that is knocked back less is back in reach sooner, which is the same advantage in small.
	 * Those fights are there to be learned from. Who wins them says how much a slow connection or a
	 * shorter knockback is worth, not which of the two is the better.
	 */
	boolean onEqualTerms() {
		return episodeKnockLag == 0 && episodeBotKnockback >= 1f;
	}
''',
     '''	/**
	 * Whether the running fight is one on equal terms: one set up that way (see
	 * {@code StageSpec.fairShare}), or one that came out that way. Only such a fight counts as won or
	 * lost against a copy of itself. One whose knockback comes late stays in reach after it is hit and
	 * swings back, while the fighter is thrown clear by every hit it takes: measured against the copy
	 * it was a minute before, it landed 38 hits a minute and took 57 where the knockback came a
	 * quarter to half a second late. One that is knocked back less is back in reach sooner, which is
	 * the same advantage in small, and a picture of the opponent that comes in fits and starts is
	 * the fighter's handicap alone. Those fights are there to be learned from.
	 */
	boolean onEqualTerms() {
		return episodeFair || (episodeKnockLag == 0 && episodeBotKnockback >= 1f && !episodeStutter);
	}
''', 1),
    ('''	float episodeBotKnockback = 1f;
''',
     '''	float episodeBotKnockback = 1f;
	/** What the fighter sees of the opponent comes in fits and starts in the running fight. */
	boolean episodeStutter;
	/** The running fight was set up on equal terms; see {@code StageSpec.fairShare}. */
	boolean episodeFair;
''', 1),
    ('''		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;
''',
     '''		// Against a copy of itself a share of the fights are on equal terms, and those are the ones
		// that count; see StageSpec.fairShare.
		episodeFair = againstItself && !episodeDrill && !episodeCritDrill && stage.fairShare > 0f
				&& rng.nextFloat() < stage.fairShare;
		if (episodeFair) {
			episodeSlow = false;
			plan.agentLag = 0;
			plan.botLag = 0;
			plan.botPing = 0;
			plan.botKnockback = 1f;
			plan.agentStall = 0f;
			plan.agentStallTicks = 0;
			plan.bot.maxBearingDeg = 0f;
		}
		episodeKnockLag = plan.botPing;
		episodeBotKnockback = plan.botKnockback;
		episodeStutter = plan.agentStall > 0f;
''', 1),
    ('''counted are the fights on equal terms, in which the copy's knockback is neither late nor shorter, about one in ten)",''',
     '''counted are the fights on equal terms, about one in four: nothing late for either, the same knockback, and they start facing each other)",''', 1),
    ('''(those in which the copy's knockback is neither late nor shorter, about one in ten; the rest are fought and learned from, and not counted)",''',
     '''(about one in four: nothing late for either, the same knockback, and they start facing each other; the rest are fought and learned from, and not counted)",''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 50', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
