package dev.swordai.core.school;

import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Technique;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

/**
 * The stages of the technique school (plan part 5), in the order of {@code docs/PVP-TECHNIQUES.md}:
 * each a family of techniques, the specialist bots that teach it, and the teacher that shows how. The
 * basics of the stages before (aim, footwork, sprint hits, W-tap) are not taught again: the school
 * starts from the installed network, widened, which is also a teacher whose pull fades over the first
 * three stages (plan part 6).
 *
 * <p>A stage is passed once it has run {@link Stage#minSteps} and, against every one of its bots, the
 * damage traded over the last {@link #RECENT} fights is better than over its first ones and not far
 * behind; or once it has run {@link Stage#maxSteps}. A share {@link #REHEARSAL} of the fights is on
 * the stages before (or, in the first one, against the real player), so nothing is forgotten.
 */
public final class SchoolStages {
	public static final class Stage {
		public final String name;
		public final String family;
		/** The specialist bots ({@link Specialists} indexes); {@link Specialists#MIXER} for the real player. */
		public final int[] bots;
		/** The techniques its teacher shows, by the keys they use. */
		public final Technique[] techniques;
		public final long minSteps;
		public final long maxSteps;
		/** The share of this stage's own fights against the real player. */
		public final float mixerShare;

		Stage(String name, String family, int[] bots, Technique[] techniques, long minSteps, long maxSteps, float mixerShare) {
			this.name = name;
			this.family = family;
			this.bots = bots;
			this.techniques = techniques;
			this.minSteps = minSteps;
			this.maxSteps = maxSteps;
			this.mixerShare = mixerShare;
		}
	}

	private static final long MIN = 100_000L;
	private static final long MAX = 260_000L;

	public static final Stage[] STAGES = {
			new Stage("T-A", "knockback and spacing (K1-K3, T6, T7, T8)",
					new int[] {Specialists.MISS_BAITER, Specialists.SWINGER, Specialists.STRAIGHT_SPRINTER, Specialists.JUMP_IN_CRITTER,
							Specialists.FALL_SPACER},
					new Technique[] {Technique.K2, Technique.K3, Technique.T6, Technique.T6A, Technique.T7, Technique.T8}, MIN, MAX, 0f),
			new Stage("T-B", "crit trades (B1, T1, C1, C2, T2)",
					new int[] {Specialists.PUNISH_CRITTER, Specialists.DOUBLE_CRITTER, Specialists.CRIT_BREAKER, Specialists.STANDING_CRITTER},
					new Technique[] {Technique.B1, Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2, Technique.C1,
							Technique.C2}, MIN, MAX, 0f),
			new Stage("T-C", "stuns and deflections (T3, T4, T5, C3, C4)",
					new int[] {Specialists.DEFLECTOR, Specialists.REVERSE_DEFLECTOR, Specialists.STUN_ESCAPER, Specialists.ATTACK_THEN_S,
							Specialists.STUNNER},
					new Technique[] {Technique.T4, Technique.T5, Technique.C3, Technique.C4}, MIN, MAX, 0f),
			new Stage("T-D", "jump resets (T9, C6, C7)",
					new int[] {Specialists.THREE_BLOCK, Specialists.JUMP_RESETTER, Specialists.RHYTHM_COMBOER},
					new Technique[] {Technique.T9, Technique.T9A, Technique.T9B, Technique.T9C, Technique.C6, Technique.C7}, MIN, MAX, 0f),
			new Stage("T-E", "jumping (T10, T11)",
					new int[] {Specialists.STUN_ESCAPER, Specialists.UPPERCUTTER, Specialists.LUNGER, Specialists.HOPPER},
					new Technique[] {Technique.T10, Technique.T11A, Technique.T11B, Technique.T11C}, MIN, MAX, 0f),
			new Stage("T-F", "everything mixed",
					allSpecialists(), allTechniques(), MIN, Long.MAX_VALUE, 0.5f),
	};

	/** The share of fights on the stages before. */
	public static final float REHEARSAL = 0.3f;
	/** Fights per bot a stage is judged by: its first ones against its last ones. */
	public static final int RECENT = 16;
	/** How far behind on damage a minute a bot may still be at the end of a stage. */
	public static final float BEHIND = 10f;
	/** The technique teacher's pull at a stage's start; it fades to nothing over the stage's least steps. */
	public static final float TECH_PULL = 1.0f;
	/** The installed network's pull at the school's start; it fades to nothing over the first three stages' least steps. */
	public static final float OLD_PULL = 0.5f;
	private static final long OLD_FADE = 3 * MIN;

	private static int[] allSpecialists() {
		int[] b = new int[Specialists.COUNT];
		for (int i = 0; i < b.length; i++) {
			b[i] = i;
		}
		return b;
	}

	private static Technique[] allTechniques() {
		Technique[] t = new Technique[Technique.COUNT - 1];
		System.arraycopy(Technique.ALL, 1, t, 0, t.length);
		return t;
	}

	// ---- progress ---------------------------------------------------------------------------------

	public int stage;
	public long stageSteps;
	public long schoolSteps;
	/** Per bot of the current stage: (dealt - taken) a minute of its fights, first ones and the last ones. */
	private final float[][] first = new float[Specialists.KINDS][RECENT];
	private final int[] firstCount = new int[Specialists.KINDS];
	private final float[][] last = new float[Specialists.KINDS][RECENT];
	private final int[] lastCount = new int[Specialists.KINDS];
	private final int[] lastAt = new int[Specialists.KINDS];

	public Stage current() {
		return STAGES[stage];
	}

