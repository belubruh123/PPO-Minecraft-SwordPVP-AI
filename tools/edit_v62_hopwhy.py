ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


J = 'core/src/main/java/dev/swordai/core/rl/JumpIn.java'
edit(J, [
    ('''	private int hopOff;
	private int hopBurns;
''', '''	private int hopOff;
	private int hopBurns;
	private int hopWhy = -1;
''', 1),
    ('''	public int plan(float[] vec, int keys, boolean onOpponent, boolean busy, int combo) {
		tookOff = NONE;
''', '''	public int plan(float[] vec, int keys, boolean onOpponent, boolean busy, int combo) {
		tookOff = NONE;
		hopWhy = -1;
''', 1),
    ('''		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit)) {''', '''		if (chase && hops && d >= HOP_FROM && d <= PURSUIT_TO) {
			hopWhy = ranFor < RUN_TICKS ? 1 : !notHitLately ? 2 : side > SIDE_MOST ? 3 : charge < CHASE_CHARGE ? 4
					: hopOff > 0 ? 5 : closing > HOP_CLOSING_MOST ? 6 : 0;
		}
		if (chase && notHitLately && ranFor >= RUN_TICKS && side <= SIDE_MOST && charge >= CHASE_CHARGE
				&& d >= CHASE_FROM && chaseOff <= 0 && (window || pursuit)) {''', 1),
    ('''	/** The kind of jump the last call of {@link #plan} took off on, or {@link #NONE}. */''', '''	/**
	 * For the log: on a tick on the ground with the opponent in view five to {@code PURSUIT_TO} blocks
	 * off, what kept the last call of {@link #plan} from a running jump at it (0: nothing, 1: not at a
	 * run yet, 2: hit lately, 3: off to the side, 4: sword not back, 5: left alone after a hit in the
	 * air, 6: the opponent coming in fast); -1 on any other tick.
	 */
	public int hopWhy() {
		return hopWhy;
	}

	/** The kind of jump the last call of {@link #plan} took off on, or {@link #NONE}. */''', 1),
])
TR = 'src/client/java/dev/swordai/client/Trainer.java'
edit(TR, [
    ('''	private final int[] meetHow = new int[5];
''', '''	private final int[] meetHow = new int[5];
	/** Ticks with the opponent five blocks off or more, by what kept it from a running jump; see {@link JumpIn#hopWhy}. */
	private final int[] hopWhy = new int[7];
''', 1),
    ('''			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0 || meet, dealtRow);
''', '''			jump = jumpIn.plan(obs.vec, keys, crosshairOnOpponent(), owes || backOut || reset != 0 || meet, dealtRow);
			if (jumpIn.hopWhy() >= 0) {
				hopWhy[jumpIn.hopWhy()]++;
			}
''', 1),
    ('''opponent under the crosshair with the sword back on {} ticks, no swing on {} of them, {} of those kept for the way down",''',
     '''opponent under the crosshair with the sword back on {} ticks, no swing on {} of them, {} of those kept for the way down | five blocks off or more on the ground on {} ticks: jumped {}, not at a run {}, hit lately {}, to the side {}, sword not back {}, left alone {}, coming in {}",''', 1),
    ('''meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3], pickTicks, pickNoSwing, pickKept);
''', '''meetHow[1] + meetHow[2] + meetHow[3] + meetHow[4], meetHow[1], meetHow[2], meetHow[3], pickTicks, pickNoSwing, pickKept,
				java.util.Arrays.stream(hopWhy).sum(), hopWhy[0], hopWhy[1], hopWhy[2], hopWhy[3], hopWhy[4], hopWhy[5], hopWhy[6]);
		java.util.Arrays.fill(hopWhy, 0);
''', 1),
])
print('hopwhy ok')
