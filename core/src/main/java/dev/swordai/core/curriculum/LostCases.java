package dev.swordai.core.curriculum;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The fights it lost, kept to be fought again until it wins them. A scripted opponent is drawn anew
 * for every fight: its reactions, its aim, its rhythm, how it crits, its connection, how far a hit
 * moves it. A fight in which the fighter took more damage than it dealt is an opponent, so drawn,
 * that beats it, and very likely the very pattern a person who finds it would beat it with too. So
 * it is kept exactly as it was set up, down to the seed its chances were drawn from (where the two
 * start, every choice the bot makes), and a share of the fights against that stage's opponents are
 * those kept ones again ({@code Config.replayLostShare}), the worst first, each a few times running,
 * from the same start, until each has been won twice in a row. Then it is beaten, and kept apart: once
 * none of a stage's lost fights are left, the beaten ones are fought again in turn, since a fight won
 * twice can still be forgotten; one lost again goes back on the list. In training terms: prioritised
 * replay of the levels it fails, each level replayed from its seed.
 *
 * <p>The user, 19 October: "make it train on the exact same case on those it still didnt beat yet
 * more more more ... until all the cases are win ... there is a very high chance that is the exact
 * round my friend finds to beat this". And at 22:10: once it has learned all of them, "let it learn
 * all the cases again and find cases it loses". 20 October: "replicate those cases it failed 1-1 from
 * start so like it actually learns it", and no fighting the ones it has won meanwhile.
 *
 * <p>Plain bookkeeping and the draw; the trainer sets the fights up and saves the list.
 */
public final class LostCases {
	/**
	 * At most this many are kept; past that a parked one makes room, else the mildest loss. 400 left each
	 * one fought again only every two hours or so (20 October).
	 */
	public static final int MOST = 200;
	/**
	 * Fought again this many times and never won: parked (12 since replays became exact and run three at a time, 20 October). It stays on the list, but comes up a tenth as
	 * often, last as the one waited longest, and first to make room. On 19-20 October fights it could not
	 * win (no-knockback 246 replays and none won) took the replays, and it learned to give up against the
	 * hardest kinds: less damage dealt to the spacer and the punisher in the frozen test.
	 */
	public static final int PARK_REPLAYS = 12;
	static final float PARKED_WEIGHT = 0.1f;
	/** Won this many times in a row, a fight is beaten and let go. */
	public static final int WINS_TO_BEAT = 2;
	/** Shorter fights than this (in ticks) say nothing either way, unless a knockout decided them. */
	public static final int SHORTEST_TICKS = 200;
	static final float MINUTE = 1200f;
	/** Added to the damage a minute it lost by, so that a close loss still comes up. */
	static final float PRIORITY_FLOOR = 20f;
	/** At most this many beaten ones are kept to be fought again; past that the oldest goes. */
	public static final int MOST_BEATEN = 400;
	/**
	 * A fight picked is fought this many times running (unless beaten sooner), the same fight from the
	 * same start each time: the user, 20 October, "replicate those cases it failed 1-1 from start".
	 */
	public static final int STICK = 3;
	/**
	 * This share of the picks is the kept fight picked longest ago, so that every one gets its turn; the
	 * rest go by how badly it was lost. Without it a few fights lost by 250-300 a minute took most of
	 * the replays while others waited (19 October). Prioritised level replay mixes in staleness the same way.
	 */
	static final float STALEST_SHARE = 0.3f;

	/** One fight as it was set up, and how it has gone since. Plain fields, for a JSON file. */
	public static final class Case {
		public int stage;
		public int kind;
		public boolean stander;
		public boolean slow;
		public BotProfile bot;
		public int agentLag;
		public int botLag;
		public int botPing;
		public float agentStall;
		public int agentStallTicks;
		public float botKnockback = 1f;
		public boolean cornerStart;
		/** The draws of the fight from its start (see EpisodePlan.seed); 0 for one kept before 20 October. */
		public long seed;
		/** The agent's heading at its start. */
		public float startYaw;
		public boolean hasYaw;
		/** The jump critter's head start in hits; -1 if none was kept. */
		public int openerHits = -1;
		/** Damage a minute taken more than dealt, the last time it was lost. */
		public float lostBy;
		public int losses;
		public int wins;
		public int wonInRow;
		public int replays;
		/** Beaten: won twice in a row, and kept apart to be fought again. */
		public boolean beaten;
		/** Times fought again since it was beaten, and whether the last of those was won. */
		public int retests;
		public boolean retestWon;
		/** When it was last picked, counted in picks. */
		public long lastPicked;
		/**
		 * How much the last fighting of it had to teach: the mean size of its advantages (|GAE|), as
		 * prioritised level replay scores a level; -1 while it has not been scored. See {@link #byPotential}.
		 */
		public float potential = -1f;

