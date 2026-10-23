package dev.swordai.client;

import dev.swordai.core.config.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A paired evaluation in one run ({@code Config.pairedBase}): every fight of a frozen measurement is fought twice, by
 * this run's network (A) and by another one (B), from the same seed, so that the two share everything the run brings
 * (its connection, its timing, the machine) and differ only in how they fight. The order turns from one pair to the
 * next (A then B, then B then A), so neither is always second. B fights with its own settings
 * ({@code Config.pairedBaseSettings}); A's are put back for its fights.
 *
 * <p>Gemini's review, 23 October (rank 6): with A and B in separate runs, the same network measured -15 and +92 net on
 * the same fights, and a comparison took two hours; "common random numbers" (Glasserman 2004) only pay when the two
 * really meet the same fights, in the same conditions.
 */
final class PairedEval {
	private final List<Field> fields = new ArrayList<>();
	private final List<Object> mine = new ArrayList<>();
	private final List<Object> theirs = new ArrayList<>();

	/** Null without {@code Config.pairedBase}. */
	static PairedEval of(Config cfg) {
		return cfg.pairedBase == null || cfg.pairedBase.isBlank() || cfg.evalSeed == 0 ? null : new PairedEval(cfg);
	}

	private PairedEval(Config cfg) {
		for (String pair : cfg.pairedBaseSettings.trim().split("\\s+")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				throw new IllegalArgumentException("pairedBaseSettings: \"" + pair + "\" is not key=value");
			}
			try {
				Field f = Config.class.getField(pair.substring(0, eq));
				fields.add(f);
				mine.add(f.get(cfg));
				theirs.add(parse(f.getType(), pair.substring(eq + 1)));
			} catch (NoSuchFieldException | IllegalAccessException e) {
				throw new IllegalArgumentException("pairedBaseSettings: no setting " + pair.substring(0, eq), e);
			}
		}
	}

	private static Object parse(Class<?> type, String text) {
		if (type == boolean.class) {
			return Boolean.parseBoolean(text);
		}
		if (type == int.class) {
			return Integer.parseInt(text);
		}
		if (type == long.class) {
			return Long.parseLong(text);
		}
		if (type == float.class) {
			return Float.parseFloat(text);
		}
		if (type == double.class) {
			return Double.parseDouble(text);
		}
		if (type == String.class) {
			return text;
		}
		throw new IllegalArgumentException("pairedBaseSettings: a setting of type " + type.getSimpleName() + " cannot be set here");
	}

	/** Whether fight {@code fight} of the run (from 0) is B's: pairs 0, 2, 4... A first, pairs 1, 3, 5... B first. */
	static boolean bFights(long fight) {
		long pair = fight / 2;
		boolean second = fight % 2 == 1;
		return second != (pair % 2 == 1);
	}

	/** The pair a fight belongs to: both its fights are set up from the same seed. */
	static long pair(long fight) {
		return fight / 2;
	}

	/** Puts B's settings in place for its fight, or A's back for A's. */
	void apply(Config cfg, boolean b) {
		for (int i = 0; i < fields.size(); i++) {
			try {
				fields.get(i).set(cfg, b ? theirs.get(i) : mine.get(i));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	String describe(Config cfg) {
		return "every fight fought twice from the same seed, by this network (A) and by " + cfg.pairedBase + " (B"
				+ (cfg.pairedBaseSettings.isBlank() ? "" : ", with " + cfg.pairedBaseSettings.trim()) + "), the order turning each pair";
	}
}