	/** A fight's opponent: a bot of this stage, of a stage before (rehearsal), or the real player. */
	public int drawBot(Random rng) {
		Stage s = current();
		if (stage == STAGES.length - 1 || rng.nextFloat() >= REHEARSAL) {
			if (s.mixerShare > 0f && rng.nextFloat() < s.mixerShare) {
				return Specialists.MIXER;
			}
			return s.bots[rng.nextInt(s.bots.length)];
		}
		if (stage == 0) {
			return Specialists.MIXER;
		}
		Stage before = STAGES[rng.nextInt(stage)];
		return rng.nextFloat() < 0.25f ? Specialists.MIXER : before.bots[rng.nextInt(before.bots.length)];
	}

	/** The stage a bot is taught in: this one if it is one of its bots, else the first stage that has it. */
	public int stageOf(int bot) {
		for (int b : current().bots) {
			if (b == bot) {
				return stage;
			}
		}
		for (int i = 0; i < STAGES.length; i++) {
			for (int b : STAGES[i].bots) {
				if (b == bot) {
					return i;
				}
			}
		}
		return stage;
	}

	public float techPull() {
		Stage s = current();
		return TECH_PULL * Math.max(0f, 1f - stageSteps / (float) s.minSteps);
	}

	public float oldPull() {
		return OLD_PULL * Math.max(0f, 1f - schoolSteps / (float) OLD_FADE);
	}

	public void addSteps(long steps) {
		stageSteps += steps;
		schoolSteps += steps;
	}

	/** One fight against {@code bot} is over: its damage traded. Only this stage's own bots count for passing it. */
	public void fight(int bot, float dealt, float taken, int ticks) {
		if (bot < 0 || bot >= Specialists.KINDS || ticks < 100 || stageOf(bot) != stage) {
			return;
		}
		float perMinute = (dealt - taken) * 1200f / ticks;
		if (firstCount[bot] < RECENT) {
			first[bot][firstCount[bot]++] = perMinute;
		}
		last[bot][lastAt[bot]] = perMinute;
		lastAt[bot] = (lastAt[bot] + 1) % RECENT;
		lastCount[bot] = Math.min(RECENT, lastCount[bot] + 1);
	}

	private static float mean(float[] a, int n) {
		float s = 0f;
		for (int i = 0; i < n; i++) {
			s += a[i];
		}
		return n == 0 ? Float.NaN : s / n;
	}

	/** Whether the stage is passed (see the class comment); the last stage never is. */
	public boolean passed() {
		Stage s = current();
		if (stage == STAGES.length - 1 || stageSteps < s.minSteps) {
			return false;
		}
		if (stageSteps >= s.maxSteps) {
			return true;
		}
		for (int b : s.bots) {
			if (lastCount[b] < RECENT || firstCount[b] < RECENT) {
				return false;
			}
			float now = mean(last[b], lastCount[b]);
			if (now <= mean(first[b], firstCount[b]) || now < -BEHIND) {
				return false;
			}
		}
		return true;
	}

	/** On to the next stage; returns its name. */
	public String advance() {
		stage = Math.min(STAGES.length - 1, stage + 1);
		stageSteps = 0;
		java.util.Arrays.fill(firstCount, 0);
		java.util.Arrays.fill(lastCount, 0);
		java.util.Arrays.fill(lastAt, 0);
		return current().name;
	}

	/** Where the stage stands, per bot: first and last damage a minute. */
	public String describe() {
		Stage s = current();
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%s %s, %d of %d steps, teacher pulls %.2f technique, %.2f installed;",
				s.name, s.family, stageSteps, s.minSteps, techPull(), oldPull()));
		for (int bot : s.bots) {
			b.append(String.format(Locale.ROOT, " %s %+.0f->%+.0f (%d)", Specialists.NAMES[bot], mean(first[bot], firstCount[bot]),
					mean(last[bot], lastCount[bot]), lastCount[bot]));
		}
		return b.toString();
	}

	public void save(Path file) throws IOException {
		Properties p = new Properties();
		p.setProperty("stage", Integer.toString(stage));
		p.setProperty("stageSteps", Long.toString(stageSteps));
		p.setProperty("schoolSteps", Long.toString(schoolSteps));
		for (int b = 0; b < Specialists.KINDS; b++) {
			p.setProperty("first." + b, join(first[b], firstCount[b]));
			p.setProperty("last." + b, join(last[b], lastCount[b]) + "@" + lastAt[b]);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			p.store(w, "SwordAI technique school");
		}
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	public void load(Path file) throws IOException {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		stage = Math.max(0, Math.min(STAGES.length - 1, Integer.parseInt(p.getProperty("stage", "0"))));
		stageSteps = Long.parseLong(p.getProperty("stageSteps", "0"));
		schoolSteps = Long.parseLong(p.getProperty("schoolSteps", "0"));
		for (int b = 0; b < Specialists.KINDS; b++) {
			firstCount[b] = split(p.getProperty("first." + b, ""), first[b]);
			String l = p.getProperty("last." + b, "@0");
			int at = l.lastIndexOf('@');
			lastCount[b] = split(l.substring(0, at), last[b]);
			lastAt[b] = Integer.parseInt(l.substring(at + 1)) % RECENT;
		}
	}

	private static String join(float[] a, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) {
			b.append(i == 0 ? "" : ",").append(a[i]);
		}
		return b.toString();
	}

	private static int split(String s, float[] into) {
		if (s.isEmpty()) {
			return 0;
		}
		String[] parts = s.split(",");
		int n = Math.min(parts.length, into.length);
		for (int i = 0; i < n; i++) {
			into[i] = Float.parseFloat(parts[i]);
		}
		return n;
	}
}