		boolean parked() {
			return replays >= PARK_REPLAYS && wins == 0;
		}

		/** How likely it is picked: the worse it went the likelier, a parked one a tenth as likely. */
		float weight() {
			return parked() ? PARKED_WEIGHT * priority() : priority();
		}

		float priority() {
			return (Math.max(0f, lostBy) + PRIORITY_FLOOR) * (1 + Math.min(losses, 5));
		}
	}

	private final List<Case> cases = new ArrayList<>();
	private final List<Case> beatenCases = new ArrayList<>();
	/** Since {@link #takeCounts}: added, replayed, won in a replay, beaten for good, beaten ones fought again, won, lost. */
	private int added;
	private int replayed;
	private int replayWins;
	private int beaten;
	private int retested;
	private int retestWins;
	private int lostAgain;
	public int beatenEver;
	/** Picks made, for {@link Case#lastPicked}; carried on from the file's. */
	private long picks = -1;
	/**
	 * Prioritised level replay (Jiang, Grefenstette and Rocktaschel, ICML 2021): the kept fights are drawn
	 * by how much fighting them last taught ({@link Case#potential}, the mean |GAE| of its steps), rank
	 * by rank, the k-th weighing 1/k, instead of by how badly they were lost. Up to 21 October the worst
	 * losses came first, and only 1 to 13 of about 85 replays in half an hour were won: the worst-lost
	 * fights are mostly the ones there is least to learn from yet. One never scored comes first (it has
	 * not been tried), the one picked longest ago first among equals. Set from {@code Config.replayByPotential}.
	 */
	public boolean byPotential;
	/** The fight being fought again over and over, and how many more times; see {@link #STICK}. */
	private Case sticky;
	private int stickyLeft;

	public List<Case> cases() {
		return cases;
	}

	/** The beaten ones, kept to be fought again. */
	public List<Case> beatenCases() {
		return beatenCases;
	}

	/** Puts a beaten one with the others, making room by letting the oldest go. */
	public void keepBeaten(Case c) {
		c.beaten = true;
		while (beatenCases.size() >= MOST_BEATEN) {
			beatenCases.remove(0);
		}
		beatenCases.add(c);
	}

	/** True once none are left to beat and every beaten one has been fought again and won. */
	public boolean allBeaten() {
		if (!cases.isEmpty() || beatenCases.isEmpty()) {
			return false;
		}
		for (Case c : beatenCases) {
			if (c.retests == 0 || !c.retestWon) {
				return false;
			}
		}
		return true;
	}

	public int size() {
		return cases.size();
	}

	/** How much damage a minute it dealt more than it took (negative: it lost). */
	public static float net(float dealt, float taken, int ticks) {
		return ticks <= 0 ? 0f : (dealt - taken) / (ticks / MINUTE);
	}

	/** A fresh fight is over. Kept if it was lost; true if it was. */
	public boolean freshFight(Case setUp, float dealt, float taken, int ticks) {
		return freshFight(setUp, dealt, taken, ticks, false);
	}

	/** As above; a fight a knockout decided counts however short it was. */
	public boolean freshFight(Case setUp, float dealt, float taken, int ticks, boolean knockedOut) {
		if (ticks <= 0 || ticks < SHORTEST_TICKS && !knockedOut || dealt >= taken) {
			return false;
		}
		setUp.lostBy = -net(dealt, taken, ticks);
		setUp.losses = 1;
		if (cases.size() >= MOST) {
			cases.remove(makeRoom());
		}
		cases.add(setUp);
		added++;
		return true;
	}

	/** A kept fight was fought again. True if that beat it for good and it was let go. */
	public boolean replayed(Case c, float dealt, float taken, int ticks) {
		return replayed(c, dealt, taken, ticks, false);
	}

	/** As above; a fight a knockout decided counts however short it was. */
	public boolean replayed(Case c, float dealt, float taken, int ticks, boolean knockedOut) {
		if (ticks <= 0 || ticks < SHORTEST_TICKS && !knockedOut) {
			return false;
		}
		if (c.beaten) {
			retestedOne(c, dealt, taken, ticks);
			return false;
		}
		replayed++;
		c.replays++;
		if (dealt > taken) {
			replayWins++;
			c.wins++;
			c.wonInRow++;
			if (c.wonInRow >= WINS_TO_BEAT) {
				cases.remove(c);
				beaten++;
				beatenEver++;
				c.wonInRow = 0;
				c.retests = 0;
				c.retestWon = false;
				keepBeaten(c);
				return true;
			}
		} else {
			c.losses++;
			c.wonInRow = 0;
			c.lostBy = -net(dealt, taken, ticks);
		}
		return false;
	}

