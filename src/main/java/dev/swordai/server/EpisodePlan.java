package dev.swordai.server;

import dev.swordai.core.curriculum.BotProfile;
import dev.swordai.core.curriculum.Curriculum;
import dev.swordai.core.curriculum.StageSpec;

/** What the client asks the server to set up for the next episode. */
public final class EpisodePlan {
	/** Already adjusted for the current bot skill. */
	public final BotProfile bot;
	/**
	 * The technique school: the style the opponent fights with ({@link TechniqueBot}); null for the scripted
	 * brain of the stages before. With a style, only the profile's placement and health rules are used.
	 */
	public dev.swordai.core.pvp.Style style;
	public final int agentMaxPillar;
	public final float startPitchRangeDeg;
	/** When the bot is played by a copy of the agent: which of the copies handed over, 0 being the newest. */
	public final int rivalIndex;
	/**
	 * Ticks of lag for this fight: how late the agent sees the opponent (only where its picture is
	 * drawn for it), and how late the opponent sees the agent. Set before the plan is handed over.
	 */
	public int agentLag;
	public int botLag;
	/**
	 * What the agent sees of the opponent also comes in fits and starts: on each tick this chance of
	 * nothing arriving for one to {@link #agentStallTicks} ticks. 0: evenly.
	 */
	public float agentStall;
	/** The opponent's own lag: a hit knocks it back this many ticks late. */
	public int botPing;
	/** And the fighter's own: a hit knocks it back this many ticks late (its game hears of it late); see KnockbackLag.setAgent. */
	public int agentKnockLag;
	/** The share of a hit's knockback the opponent takes in this fight; 1 is all of it. */
	public float botKnockback = 1f;
	public int agentStallTicks;
	/**
	 * Against a copy of itself: which of the two makes its move first is drawn on every tick. Two
	 * copies of one fighter trade hit for hit on the same tick, and the hit that is dealt first is
	 * the one that counts when both are a hit from the end: with the copy always first, as up to the
	 * first runs of 0.14.0, a fighter lost 12 fights of 14 on equal terms to a copy of itself, and
	 * with the fighter always first it won 16 of 23, landing as many hits as it took either way. On
	 * a server it is whichever player's click arrives first, tick by tick.
	 */
	public boolean mixedTurns;
	/**
	 * The fight begins at a corner of the arena: the opponent stands in it with the walls behind it,
	 * and the agent a few steps out from it, looking more or less its way.
	 */
	public boolean cornerStart;
	/**
	 * The fight's chances (where the two start, every choice the bot makes) are drawn from this, so that
	 * a fight it lost can be set up again exactly, from the start; 0: drawn afresh. See {@code LostCases}.
	 */
	public long seed;
	/** The agent's heading at the start of a fight set up again exactly; NaN: kept as it is. */
	public float startYaw = Float.NaN;
	/**
	 * A fight of a paired evaluation ({@code Config.evalSeed}): with no {@link #startYaw}, the heading it
	 * starts with is drawn from {@link #seed} too, not kept from where the last fight ended. The bot is
	 * placed relative to that heading, so until 23 October two networks given the same fight met the bot
	 * somewhere else whenever they had ended the fight before looking another way (Gemini's audit).
	 */
	public boolean seedYaw;
	/**
	 * The punish crit drill (SchoolSession#DRILL_SHARE): the bot starts in its reach with its sword all the way back, the
	 * agent's sword {@link #drillAgentTicker} ticks into coming back, so the bot's sprint hit lifts the agent first.
	 */
	public boolean drillLift;
	/** A moment of an earlier fight to start from (SceneRecorder#at): both fighters put back exactly as they were. */
	public double[] scene;
	public int drillAgentTicker;
	/** The heading the agent was given at the start, as the server set it; kept with a fight it lost. */
	public volatile float usedYaw = Float.NaN;

	public EpisodePlan(StageSpec stage, float botSkill) {
		this(stage, botSkill, 0);
	}

	public EpisodePlan(StageSpec stage, float botSkill, int rivalIndex) {
		this.bot = stage.bot.atSkill(botSkill).copy();
		this.agentMaxPillar = stage.agentMaxPillar;
		this.startPitchRangeDeg = stage.startPitchRangeDeg;
		this.rivalIndex = rivalIndex;
	}

	/** A fight of the technique school against {@code style}: placed between {@code minDistance} and {@code maxDistance}, in view. */
	public static EpisodePlan school(dev.swordai.core.pvp.Style style, float minDistance, float maxDistance) {
		BotProfile b = new BotProfile();
		b.movement = BotProfile.FIGHT;
		b.attacks = true;
		b.minDistance = minDistance;
		b.maxDistance = maxDistance;
		b.maxBearingDeg = 40f;
		b.hopIntervalTicks = 0;
		// A real fight: to the knockout.
		b.agentImmortal = false;
		b.botImmortal = false;
		EpisodePlan plan = new EpisodePlan(b, 0f);
		plan.style = style;
		return plan;
	}

