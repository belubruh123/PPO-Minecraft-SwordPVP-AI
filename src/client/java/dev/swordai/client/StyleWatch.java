package dev.swordai.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.swordai.core.curriculum.PersonStyle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Counts how the opponent of a {@code /swordai fight} fights, from what this game can see of it, into
 * that player's {@link PersonStyle}: where it keeps its distance, whether it answers the fighter's
 * hits on its way down, whether it comes after a fighter that backs off with a hit from the air, how
 * far a hit sends it, whether it jumps with the hits it takes. Saved when the fight stops. Nothing of
 * it reaches the network in the fight; training makes a scripted opponent of it.
 */
final class StyleWatch {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** No hit either way for this long, and both on the ground: the distance kept is read. */
	private static final int QUIET_TICKS = 10;
	private static final int ANSWER_FROM = 3;
	private static final int ANSWER_TO = 13;
	/** Going away from the opponent this fast for this long is backing off; a hit from the air within the window after it is a chase. */
	private static final double BACK_SPEED = 0.1;
	private static final int BACK_TICKS = 3;
	private static final int CHASE_WINDOW = 20;
	/** The opponent's speed away, read this many ticks after the fighter's hit was seen to land. */
	private static final int KNOCK_READ_TICK = 2;

	private PersonStyle style;
	private int sinceDealt = 1000;
	private int sinceTaken = 1000;
	private int backing;
	private int sinceBackOff = 1000;
	private boolean answeredThis;
	private boolean groundAtHit;
	private boolean jumpSeen;
	private double lastOppY = Double.NaN;

	/** The opponent this fight is against; what is known of it from earlier fights is added to. */
	void start(String name) {
		style = load(name);
		style.fights++;
		sinceDealt = 1000;
		sinceTaken = 1000;
		backing = 0;
		sinceBackOff = 1000;
		lastOppY = Double.NaN;
	}

	/** One tick of the fight, after who hit whom has been worked out. */
	void tick(LocalPlayer me, Player opp, boolean dealtNow, boolean takenNow) {
		if (style == null) {
			return;
		}
		style.ticks++;
		sinceDealt = Math.min(sinceDealt + 1, 1000);
		sinceTaken = Math.min(sinceTaken + 1, 1000);
		sinceBackOff = Math.min(sinceBackOff + 1, 1000);
		Vec3 eye = me.getEyePosition();
		double dist = Math.sqrt(opp.getBoundingBox().distanceToSqr(eye));
		double dx = opp.getX() - me.getX();
		double dz = opp.getZ() - me.getZ();
		double flat = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
		double ux = dx / flat;
		double uz = dz / flat;
		Vec3 mine = me.getDeltaMovement();
		Vec3 theirs = new Vec3(opp.getX() - opp.xo, opp.getY() - opp.yo, opp.getZ() - opp.zo);
		double myAway = -(mine.x * ux + mine.z * uz);
		double theirAway = theirs.x * ux + theirs.z * uz;
		double theirAcross = Math.abs(-theirs.x * uz + theirs.z * ux);
		boolean oppGround = opp.onGround();
		boolean oppFalling = !oppGround && theirs.y < -0.03;

		if (sinceDealt >= QUIET_TICKS && sinceTaken >= QUIET_TICKS && me.onGround() && oppGround && dist < 8.0) {
			style.quietTicks++;
			style.quietDistanceSum += dist;
		}
		if (theirAcross > 0.1 && theirAcross > Math.abs(theirAway)) {
			style.strafeTicks++;
		}
		// Backing off: going away from it for a few ticks in a row.
		backing = myAway >= BACK_SPEED ? backing + 1 : 0;
		if (backing == BACK_TICKS) {
			style.backOffs++;
			sinceBackOff = 0;
		}
		if (dealtNow) {
			style.hitsDealt++;
			sinceDealt = 0;
			answeredThis = false;
			groundAtHit = oppGround;
			jumpSeen = false;
			if (oppGround) {
				style.groundHitsTaken++;
			}
		}
		if (sinceDealt == KNOCK_READ_TICK) {
			style.knockSamples++;
			style.knockSpeedSum += Math.max(0.0, Math.sqrt(theirs.x * theirs.x + theirs.z * theirs.z));
		}
		if (groundAtHit && !jumpSeen && sinceDealt <= 2 && !Double.isNaN(lastOppY) && opp.getY() - lastOppY > 0.3) {
			// Up by more than a hit lifts it in a tick: it jumped with the hit.
			jumpSeen = true;
			style.jumpsWithHit++;
		}
		if (takenNow) {
			style.hitsTaken++;
			sinceTaken = 0;
			if (oppFalling) {
				style.critsTaken++;
			}
			if (!answeredThis && sinceDealt >= ANSWER_FROM && sinceDealt <= ANSWER_TO) {
				answeredThis = true;
				style.answered++;
				if (oppFalling) {
					style.punishCrits++;
				}
			}
			if (!oppGround && sinceBackOff <= CHASE_WINDOW) {
				style.chasedCrits++;
				sinceBackOff = 1000;
			}
		}
		lastOppY = opp.getY();
	}

	/** The fight is over: what was counted is saved, with whether the fighter lost it. */
	void finish(boolean lost, int pingMillis) {
		if (style == null) {
			return;
		}
		if (lost) {
			style.losses++;
		}
		if (pingMillis > 0) {
			style.pingFights++;
			style.pingSum += pingMillis;
		}
		try {
			save(style);
		} catch (IOException e) {
			// Not worth stopping anything for; the next fight adds to what was saved before.
		}
		style = null;
	}

	static Path dir() {
		return SwordFiles.dir().resolve("people");
	}

	private static Path file(String name) {
		return dir().resolve(ModelPack.clean(name) + ".json");
	}

	static PersonStyle load(String name) {
		try {
			Path f = file(name);
			if (Files.exists(f)) {
				PersonStyle s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), PersonStyle.class);
				if (s != null) {
					return s;
				}
			}
		} catch (IOException | RuntimeException e) {
			// Start that player over.
		}
		PersonStyle s = new PersonStyle();
		s.name = name;
		return s;
	}

	private static void save(PersonStyle s) throws IOException {
		Files.createDirectories(dir());
		Files.writeString(file(s.name), GSON.toJson(s), StandardCharsets.UTF_8);
	}

	/** Every player there is a style of, enough of whom has been seen to make a script of. */
	static java.util.List<PersonStyle> known() {
		java.util.List<PersonStyle> all = new java.util.ArrayList<>();
		if (!Files.isDirectory(dir())) {
			return all;
		}
		try (java.util.stream.Stream<Path> files = Files.list(dir())) {
			for (Path f : (Iterable<Path>) files::iterator) {
				String n = f.getFileName().toString();
				if (n.endsWith(".json")) {
					PersonStyle s = load(n.substring(0, n.length() - 5));
					if (s.enough()) {
						all.add(s);
					}
				}
			}
		} catch (IOException e) {
			// None, then.
		}
		return all;
	}
}
