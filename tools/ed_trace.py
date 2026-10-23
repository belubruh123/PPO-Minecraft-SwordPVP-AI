import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

e = Ed('D:/swordai/src/client/java/dev/swordai/client/FightTrace.java')
e.rep("""package dev.swordai.client;

import java.io.IOException;""", """package dev.swordai.client;

import dev.swordai.SwordAi;

import java.io.IOException;""")
e.save()

e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/config/Config.java')
e.rep("""	public boolean opponentReader = false;
""", """	public boolean opponentReader = false;
	/** Every fight against a scripted opponent written down tick by tick to {@code swordai/traces}, for reading how fights are lost. */
	public boolean traceFights = false;
""")
e.save()

e = Ed('D:/swordai/src/client/java/dev/swordai/client/Trainer.java')
e.rep("""	private final dev.swordai.core.rl.OpponentReader.Window oppWindow = new dev.swordai.core.rl.OpponentReader.Window();
""", """	private final dev.swordai.core.rl.OpponentReader.Window oppWindow = new dev.swordai.core.rl.OpponentReader.Window();
	/** The running fight tick by tick, with {@code Config.traceFights}; see {@link FightTrace}. */
	private final FightTrace trace = new FightTrace();
""")
e.rep("""		episodePlan = plan;
		episodeCase = c;
		if (cfg.evalSeed != 0) {""", """		episodePlan = plan;
		episodeCase = c;
		if (cfg.traceFights) {
			trace.begin(kindName(episodeKind), seed, false, traceSetup(plan));
		}
		if (cfg.evalSeed != 0) {""")
e.rep("""		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);""", """		if (trace.on()) {
			trace.end(SwordFiles.dir().resolve("traces"), kindName(episodeKind), episodeCase.seed, episodeDealt, episodeTaken, episodeTicksCounted);
		}
		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);""")
e.rep("""		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
	}
""", """		if (trace.on()) {
			trace.tick(traceLine(truth, tactic, answer, keep, meet, meetSwing, reset, backOut, step, dodge, hold, jump, owes));
		}
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
	}

	/** How the fight was set up, for the head of its trace. */
	private String traceSetup(EpisodePlan plan) {
		return String.format(Locale.ROOT, "its knockback %.0f%%, its connection %d ticks (seen %d, it sees %d), crit chance %.2f, reaction %d ticks%s",
				100f * plan.botKnockback, plan.botPing, plan.agentLag, plan.botLag, plan.bot.critChance, plan.bot.reactionTicks,
				episodeStander ? ", stands and hits" : "");
	}

	/** One tick of the trace: the state the step was chosen in, what was chosen and by what, and what the last tick brought. */
	private String traceLine(Truth truth, int tactic, boolean answer, boolean keep, boolean meet, boolean meetSwing, int reset, boolean backOut,
			int step, boolean dodge, boolean hold, int jump, boolean owes) {
		float[] v = obs.vec;
		int sc = dev.swordai.core.rl.ObsSpec.VEC_SCALARS;
		float scale = dev.swordai.core.env.BodyState.VELOCITY_SCALE;
		float up = (v[sc + dev.swordai.core.rl.ObsSpec.SC_REL_VEL_UP] + v[sc + dev.swordai.core.rl.ObsSpec.SC_VEL_UP]) / scale;
		float toward = -(v[sc + dev.swordai.core.rl.ObsSpec.SC_REL_VEL_FORWARD] + v[sc + dev.swordai.core.rl.ObsSpec.SC_VEL_FORWARD]) / scale;
		StringBuilder keys = new StringBuilder();
		int fwd = ActionSpace.forwardAxis(action);
		int side = ActionSpace.strafeAxis(action);
		keys.append(fwd > 0 ? 'W' : fwd < 0 ? 'S' : '.');
		keys.append(side > 0 ? 'D' : side < 0 ? 'A' : '.');
		keys.append(action[ActionSpace.SPRINT] == 1 ? 'r' : '.');
		keys.append(action[ActionSpace.JUMP] == 1 ? 'J' : '.');
		keys.append(action[ActionSpace.ATTACK] == 1 ? 'X' : '.');
		StringBuilder rules = new StringBuilder();
		if (answer) rules.append("answer ");
		if (keep) rules.append("keep ");
		if (owes && !answer && !keep) rules.append("owes ");
		if (meet) rules.append(meetSwing ? "meet+swing " : "meet ");
		if (reset != 0) rules.append("jumpreset ");
		if (backOut) rules.append("backout ");
		if (step != 0) rules.append("stepback ");
		if (dodge) rules.append("dodge ");
		if (hold) rules.append("edgehold ");
		if (jump != 0 || jumpIn != null && jumpIn.kind() != JumpIn.NONE) rules.append("jumpin:").append(jumpIn == null ? 0 : jumpIn.kind()).append(' ');
		StringBuilder ev = new StringBuilder();
		if (facts.hitsDealt > 0) {
			ev.append(String.format(Locale.ROOT, "HIT %s%.1f ", facts.critDealt ? "CRIT " : facts.sprintHit ? "sprint " : "", facts.damageDealt));
		}
		if (facts.hitsTaken > 0) {
			ev.append(String.format(Locale.ROOT, "TAKEN %s%.1f ", facts.critsTaken > 0 ? "CRIT " : "", facts.damageTaken));
		}
		if (facts.clicked && facts.hitsDealt == 0) {
			ev.append(facts.clickOnTarget ? "swing-blocked " : "whiff ");
		}
		return String.format(Locale.ROOT, "%4d | %s | %.2f %.2f | %s%s | %+.2f %+.2f | %s | %s| %s | %s| %.0f/%.0f",
				episodeTick, facts.enemyVisible ? String.format(Locale.ROOT, "%.2f", facts.reachDist) : "  - ", chargeAtClick, truth.botCharge(),
				groundAtAction ? "G" : "a", truth.botOnGround() ? "G" : "a", up, toward, keys, rules,
				dev.swordai.core.rl.Tactic.name(tactic), ev, episodeDealt, episodeTaken);
	}
""")
e.save()
