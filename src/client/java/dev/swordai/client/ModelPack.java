package dev.swordai.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A trained model as one file, to keep or to take to another game: {@code /swordai export} writes
 * one to {@code swordai/models}, {@code /swordai import} puts one back. A pack holds the network and
 * what goes with it (its settings, where its training has got to, the copies it trains against),
 * and nothing else: no history, no backups, so that it stays a few megabytes.
 */
final class ModelPack {
	/** What a pack is made of; any other name in a zip is left alone on import. */
	static final List<String> FILES = List.of("checkpoint.bin", "config.json", "curriculum.properties", "champion.bin",
			"drill-copy.bin", "crit-drills.txt", "eye.properties");
	/** The pack made of what an import replaced: one, overwritten by the next import. */
	static final String BEFORE_IMPORT = "before-import";
	/** No file in a pack is bigger than this; a zip that says otherwise is not one of these. */
	private static final long MOST_BYTES = 64L << 20;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

	private ModelPack() {
	}

	static Path dir() {
		return SwordFiles.dir().resolve("models");
	}

	/** A name for a pack made now. */
	static String defaultName() {
		return "model-" + LocalDateTime.now().format(STAMP);
	}

	/** Letters, digits, dash, underscore and dot only, and no more than 48 of them. */
	static String clean(String name) {
		String n = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
		n = n.replaceAll("[^A-Za-z0-9._-]", "_");
		if (n.isEmpty() || n.chars().allMatch(c -> c == '.')) {
			n = defaultName();
		}
		return n.length() > 48 ? n.substring(0, 48) : n;
	}

	static Path file(String name) {
		return dir().resolve(clean(name) + ".zip");
	}

	/** Writes the current model to a pack of this name, replacing one of that name. */
	static Path export(String name) throws IOException {
		if (!Files.exists(SwordFiles.checkpoint())) {
			throw new IOException("there is no trained network to export yet");
		}
		Path out = file(name);
		write(out);
		return out;
	}

	private static void write(Path out) throws IOException {
		Files.createDirectories(out.getParent());
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
			for (String f : FILES) {
				Path p = SwordFiles.dir().resolve(f);
				if (Files.exists(p)) {
					zip.putNextEntry(new ZipEntry(f));
					Files.copy(p, zip);
					zip.closeEntry();
				}
			}
		}
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
	}

	/** The packs there are, newest first, by name without {@code .zip}. */
	static List<String> list() {
		List<String> names = new ArrayList<>();
		if (!Files.isDirectory(dir())) {
			return names;
		}
		try (Stream<Path> files = Files.list(dir())) {
			files.filter(p -> p.getFileName().toString().endsWith(".zip"))
					.sorted((a, b) -> Long.compare(modified(b), modified(a)))
					.forEach(p -> {
						String f = p.getFileName().toString();
						names.add(f.substring(0, f.length() - 4));
					});
		} catch (IOException e) {
			// Nothing to list.
		}
		return names;
	}

	/** When a file was last written and how big it is, for the menu: "18 Oct 16:49, 2.1 MB". */
	static String details(Path p) {
		try {
			LocalDateTime when = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault());
			return when.format(SHOWN) + ", " + String.format(Locale.ROOT, "%.1f MB", Files.size(p) / 1048576.0);
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Adds a pack from anywhere (a file dropped on the menu) to {@link #dir()}, under its own name or,
	 * if that is taken, the name with a number after it. Only a zip with a network in it is taken.
	 *
	 * @return the name it can be imported by
	 */
	static String add(Path src) throws IOException {
		String file = src.getFileName().toString();
		if (!file.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new IOException(file + " is not a .zip");
		}
		if (Files.size(src) > MOST_BYTES * FILES.size()) {
			throw new IOException(file + " is far bigger than a model");
		}
		boolean network = false;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(src))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
				if (!e.isDirectory() && e.getName().equals("checkpoint.bin")) {
					network = true;
					break;
				}
			}
		} catch (IllegalArgumentException e) {
			throw new IOException(file + " could not be read as a zip");
		}
		if (!network) {
			throw new IOException(file + " is not a SwordAI model (it has no checkpoint.bin)");
		}
		String base = clean(file);
		if (base.equals(BEFORE_IMPORT)) {
			base = BEFORE_IMPORT + "-copy";
		}
		Files.createDirectories(dir());
		if (src.toAbsolutePath().normalize().startsWith(dir().toAbsolutePath().normalize())) {
			// Already one of the packs.
			return clean(file);
		}
		String name = base;
		for (int i = 2; Files.exists(file(name)); i++) {
			String suffix = "-" + i;
			name = base.substring(0, Math.min(base.length(), 48 - suffix.length())) + suffix;
		}
		Files.copy(src, file(name));
		return name;
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	/**
	 * Makes the model in the pack of this name the current one. What it replaces is first written to
	 * the pack {@link #BEFORE_IMPORT}, so an import can be taken back by importing that. Files the
	 * pack does not have are left as they are, except the network itself, which every pack has.
	 *
	 * @return how many files were put in place
	 */
	static int importPack(String name) throws IOException {
		Path in = file(name);
		if (!Files.exists(in)) {
			throw new IOException("there is no " + in.getFileName() + " in " + dir());
		}
		// Read it all first: a zip that turns out not to be a pack changes nothing.
		Path staging = dir().resolve(".import");
		deleteDir(staging);
		Files.createDirectories(staging);
		Set<String> wanted = Set.copyOf(FILES);
		List<String> found = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(in))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
				String n = e.getName();
				if (e.isDirectory() || !wanted.contains(n) || found.contains(n)) {
					continue;
				}
				copyAtMost(zip, staging.resolve(n));
				found.add(n);
			}
		}
		if (!found.contains("checkpoint.bin")) {
			deleteDir(staging);
			throw new IOException(in.getFileName() + " has no network in it (checkpoint.bin)");
		}
		if (!clean(name).equals(BEFORE_IMPORT) && Files.exists(SwordFiles.checkpoint())) {
			write(file(BEFORE_IMPORT));
		}
		for (String n : found) {
			Files.move(staging.resolve(n), SwordFiles.dir().resolve(n), StandardCopyOption.REPLACE_EXISTING);
		}
		deleteDir(staging);
		return found.size();
	}

	private static void copyAtMost(InputStream from, Path to) throws IOException {
		byte[] buf = new byte[1 << 16];
		long total = 0;
		try (OutputStream out = Files.newOutputStream(to)) {
			for (int n; (n = from.read(buf)) > 0; ) {
				total += n;
				if (total > MOST_BYTES) {
					throw new IOException("a file in the pack is far bigger than any SwordAI file");
				}
				out.write(buf, 0, n);
			}
		}
	}

	private static void deleteDir(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				Files.deleteIfExists(p);
			}
		}
		Files.deleteIfExists(dir);
	}
}
