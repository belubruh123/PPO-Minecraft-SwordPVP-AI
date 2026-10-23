ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/main/java/dev/swordai/server/BotBrain.java', [
    ('''		if (critPlan) {
			// Only on the way down from its jump, and not sprinting: that is what makes the hit critical.
			if (bot.onGround() || bot.fallDistance <= 0) {
				return;
			}
			bot.setSprinting(false);
		}
		boolean crit = critPlan;''', '''		// The one that plays on the agent's rhythm keeps its swing for the way down only in a jump of
		// its own or from the agent's hit: on its feet it hits whatever comes into its reach, first.
		boolean critSwing = critPlan && (!profile.timer || critJump >= 0 || punishTicks > 0);
		if (critSwing) {
			// Only on the way down from its jump, and not sprinting: that is what makes the hit critical.
			if (bot.onGround() || bot.fallDistance <= 0) {
				return;
			}
			bot.setSprinting(false);
		}
		boolean crit = critSwing;''', 1),
])
print('timer2 ok')
