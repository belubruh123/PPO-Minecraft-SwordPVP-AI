package dev.swordai.core.pvp;

/**
 * Every technique and counter of {@code docs/PVP-TECHNIQUES.md}, by its ID there. Nothing is in this
 * list that is not in that file. T3 (the stun) is what a hit does, not something pressed, so it has
 * no entry; C5 is the jump reset, which is T9.
 */
public enum Technique {
	/** No technique: the ordinary fight of part 0 (sprint hits, W-tap, S-tap). */
	BASE("-", "ordinary fight"),
	B1("B1", "punish crit"),
	T1("T1", "double crit"),
	T1A("T1a", "bait: first crit jumped in close"),
	T1B("T1b", "double crit around them"),
	T1C("T1c", "punish crit into double crit"),
	T2("T2", "standing crit"),
	T4("T4", "deflection"),
	T5("T5", "reverse deflection"),
	T6("T6", "make them miss, then stun"),
	T6A("T6a", "strafe so they miss"),
	T7("T7", "three-block hit"),
	T8("T8", "hit the rising jumper"),
	T9("T9", "jump reset"),
	T9A("T9a", "jump reset vs combos (on landing)"),
	T9B("T9b", "jump reset vs deflections (after own attack)"),
	T9C("T9c", "jump reset vs three-block hits"),
	T10("T10", "uppercut"),
	T11A("T11a", "lunge"),
	T11B("T11b", "hops"),
	T11C("T11c", "hops and lunges"),
	C1("C1", "sprint hit vs double crit"),
	C2("C2", "hold S when the double crit is sprint-hit"),
	C3("C3", "jump + hold S vs stun"),
	C4("C4", "attack, then hold S"),
	C6("C6", "standing crit vs jump reset"),
	C7("C7", "delayed sprint hit vs jump reset"),
	K2("K2", "no knockback hit on a falling player"),
	K3("K3", "make them hit you falling");

	public final String id;
	public final String title;

	Technique(String id, String title) {
		this.id = id;
		this.title = title;
	}

	public static final Technique[] ALL = values();
	public static final int COUNT = ALL.length;

	public static Technique byId(String id) {
		for (Technique t : ALL) {
			if (t.id.equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) {
				return t;
			}
		}
		throw new IllegalArgumentException("no technique " + id);
	}
}
