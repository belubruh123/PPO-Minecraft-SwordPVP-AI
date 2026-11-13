package dev.swordai.client;

import dev.swordai.SwordAi;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Fighter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Every round of {@code /swordai fight}, tick by tick, for working out afterwards why a round was
 * lost and what the opponent did: the distance, both swords, the keys and which rule pressed them,
 * how the opponent moved, and every hit either way with what kind it was likely to be. A round ends
 * with a death on either side. Each is written to {@code swordai/fights} as a small gzipped text
 * file, a summary on top and the timeline below, and a line goes to {@code swordai/fights/rounds.csv}.
 * Only the newest {@link #KEPT} rounds are kept, a few megabytes at most.
 *
 * <p>The user, 18 October: store more of the fights "so when I tested it on my friends again you
 * can see the round it lose and know exactly the reason it fails and what opponent is doing".
 */
final class RoundRecorder {
	/** Rounds kept on disk; older ones are deleted. */
	static final int KEPT = 200;
	/** Shorter rounds (a few seconds of nothing) are not worth a file. */
	private static final int MIN_TICKS = 40;
	/** A round longer than this is cut: a quarter of an hour, a few hundred kilobytes before compression. */
	private static final int MAX_TICKS = 18_000;
	/** The ticks before a hit taken that say how it came. */
	private static final int HISTORY = 12;
	/** No hit either way for this long: the next hit begins a new exchange. */
	private static final int EXCHANGE_GAP = 20;
	private static final float REACH = 3.0f;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** One tick, as recorded. */
	private record Tick(float dist, float myCharge, float oppSword, float myHp, float oppHp, boolean myGround, boolean oppGround,
						boolean oppSprint, float oppUp, float oppToward, float oppSide, float myToward, String keys, String rules,
						float oppSideSigned, float oppFacing, float oppPitch, boolean oppSwinging, String event) {
	}

	private final List<Tick> timeline = new ArrayList<>();
	private String opponent = "?";
	private String arm;
	private LocalDateTime started;
	private boolean open;
	/** What this tick's own swing came to; filled in at the end of the tick. */
	private String ownSwing = "";

	// Counted as the round goes.
	private int dealt;
	private int taken;
	private float damageDealt;
	private float damageTaken;
	private float lastMyHp = -1f;
	private float lastOppHp = -1f;
	private int swings;
	private int whiffs;
	private int ownCrits;
	private int ownSprintHits;
	private int takenCrit;
	private int takenInCooldown;
	private int takenReady;
	private int takenJumpIn;
	private int takenStanding;
	private int oppBackedAfterHit;
	private int answered;
	private int combosTaken;
	private int longestCombo;
	private int takenRow;
	private int firstWon;
	private int firstLost;
	private int sinceAnyHit = 1000;
	private int answerWindow;
	private int backWatch;
	private int backTicks;
	private int entersOnReady;
	private int entersOnCooling;
	private float lastDist = 99f;
	private long oppApproach;
	private long oppBack;
	private long oppStill;
	private long oppStrafe;
	private int oppJumps;
	private boolean oppWasGround = true;
	private double distSum;
	private long distTicks;

	boolean open() {
		return open;
	}

	void start(String opponentName) {
		start(opponentName, null);
	}

	/**
	 * A new round. {@code armTag}: the live A/B pool's arm that plays it ({@code AbPool}), or null; it goes on the round file's
	 * first line (13 Nov, so tools/duelday.py can split the user's real rounds by network without matching the game log).
	 */
	void start(String opponentName, String armTag) {
		timeline.clear();
		opponent = opponentName;
		arm = armTag;
		started = LocalDateTime.now();
		open = true;
		ownSwing = "";
		dealt = taken = swings = whiffs = ownCrits = ownSprintHits = 0;
		takenCrit = takenInCooldown = takenReady = takenJumpIn = takenStanding = oppBackedAfterHit = answered = 0;
		combosTaken = longestCombo = takenRow = firstWon = firstLost = 0;
		damageDealt = damageTaken = 0f;
		lastMyHp = lastOppHp = -1f;
		sinceAnyHit = 1000;
		answerWindow = backWatch = backTicks = 0;
		entersOnReady = entersOnCooling = 0;
		lastDist = 99f;
		oppApproach = oppBack = oppStill = oppStrafe = 0;
		oppJumps = 0;
		oppWasGround = true;
		distSum = 0;
		distTicks = 0;
	}

	/** One tick of the fight, after the decision. {@code rules} is {@link Fighter#rules()}. */
	void tick(LocalPlayer me, Player opp, BodyState body, int[] action, int rules, boolean dealtNow, boolean takenNow) {
		if (!open || timeline.size() >= MAX_TICKS) {
			return;
		}
		Vec3 eye = me.getEyePosition();
		float dist = (float) Math.sqrt(opp.getBoundingBox().distanceToSqr(eye));
		double ux = opp.getX() - me.getX();
		double uz = opp.getZ() - me.getZ();
		double flat = Math.sqrt(ux * ux + uz * uz);
		ux = flat > 1e-6 ? ux / flat : 0;
		uz = flat > 1e-6 ? uz / flat : 0;
		double ovx = opp.getX() - opp.xo;
		double ovz = opp.getZ() - opp.zo;
		float oppToward = (float) -(ovx * ux + ovz * uz);
		float oppSide = (float) Math.abs(ovx * uz - ovz * ux);
		// 13 Nov (learning from the opponents' winning moves, the user's 12 Nov request): what is needed to read the opponent's own
		// keys back from a lost round: the side speed with its sign (plus: to its right as it faces this player), where its head looks
		// (degrees off the line to this player; and its pitch) and whether its arm is swinging.
		float oppSideSigned = (float) (ovx * uz - ovz * ux);
		double toMeYaw = Math.toDegrees(Math.atan2(-(me.getX() - opp.getX()), me.getZ() - opp.getZ()));
		float oppFacing = (float) net.minecraft.util.Mth.wrapDegrees(opp.getYHeadRot() - toMeYaw);
		float oppPitch = opp.getXRot();
		boolean oppSwinging = opp.swinging;
		float myToward = (float) ((me.getX() - me.xo) * ux + (me.getZ() - me.zo) * uz);
		float oppUp = (float) (opp.getY() - opp.yo);
		float myCharge = me.getAttackStrengthScale(0f);
		float oppSword = body.opponentCharge();
		float myHp = me.getHealth();
		float oppHp = opp.getHealth();
		if (lastMyHp >= 0f && myHp < lastMyHp) {
			damageTaken += lastMyHp - myHp;
		}
		if (lastOppHp >= 0f && oppHp < lastOppHp) {
			damageDealt += lastOppHp - oppHp;
		}
		lastMyHp = myHp;
		lastOppHp = oppHp;
		boolean oppGround = opp.onGround();
		if (oppWasGround && !oppGround && oppUp > 0.05f) {
			oppJumps++;
		}
		oppWasGround = oppGround;
		if (oppToward > 0.05f) {
			oppApproach++;
		} else if (oppToward < -0.05f) {
			oppBack++;
		} else if (oppSide > 0.05f) {
			oppStrafe++;
		} else {
			oppStill++;
		}
		if (dist < 8f) {
			distSum += dist;
			distTicks++;
		}
		// Coming into reach: with the opponent's sword ready, or on its way back.
		if (lastDist > REACH && dist <= REACH) {
			if (oppSword >= 0.9f) {
				entersOnReady++;
			} else {
				entersOnCooling++;
			}
		}
		lastDist = dist;

		StringBuilder ev = new StringBuilder();
		if (dealtNow || takenNow) {
			if (sinceAnyHit >= EXCHANGE_GAP) {
				// Who struck first in this exchange.
				if (dealtNow && !takenNow) {
					firstWon++;
					ev.append("first+ ");
				} else if (takenNow && !dealtNow) {
					firstLost++;
					ev.append("first- ");
				}
			}
			sinceAnyHit = 0;
		} else if (sinceAnyHit < 1000) {
			sinceAnyHit++;
		}
		if (dealtNow) {
			dealt++;
			ev.append("DEALT ");
			if (answerWindow > 0) {
				answered++;
				answerWindow = 0;
			}
			if (takenRow >= 3) {
				combosTaken++;
			}
			takenRow = 0;
		}
		if (takenNow) {
			taken++;
			takenRow++;
			longestCombo = Math.max(longestCombo, takenRow);
			answerWindow = 15;
			ev.append("TAKEN");
			// How it came, from the ticks just before (what this client saw of the opponent then).
			boolean crit = false;
			boolean jumpIn = false;
			boolean standing = true;
			int n = timeline.size();
			for (int i = Math.max(0, n - HISTORY); i < n; i++) {
				Tick t = timeline.get(i);
				if (i >= n - 4 && !t.oppGround() && t.oppUp() < 0f && !t.oppSprint()) {
					crit = true;
				}
				if (!t.oppGround() && t.dist() > REACH) {
					jumpIn = true;
				}
				if (i >= n - 4 && (Math.abs(t.oppToward()) > 0.04f || t.oppSide() > 0.04f)) {
					standing = false;
				}
			}
			if (crit) {
				takenCrit++;
				ev.append(":crit?");
			}
			if (jumpIn) {
				takenJumpIn++;
				ev.append(":jumped-in");
			}
			if (standing && oppGround) {
				takenStanding++;
				ev.append(":opp-standing");
			}
			if (myCharge < 0.9f) {
				takenInCooldown++;
				ev.append(":my-sword-empty");
			} else {
				takenReady++;
				ev.append(":my-sword-ready");
			}
			backWatch = 12;
			backTicks = 0;
			ev.append(' ');
		} else if (answerWindow > 0) {
			answerWindow--;
		}
		if (backWatch > 0) {
			// After its hit, did the opponent walk straight back out (S)?
			if (oppToward < -0.05f && oppGround) {
				backTicks++;
			}
			if (--backWatch == 0 && backTicks >= 3) {
				oppBackedAfterHit++;
				ev.append("opp-backed-out ");
			}
		}
		timeline.add(new Tick(dist, myCharge, oppSword, myHp, oppHp, me.onGround(), oppGround, opp.isSprinting(), oppUp,
				oppToward, oppSide, myToward, keys(action), rulesText(rules), oppSideSigned, oppFacing, oppPitch, oppSwinging, ev.toString().trim()));
	}

	/** At the end of the tick: what its swing came to, as this game made it out ({@code crit}, {@code sprint}, ...), or a miss. */
	void swing(String kind) {
		if (!open || timeline.isEmpty()) {
			return;
		}
		swings++;
		if ("miss".equals(kind)) {
			whiffs++;
		} else if ("crit".equals(kind)) {
			ownCrits++;
		} else if ("sprint".equals(kind)) {
			ownSprintHits++;
		}
		Tick t = timeline.remove(timeline.size() - 1);
		String ev = (t.event().isEmpty() ? "" : t.event() + " ") + "swing:" + kind;
		timeline.add(new Tick(t.dist(), t.myCharge(), t.oppSword(), t.myHp(), t.oppHp(), t.myGround(), t.oppGround(), t.oppSprint(),
				t.oppUp(), t.oppToward(), t.oppSide(), t.myToward(), t.keys(), t.rules(), t.oppSideSigned(), t.oppFacing(), t.oppPitch(),
				t.oppSwinging(), ev));
	}

	/**
	 * The round is over: {@code won}, {@code lost}, or {@code open} (the opponent left, or it was
	 * stopped). Writes it out and returns the one line that says how it went, or null if too short.
	 */
	String finish(String result, int oppPingMillis) {
		if (!open) {
			return null;
		}
		open = false;
		if (timeline.size() < MIN_TICKS && dealt + taken == 0) {
			return null;
		}
		String line = closeLine(result, oppPingMillis);
		List<String> why = reasons();
		try {
			Path dir = SwordFiles.fightsDir();
			Files.createDirectories(dir);
			String name = started.format(STAMP) + "-" + ModelPack.clean(opponent) + "-" + result + ".txt.gz";
			write(dir.resolve(name), line, why, oppPingMillis);
			Path csv = dir.resolve("rounds.csv");
			if (!Files.exists(csv)) {
				Files.writeString(csv, "time,result,opponent,ping,seconds,dealt,taken,damageDealt,damageTaken,firstWon,firstLost,takenCrit,takenInCooldown,"
						+ "takenReady,takenJumpIn,takenStanding,oppBackedAfterHit,combosTaken3,longestCombo,swings,whiffs,entersOnReady,entersOnCooling,file\n",
						StandardCharsets.UTF_8);
			}
			Files.writeString(csv, String.format(Locale.ROOT, "%s,%s,%s,%d,%.1f,%d,%d,%.0f,%.0f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",
					started.format(SHOWN), result, opponent, oppPingMillis, timeline.size() / 20f, dealt, taken, damageDealt, damageTaken,
					firstWon, firstLost, takenCrit, takenInCooldown, takenReady, takenJumpIn, takenStanding, oppBackedAfterHit, combosTaken,
					longestCombo, swings, whiffs, entersOnReady, entersOnCooling, name), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
			prune(dir);
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not save the round", e);
		}
		return line + (why.isEmpty() ? "" : ". " + why.get(0));
	}

	/**
	 * The round is over and goes to {@code dir/fileName}, in the same text as {@link #finish}: the benchmark's episodes
	 * (docs/BENCHMARK.md, SchoolSession's bench mode), which two networks play on the same seeds and are compared file by file.
	 * So every round is written however short (one missing from a network's list would unpair the comparison), with no line in
	 * rounds.csv and nothing pruned (those are the user's real fights). Written under a temporary name and then moved, so that
	 * a reader of the folder never meets half a file. Returns the line that says how it went, or null if nothing was written.
	 */
	String finishTo(Path dir, String fileName, String result) {
		if (!open) {
			return null;
		}
		open = false;
		String line = closeLine(result, 0);
		List<String> why = reasons();
		Path file = dir.resolve(fileName);
		Path part = dir.resolve(fileName + ".part");
		try {
			Files.createDirectories(dir);
			write(part, line, why, 0);
			Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not save the round to " + file, e);
			return null;
		}
		return line + (why.isEmpty() ? "" : ". " + why.get(0));
	}

	/** The round's one line (a combo still running at the end counted first). */
	private String closeLine(String result, int oppPingMillis) {
		if (takenRow >= 3) {
			combosTaken++;
		}
		// 13 Nov 23:00 (the user: "dealt and taken are wrong, it won but has lower dealt"): a server that hides the other player's
		// health leaves damage dealt at 0, and a hit count weighs a crit (1.5x damage) as a plain hit. So the crits are shown with the
		// hits, a crit-weighted count (a crit as 1.5 hits) is given, and an unreadable damage dealt says so instead of 0.
		String dmgDealt = damageDealt > 0f ? String.format(Locale.ROOT, "%.0f", damageDealt) : "hidden by the server";
		// The prefix up to "taken" stays as it was: tools/duelday.py and roundidx.py split on " dealt / ".
		return String.format(Locale.ROOT, "%s vs %s (%d ms): %.1f s, hits %d dealt / %d taken, damage %s / %.0f; crits %d dealt / %d likely taken, crit-weighted hits %.1f / %.1f",
				result.toUpperCase(Locale.ROOT), opponent, oppPingMillis, timeline.size() / 20f, dealt, taken, dmgDealt, damageTaken,
				ownCrits, takenCrit, dealt + 0.5f * ownCrits, taken + 0.5f * takenCrit);
	}

	/** The round's file: the summary on top, the reasons, then the timeline a tick a line. */
	private void write(Path file, String line, List<String> why, int oppPingMillis) throws IOException {
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
			 Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
			w.write("SwordAI round, " + started.format(SHOWN) + (arm != null ? ", A/B arm " + arm : "") + "\n" + line + "\n\n");
			w.write(details(oppPingMillis));
			w.write("\nWhat decided it, most first:\n");
			for (String r : why) {
				w.write("  - " + r + "\n");
			}
			w.write("\nTimeline, one line a tick. dist: eye to the opponent's hitbox. sword: its own charge, and the opponent's as read off its arm. "
					+ "opp: on ground (g) or in the air (a), s if sprinting, toward = blocks a tick it comes at it (minus: going away), side = sideways. "
					+ "keys: W/S, A/D, r = sprint, j = jump, x = attack. rules: which rule had the keys (" + RULE_NAMES + ").\n");
			w.write("Since 13 Nov: oppSideS = sideways with its sign (plus: to its right as it faces this player), oppFacing = degrees its head "
					+ "turns off the line to this player, oppPitch, oppSwing = 1 while its arm swings.\n");
			w.write("tick,dist,mySword,oppSword,myHp,oppHp,me,opp,oppUp,oppToward,oppSide,myToward,keys,rules,oppSideS,oppFacing,oppPitch,oppSwing,event\n");
			for (int i = 0; i < timeline.size(); i++) {
				Tick t = timeline.get(i);
				w.write(String.format(Locale.ROOT, "%d,%.2f,%.2f,%.2f,%.0f,%.0f,%s,%s%s,%.2f,%.2f,%.2f,%.2f,%s,%s,%.2f,%.0f,%.0f,%d,%s\n",
						i, t.dist(), t.myCharge(), t.oppSword(), t.myHp(), t.oppHp(), t.myGround() ? "g" : "a",
						t.oppGround() ? "g" : "a", t.oppSprint() ? "s" : "", t.oppUp(), t.oppToward(), t.oppSide(), t.myToward(),
						t.keys(), t.rules(), t.oppSideSigned(), t.oppFacing(), t.oppPitch(), t.oppSwinging() ? 1 : 0, t.event()));
			}
		}
	}

	private String details(int oppPingMillis) {
		long moving = Math.max(1, oppApproach + oppBack + oppStill + oppStrafe);
		return String.format(Locale.ROOT, """
				Its hits: %d (%d critical, %d with the sprint), swings %d, of which missed or at nothing %d.
				Hits taken: %d. Likely critical %d; from an opponent that came in through the air %d; from one standing still %d.
				  taken with its own sword empty (punished in its cooldown) %d; with its sword ready (beaten to the hit) %d.
				  answered within 15 ticks %d; combos of 3+ taken %d, longest %d.
				First hit of an exchange: it %d, the opponent %d.
				Came into reach with the opponent's sword ready %d times, with it on its way back %d times.
				The opponent: came at it %.0f%% of the time, went back %.0f%%, sideways %.0f%%, still %.0f%%; jumps %d; backed straight out after its hit %d times;
				  distance kept (within 8 blocks) %.2f on average; ping %d ms.
				""", dealt, ownCrits, ownSprintHits, swings, whiffs, taken, takenCrit, takenJumpIn, takenStanding, takenInCooldown, takenReady,
				answered, combosTaken, longestCombo, firstWon, firstLost, entersOnReady, entersOnCooling,
				100.0 * oppApproach / moving, 100.0 * oppBack / moving, 100.0 * oppStrafe / moving, 100.0 * oppStill / moving, oppJumps,
				oppBackedAfterHit, distTicks == 0 ? 0.0 : distSum / distTicks, oppPingMillis);
	}

	/** The likely reasons, biggest first; a line each. */
	private List<String> reasons() {
		List<Object[]> r = new ArrayList<>();
		r.add(new Object[] {takenReady, "beaten to the hit with its own sword ready " + takenReady + "x: the opponent spaced it (hit it first at the edge of reach)"});
		r.add(new Object[] {takenInCooldown, "hit while its own sword was empty " + takenInCooldown + "x: punished in its cooldown"});
		r.add(new Object[] {takenCrit, "took " + takenCrit + " likely critical hits" + (takenJumpIn > 0 ? ", " + takenJumpIn + " from jumps in from outside its reach" : "")});
		r.add(new Object[] {takenStanding, "hit " + takenStanding + "x by an opponent standing still: it walked into a ready sword"});
		r.add(new Object[] {oppBackedAfterHit, "the opponent backed straight out with S after its hit " + oppBackedAfterHit + "x (crit/hit and out)"});
		r.add(new Object[] {firstLost - firstWon, "lost the first hit of an exchange " + firstLost + "x, won it " + firstWon + "x"});
		r.add(new Object[] {combosTaken * 2, "comboed (3+ hits in a row) " + combosTaken + "x, longest " + longestCombo});
		r.add(new Object[] {entersOnReady - entersOnCooling, "came into reach on a ready sword " + entersOnReady + "x, on one coming back " + entersOnCooling + "x"});
		r.add(new Object[] {whiffs, "missed " + whiffs + " of " + swings + " swings"});
		r.sort((a, b) -> Integer.compare((int) b[0], (int) a[0]));
		List<String> out = new ArrayList<>();
		for (Object[] e : r) {
			if ((int) e[0] > 0) {
				out.add((String) e[1]);
			}
		}
		return out;
	}

	private static String keys(int[] a) {
		StringBuilder s = new StringBuilder();
		s.append(a[ActionSpace.FORWARD] == 1 ? 'W' : a[ActionSpace.FORWARD] == 2 ? 'S' : '-');
		s.append(a[ActionSpace.STRAFE] == 1 ? 'A' : a[ActionSpace.STRAFE] == 2 ? 'D' : '-');
		s.append(a[ActionSpace.SPRINT] == 1 ? 'r' : '-');
		s.append(a[ActionSpace.JUMP] == 1 ? 'j' : '-');
		s.append(a[ActionSpace.ATTACK] == 1 ? 'x' : '-');
		return s.toString();
	}

	static final String RULE_NAMES = "a = answer swing, k = swing kept for the way down, m = run at a jumper, r = jump reset, b = backed out, "
			+ "t = step back, J = jump made for it, c = camera after a hit, l = looking for a lost opponent, s = straight in";
	private static final char[] RULE_CHARS = {'a', 'k', 'm', 'r', 'b', 't', 'J', 'c', 'l', 's'};

	private static String rulesText(int rules) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < RULE_CHARS.length; i++) {
			if ((rules & (1 << i)) != 0) {
				s.append(RULE_CHARS[i]);
			}
		}
		return s.toString();
	}

	/** Keeps the newest {@link #KEPT} rounds. */
	private static void prune(Path dir) throws IOException {
		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".txt.gz")).sorted().toList());
		}
		for (int i = 0; i < files.size() - KEPT; i++) {
			Files.deleteIfExists(files.get(i));
		}
	}
}
