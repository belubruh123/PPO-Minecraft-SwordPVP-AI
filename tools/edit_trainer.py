import io
import re


def load(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in s else '\n'
    return s.replace('\r\n', '\n'), nl


def save(p, s, nl):
    io.open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', nl))


def rep(s, a, b):
    assert s.count(a) == 1, (s.count(a), a[:80])
    return s.replace(a, b)


p = 'D:/swordai/src/client/java/dev/swordai/client/Trainer.java'
s, nl = load(p)

# fields
s = rep(s, '''	final Tally traderTally = new Tally();
	final Tally lastTrader = new Tally();
''', '''	final Tally traderTally = new Tally();
	final Tally lastTrader = new Tally();
	/** The running fight is against an opponent on a slow connection: its knockback comes a quarter to half a second late; see {@code StageSpec.slowShare}. */
	boolean episodeSlow;
	/** How late a hit knocks the opponent back in the running fight, in ticks. */
	int episodeKnockLag;
	/** The stage's own fights since the last update by the opponent's connection, for the log: slow, and the rest. */
	final PingCount slowCount = new PingCount();
	final PingCount quickCount = new PingCount();
	final PingCount lastSlow = new PingCount();
	final PingCount lastQuick = new PingCount();

	/** What the fights against one kind of connection came to. */
	static final class PingCount {
		long steps;
		long dealt;
		long taken;
		long crits;
		long sprint;
		double reward;

		void add(StepFacts f, float r) {
			steps++;
			dealt += f.hitsDealt;
			taken += f.hitsTaken;
			if (f.hitsDealt > 0 && f.critDealt) {
				crits += f.hitsDealt;
			} else if (f.hitsDealt > 0 && f.sprintHit) {
				sprint += f.hitsDealt;
			}
			reward += r;
		}

		void set(PingCount o) {
			steps = o.steps;
			dealt = o.dealt;
			taken = o.taken;
			crits = o.crits;
			sprint = o.sprint;
			reward = o.reward;
		}

		void clear() {
			steps = 0;
			dealt = 0;
			taken = 0;
			crits = 0;
			sprint = 0;
			reward = 0.0;
		}

		String brief() {
			if (steps == 0) {
				return "-";
			}
			return String.format(Locale.ROOT, "%d steps: %d dealt (%.0f%% crit, %.0f%% sprint), %d taken, rew %.3f",
					steps, dealt, dealt == 0 ? 0f : 100f * crits / dealt, dealt == 0 ? 0f : 100f * sprint / dealt, taken, reward / steps);
		}

		/** For the status lines. */
		String line() {
			if (steps == 0) {
				return "none fought yet";
			}
			return String.format(Locale.ROOT, "%,d hits landed and %,d taken in %.1f minutes (%.1f to 1), %.0f%% of its hits critical and %.0f%% sprint hits",
					dealt, taken, steps / 1200f, taken == 0 ? (float) dealt : dealt / (float) taken,
					dealt == 0 ? 0f : 100f * crits / dealt, dealt == 0 ? 0f : 100f * sprint / dealt);
		}
	}
''')

# the draw
a = s[s.index('		// Each fight has its own lag, from none at all to the most the stage has.\n		if (stage.pathLagTicks > 0) {'):s.index('		} else {\n			plan.agentLag = stage.seenLagTicks > 0 ? rng.nextInt(stage.seenLagTicks + 1) : 0;')]
b = '''		// Each fight has its own connection.
		episodeSlow = false;
		if (stage.pathLagTicks > 0) {
			// One connection for all of it, as on a server. A hit moves the opponent once it has gone to
			// the opponent's game and the answer is back, which is its connection twice; what each sees of
			// the other is old by the opponent's way and the fighter's own. A share of the fights are
			// against an opponent on a slow connection: knocked back a quarter to half a second late.
			episodeSlow = stage.slowShare > 0f && rng.nextFloat() < stage.slowShare;
			int knock = episodeSlow
					? stage.slowKnockbackMinTicks + rng.nextInt(Math.max(1, stage.slowKnockbackMaxTicks - stage.slowKnockbackMinTicks + 1))
					: 2 * drawPathLag(stage.pathLagTicks);
			int seen = Math.min(ReplicaBody.MAX_LAG_TICKS, (knock + 1) / 2 + rng.nextInt(2));
			plan.agentLag = seen;
			plan.botLag = seen;
			plan.botPing = knock;
'''
s = s.replace(a, b)
s = rep(s, '''		setAgentLag(plan.agentLag, plan.agentStall, plan.agentStallTicks);
''', '''		episodeKnockLag = plan.botPing;
		setAgentLag(plan.agentLag, plan.agentStall, plan.agentStallTicks);
''')
s = rep(s, '''	/**
	 * The delay between the two fighters for the fight about to begin, in ticks one way: from none to
	 * {@code most}, short ones more often than long ones. About four fights in ten get none or one
	 * tick (a good connection, or sparring at home), three in ten get four or more (200 ms and up).
	 */''', '''	/**
	 * What the opponent's connection takes one way in an ordinary fight, in ticks: from none to
	 * {@code most}, short ones more often than long ones. With two ticks at the most, four fights in
	 * ten get none (sparring at home), four get one and two get two (200 ms).
	 */''')

# counting
s = rep(s, '''				float reward = rewards.step(stage, facts, target);
''', '''				float reward = rewards.step(stage, facts, target);
				if (target == tally || target == traderTally) {
					(episodeSlow ? slowCount : quickCount).add(facts, reward);
				}
''')
s = rep(s, '''		episodeTrader = false;
		critDrillKind = -1;''', '''		episodeTrader = false;
		episodeSlow = false;
		critDrillKind = -1;''')
s = rep(s, '''		keepLast(traderTally, lastTrader);
		traderTally.clear();
		rehearsalTally.clear();''', '''		keepLast(traderTally, lastTrader);
		traderTally.clear();
		if (slowCount.steps > 0) {
			lastSlow.set(slowCount);
		}
		if (quickCount.steps > 0) {
			lastQuick.set(quickCount);
		}
		slowCount.clear();
		quickCount.clear();
		rehearsalTally.clear();''')
s = rep(s, '''		tally.clear();
		traderTally.clear();
		drillTally.clear();
		saveCurriculum();''', '''		tally.clear();
		traderTally.clear();
		slowCount.clear();
		quickCount.clear();
		drillTally.clear();
		saveCurriculum();''')
s = rep(s, '''		SwordAi.LOGGER.info("fights since the last update ({} steps in total): own {} | traders {} | crit drill 1 {} | punish held {} | punish free {}",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally));''', '''		SwordAi.LOGGER.info("fights since the last update ({} steps in total): own {} | traders {} | crit drill 1 {} | punish held {} | punish free {} | slow connection {} | ordinary connection {}",
				learner.totalSteps, brief(tally), brief(traderTally), brief(jumpDrillTally), brief(punishDrillTally), brief(freeDrillTally),
				slowCount.brief(), quickCount.brief());''')
s = rep(s, '''						now(traderTally, lastTrader)));
			}
		}
		return out;
''', '''						now(traderTally, lastTrader)));
			}
		}
		if (mode == Mode.TRAIN && current.slowShare > 0f && !curriculum.critDrillsOnly()) {
			out.add(String.format(Locale.ROOT, "by the opponent's connection, lately: slow (knocked back %.2f to %.2f s late, %.0f%% of the fights) %s; ordinary (none to %.1f s late) %s",
					current.slowKnockbackMinTicks / 20f, current.slowKnockbackMaxTicks / 20f, current.slowShare * 100f,
					(slowCount.steps > 0 ? slowCount : lastSlow).line(), current.pathLagTicks / 10f, (quickCount.steps > 0 ? quickCount : lastQuick).line()));
		}
		return out;
''')
if 'import dev.swordai.server.ReplicaBody;' not in s:
    m = re.search(r'import dev\.swordai\.server\.\w+;\n', s)
    assert m
    s = s[:m.start()] + 'import dev.swordai.server.ReplicaBody;\n' + s[m.start():]
save(p, s, nl)
print('ok')
