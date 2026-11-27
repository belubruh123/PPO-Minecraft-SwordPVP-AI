package dev.swordai.client;

import dev.swordai.SwordAi;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.server.InventoryObs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Records every duel tick-by-tick in the exact gzipped CSV format read by {@code tools/bench.py},
 * {@code tools/duels.py}, and {@link dev.swordai.core.league.HumanImitation}.
 */
final class RoundRecorder {
	static final int KEPT = 200;
	private static final int MIN_TICKS = 40;
	private static final int MAX_TICKS = 18_000;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private record Tick(float dist, float myCharge, float oppSword, float myHp, float oppHp, boolean myGround,
			boolean oppGround, boolean oppSprint, float oppUp, float oppToward, float oppSide, float myToward,
			String keys, float oppSideSigned, float oppFacing, float oppPitch, boolean oppSwinging, String event,
			String myItem, String oppItem, boolean myUse, boolean oppUse) {
	}

	private final List<Tick> timeline = new ArrayList<>();
	private String opponent = "?";
	private LocalDateTime started;
	private boolean open;
	private int dealt, taken, ownCrits, takenCrit;
	private float damageDealt, damageTaken;
	private float lastMyHp = -1f, lastOppHp = -1f;

	boolean open() {
		return open;
	}

	void start(String opponentName) {
		timeline.clear();
		opponent = opponentName == null ? "?" : opponentName;
		started = LocalDateTime.now();
		open = true;
		dealt = taken = ownCrits = takenCrit = 0;
		damageDealt = damageTaken = 0f;
		lastMyHp = lastOppHp = -1f;
	}

	void tick(LocalPlayer me, Player opp, BodyState body, int[] action, boolean dealtNow, boolean takenNow) {
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
		float oppSideSigned = (float) (ovx * uz - ovz * ux);
		float oppSide = Math.abs(oppSideSigned);
		double toMeYaw = Math.toDegrees(Math.atan2(-(me.getX() - opp.getX()), me.getZ() - opp.getZ()));
		float oppFacing = (float) Mth.wrapDegrees(opp.getYHeadRot() - toMeYaw);
		float oppPitch = opp.getXRot();
		float myToward = (float) ((me.getX() - me.xo) * ux + (me.getZ() - me.zo) * uz);
		float oppUp = (float) (opp.getY() - opp.yo);
		float myCharge = me.getAttackStrengthScale(0f);
		float oppSword = body.opponentCharge();
		float myHp = me.getHealth();
		float oppHp = opp.getHealth();
		if (lastMyHp >= 0f && myHp < lastMyHp) damageTaken += lastMyHp - myHp;
		if (lastOppHp >= 0f && oppHp < lastOppHp) damageDealt += lastOppHp - oppHp;
		lastMyHp = myHp;
		lastOppHp = oppHp;

		StringBuilder ev = new StringBuilder();
		if (dealtNow) {
			dealt++;
			ev.append("DEALT ");
		}
		if (takenNow) {
			taken++;
			ev.append("TAKEN");
			boolean crit = !opp.onGround() && oppUp < 0f && !opp.isSprinting();
			if (crit) {
				takenCrit++;
				ev.append(":crit?");
			}
			ev.append(myCharge < 0.9f ? ":my-sword-empty " : ":my-sword-ready ");
		}
		timeline.add(new Tick(dist, myCharge, oppSword, myHp, oppHp, me.onGround(), opp.onGround(),
				opp.isSprinting(), oppUp, oppToward, oppSide, myToward, keys(action),
				oppSideSigned, oppFacing, oppPitch, opp.swinging, ev.toString().trim(),
				InventoryObs.itemTag(me.getMainHandItem()), InventoryObs.itemTag(opp.getMainHandItem()),
				me.isUsingItem(), opp.isUsingItem()));
	}

