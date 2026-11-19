package dev.swordai.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Export/import a trained checkpoint + config as a single {@code .zip} in {@code swordai/models}. */
final class ModelPack {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

	private ModelPack() {
	}

	static Path dir() {
		return SwordFiles.dir().resolve("models");
	}

	static String defaultName() {
		return "model-" + LocalDateTime.now().format(STAMP);
	}

	static String clean(String name) {
		String n = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
		n = n.replaceAll("[^A-Za-z0-9._-]", "_");
		if (n.isEmpty() || n.chars().allMatch(c -> c == '.')) {
			n = defaultName();
		}
		return n.length() > 48 ? n.substring(0, 48) : n;
	}

	static Path export(String name) throws IOException {
		Path src = Files.exists(SwordFiles.schoolCheckpoint()) ? SwordFiles.schoolCheckpoint() : SwordFiles.checkpoint();
		if (!Files.exists(src)) {
			throw new IOException("there is no trained network to export yet");
		}
		Path out = dir().resolve(clean(name == null ? defaultName() : name) + ".zip");
		Files.createDirectories(out.getParent());
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
			zip.putNextEntry(new ZipEntry("checkpoint.bin"));
			Files.copy(src, zip);
			zip.closeEntry();
			if (Files.exists(SwordFiles.config())) {
				zip.putNextEntry(new ZipEntry("config.json"));
				Files.copy(SwordFiles.config(), zip);
				zip.closeEntry();
			}
		}
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		return out;
	}

	static void importPack(String name) throws IOException {
		Path src = dir().resolve(clean(name) + ".zip");
		if (!Files.exists(src)) {
			throw new IOException("no saved model named " + name);
		}
		Files.createDirectories(SwordFiles.schoolDir());
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(src))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
				if (e.isDirectory()) continue;
				if ("checkpoint.bin".equals(e.getName())) {
					Files.copy(zip, SwordFiles.schoolCheckpoint(), StandardCopyOption.REPLACE_EXISTING);
				} else if ("config.json".equals(e.getName())) {
					Files.copy(zip, SwordFiles.config(), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	static List<String> list() {
		List<String> names = new ArrayList<>();
		if (!Files.isDirectory(dir())) return names;
		try (Stream<Path> s = Files.list(dir())) {
			s.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().forEach(p -> {
				String f = p.getFileName().toString();
				names.add(f.substring(0, f.length() - 4));
			});
		} catch (IOException ignored) {
		}
		return names;
	}
}