	private EpisodePlan(BotProfile bot, float startPitchRangeDeg) {
		this.bot = bot;
		this.agentMaxPillar = 0;
		this.startPitchRangeDeg = startPitchRangeDeg;
		this.rivalIndex = 0;
	}

	/**
	 * A combo drill: the stage's fight, but against the newest copy of the agent, which starts right
	 * in front of it on level ground.
	 */
	public static EpisodePlan drill(StageSpec stage) {
		return new EpisodePlan(stage.bot.drill(), stage.startPitchRangeDeg);
	}

	/** Range drill: how often the standing opponent is put somewhere else, and how often that is out of reach. */
	private static final int RANGE_DRILL_HOP_TICKS = 80;
	private static final float RANGE_DRILL_FAR_SHARE = 0.5f;

	/**
	 * The opponent of a crit drill, by the lesson the drill is for (see {@code Curriculum.DRILL_RANGE}
	 * and the one after it).
	 *
	 * <p>The jump: it stands where it is put and does nothing, and every four seconds it is somewhere
	 * else. Half of the time that is right in the agent's face, which is where a critical hit is the
	 * thing to make: jump, swing on the way down. The other half it is farther off, from the range a
	 * sprint hit is made at to across half the arena, and there the jump is to be left alone.
	 *
	 * <p>The punish crit: it walks up close, jumps and hits on the way down, every time, and after
	 * each of those hits it keeps running at the agent. A hit made like that throws the agent up and
	 * only a little way back, so the agent comes down with the opponent still in reach; that fall is
	 * what a punish crit is made from. (Up to 0.12.3 the opponent for this stood still and hit with
	 * a plain swing. Its hit carried the agent a good two blocks away from it and nothing followed,
	 * so there was hardly ever anything in reach to punish.)
	 */
	public static EpisodePlan critDrill(int lesson, boolean kept) {
		BotProfile b = new BotProfile();
		b.hopIntervalTicks = 0;
		// Within a sword's reach from the start: nothing has to be walked up to before the first swing.
		b.minDistance = 2.0f;
		b.maxDistance = 2.8f;
		if (lesson == Curriculum.DRILL_PUNISH) {
			b.movement = BotProfile.FIGHT;
			b.attacks = true;
			b.sprint = true;
			b.critChance = 1f;
			b.critPress = 1f;
			b.reactionTicks = 2;
			b.aimNoiseDeg = 3f;
			b.turnRateDeg = 30f;
		} else {
			b.movement = BotProfile.STAND;
			b.hopIntervalTicks = RANGE_DRILL_HOP_TICKS;
			// Measured between the two fighters' middles, which is a third of a block more than from
			// the eye to the hitbox. In its face: 1.2 to 1.8. Farther off: from 2.4, which is where a
			// sprint hit is made from, outwards. (In 0.13.1 "near" was anything within reach, up to
			// 3.0. So it learned to jump whenever the opponent was in reach, and in a fight that is
			// all the time it should be sprint-hitting: its combos were gone.)
			b.minDistance = 1.2f;
			b.maxDistance = 1.8f;
			b.farChance = RANGE_DRILL_FAR_SHARE;
			// Once the lesson is passed and the drill only goes on, "farther off" is out of reach
			// altogether. Whether to jump at an opponent between the two is then left to the fights:
			// that is where it can find out when a critical hit beats a sprint hit, and a drill that
			// went on charging every jump from there would take it back each time.
			b.farMinDistance = kept ? 3.5f : 2.4f;
			b.farMaxDistance = 6.5f;
		}
		return new EpisodePlan(b, 0f);
	}

	/**
	 * The opponent of the first crit drill, the crit in its face: a sparring partner that never swings
	 * and cannot be kept away. It runs straight at the agent and keeps pushing into it, and a hit
	 * moves it only a little (the caller sets how little). Nothing it does can hurt, so the agent is
	 * free to find out what a jump is for: with the opponent in its face whatever it does, a sprint
	 * hit gets it nothing a critical hit does not, and a jump made while the sword recharges has it
	 * coming down, sword back, on an opponent that is still there.
	 */
	public static EpisodePlan faceDrill() {
		BotProfile b = new BotProfile();
		b.movement = BotProfile.FIGHT;
		b.attacks = false;
		b.sprint = true;
		b.rushChance = 1f;
		b.hopIntervalTicks = 0;
		b.minDistance = 2.5f;
		b.maxDistance = 6f;
		b.maxBearingDeg = 60f;
		b.aimNoiseDeg = 5f;
		b.turnRateDeg = 28f;
		return new EpisodePlan(b, 0f);
	}

	/** A motionless, harmless bot that stays where it is put; used by the self-test. */
	public static EpisodePlan dummy() {
		BotProfile b = new BotProfile();
		b.movement = BotProfile.STAND;
		b.hopIntervalTicks = 0;
		return new EpisodePlan(b, 0f);
	}
}
