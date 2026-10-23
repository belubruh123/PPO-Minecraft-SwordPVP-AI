"""20 Oct 07:00, the user: "replicate those cases it failed 1-1 from start so it actually learns it; you still let it
fight the cases it won". A kept fight now carries its seed (start places and every choice the bot makes), the agent's
start heading and the jumper's head start, and is set up exactly again; a picked fight is fought up to 3 times running;
beaten ones are fought again only once a stage has none left."""
import io

ROOT = 'D:/swordai/'


def edit(path, pairs):
    p = ROOT + path
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:80], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('edited', path)


T = 'src/client/java/dev/swordai/client/Trainer.java'
edit(T, [(
    '''	private boolean episodeReplay;
''', '''	private boolean episodeReplay;
	/** This fight's plan, for the heading the server gave the agent, kept with the fight if it is lost. */
	private EpisodePlan episodePlan;
'''), (
    '''				plan.cornerStart = c.cornerStart;
				episodeKind = c.kind;''', '''				plan.cornerStart = c.cornerStart;
				// Exactly as it was, from the start: the same draws, the same heading. See EpisodePlan.seed.
				plan.seed = c.seed;
				if (c.hasYaw) {
					plan.startYaw = c.startYaw;
				}
				episodeKind = c.kind;'''), (
    '''		c.cornerStart = plan.cornerStart;
		episodeCase = c;
	}''', '''		c.cornerStart = plan.cornerStart;
		// Drawn from a seed of its own, so that it can be set up exactly again if it is lost.
		long seed;
		do {
			seed = rng.nextLong();
		} while (seed == 0L);
		plan.seed = seed;
		c.seed = seed;
		episodePlan = plan;
		episodeCase = c;
	}'''), (
    '''		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		} else {''', '''		if (episodeReplay) {
			lostCases.replayed(episodeCase, episodeDealt, episodeTaken, episodeTicksCounted, knockedOut);
		} else {
			if (episodePlan != null && !Float.isNaN(episodePlan.usedYaw)) {
				episodeCase.startYaw = episodePlan.usedYaw;
				episodeCase.hasYaw = true;
			}'''), (
    '''				: episodeKind == KIND_JUMPER ? 1 + rng.nextInt(2) : 0;
		openerSeen = 0;''', '''				: episodeKind == KIND_JUMPER ? 1 + rng.nextInt(2) : 0;
		if (episodeCase != null) {
			// The jump critter's head start is part of the fight as it was.
			if (!episodeReplay) {
				episodeCase.openerHits = openerHits;
			} else if (episodeCase.openerHits >= 0) {
				openerHits = episodeCase.openerHits;
			}
		}
		openerSeen = 0;''')])

LC = 'core/src/main/java/dev/swordai/core/curriculum/LostCases.java'
edit(LC, [(
    '''	/** While a stage still has lost fights kept, this share of its replays are beaten ones fought again. */
	static final float RETEST_SHARE = 0.15f;''', '''	/**
	 * A fight picked is fought this many times running (unless beaten sooner), the same fight from the
	 * same start each time: the user, 20 October, "replicate those cases it failed 1-1 from start".
	 */
	public static final int STICK = 3;'''), (
    '''		public boolean cornerStart;
''', '''		public boolean cornerStart;
		/** The draws of the fight from its start (see EpisodePlan.seed); 0 for one kept before 20 October. */
		public long seed;
		/** The agent's heading at its start. */
		public float startYaw;
		public boolean hasYaw;
		/** The jump critter's head start in hits; -1 if none was kept. */
		public int openerHits = -1;
'''), (
    '''	private long picks = -1;
''', '''	private long picks = -1;
	/** The fight being fought again over and over, and how many more times; see {@link #STICK}. */
	private Case sticky;
	private int stickyLeft;
'''), (
    '''	public Case pick(Random rng, int stage) {
		Case c = null;
		if (rng.nextFloat() < RETEST_SHARE) {
			c = pickBeaten(rng, stage);
		}
		if (c == null) {
			c = rng.nextFloat() < STALEST_SHARE ? pickStalest(stage) : pickLost(rng, stage);
		}
		if (c == null) {
			c = pickBeaten(rng, stage);
		}''', '''	public Case pick(Random rng, int stage) {
		Case c = null;
		if (sticky != null && stickyLeft > 0 && sticky.stage == stage && cases.contains(sticky)) {
			stickyLeft--;
			c = sticky;
		} else {
			c = rng.nextFloat() < STALEST_SHARE ? pickStalest(stage) : pickLost(rng, stage);
			if (c != null) {
				sticky = c;
				stickyLeft = STICK - 1;
			}
		}
		if (c == null) {
			// Only once none of the stage's lost fights are left: the beaten ones, fought again.
			c = pickBeaten(rng, stage);
		}''')])
