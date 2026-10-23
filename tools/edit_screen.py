import re

ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/client/java/dev/swordai/client/TrainingSession.java', [
    ('''	@Override
	void setAgentLag(int ticks, float stallChance, int stallTicks) {
		SeenLag.set(ticks, stallChance, stallTicks);
	}
''',
     '''	@Override
	void setAgentLag(int ticks, float stallChance, int stallTicks) {
		SeenLag.set(ticks, stallChance, stallTicks);
	}

	/**
	 * On the screen no fight against a copy is on equal terms. The user's character sees the copy as
	 * a player sees another, a tick or more late, and its clicks reach the server a tick late; the
	 * copy is a fake player on the server and has neither delay. After two hours of it the fighter
	 * won one such fight in five against a copy it then beat four times in five without the screen.
	 * Counted, those fights would never let it take the copy's place, and at one win in ten
	 * self-play would throw away what it had learned on the screen and start again from the copy.
	 */
	@Override
	boolean judgesCopyFights() {
		return false;
	}
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''	boolean onEqualTerms() {
		return episodeFair || (episodeKnockLag == 0 && episodeBotKnockback >= 1f && !episodeStutter);
	}
''',
     '''	boolean onEqualTerms() {
		return judgesCopyFights() && (episodeFair || (episodeKnockLag == 0 && episodeBotKnockback >= 1f && !episodeStutter));
	}

	/** Whether a fight against a copy of itself can be on equal terms at all in this kind of training; not on the screen. */
	boolean judgesCopyFights() {
		return true;
	}
''', 1),
    ('''		if (curriculum.selfPlay() && fairCount.steps > 0) {
''',
     '''		if (curriculum.selfPlay() && mode == Mode.TRAIN && !judgesCopyFights()) {
			out.add("on the screen its fights against the copy are not counted as won or lost: your character sees the copy and clicks as a player does, "
					+ "a tick or two behind a copy that plays on the server itself. The duel and self-play move on in /swordai train headless; "
					+ "the screen is for the stretch before a fight");
		}
		if (curriculum.selfPlay() && fairCount.steps > 0) {
''', 1),
])

p = ROOT + 'core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
m = re.search(r'CURRENT_VERSION = (\d+)', s)
print('version was', m.group(1))
s = s.replace('CURRENT_VERSION = ' + m.group(1), 'CURRENT_VERSION = 53', 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
