ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:70], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


edit('src/client/java/dev/swordai/client/Report.java', [
    ('''		String backHelp = backLine(cfg, curriculum);
		if (backHelp != null) {
			header.add(backHelp);
		}
''', '''		String backHelp = backLine(cfg, curriculum);
		if (backHelp != null) {
			header.add(backHelp);
		}
		String rules = rulesLine(cfg, curriculum);
		if (rules != null) {
			header.add(rules);
		}
''', 1),
    ('''	/** What the current stage is doing about S while it is comboed right now, or null when it does nothing. */
''', '''	/**
	 * The things that are done for the fighter in a fight whatever the network says, as the config
	 * has them; null before the stage that gives it the jump key, where none of them apply.
	 */
	static String rulesLine(Config cfg, Curriculum curriculum) {
		if (!curriculum.current().unlocked(dev.swordai.core.rl.ActionSpace.JUMP)) {
			return null;
		}
		StringBuilder on = new StringBuilder();
		add(on, cfg.answerRule, "a hit is answered with a swing where one reaches (answerRule)");
		add(on, cfg.answerRule && cfg.punishRule, "in the air that swing is kept for the way down, a punish crit (punishRule)");
		add(on, cfg.answerRule && cfg.aimRule, "after a hit the camera is brought onto the opponent (aimRule)");
		add(on, cfg.farRestRule, "on an opponent far away the camera is held steady (farRestRule)");
		add(on, cfg.chaseRule, "a running jump and a critical hit on the way down at an opponent that is backing off or running (chaseRule)");
		add(on, cfg.faceRule, "a jump and a critical hit at an opponent that its hits do not send away (faceRule)");
		add(on, cfg.uppercutChance > 0f, String.format(Locale.ROOT, "an uppercut after %.0f%% of the hits of a combo, from the third on (uppercutChance)", cfg.uppercutChance * 100f));
		return on.length() == 0 ? "rules in a fight: none are switched on in config.json" : "rules in a fight, in training and in every fight (config.json): " + on;
	}

	private static void add(StringBuilder to, boolean on, String what) {
		if (on) {
			to.append(to.length() == 0 ? "" : "; ").append(what);
		}
	}

	/** What the current stage is doing about S while it is comboed right now, or null when it does nothing. */
''', 1),
])

edit('src/client/java/dev/swordai/client/Trainer.java', [
    ('''		String backHelp = Report.backLine(cfg, curriculum);
		if (backHelp != null) {
			out.add(backHelp + String.format(Locale.ROOT, "; since the last update it held S on %.0f%% of those ticks (%d of them)", tally.pushRate() * 100f, tally.pushTicks));
		}
''', '''		String backHelp = Report.backLine(cfg, curriculum);
		if (backHelp != null) {
			out.add(backHelp + String.format(Locale.ROOT, "; since the last update it held S on %.0f%% of those ticks (%d of them)", tally.pushRate() * 100f, tally.pushTicks));
		}
		String rules = Report.rulesLine(cfg, curriculum);
		if (rules != null) {
			out.add(rules);
		}
''', 1),
    ('''		episodeDrill = false;
		episodeCritDrill = false;
		episodeTrader = false;
		episodeSlow = false;
''', '''		episodeDrill = false;
		episodeCritDrill = false;
		episodeTrader = false;
		episodeRunner = false;
		episodeCircler = false;
		episodeKind = KIND_PLAIN;
		dealtRow = 0;
		fallWatch = false;
		chaseWatch = false;
		if (jumpIn != null) {
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		punishMemory.reset();
		episodeSlow = false;
''', 1),
    ('''		dealtRow = 0;
		fallWatch = false;
		if (jumpIn != null) {
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		punishMemory.reset();
		drillJumpOutOfZone = false;
''', '''		dealtRow = 0;
		fallWatch = false;
		chaseWatch = false;
		if (jumpIn != null) {
			jumpIn.reset();
		}
		if (aimRule != null) {
			aimRule.reset();
		}
		punishMemory.reset();
		drillJumpOutOfZone = false;
''', 1),
])
print('ok')