	/** A beaten one was fought again: won, it stays beaten; lost, it goes back on the list. */
	private void retestedOne(Case c, float dealt, float taken, int ticks) {
		retested++;
		c.replays++;
		c.retests++;
		if (dealt > taken) {
			retestWins++;
			c.wins++;
			c.retestWon = true;
			return;
		}
		lostAgain++;
		beatenCases.remove(c);
		c.beaten = false;
		c.retestWon = false;
		c.losses++;
		c.wonInRow = 0;
		c.lostBy = -net(dealt, taken, ticks);
		if (cases.size() >= MOST) {
			cases.remove(makeRoom());
		}
		cases.add(c);
	}

	/**
	 * One of the kept fights of this stage, the worse it went the likelier, fought {@link #STICK} times
	 * running; or, once none are left, a beaten one not yet fought again since it was beaten. Null if
	 * there are neither: a fresh fight then.
	 */
	public Case pick(Random rng, int stage) {
		Case c = null;
		if (sticky != null && stickyLeft > 0 && sticky.stage == stage && cases.contains(sticky)) {
			stickyLeft--;
			c = sticky;
		} else {
			c = rng.nextFloat() < STALEST_SHARE ? pickStalest(stage) : pickLost(rng, stage);
			if (c != null) {
				sticky = c;
				stickyLeft = STICK - 1;
			}
		}
		if (c == null) {
			// Only once none of the stage's lost fights are left: the beaten ones, fought again.
			c = pickBeaten(rng, stage);
		}
		if (c != null) {
			if (picks < 0) {
				picks = 0;
				for (Case k : cases) {
					picks = Math.max(picks, k.lastPicked);
				}
				for (Case k : beatenCases) {
					picks = Math.max(picks, k.lastPicked);
				}
			}
			c.lastPicked = ++picks;
		}
		return c;
	}

	/** The kept fight of this stage picked longest ago (never picked: the first kept), a parked one last. */
	private Case pickStalest(int stage) {
		Case best = null;
		for (Case c : cases) {
			if (c.stage == stage && (best == null || c.parked() == best.parked() && c.lastPicked < best.lastPicked
					|| best.parked() && !c.parked())) {
				best = c;
			}
		}
		return best;
	}

	/**
	 * A beaten one of this stage not yet fought again since it was beaten, picked at random; null once
	 * all have been: then the stage's fights are fresh ones, to find what it loses to next, and none is
	 * spent on what it has already won (the user, 20 October).
	 */
	private Case pickBeaten(Random rng, int stage) {
		int n = 0;
		for (Case c : beatenCases) {
			if (c.stage == stage && c.retests == 0) {
				n++;
			}
		}
		if (n == 0) {
			return null;
		}
		int at = rng.nextInt(n);
		for (Case c : beatenCases) {
			if (c.stage == stage && c.retests == 0 && at-- == 0) {
				return c;
			}
		}
		return null;
	}


	private Case pickLost(Random rng, int stage) {
		if (byPotential) {
			return pickByPotential(rng, stage);
		}
		double total = 0;
		for (Case c : cases) {
			if (c.stage == stage) {
				total += c.weight();
			}
		}
		if (total <= 0) {
			return null;
		}
		double at = rng.nextDouble() * total;
		Case last = null;
		for (Case c : cases) {
			if (c.stage != stage) {
				continue;
			}
			last = c;
			at -= c.weight();
			if (at <= 0) {
				return c;
			}
		}
		return last;
	}

	/** See {@link #byPotential}. A parked one weighs a tenth of what its rank gives it. */
	private Case pickByPotential(Random rng, int stage) {
		List<Case> mine = new ArrayList<>();
		for (Case c : cases) {
			if (c.stage == stage) {
				mine.add(c);
			}
		}
		if (mine.isEmpty()) {
			return null;
		}
		mine.sort((a, b) -> {
			int byScore = Float.compare(score(b), score(a));
			return byScore != 0 ? byScore : Long.compare(a.lastPicked, b.lastPicked);
		});
		double[] weight = new double[mine.size()];
		double total = 0;
		for (int k = 0; k < weight.length; k++) {
			weight[k] = (mine.get(k).parked() ? PARKED_WEIGHT : 1.0) / (k + 1);
			total += weight[k];
		}
		double at = rng.nextDouble() * total;
		for (int k = 0; k < weight.length; k++) {
			at -= weight[k];
			if (at < 0) {
				return mine.get(k);
			}
		}
		return mine.get(mine.size() - 1);
	}

	private static float score(Case c) {
		return c.potential < 0f ? Float.MAX_VALUE : c.potential;
	}

	/** The one to let go of when the list is full: a parked one (the one fought most), else the mildest loss. */
	private int makeRoom() {
		int best = 0;
		for (int i = 1; i < cases.size(); i++) {
			Case a = cases.get(i);
			Case b = cases.get(best);
			if (a.parked() != b.parked() ? a.parked()
					: a.parked() ? a.replays > b.replays : a.priority() < b.priority()) {
				best = i;
			}
		}
		return best;
	}

