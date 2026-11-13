import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Keys;
import dev.swordai.core.pvp.PvpPlayer;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.school.Teacher;
import dev.swordai.core.vision.ViewGeometry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Agent 37's check (14 Nov, not part of the build): can a competent sprint-hit player chain hits against
 * Specialists.comboable()? Run (Git Bash, from the repo root; JDK as in tools/compile-check.sh):
 *   J=C:/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin; mkdir -p /d/tmp-combosim
 *   "$J/javac" -nowarn -d /d/tmp-combosim $(find core/src/main/java -name '*.java') tools/combosim/ComboSim.java
 *   "$J/java" -cp /d/tmp-combosim ComboSim FIGHTS [trace|x] [POLICY-PREFIX|""] [TRACE-IF-LONGEST] [run93|stun|none|stunA-B] [GATE]
 * e.g. ComboSim 300 x "" 99 run93 (every policy against run 93's bot), ComboSim 300 x "" 99 stun (run 93b's).
 * Physics as the game's LivingEntity.travel: acceleration AND friction by the ground state
 * BEFORE the move (ToyFight uses the state after it, which makes a knockback flight about 40% longer), the hurt
 * knockback 0.4 before the sprint hit's extra 0.5 (Player.attack), the bot's knockback and its feeling of the hit
 * max(1, ping) ticks late (TechniqueBot/KnockbackLag), each side seeing the other `lag` ticks late, and a hit landing
 * when the attacker SEES the other in reach (the server's interaction check has a 3-block buffer).
 * The run of hits is counted exactly as SchoolSession counts it (COMBO_GAP 20, a hit taken ends it).
 */
public class ComboSim {
	static final double WALK = 0.1, SPRINT_F = 1.3, AIR = 0.02, AIR_SPRINT = 0.026;
	static final double GROUND_FRICTION = 0.546, AIR_FRICTION = 0.91, JUMP = 0.42, SPRINT_JUMP = 0.2;
	static final double KB = 0.4, SPRINT_KB = 0.5;
	static int COMBO_GAP = 20;
	static boolean TOY_FRICTION = false; // true: ToyFight's order (friction by the state after the move)

	interface Policy {
		void start(long seed, int lag);

		void tick(Duel d, Events e, Keys k);
	}

	static final class Side {
		final Body b = new Body();
		final double[] vel = new double[3];
		int ticker = 100;
		float lastHurt;
		int invul;
		double fall;
		int seenLag;
		int ping;
		boolean feelLate;
		boolean gateAt0; // the agent: its swing gate reads getAttackStrengthScale(0)
		final ArrayDeque<Body> hist = new ArrayDeque<>();
		final ArrayDeque<double[]> kbQ = new ArrayDeque<>();
		final ArrayDeque<int[]> evQ = new ArrayDeque<>();
		final Events ev = new Events();
		final Keys keys = new Keys();
		final Duel view = new Duel();
		Policy pol;
		boolean sprinting;
		int sinceJump = 1000, airTicks, sinceSwing = 1000, sinceHurt = 1000;
		float yaw;
	}

	final Side[] s = {new Side(), new Side()};
	int t;
	// metrics
	int[] hits = new int[2];
	int row, lastOwnHit = -1000, longest;
	List<Integer> runs = new ArrayList<>();
	List<Integer> gaps = new ArrayList<>();
	int[] gapHist = new int[64];
	int secondWithin20, firstHits;
	int botBackTicks;
	// SchoolSession's run 93b counter: after each own hit, what came first
	int cbAt = -1, cbFollowed, cbTraded, cbTheirFirst, cbTimedOut;
	double cbDist;
	boolean dealtNow, takenNow;
	StringBuilder trace;

	void cbTick() {
		int since = t - cbAt;
		if (cbAt >= 0 && since > COMBO_GAP) {
			cbTimedOut++;
			cbDist += Duel.eyeToBox(s[0].b, seen(0));
			cbAt = -1;
		}
		if (cbAt >= 0 && takenNow) {
			if (since <= 3) {
				cbTraded++;
			} else {
				cbTheirFirst++;
			}
			cbAt = -1;
		} else if (cbAt >= 0 && dealtNow) {
			cbFollowed++;
			cbAt = -1;
		}
		if (dealtNow && !takenNow) {
			cbAt = t;
		}
		dealtNow = takenNow = false;
	}
	String note = "";

	static PvpPlayer botPlayer(Style st, long seed) {
		PvpPlayer p = new PvpPlayer();
		p.start(st, seed);
		return p;
	}

	void place(double dist, Random rng) {
		for (int i = 0; i < 2; i++) {
			Side x = s[i];
			x.b.x = i == 0 ? 0 : dist;
			x.b.y = 0;
			x.b.z = 0;
			x.b.onGround = true;
			Arrays.fill(x.vel, 0);
			x.hist.clear();
			x.kbQ.clear();
			x.evQ.clear();
			x.ticker = 100;
			x.invul = 0;
			x.lastHurt = 0;
			x.b.health = 20f;
		}
		look();
		for (int i = 0; i < 2; i++) {
			s[i].hist.addFirst(snapshot(i));
		}
	}

	void look() {
		for (int i = 0; i < 2; i++) {
			Side me = s[i];
			Body them = seen(i);
			me.yaw = ViewGeometry.yawTo(them.x - me.b.x, them.z - me.b.z);
			me.b.yaw = me.yaw;
		}
	}

	float charge(Side x, boolean forGate) {
		return Math.min(1f, (x.ticker + (forGate ? 0f : 0.5f)) / Body.SWORD_TICKS);
	}

	Body snapshot(int i) {
		Side x = s[i];
		Body c = x.b.copy();
		c.charge = charge(x, false);
		c.sprinting = x.sprinting;
		c.sinceJump = x.sinceJump;
		c.airTicks = x.airTicks;
		c.sinceSwing = x.sinceSwing;
		c.sinceHurt = x.sinceHurt;
		return c;
	}

	/** What side i sees of the other: its body seenLag ticks ago. */
	Body seen(int i) {
		Side o = s[1 - i];
		int lag = s[i].seenLag;
		Body last = null;
		int k = 0;
		for (Body b : o.hist) {
			last = b;
			if (k++ >= lag) {
				break;
			}
		}
		return last == null ? snapshot(1 - i) : last;
	}

	void step() {
		// 1. knockback due now
		for (int i = 0; i < 2; i++) {
			Side x = s[i];
			while (!x.kbQ.isEmpty() && x.kbQ.peekFirst()[0] <= t) {
				double[] kb = x.kbQ.pollFirst();
				knock(i, kb[1], kb[2], kb[3] > 0);
			}
		}
		// 2. views and events
		for (int i = 0; i < 2; i++) {
			Side x = s[i];
			x.ev.clear();
			while (!x.evQ.isEmpty() && x.evQ.peekFirst()[0] <= t) {
				int[] e = x.evQ.pollFirst();
				switch (e[1]) {
					case 0 -> x.ev.theyHit = e[2];
					case 1 -> x.ev.iHit = e[2];
					case 2 -> x.ev.theySwung = true;
					case 3 -> x.ev.iSwung = true;
				}
			}
			x.view.me.copyFrom(snapshot(i));
			if (x.gateAt0) {
				x.view.me.charge = charge(x, true);
			}
			x.view.them.copyFrom(seen(i));
			x.view.measure();
			x.pol.tick(x.view, x.ev, x.keys);
		}
		look();
		// 3. swings, from where both are as the tick begins (the client's attack comes before its move; the bot's
		// swing in the arena tick); a knockback reaches its victim's moves only from its due tick on.
		boolean[] swung = new boolean[2];
		for (int i = 0; i < 2; i++) {
			if (s[i].keys.attack) {
				swung[i] = true;
				swing(i);
			}
		}
		cbTick();
		// 4. move
		for (int i = 0; i < 2; i++) {
			move(i, s[i].keys);
		}
		for (int i = 0; i < 2; i++) {
			Side x = s[i];
			x.ticker++;
			x.sinceSwing = swung[i] ? 0 : Math.min(x.sinceSwing + 1, 1000);
			x.sinceHurt = Math.min(x.sinceHurt + 1, 1000);
			if (x.invul > 0) {
				x.invul--;
			}
			if (swung[i]) {
				x.evQ.addLast(new int[] {t + 1, 3, 0});
				s[1 - i].evQ.addLast(new int[] {t + Math.max(1, s[1 - i].seenLag), 2, 0});
			}
			x.hist.addFirst(snapshot(i));
			while (x.hist.size() > 12) {
				x.hist.removeLast();
			}
		}
		if (s[1].keys.forward < 0) {
			botBackTicks++;
		}
		if (trace != null) {
			Body a = s[0].b, b = s[1].b;
			trace.append(String.format(Locale.ROOT, "%4d dist %.2f seenA %.2f | A %s ch %.2f %s v%.2f | B %s ch %.2f %s v%.2f", t,
					Duel.eyeToBox(a, b), s[0].view.reach, s[0].keys.brief(), charge(s[0], true), a.onGround ? "g" : "a",
					Math.hypot(a.vx, a.vz), s[1].keys.brief(), charge(s[1], false), b.onGround ? "g" : "a", Math.hypot(b.vx, b.vz)) + note + " row " + row + "\n");
			note = "";
		}
		t++;
	}

	void move(int i, Keys k) {
		Side x = s[i];
		Body me = x.b;
		double[] v = x.vel;
		boolean groundBefore = me.onGround;
		if (k.forward <= 0) {
			x.sprinting = false;
		} else if (k.sprint) {
			x.sprinting = true;
		}
		x.sinceJump = Math.min(x.sinceJump + 1, 1000);
		if (k.jump && me.onGround) {
			v[1] = JUMP;
			if (x.sprinting) {
				v[0] += -Math.sin(Math.toRadians(x.yaw)) * SPRINT_JUMP;
				v[2] += Math.cos(Math.toRadians(x.yaw)) * SPRINT_JUMP;
			}
			x.sinceJump = 0;
		}
		double f = k.forward * 0.98, st = k.strafe * 0.98;
		double len = Math.sqrt(f * f + st * st);
		if (len > 1) {
			f /= len;
			st /= len;
		}
		double speed = groundBefore ? WALK * (x.sprinting ? SPRINT_F : 1) : x.sprinting ? AIR_SPRINT : AIR;
		double lx = -Math.sin(Math.toRadians(x.yaw)), lz = Math.cos(Math.toRadians(x.yaw));
		double rx = -lz, rz = lx;
		v[0] += (f * lx + st * rx) * speed;
		v[2] += (f * lz + st * rz) * speed;
		double prevY = me.y;
		me.vx = v[0];
		me.vz = v[2];
		me.x += v[0];
		me.z += v[2];
		me.y += v[1];
		me.vy = v[1];
		boolean ground;
		if (me.y <= 0) {
			me.vy = -prevY;
			me.y = 0;
			v[1] = 0;
			ground = true;
		} else {
			ground = false;
		}
		me.onGround = ground;
		boolean fr = TOY_FRICTION ? ground : groundBefore;
		v[0] *= fr ? GROUND_FRICTION : AIR_FRICTION;
		v[2] *= fr ? GROUND_FRICTION : AIR_FRICTION;
		if (!ground) {
			v[1] = (v[1] - 0.08) * 0.98;
		}
		x.fall = ground ? 0 : me.y < prevY ? x.fall + (prevY - me.y) : x.fall;
		x.airTicks = ground ? 0 : x.airTicks + 1;
	}

	void swing(int i) {
		Side me = s[i];
		Side them = s[1 - i];
		float ch = charge(me, false);
		me.ticker = 0;
		if (Duel.eyeToBox(me.b, seen(i)) > Duel.REACH) {
			return;
		}
		boolean crit = ch > 0.9f && me.fall > 0 && !me.b.onGround && !me.sprinting;
		boolean knock = ch > 0.9f && me.sprinting;
		float dmg = 7f * (0.2f + ch * ch * 0.8f) * (crit ? 1.5f : 1f);
		if (them.invul > 10) {
			if (dmg <= them.lastHurt) {
				return;
			}
			dmg -= them.lastHurt;
			them.lastHurt += dmg;
		} else {
			them.lastHurt = dmg;
			them.invul = 20;
		}
		them.sinceHurt = 0;
		them.b.health -= dmg * 0.27f;
		int kind = Events.kindOf(ch, me.sprinting, crit);
		double dx = them.b.x - me.b.x, dz = them.b.z - me.b.z, d = Math.max(1e-6, Math.hypot(dx, dz));
		dx /= d;
		dz /= d;
		if (knock) {
			me.vel[0] *= 0.6;
			me.vel[2] *= 0.6;
			me.sprinting = false;
		}
		int due = t + Math.max(1, them.ping);
		them.kbQ.addLast(new double[] {due, dx, dz, knock ? 1 : 0});
		them.evQ.addLast(new int[] {them.feelLate ? due : t + 1, 0, kind});
		me.evQ.addLast(new int[] {t + Math.max(1, me.seenLag), 1, kind});
		hits[i]++;
		if (i == 0) {
			dealtNow = true;
		} else {
			takenNow = true;
		}
		note += (i == 0 ? " A-HIT" : " B-HIT") + (kind == Events.SPRINT ? "s" : kind == Events.CRIT ? "c" : "p");
		if (i == 0) {
			ownHit();
		} else {
			if (row > 0) {
				runs.add(row);
			}
			row = 0;
			lastOwnHit = -1000;
		}
	}

	void ownHit() {
		firstHits += row == 0 || t - lastOwnHit > COMBO_GAP ? 1 : 0;
		if (lastOwnHit > -1000) {
			int g = t - lastOwnHit;
			gaps.add(g);
			gapHist[Math.min(63, g)]++;
		}
		if (t - lastOwnHit <= COMBO_GAP && row > 0) {
			row++;
			if (row == 2) {
				secondWithin20++;
			}
		} else {
			if (row > 0) {
				runs.add(row);
			}
			row = 1;
		}
		longest = Math.max(longest, row);
		lastOwnHit = t;
	}

	/** Game order: the hurt knockback (0.4) first, then the sprint hit's extra (0.5). */
	void knock(int j, double dx, double dz, boolean sprint) {
		Side x = s[j];
		double[] v = x.vel;
		v[0] = v[0] / 2 + dx * KB;
		v[2] = v[2] / 2 + dz * KB;
		if (x.b.onGround) {
			v[1] = Math.min(0.4, v[1] / 2 + KB);
		}
		if (sprint) {
			v[0] = v[0] / 2 + dx * SPRINT_KB;
			v[2] = v[2] / 2 + dz * SPRINT_KB;
			if (x.b.onGround) {
				v[1] = Math.min(0.4, v[1] / 2 + SPRINT_KB);
			}
		}
	}

	// ---------------------------------------------------------------- policies

	/** A PvpPlayer (the bot, or the teacher in the agent's seat). */
	static Policy player(Style st, boolean teacherLag) {
		PvpPlayer p = new PvpPlayer();
		return new Policy() {
			public void start(long seed, int lag) {
				p.start(st, seed);
				if (teacherLag) {
					p.seenLag(lag);
				}
			}

			public void tick(Duel d, Events e, Keys k) {
				p.tick(d, e, k);
			}
		};
	}

	/** W + sprint at them always, the swing the tick the gate opens with them seen in reach; never a jump. */
	static float GATE = 1f;

	static Policy chaser() {
		return new Policy() {
			public void start(long seed, int lag) {
			}

			public void tick(Duel d, Events e, Keys k) {
				k.clear();
				k.forward = 1;
				k.sprint = true;
				k.attack = d.me.charge >= GATE && d.reach <= Duel.REACH;
			}
		};
	}

	/**
	 * The document's lunge (T11a, "extra hits on players who hold S out of a trade (C3)"): as the chaser, and from the ground,
	 * them out of reach and going away, a sprint-jump once the sword will be back on the way down; the sprint hit falling.
	 */
	static Policy lunger(float chargeAt, double from, double to) {
		return new Policy() {
			public void start(long seed, int lag) {
			}

			public void tick(Duel d, Events e, Keys k) {
				k.clear();
				k.forward = 1;
				k.sprint = true;
				k.attack = d.me.charge >= 1f && d.reach <= Duel.REACH;
				if (d.me.onGround && d.reach > from && d.reach < to && d.theyClose < -0.05 && d.me.charge >= chargeAt) {
					k.jump = true;
				}
			}
		};
	}

	// ---------------------------------------------------------------- runs

	static Technique[] taughtStage4() {
		return new Technique[] {Technique.K2, Technique.K3, Technique.T6, Technique.T6A, Technique.T7, Technique.T8, Technique.B1,
				Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2, Technique.C1, Technique.C2, Technique.T4,
				Technique.T5, Technique.C3, Technique.C4, Technique.T9, Technique.T9A, Technique.T9B, Technique.T9C, Technique.C6,
				Technique.C7, Technique.T10, Technique.T11A, Technique.T11B, Technique.T11C};
	}

	static int traceLongest = 0;
	static String botVariant = "run93";

	/**
	 * A noisy, half-trained player: W 80% of ticks (S 5%), the sprint key 80%, a jump 4%, the swing on 50% of the ticks the gate
	 * and the pick let it: about what PPO starts from, to see whether chains come by chance.
	 */
	static Policy noisy() {
		Random r = new Random();
		return new Policy() {
			public void start(long seed, int lag) {
				r.setSeed(seed);
			}

			public void tick(Duel d, Events e, Keys k) {
				k.clear();
				float u = r.nextFloat();
				k.forward = u < 0.8f ? 1 : u < 0.85f ? -1 : 0;
				k.sprint = r.nextFloat() < 0.8f;
				k.jump = r.nextFloat() < 0.04f;
				k.attack = d.me.charge >= 1f && d.reach <= Duel.REACH && r.nextFloat() < 0.5f;
			}
		};
	}

	static String run(String name, java.util.function.Supplier<Policy> agent, int fights, long seed0, boolean printTrace) {
		boolean traced = false;
		int totalHits = 0, totalTaken = 0, longestAll = 0, sec = 0, firsts = 0;
		int[] runHist = new int[12];
		int[][] byLag = new int[5][4]; // fights, own hits, taken, runs3+
		int[] cb = new int[4];
		double cbDistAll = 0;
		List<Integer> allGaps = new ArrayList<>();
		long backTicks = 0, ticks = 0;
		for (int f = 0; f < fights; f++) {
			Random rng = new Random(seed0 + f * 7919L);
			ComboSim sim = new ComboSim();
			Style bot = Specialists.comboable(new Random(rng.nextLong()));
			int window = Math.max(bot.stunAfterHit, bot.backAfterHit);
			bot.stunAfterHit = 0;
			bot.backAfterHit = 0;
			switch (botVariant) {
				case "run93" -> bot.backAfterHit = window;
				case "stun" -> bot.stunAfterHit = window;
				case "none" -> {
				}
				default -> {
					// "stunA-B"
					String[] ab = botVariant.substring(4).split("-");
					int a = Integer.parseInt(ab[0]), b2 = Integer.parseInt(ab[1]);
					bot.stunAfterHit = a + (window - 12) * (b2 - a) / 8;
				}
			}
			int agentLag = 1 + rng.nextInt(4);
			sim.s[0].seenLag = agentLag;
			sim.s[0].ping = 1;
			sim.s[0].gateAt0 = true;
			sim.s[0].pol = agent.get();
			sim.s[0].pol.start(rng.nextLong(), agentLag);
			sim.s[1].seenLag = Math.max(0, bot.lag);
			sim.s[1].ping = Math.max(1, bot.ping);
			sim.s[1].feelLate = true;
			sim.s[1].pol = player(bot, false);
			sim.s[1].pol.start(rng.nextLong(), 0);
			sim.place(3f + 6f * rng.nextDouble(), rng);
			if (printTrace) {
				sim.trace = new StringBuilder();
			}
			for (int k = 0; k < 600 && sim.s[0].b.health > 0 && sim.s[1].b.health > 0; k++) {
				sim.step();
			}
			if (sim.row > 0) {
				sim.runs.add(sim.row);
			}
			for (int r : sim.runs) {
				runHist[Math.min(11, r)]++;
				byLag[agentLag][3] += r >= 3 ? 1 : 0;
			}
			byLag[agentLag][0]++;
			byLag[agentLag][1] += sim.hits[0];
			byLag[agentLag][2] += sim.hits[1];
			totalHits += sim.hits[0];
			totalTaken += sim.hits[1];
			longestAll = Math.max(longestAll, sim.longest);
			sec += sim.secondWithin20;
			firsts += sim.firstHits;
			allGaps.addAll(sim.gaps);
			backTicks += sim.botBackTicks;
			ticks += sim.t;
			cb[0] += sim.cbFollowed;
			cb[1] += sim.cbTraded;
			cb[2] += sim.cbTheirFirst;
			cb[3] += sim.cbTimedOut;
			cbDistAll += sim.cbDist;
			if (sim.trace != null && sim.longest >= traceLongest && !traced) {
				traced = true;
				System.out.println("fight " + f + " agentLag " + agentLag + " bot " + bot.describe() + " back " + bot.backAfterHit + " closeIn "
						+ bot.closeIn + " spacing " + bot.spacing + " swingCharge " + bot.swingCharge);
				System.out.println(sim.trace.substring(0, Math.min(sim.trace.length(), 14000)));
			}
		}
		allGaps.sort(null);
		int runsN = Arrays.stream(runHist).sum();
		int reach3 = 0, reach5 = 0, reach10 = 0;
		for (int r = 3; r < 12; r++) {
			reach3 += runHist[r];
		}
		for (int r = 5; r < 12; r++) {
			reach5 += runHist[r];
		}
		reach10 = runHist[10] + runHist[11];
		int le20 = 0;
		for (int g : allGaps) {
			le20 += g <= 20 ? 1 : 0;
		}
		StringBuilder lagLine = new StringBuilder();
		for (int l = 1; l <= 4; l++) {
			lagLine.append(String.format(Locale.ROOT, " L%d: f%d hits %.1f taken %.1f runs3+ %d;", l, byLag[l][0], byLag[l][1] / (double) Math.max(1, byLag[l][0]),
					byLag[l][2] / (double) Math.max(1, byLag[l][0]), byLag[l][3]));
		}
		int cbAll = cb[0] + cb[1] + cb[2] + cb[3];
		lagLine.append(String.format(Locale.ROOT, "%n   after its hits (%d): next hit within 20 %.0f%%, their hit first within 3 %.0f%%, later %.0f%%, neither within 20 %.0f%% (distance then %.2f)",
				cbAll, 100.0 * cb[0] / Math.max(1, cbAll), 100.0 * cb[1] / Math.max(1, cbAll), 100.0 * cb[2] / Math.max(1, cbAll),
				100.0 * cb[3] / Math.max(1, cbAll), cbDistAll / Math.max(1, cb[3])));
		return lagLine + "\n" + String.format(Locale.ROOT,
				"%-34s fights %d hits %d taken %d | runs %d: 1=%d 2=%d 3+=%d 5+=%d 10+=%d longest %d | own-hit gaps p25/p50/p75 %s/%s/%s, <=20: %d of %d | bot S %.0f%% of ticks",
				name, fights, totalHits, totalTaken, runsN, runHist[1], runHist[2], reach3, reach5, reach10, longestAll,
				q(allGaps, 0.25), q(allGaps, 0.5), q(allGaps, 0.75), le20, allGaps.size(), 100.0 * backTicks / Math.max(1, ticks));
	}

	static String q(List<Integer> a, double p) {
		return a.isEmpty() ? "-" : String.valueOf(a.get(Math.min(a.size() - 1, (int) (p * a.size()))));
	}

	public static void main(String[] args) {
		int n = args.length > 0 ? Integer.parseInt(args[0]) : 200;
		boolean tr = args.length > 1 && args[1].equals("trace");
		String only = args.length > 2 ? args[2] : null;
		traceLongest = args.length > 3 ? Integer.parseInt(args[3]) : 0;
		botVariant = args.length > 4 ? args[4] : "run93";
		GATE = args.length > 5 ? Float.parseFloat(args[5]) : 1f;
		System.out.println("bot variant " + botVariant + ", COMBO_GAP " + COMBO_GAP);
		Style teacher = Teacher.style(taughtStage4());
		teacher.swingCharge = 1f;
		Style teacherOld = teacher.copy();
		teacherOld.spaceAfterHit = false;
		teacherOld.hopsAfterStun = true;
		Style teacherNoSpacing = teacher.copy();
		teacherNoSpacing.spaceAfterHit = false;
		teacherNoSpacing.hopsAfterStun = false;
		teacherNoSpacing.p[Technique.T10.ordinal()] = 0f;
		teacherNoSpacing.p[Technique.T11B.ordinal()] = 0f;
		teacherNoSpacing.p[Technique.T11C.ordinal()] = 0f;
		String[] names = {"chaser (W+sprint, swing at 1.0)", "lunger (T11a at a backer)", "teacher (run 92/93)", "teacher, pre-run-92 (hops)",
				"teacher, no space, no hops/T10", "noisy (half-trained)"};
		@SuppressWarnings("unchecked")
		java.util.function.Supplier<Policy>[] pols = new java.util.function.Supplier[] {ComboSim::chaser, () -> lunger(0.45f, 3.0, 5.5),
				() -> player(teacher, true), () -> player(teacherOld, true), () -> player(teacherNoSpacing, true), ComboSim::noisy};
		for (int i = 0; i < pols.length; i++) {
			if (only != null && !names[i].startsWith(only)) {
				continue;
			}
			System.out.println(run(names[i], pols[i], n, 12345L, tr));
		}
	}
}