	void swing(String kind) {
		if (!open || timeline.isEmpty()) {
			return;
		}
		if ("crit".equals(kind)) ownCrits++;
		Tick t = timeline.remove(timeline.size() - 1);
		String ev = (t.event().isEmpty() ? "" : t.event() + " ") + "swing:" + kind;
		timeline.add(new Tick(t.dist(), t.myCharge(), t.oppSword(), t.myHp(), t.oppHp(), t.myGround(),
				t.oppGround(), t.oppSprint(), t.oppUp(), t.oppToward(), t.oppSide(), t.myToward(),
				t.keys(), t.oppSideSigned(), t.oppFacing(), t.oppPitch(), t.oppSwinging(), ev,
				t.myItem(), t.oppItem(), t.myUse(), t.oppUse()));
	}

	String finish(String result, int oppPingMillis) {
		if (!open) return null;
		open = false;
		if (timeline.size() < MIN_TICKS && dealt + taken == 0) return null;
		String line = closeLine(result, oppPingMillis);
		try {
			Path dir = SwordFiles.fightsDir();
			Files.createDirectories(dir);
			String name = started.format(STAMP) + "-" + ModelPack.clean(opponent) + "-" + result + ".txt.gz";
			write(dir.resolve(name), line);
			prune(dir);
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not save round", e);
		}
		return line;
	}

	String finishTo(Path dir, String fileName, String result) {
		if (!open) return null;
		open = false;
		String line = closeLine(result, 0);
		Path file = dir.resolve(fileName);
		Path part = dir.resolve(fileName + ".part");
		try {
			Files.createDirectories(dir);
			write(part, line);
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not save bench round " + file, e);
			return null;
		}
		return line;
	}

	private String closeLine(String result, int ping) {
		String dmg = damageDealt > 0f ? String.format(Locale.ROOT, "%.0f", damageDealt) : "hidden by the server";
		return String.format(Locale.ROOT,
				"%s vs %s (%d ms): %.1f s, hits %d dealt / %d taken, damage %s / %.0f; crits %d dealt / %d likely taken, crit-weighted hits %.1f / %.1f",
				result.toUpperCase(Locale.ROOT), opponent, ping, timeline.size() / 20f, dealt, taken,
				dmg, damageTaken, ownCrits, takenCrit, dealt + 0.5f * ownCrits, taken + 0.5f * takenCrit);
	}

	private void write(Path file, String line) throws IOException {
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
			 Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
			w.write("SwordAI round, " + started.format(SHOWN) + "\n" + line + "\n\n");
			w.write("tick,dist,mySword,oppSword,myHp,oppHp,me,opp,oppUp,oppToward,oppSide,myToward,keys,rules,oppSideS,oppFacing,oppPitch,oppSwing,event,myItem,oppItem,myUse,oppUse\n");
			for (int i = 0; i < timeline.size(); i++) {
				Tick t = timeline.get(i);
				w.write(String.format(Locale.ROOT,
						"%d,%.2f,%.2f,%.2f,%.0f,%.0f,%s,%s%s,%.2f,%.2f,%.2f,%.2f,%s,,%.2f,%.0f,%.0f,%d,%s,%s,%s,%d,%d\n",
						i, t.dist(), t.myCharge(), t.oppSword(), t.myHp(), t.oppHp(),
						t.myGround() ? "g" : "a", t.oppGround() ? "g" : "a", t.oppSprint() ? "s" : "",
						t.oppUp(), t.oppToward(), t.oppSide(), t.myToward(), t.keys(),
						t.oppSideSigned(), t.oppFacing(), t.oppPitch(), t.oppSwinging() ? 1 : 0, t.event(),
						t.myItem(), t.oppItem(), t.myUse() ? 1 : 0, t.oppUse() ? 1 : 0));
			}
		}
	}

	private static String keys(int[] a) {
		return "" + (a[ActionSpace.FORWARD] == 1 ? 'W' : a[ActionSpace.FORWARD] == 2 ? 'S' : '-')
				+ (a[ActionSpace.STRAFE] == 1 ? 'A' : a[ActionSpace.STRAFE] == 2 ? 'D' : '-')
				+ (a[ActionSpace.SPRINT] == 1 ? 'r' : '-')
				+ (a[ActionSpace.JUMP] == 1 ? 'j' : '-')
				+ (a[ActionSpace.ATTACK] == 1 ? 'x' : '-');
	}

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