	/** Brings a list read from a file down to {@link #MOST}. */
	public void trim() {
		while (cases.size() > MOST) {
			cases.remove(makeRoom());
		}
	}

	/** How many are parked; see {@link #PARK_REPLAYS}. */
	public int parked() {
		int n = 0;
		for (Case c : cases) {
			if (c.parked()) {
				n++;
			}
		}
		return n;
	}

	/**
	 * A fight to mutate into a new one (ACCEL, Parker-Holder et al. 2022: levels at the edge of what it
	 * can do, edited a little): a beaten one of this stage if there is one, most lately beaten likelier,
	 * else a kept lost one. Null if there are neither.
	 */
	public Case pickToMutate(Random rng, int stage) {
		List<Case> pool = new ArrayList<>();
		for (Case c : beatenCases) {
			if (c.stage == stage) {
				pool.add(c);
			}
		}
		if (pool.isEmpty() || rng.nextBoolean()) {
			for (Case c : cases) {
				if (c.stage == stage && !c.parked()) {
					pool.add(c);
				}
			}
		}
		return pool.isEmpty() ? null : pool.get(rng.nextInt(pool.size()));
	}

	/**
	 * A new fight close to {@code from}: the same kind of opponent, its skill and its connection moved a
	 * little (reactions, aim, crits, jump resets, how far back its sword must be, lag, knockback), and
	 * nothing else. The seed and the record start afresh; the caller sets the seed.
	 */
	public static Case mutate(Case from, Random rng) {
		Case c = new Case();
		c.stage = from.stage;
		c.kind = from.kind;
		c.stander = from.stander;
		c.slow = from.slow;
		c.bot = from.bot.copy();
		BotProfile b = c.bot;
		b.reactionTicks = Math.max(0, b.reactionTicks + rng.nextInt(3) - 1);
		b.aimNoiseDeg = Math.max(0f, b.aimNoiseDeg * (0.8f + 0.4f * rng.nextFloat()));
		if (b.critChance > 0f) {
			b.critChance = clamp01(b.critChance + 0.2f * (rng.nextFloat() - 0.5f));
		}
		if (b.jumpResetChance > 0f) {
			b.jumpResetChance = clamp01(b.jumpResetChance + 0.2f * (rng.nextFloat() - 0.5f));
		}
		if (b.dodgeChance > 0f) {
			b.dodgeChance = clamp01(b.dodgeChance + 0.2f * (rng.nextFloat() - 0.5f));
		}
		b.attackChargeMin = Math.max(0.9f, Math.min(1f, b.attackChargeMin + 0.04f * (rng.nextFloat() - 0.5f)));
		c.agentLag = Math.max(0, from.agentLag + rng.nextInt(3) - 1);
		c.botLag = Math.max(0, from.botLag + rng.nextInt(3) - 1);
		c.botPing = Math.max(0, from.botPing + rng.nextInt(3) - 1);
		c.agentStall = from.agentStall;
		c.agentStallTicks = from.agentStallTicks;
		c.botKnockback = Math.max(0.02f, Math.min(1f, from.botKnockback + 0.1f * (rng.nextFloat() - 0.5f)));
		c.cornerStart = from.cornerStart;
		c.openerHits = -1;
		return c;
	}

	private static float clamp01(float x) {
		return Math.max(0f, Math.min(1f, x));
	}

	/** For the log: how many are kept, by the name of their kind. */
	public String byKind(java.util.function.IntFunction<String> name) {
		Map<String, Integer> n = new TreeMap<>();
		for (Case c : cases) {
			n.merge(name.apply(c.kind) + (c.stander ? " (stander)" : ""), 1, Integer::sum);
		}
		StringBuilder s = new StringBuilder();
		for (Map.Entry<String, Integer> e : n.entrySet()) {
			if (s.length() > 0) {
				s.append(", ");
			}
			s.append(e.getKey()).append(' ').append(e.getValue());
		}
		return s.length() == 0 ? "none" : s.toString();
	}

	/** For the log: what happened since the last call, which starts the count again. */
	public String takeCounts(java.util.function.IntFunction<String> name) {
		String line = String.format(Locale.ROOT, "lost fights kept %d (%s; parked %d), new %d, replayed %d of which won %d, beaten for good %d (%d in all); "
				+ "beaten ones kept %d, fought again %d of which won %d, lost again %d%s",
				cases.size(), byKind(name), parked(), added, replayed, replayWins, beaten, beatenEver,
				beatenCases.size(), retested, retestWins, lostAgain, allBeaten() ? "; ALL BEATEN" : "");
		retested = 0;
		retestWins = 0;
		lostAgain = 0;
		added = 0;
		replayed = 0;
		replayWins = 0;
		beaten = 0;
		return line;
	}
}
