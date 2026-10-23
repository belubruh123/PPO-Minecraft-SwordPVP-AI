package dev.swordai.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.report.UpdateRecord;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Everything SwordAI keeps on disk lives in {@code <game dir>/swordai}. */
final class SwordFiles {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private SwordFiles() {
	}

	static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("swordai");
	}

	static Path checkpoint() {
		return dir().resolve("checkpoint.bin");
	}

	static Path curriculum() {
		return dir().resolve("curriculum.properties");
	}

	/**
	 * The copy that learns from fights against people while the proven network ({@link #checkpoint})
	 * stays as it is; see {@code SafeLearn}.
	 */
	static Path learning() {
		return dir().resolve("learning.bin");
	}

	/**
	 * The network training is held near (kickstarting; {@code Config.anchorWeight}): a checkpoint put
	 * there by hand, usually the one installed. Never written by the game.
	 */
	static Path anchor() {
		return dir().resolve("anchor.bin");
	}

	/** The fights it lost in training, kept to be fought again; see {@code LostCases}. */
	static Path lostCases() {
		return dir().resolve("lost-cases.json");
	}

	static dev.swordai.core.curriculum.LostCases loadLostCases() {
		dev.swordai.core.curriculum.LostCases lost = new dev.swordai.core.curriculum.LostCases();
		Path file = lostCases();
		if (Files.exists(file)) {
			try {
				LostCasesFile f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LostCasesFile.class);
				if (f != null && f.cases != null) {
					for (dev.swordai.core.curriculum.LostCases.Case c : f.cases) {
						if (c != null && c.bot != null) {
							lost.cases().add(c);
						}
					}
					if (f.beaten != null) {
						for (dev.swordai.core.curriculum.LostCases.Case c : f.beaten) {
							if (c != null && c.bot != null) {
								lost.keepBeaten(c);
							}
						}
					}
					lost.trim();
					lost.beatenEver = f.beatenEver;
				}
			} catch (IOException | RuntimeException e) {
				SwordAi.LOGGER.warn("swordai/lost-cases.json could not be read; starting a new list", e);
			}
		}
		return lost;
	}

	static void saveLostCases(dev.swordai.core.curriculum.LostCases lost) {
		LostCasesFile f = new LostCasesFile();
		f.cases = new ArrayList<>(lost.cases());
		f.beaten = new ArrayList<>(lost.beatenCases());
		f.beatenEver = lost.beatenEver;
		try {
			Path tmp = lostCases().resolveSibling("lost-cases.json.tmp");
			Files.writeString(tmp, GSON.toJson(f), StandardCharsets.UTF_8);
			Files.move(tmp, lostCases(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("could not save the lost fights", e);
		}
	}

	private static final class LostCasesFile {
		List<dev.swordai.core.curriculum.LostCases.Case> cases;
		List<dev.swordai.core.curriculum.LostCases.Case> beaten;
		int beatenEver;
	}

	/** How that copy and the proven network have done in their fights so far. */
	static Path learningScore() {
		return dir().resolve("learning.properties");
	}

	static Path config() {
		return dir().resolve("config.json");
	}

	static Path metrics() {
		return dir().resolve("metrics.csv");
	}

	static Path report() {
		return dir().resolve("report.txt");
	}

	static Path lastError() {
		return dir().resolve("last-error.txt");
	}

	/**
	 * Commands left here are run as if they had been typed, one per line, and the file is then removed:
	 * for running it unattended (see {@code SwordAiClient.readInbox}).
	 */
	static Path inbox() {
		return dir().resolve("inbox.txt");
	}

	/** The user's own fights recorded with {@code /swordai demo}, one file each; see {@code DemoFile}. */
	static Path demos() {
		return dir().resolve("demos");
	}

	/** The recorded fights, the newest first. */
	static List<Path> demoFiles() {
		Path dir = demos();
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".bin.gz"))
					.sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/**
	 * There while the user records fights, so that scripts driving training through the inbox wait for
	 * them to finish; removed when they do, and at the next start if the game closed meanwhile.
	 */
	static Path demoLock() {
		return dir().resolve("demo.lock");
	}

	/** What the crit drills had come to when training last ran, for a report made while it is not running. */
	static Path critNotes() {
		return dir().resolve("crit-drills.txt");
	}

	/**
	 * The checkpoint as the previous version of the mod left it, kept when this version upgrades it:
	 * the upgraded one has an input more, and the previous version cannot read that.
	 */
	static Path beforeUpgrade() {
		return dir().resolve("checkpoint-before-upgrade.bin");
	}

	/** Before the duel: the saved copy of the network that the combo drills are fought against. */
	static Path drillCopy() {
		return dir().resolve("drill-copy.bin");
	}

	/** Self-play: the saved copy of the network that the agent currently has to beat. */
	static Path champion() {
		return dir().resolve("champion.bin");
	}

	/** Self-play keeps this many of the copies from before the current one, to fight now and then. */
	static final int OLDER_CHAMPIONS = 4;

	/** A copy from before the current one; 1 is the one it replaced. */
	static Path olderChampion(int age) {
		return dir().resolve("champion-old-" + age + ".bin");
	}

	/** The copies from before the current one that exist, newest first. */
	static List<Path> olderChampions() {
		List<Path> out = new ArrayList<>();
		for (int age = 1; age <= OLDER_CHAMPIONS; age++) {
			if (Files.exists(olderChampion(age))) {
				out.add(olderChampion(age));
			}
		}
		return out;
	}

	/** The current copy is about to be replaced: it joins the older ones, and the oldest of those is dropped. */
	static void retireChampion() throws IOException {
		if (!Files.exists(champion())) {
			return;
		}
		for (int age = OLDER_CHAMPIONS; age >= 2; age--) {
			if (Files.exists(olderChampion(age - 1))) {
				Files.move(olderChampion(age - 1), olderChampion(age), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.copy(champion(), olderChampion(1), StandardCopyOption.REPLACE_EXISTING);
	}

	private static List<Path> allChampions() {
		List<Path> out = new ArrayList<>();
		out.add(champion());
		for (int age = 1; age <= OLDER_CHAMPIONS; age++) {
			out.add(olderChampion(age));
		}
		return out;
	}

	/** An old network kept to fight in self-play as it played then; see {@code Config.oldModelShare}. */
	static Path oldModel() {
		return dir().resolve("old-model.bin");
	}

	static Path oldModelConfig() {
		return dir().resolve("old-model-config.json");
	}

	/**
	 * The settings the old model played with: its own config file where there is one (else this
	 * session's), with what it did not have yet turned off and its sword all the way back for a rule's
	 * swing as for its own.
	 */
	static Config loadOldModelConfig(Config fallback) {
		Config c = null;
		try {
			if (Files.exists(oldModelConfig())) {
				c = GSON.fromJson(Files.readString(oldModelConfig(), StandardCharsets.UTF_8), Config.class);
			}
		} catch (IOException | JsonParseException e) {
			SwordAi.LOGGER.warn("old-model-config.json could not be read; the old model plays with this session's settings", e);
		}
		if (c == null) {
			c = GSON.fromJson(GSON.toJson(fallback), Config.class);
		}
		c.ruleSwingCharge = c.swingMinCharge;
		c.punishInCombo = true;
		c.lostRule = false;
		c.straightInRule = false;
		c.comboStrafeRule = false;
		c.hopRule = false;
		c.recordRounds = false;
		return c;
	}

	/** Where the rounds of {@code /swordai fight} are kept; see {@link RoundRecorder}. */
	static Path fightsDir() {
		return dir().resolve("fights");
	}

	static Path lastFight() {
		return dir().resolve("last-fight.txt");
	}

	/** What was measured about the real picture while the network played the user's character. */
	static Path eye() {
		return dir().resolve("eye.properties");
	}

	static Path optionsBackup() {
		return dir().resolve("options-backup.properties");
	}

	static Path debugDir() {
		return dir().resolve("debug");
	}

	static String modVersion() {
		return FabricLoader.getInstance().getModContainer(SwordAi.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	/**
	 * Loads the config, writing the defaults when there is none. A file written for an older config
	 * version (or one that does not parse) is set aside and replaced, so defaults shipped with a new
	 * jar always take effect.
	 */
	static Config loadConfig() {
		Path file = config();
		Config defaults = new Config();
		try {
			Files.createDirectories(dir());
			if (Files.exists(file)) {
				try {
					Config loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
					if (loaded != null && loaded.configVersion == Config.CURRENT_VERSION && loaded.stages != null && !loaded.stages.isEmpty()) {
						return loaded;
					}
					if (loaded != null && loaded.visionFov > 0) {
						// The one setting that belongs to the trained network rather than to a version.
						defaults.visionFov = loaded.visionFov;
					}
					if (loaded != null) {
						// And the ones the user sets by command: a new version does not unset them.
						if (loaded.trainSpeed >= 1f) {
							defaults.trainSpeed = loaded.trainSpeed;
						}
						if (loaded.botName != null) {
							defaults.botName = loaded.botName;
						}
						defaults.showOpponentSkin = loaded.showOpponentSkin;
						if (loaded.configVersion >= Config.PEOPLE_AND_ARENA_FROM) {
							// Before that, learning from people was on unless it was turned off, and it
							// did the network harm: a file from then does not say what the user wants now.
							defaults.learnFromPeople = loaded.learnFromPeople;
						}
						if (loaded.arenaRadius > 0) {
							// A larger arena than the default is kept; a smaller one no longer holds the fights.
							defaults.arenaRadius = Math.max(Config.MIN_ARENA_RADIUS, loaded.arenaRadius);
						}
						if (loaded.agentName != null) {
							defaults.agentName = loaded.agentName;
						}
					}
				} catch (JsonParseException e) {
					SwordAi.LOGGER.warn("swordai/config.json could not be read, regenerating it", e);
				}
				Files.move(file, dir().resolve("config-old-" + LocalDateTime.now().format(STAMP) + ".json"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not write swordai/config.json, using built-in defaults", e);
		}
		return defaults;
	}

	static void saveConfig(Config cfg) throws IOException {
		Files.createDirectories(dir());
		Files.writeString(config(), GSON.toJson(cfg), StandardCharsets.UTF_8);
	}

	static List<UpdateRecord> loadHistory() {
		List<UpdateRecord> out = new ArrayList<>();
		Path file = metrics();
		if (!Files.exists(file)) {
			return out;
		}
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			String header = lines.isEmpty() ? UpdateRecord.CSV_HEADER : lines.get(0).trim();
			boolean before = !header.equals(UpdateRecord.CSV_HEADER);
			if (!UpdateRecord.readableHeader(header)) {
				// Written by a version with different columns; appending to it would corrupt both.
				Files.move(file, dir().resolve("metrics-old-" + LocalDateTime.now().format(STAMP) + ".csv"), StandardCopyOption.REPLACE_EXISTING);
				return out;
			}
			for (String line : lines) {
				UpdateRecord r = UpdateRecord.fromCsv(line);
				if (r != null) {
					out.add(r);
				}
			}
			if (before) {
				// An earlier version's file: the same history, rewritten with the columns added since.
				StringBuilder csv = new StringBuilder(UpdateRecord.CSV_HEADER).append('\n');
				for (UpdateRecord r : out) {
					csv.append(r.toCsv()).append('\n');
				}
				Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not read swordai/metrics.csv", e);
		}
		return out;
	}

	static void appendHistory(UpdateRecord record) {
		Path file = metrics();
		try {
			Files.createDirectories(dir());
			if (!Files.exists(file)) {
				Files.writeString(file, UpdateRecord.CSV_HEADER + "\n", StandardCharsets.UTF_8);
			}
			Files.writeString(file, record.toCsv() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not append to swordai/metrics.csv", e);
		}
	}

	/** The copy of the checkpoint that is kept at the moment a stage (counted from 1) is passed. */
	static Path stageCheckpoint(int stagePassed) {
		return dir().resolve("checkpoint-after-stage-" + stagePassed + ".bin");
	}

	/**
	 * Puts the run back to the moment a stage began: the network as it was when the stage before it
	 * was passed, and the history up to there. Everything replaced is first copied to a backup folder,
	 * which is returned.
	 *
	 * @param stageIndex the stage to start again, counted from 0; must be 1 or more
	 */
	static Path rewindToStage(int stageIndex) throws IOException {
		Path saved = stageCheckpoint(stageIndex);
		if (!Files.exists(saved)) {
			throw new IOException("no checkpoint was kept from the end of stage " + stageIndex);
		}
		List<UpdateRecord> kept = new ArrayList<>();
		for (UpdateRecord r : loadHistory()) {
			if (r.stage < stageIndex) {
				kept.add(r);
			}
		}
		Path backup = dir().resolve("backup-" + LocalDateTime.now().format(STAMP));
		Files.createDirectories(backup);
		for (Path f : new Path[] {checkpoint(), curriculum(), metrics()}) {
			if (Files.exists(f)) {
				Files.copy(f, backup.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		for (Path copy : allChampions()) {
			if (Files.exists(copy)) {
				// A copy of itself from further on in the run has no place before that point.
				Files.move(copy, backup.resolve(copy.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.copy(saved, checkpoint(), StandardCopyOption.REPLACE_EXISTING);
		StringBuilder csv = new StringBuilder(UpdateRecord.CSV_HEADER).append('\n');
		for (UpdateRecord r : kept) {
			csv.append(r.toCsv()).append('\n');
		}
		Files.writeString(metrics(), csv.toString(), StandardCharsets.UTF_8);
		return backup;
	}

	/** Where the run is kept as it was when training without the screen last took over from training on it. */
	static Path beforeHeadless() {
		return dir().resolve("before-headless");
	}

	/** Where the run is kept as it was before it last began to learn from fights against people. */
	static Path beforePeople() {
		return dir().resolve("before-people");
	}

	/** Everything that makes up what has been learned so far. */
	private static List<Path> learnedState() {
		List<Path> files = new ArrayList<>(List.of(checkpoint(), curriculum(), metrics()));
		files.addAll(allChampions());
		return files;
	}

	/** Copies everything learned so far into {@code keep}, replacing what was kept there before. */
	private static void keepIn(Path keep) throws IOException {
		Files.createDirectories(keep);
		for (Path f : learnedState()) {
			Path copy = keep.resolve(f.getFileName());
			if (Files.exists(f)) {
				Files.copy(f, copy, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.deleteIfExists(copy);
			}
		}
	}

	/**
	 * Keeps a copy of everything learned so far, replacing the copy kept before. Called when training
	 * without the screen takes over from training on it, so that one command brings back the network
	 * the screen trained if the other kind turns out worse.
	 */
	static void keepBeforeHeadless() throws IOException {
		keepIn(beforeHeadless());
	}

	/**
	 * Keeps everything learned so far before the copy that learned from fights against people takes
	 * the proven network's place, so that {@code /swordai undo} brings back the one it replaced.
	 */
	static void keepBeforePromotion() throws IOException {
		keepIn(beforePeople());
	}

	/**
	 * Called when a fight or a sparring match that is learned from begins. If the run's last update was
	 * not already one of those, everything learned so far is kept, so that {@code /swordai undo} brings
	 * back the network from before people had a hand in it.
	 *
	 * @param say told when the copy could not be made
	 * @return whether a copy was kept just now
	 */
	static boolean keepBeforePeople(List<UpdateRecord> history, java.util.function.Consumer<String> say) {
		if (!history.isEmpty() && history.get(history.size() - 1).opponent != UpdateRecord.OPPONENT_STAGE) {
			return false;
		}
		try {
			keepIn(beforePeople());
			return true;
		} catch (IOException e) {
			say.accept("Could not keep a copy of the network from before this (" + e + "); /swordai undo will not have it.");
			return false;
		}
	}

	private static boolean kept(Path keep) {
		return Files.exists(keep.resolve(checkpoint().getFileName()));
	}

	/**
	 * The copy {@code /swordai undo} goes back to: whichever of the two was kept last, or null when
	 * neither is there.
	 */
	static Path newestKept() {
		Path headless = beforeHeadless();
		Path people = beforePeople();
		if (!kept(headless)) {
			return kept(people) ? people : null;
		}
		if (!kept(people)) {
			return headless;
		}
		try {
			Path name = checkpoint().getFileName();
			return Files.getLastModifiedTime(people.resolve(name)).compareTo(Files.getLastModifiedTime(headless.resolve(name))) > 0 ? people : headless;
		} catch (IOException e) {
			return headless;
		}
	}

	/**
	 * Puts the run back to what was kept in {@code keep}. What it replaces is moved to a backup folder
	 * first, which is returned, and so is the kept copy itself: an undo is used up, and the next one
	 * goes back to whatever was kept before it.
	 */
	static Path restoreKept(Path keep) throws IOException {
		if (!kept(keep)) {
			throw new IOException("nothing was kept to go back to");
		}
		Path backup = dir().resolve("backup-" + LocalDateTime.now().format(STAMP));
		Files.createDirectories(backup);
		for (Path f : learnedState()) {
			if (Files.exists(f)) {
				Files.move(f, backup.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
			Path copy = keep.resolve(f.getFileName());
			if (Files.exists(copy)) {
				Files.move(copy, f, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.deleteIfExists(drillCopy());
		return backup;
	}

	/** Moves the learned state out of the way so the next run starts from nothing. Returns the backup folder, or null. */
	static Path archiveRun() throws IOException {
		List<Path> files = new ArrayList<>(List.of(checkpoint(), curriculum(), metrics()));
		files.addAll(allChampions());
		Path backup = null;
		for (Path f : files) {
			if (Files.exists(f)) {
				if (backup == null) {
					backup = dir().resolve("backup-" + LocalDateTime.now().format(STAMP));
					Files.createDirectories(backup);
				}
				Files.move(f, backup.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		// Notes on a run that is no longer the current one.
		Files.deleteIfExists(critNotes());
		return backup;
	}

	static void writeError(String message, Throwable error) {
		try {
			Files.createDirectories(dir());
			StringWriter sw = new StringWriter();
			sw.append(LocalDateTime.now().toString()).append("  ").append(message).append('\n');
			if (error != null) {
				error.printStackTrace(new PrintWriter(sw));
			}
			Files.writeString(lastError(), sw.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not write swordai/last-error.txt", e);
		}
	}

	static String readLastError() {
		try {
			if (Files.exists(lastError())) {
				List<String> lines = Files.readAllLines(lastError(), StandardCharsets.UTF_8);
				// The first lines carry the message and the top of the stack: enough to diagnose, short enough to paste.
				return String.join("\n", lines.subList(0, Math.min(lines.size(), 12)));
			}
		} catch (IOException e) {
			return "could not read last-error.txt: " + e;
		}
		return null;
	}

	static void clearLastError() {
		try {
			Files.deleteIfExists(lastError());
		} catch (IOException ignored) {
			// Nothing depends on this file being gone.
		}
	}

	/** Keeps the result of the latest fight or sparring match, so the next report can show it. */
	static void writeLastFight(String summary) {
		try {
			Files.createDirectories(dir());
			Files.writeString(lastFight(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "  " + summary + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not write swordai/last-fight.txt", e);
		}
	}

	static String readLastFight() {
		try {
			if (Files.exists(lastFight())) {
				return Files.readString(lastFight(), StandardCharsets.UTF_8).trim();
			}
		} catch (IOException e) {
			return "could not read last-fight.txt: " + e;
		}
		return null;
	}

	/** A frame is about this old when the network decides on it, until it has been measured. */
	static final float DEFAULT_FRAME_AGE_MILLIS = 25f;

	/**
	 * Keeps what the real picture was measured to be: how old a frame is when it is decided on, and how
	 * well the drawn picture matched it. A fake player's picture is made to the first; the second goes
	 * into the report.
	 */
	static void writeEye(float frameAgeMillis, String check) {
		try {
			Files.createDirectories(dir());
			Properties p = new Properties();
			p.setProperty("frame_age_ms", String.format(Locale.ROOT, "%.1f", frameAgeMillis));
			if (check != null) {
				p.setProperty("check", check);
			}
			try (var out = Files.newBufferedWriter(eye(), StandardCharsets.UTF_8)) {
				p.store(out, "SwordAI: the real picture, as measured");
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not write swordai/eye.properties", e);
		}
	}

	private static Properties readEye() {
		Properties p = new Properties();
		try {
			if (Files.exists(eye())) {
				try (var in = Files.newBufferedReader(eye(), StandardCharsets.UTF_8)) {
					p.load(in);
				}
			}
		} catch (IOException | IllegalArgumentException e) {
			SwordAi.LOGGER.warn("could not read swordai/eye.properties", e);
		}
		return p;
	}

	static float readFrameAgeMillis() {
		try {
			float age = Float.parseFloat(readEye().getProperty("frame_age_ms", Float.toString(DEFAULT_FRAME_AGE_MILLIS)));
			return age > 0f && age < 200f ? age : DEFAULT_FRAME_AGE_MILLIS;
		} catch (NumberFormatException e) {
			return DEFAULT_FRAME_AGE_MILLIS;
		}
	}

	/** The last comparison of the drawn picture with the real one, or null. */
	static String readEyeCheck() {
		return readEye().getProperty("check");
	}
}
