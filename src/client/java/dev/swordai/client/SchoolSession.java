package dev.swordai.client;

import dev.swordai.SwordAi;
import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.RewardModel;
import dev.swordai.core.curriculum.StageSpec;
import dev.swordai.core.curriculum.Stages;
import dev.swordai.core.curriculum.Tally;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.env.SessionClock;
import dev.swordai.core.env.StepFacts;
import dev.swordai.core.pvp.Habits;
import dev.swordai.core.pvp.Specialists;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.AimMotor;
import dev.swordai.core.rl.MixIdea;
import dev.swordai.core.rl.SwingGate;
import dev.swordai.core.school.Performance;
import dev.swordai.core.school.SchoolLearner;
import dev.swordai.core.school.SchoolPpo;
import dev.swordai.core.school.SchoolRollout;
import dev.swordai.core.school.SchoolStages;
import dev.swordai.core.school.Teacher;
import dev.swordai.core.vision.ViewGeometry;
import dev.swordai.server.ArenaServer;
import dev.swordai.server.CombatLog;
import dev.swordai.server.EpisodePlan;
import dev.swordai.server.SceneRecorder;
import dev.swordai.server.Truth;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The technique school on the screen ({@code /swordai school}): the network plays the user's own
 * character, first person, against the technique bots ({@code TechniqueBot}), and learns by PPO from
 * the damage traded, while the technique teachers and the installed network pull on its choices with a
 * pull that fades ({@link SchoolStages}, {@link Teacher}). Nothing presses a key for it but the game's
 * own limit on swinging before the sword is back.
 *
 * <p>{@code /swordai school check} plays the same fights with this network and with the model installed
 * in the user's game, as that one plays a real fight (its rules and all), pair by pair from the same
 * seeds, and learns nothing: the paired check that decides an install.
 */
public final class SchoolSession implements AgentSession {
	enum Mode {
		TRAIN, CHECK
	}

	private enum Phase {
		WAIT_ARENA, SELFTEST, WAIT_RESET, SETTLE, RUN, UPDATING, PAUSED, WAIT_FRAMES, STOPPED
	}

	private static final int ARENA_TIMEOUT_TICKS = 600;
	private static final int RESET_TIMEOUT_TICKS = 600; // 200 until 7 Nov 16:20: the server 2-5 s behind under the repeat's quick resets stopped training
	private static final int SETTLE_TICKS = 4;
	private static final int FROZEN_FRAME_TICKS = 20;
	private static final int FRAMES_TO_RESUME = 3;
	private static final long STALL_TICKS = 20;
	private static final int MISSING_BOT_TICKS = 60;
	/** A fight lasts until a knockout or this long (30 seconds), then it is cut and valued by the critic. */
	static final int FIGHT_TICKS = 600;
	static final int ROLLOUT = 4096;
	/** The reward: damage traded, a tenth a point of health, and a knockout. */
	static final float PER_HEALTH = 0.1f;
	static final float KNOCKOUT = 1f;
	/** Damage dealt weighs this much more than damage taken (25 Oct 09:25, the user: "prioritize to make it dealt more damage"). */
	static final float DEALT_WEIGHT = 1f; // run 2 (EXPERIMENTS.md): the 06:54 recipe (1.25 at 09:41, 1.5 proposed 09:55)
	/**
	 * A real punish crit (B1): a crit the game confirms, landed within this many ticks of being hit (as {@code Habits}
	 * counts it). Its damage alone paid it about a third of a point more than a plain hit, too little to outweigh letting
	 * go of W while falling (the user, 24 Oct: "the reward you give isnt big"). It pays half a knockout on top.
	 */
	static final int PUNISH_WINDOW = 18;
	static final float PUNISH_CRIT = 0.5f;
	/**
	 * Since 24 Oct 15:45 the punish crit is paid only as the game counts it (the user's table, "punish crit 0 of 89"):
	 * thrown up from the ground by their hit, the crit before it lands. Any crit within the window was paid before, 4 or 5
	 * an update against the game's 0 or 1. A crit that is not a punish crit pays this.
	 */
	static final float OTHER_CRIT = 0f; // 0.2 until 29 Oct 07:10: see CRIT_SWING_IN_LIFTS_ONLY
	/**
	 * The technique reward (the user, 24 Oct: "it is quite easy for me to code a PPO bot to do these techniques": its
	 * reward is the technique). In a fight where the network is told the teacher's technique, every tick of a moment
	 * pays for the share of the taught keys pressed as the teacher would, and a moment done pays once more; both times
	 * the technique's pull share now (0 once mastered, up to 3 while stuck), so the reward goes where it is not learned.
	 * An imitation reward beside the task's, as DeepMimic learns its many skills with PPO (Peng et al. 2018).
	 */
	static final float IMITATE = 0.02f;
	static final float DONE_BONUS = 0.2f;
	/**
	 * Since 24 Oct 16:35 a moment is paid for what it did to the fight, not for the keys alone (the user: "let it learn to turn
	 * that into damage the way a player would"): the done bonus only where it paid (the damage traded over it and the ticks
	 * after not a loss), and this on top where it won damage. In the update before, the technique reward came to about +131
	 * and the damage reward about -26: copying the keys paid five times what the trade did. The per-tick imitation was
	 * halved with it and put back at 17:30: the deflection's (T4) W/S and sprint slipped, 88% to 66% in the hour.
	 */
	static final float WON_BONUS = 0.3f;
	static final float SHARE_MOST = 3f;
	/**
	 * The golden rule (K2) broken: a knockback hit landed on a falling player, who crits back (the user's first wish). A
	 * whole knockout's worth since 24 Oct 15:40: at 0.5, 4 to 14 an update when jumpers (the uppercutter) came in.
	 */
	static final float K2_BROKEN = 1.0f; // run 2 (EXPERIMENTS.md): the 06:54 recipe (0 from 09:35)
	/** The K2_BROKEN penalty not paid within its own combo (09:25); off in run 2, as in the 06:54 recipe. */
	static final boolean K2_COMBO_EXEMPT = false;
	/**
	 * The basics teacher (the user, 24 Oct: "make a brand new teacher specializing on teaching this"): outside the technique
	 * moments the swing is its, as soon as the sword is back and only with the crosshair on them (the game's own pick, so in
	 * reach): no swing at nothing ("MANY free hit", the user, 18 Oct). In the 13:36 check the school network landed about
	 * half its swings, the installed model three in four. A swing at nothing also costs this much (0.1 until the 16:31
	 * check, where 37% of its swings were at nothing against the installed model's 9%; 0.3 until 19:00, when with the whiff
	 * guard it swung a fifth less than the installed model).
	 */
	static final float AIR_SWING = 0.15f;
	/**
	 * The crit teacher (the user, 24 Oct 15:20, the game's count of its own moves: "punish crit 0 of 89 ... ZERO percent
	 * here, fix it"). A crit is "a hit made while falling (not sprinting, not on the ground)"; of the update before, 339
	 * ticks of crit moments had 9 falling in reach with the sword back, 12 swings and no crit. On every tick a crit can be
	 * made now (falling, in reach, sword back, not sprinting, the crosshair on them) the swing is taught, technique moment
	 * or not, and a swing made there pays this much on top of its damage.
	 */
	static final float CRIT_SWING = 0.3f;
	/**
	 * Run 42 (block 3, 7 Nov): the combo reward. Each follow-up hit of its own combo (a hit dealt within COMBO_GAP ticks of its
	 * last one, with no hit taken since) pays this on top of its damage. The user (6 Nov): learn every way the document starts
	 * a combo (T4, T5, T6/T6a, T7, T8, T9) and every way it holds one (T10, T11a-c, T2), by reward, PPO not copying. A starter
	 * is worth only the follow-up it makes possible and a holder is the follow-up itself, so one term pays both. Before it no
	 * reward term touched combos and every starter/holder copy pull was 0 (SchoolStages.PULL_CAP): they were judged only.
	 * Not paid for a hit that is not followed (that would pay hit-and-out, taken out on 1 Nov). Size: follow-up hits a fight
	 * were A 1.74, B 1.75 within 20 ticks (last three checks before run 42), so about +0.35 a fight against a damage
	 * reward of about +-6.
	 */
	static final float COMBO_HIT = 0.2f;
	static final int COMBO_GAP = 20;
	/**
	 * Run 44 (block 5, the user 7 Nov 07:30: "always like 3+ combo"): the third and every later hit of its own combo pays this
	 * on top of COMBO_HIT (0.4 a hit in all). Of its 2-hit combos 37% went on to a third in run 42's benchmark.
	 */
	static final float COMBO_HIT3 = 0.2f;
	/**
	 * Run 44, the chase crit (the user, 7 Nov 07:50, their own knowledge: "a lot of player back out of combo so ... once the AI
	 * sees player backing out, jump and chase it and crit it cause it is a good chance ... their back out ... is over three
	 * block so jump in crit won't get you knock away"): a crit that carries on its own combo (within CHASE_GAP ticks of its last
	 * hit, no hit taken since) on an opponent that went away from it for AWAY_TICKS or more since that hit pays this on top.
	 * Taught by reward, no rule; run 43's AIR_JUMP_HIT costs only when the jump is hit, which a safe chase is not.
	 */
	static final float CHASE_CRIT = 0.4f;
	/**
	 * Run 44, made to miss (the document's T6, "make them miss, then stun"; the user, 7 Nov 08:00: strafe one way, then
	 * suddenly the other, circle strafe, so the opponent used to aiming one way misses): a bot's swing that misses it within
	 * reach (TechniqueBot#whiffs; the bots now aim as people do, Style#aimAdapt) pays this. The strafe that makes it is the
	 * network's to find.
	 */
	static final float MADE_MISS = 0.15f;
	/**
	 * Run 45, the deflection start (the document's T4: "sprint-hit the opponent immediately after they attack you"; the user,
	 * 7 Nov 08:30: knock the attacker away, crit or not, and combo from it): a sprint hit that opens a combo of its own (no hit of
	 * its own within COMBO_GAP before) while their sword is under DEFLECT_CHARGE (they just swung) and no hit taken this tick
	 * pays this; COMBO_HIT then pays the follow-ups. Measured before: such first hits got a 2nd hit 39% in the user's real duels
	 * (44% benchmark), into a ready sword 22% (20%), and they were only a third of its first hits. The jump-in hold (the user's
	 * 5 Nov rule: only an early riser, a lag buffer, never a faller) and K2's cost stay as they are.
	 */
	static final float DEFLECT_HIT = 0.25f;
	static final float DEFLECT_CHARGE = 0.5f;
	private int deflectStarts;
	// Run 47: deflection chances (KIND_DEFLECT): the open one (fight tick, -1 none), the missed one to keep, the counts.
	private int deflectChanceAt = -1, deflectFrom = -1, keepDeflectAt;
	private float lastBotChargeSeen = -1f;
	private int deflectChances, deflectMade, deflectMissed;
	private boolean sceneDeflected, sceneDeflectOver;
	private int lastWhiffs;
	private int madeMiss;
	static final int CHASE_GAP = 30;
	static final int AWAY_TICKS = 3;
	/** Ticks since its last hit that they moved away from it (Duel.theyClose below -0.05), and chase crits paid since the update. */
	private int awayTicks;
	private int chaseCrits;
	/** Its hits in a row in the reward's count (1 = a first hit), and the 3rd+ hits paid since the last update. */
	private int rewardRow;
	private int combo3Paid;
	/** The fight tick of its last hit dealt with no hit taken since (-1000: none), and the follow-up hits paid since the last update. */
	private int lastOwnHitAt = -1000;
	private int comboHitsPaid;
	/**
	 * Run 43 (block 4, 7 Nov): a hit taken in the air from a jump the network pressed costs this on top of its damage. In run
	 * 42's benchmark (tools/airjumps.py) 24% of the damage it took came that way (jumps no rule pressed; rule jumps 3%), the
	 * game's model 19%, the 23 Oct model 6% in the hourly checks; most were take-offs 4.5+ blocks out, hit 5-9 ticks later
	 * on the way in. Jumping in is not banned (the user, 2 Nov: land 2.5-3 blocks out, not in their reach; the document's
	 * T1a, T9, T11b jump): only being hit in the air out of one's own jump costs more, so PPO finds when a jump is safe.
	 * Size: about 1.8 such hits an episode in that benchmark, about -0.5 a fight against the damage reward's +-6.
	 */
	static final float AIR_JUMP_HIT = 0.3f;
	/** In the air from a jump the network pressed on the ground (not a rule's), until it stands again; such hits since the update. */
	private boolean ownJumpAir;
	private int airJumpHits;
	/**
	 * The crit swing's pay only in a lift by their hit (the punish crit's chance), and no pay for other crits (OTHER_CRIT 0),
	 * from 29 Oct 07:10: on fresh seeds (pairs 1700-2431) the school network jumped in at an opponent standing with its sword
	 * ready 3.4 times as often as the installed model (1.33 a fight to 0.39), was hit first 0.90 a fight and hit first 0.34,
	 * and its jump-ins netted -3.4 a fight to the installed model's -1.4; damage taken in the air was its largest gap (-1.75).
	 * Paid on top of their damage, its own jump-ins' crits looked better to it than they were. Now damage alone pays them.
	 */
	static final boolean CRIT_SWING_IN_LIFTS_ONLY = true;
	/** Updates at the start of a new school that teach the critic only (the actor is the installed one's, the critic new). */
	static final int CRITIC_WARMUP = 3;
	/** The bot is put between these distances, in view. */
	static final float NEAREST = 3f;
	/**
	 * The punish crit drill (start-state curriculum: Salimans and Chen 2018, the research round of 25 Oct 20:45): this share of
	 * the training fights starts with the bot in its reach (DRILL_NEAR to DRILL_FAR) and its sword all the way back, the
	 * agent's sword 2 to 8 ticks into coming back, so the bot's sprint hit lifts it first with its sword coming back on the way
	 * down: the punish crit's chance (B1, "their hit knocks you up; you crit them on the way down") in every such fight, where
	 * a fight had about one in four minutes. The rest of the fight goes on as any. The user, 25 Oct: learn it in a few tries.
	 */
	static final float DRILL_SHARE = 0f; // 0.25 in runs 15-16b: punish crits in the checks never rose (4% vs 10%); off in run 17. 0.15 on 28 Oct 03:28-04:30 as the punish drill of the user's method (told B1, its own keys, the teacher's jump reset): the drill critted 1 of 61 lifts in reach, and the 04:17 check went -4.5 (4.3) with 304 swings to the installed model's 354 (331 vs 328 at 03:15): off
	static final float DRILL_NEAR = 2.2f;
	static final float DRILL_FAR = 2.8f;
	/** This fight is a drill: the agent's sword is set this many ticks into coming back on its first tick; -1 otherwise. */
	private int drillTicker = -1;
	private int clientClockSets;
	// Run 46f: a scene is put back again when this client is ready to act (ArenaServer.putBackScene).
	private boolean putBackAsked;
	private int putBackBefore, putBackWait, putBackChecks, putBackTimeouts, putBackSprintKept;
	private double putBackPosErr, putBackVelErr, putBackVel;

	/** True once the waiting scene (if any) is put back and this client stands where it was put, moving as it was. */
	private boolean scenePutBackReady() {
		if (sceneNow == null) {
			return true;
		}
		if (!putBackAsked) {
			putBackAsked = true;
			putBackBefore = ArenaServer.scenesPutBack;
			putBackWait = 0;
			mc.getSingleplayerServer().execute(ArenaServer::putBackScene);
			return false;
		}
		double[] at = ArenaServer.agentPutBack;
		boolean put = ArenaServer.scenesPutBack != putBackBefore && at != null;
		boolean here = put && Math.abs(mc.player.getX() - at[0]) < 1e-4 && Math.abs(mc.player.getY() - at[1]) < 1e-4
				&& Math.abs(mc.player.getZ() - at[2]) < 1e-4;
		if (!here && ++putBackWait <= 20) {
			return false;
		}
		putBackAsked = false;
		if (!here) {
			putBackTimeouts++;
		} else {
			// The direct check of the agent's side: what its own client has the moment it can first act.
			net.minecraft.world.phys.Vec3 v = mc.player.getDeltaMovement();
			double velErr = Math.sqrt((v.x - at[3]) * (v.x - at[3]) + (v.y - at[4]) * (v.y - at[4]) + (v.z - at[5]) * (v.z - at[5]));
			boolean sprint = mc.player.isSprinting() == (at[6] > 0.5);
			// The server's sprint flag reaches this client with the next entity update, a tick after the teleport: set here.
			mc.player.setSprinting(at[6] > 0.5);
			putBackChecks++;
			putBackVelErr += velErr;
			putBackVel += Math.sqrt(at[3] * at[3] + at[4] * at[4] + at[5] * at[5]);
			putBackSprintKept += sprint ? 1 : 0;
			if (putBackChecks <= 20 || putBackChecks % 50 == 0) {
				SwordAi.LOGGER.info(String.format(Locale.ROOT, "school: scene put back for the client after %d ticks: velocity error %.3f of %.3f, sprint from the server %s (set)"
						+ " | so far %d: velocity error %.3f of %.3f, sprint arrived %.0f%%, timeouts %d", putBackWait, velErr,
						Math.sqrt(at[3] * at[3] + at[4] * at[4] + at[5] * at[5]), sprint ? "kept" : "LOST", putBackChecks,
						putBackVelErr / putBackChecks, putBackVel / putBackChecks, 100.0 * putBackSprintKept / putBackChecks, putBackTimeouts));
			}
		}
		return true;
	}
	private boolean drillFight;
	private int drillFights;
	private int drillPunishCrits;
	static final float FARTHEST = 8f;
	/** Steps whose policy loss counts for nothing: the opponent farther than this and out of sight. */
	static final float SKIP_FARTHER = 8f;
	/**
	 * Of the installed model's choice as a target, the share on its key: all of it. Its choices are drawn, so over many steps the target
	 * is its whole policy; a share spread over the other keys would teach a jump on every tenth tick (24 Oct: 176 jumps a minute).
	 */
	static final float SHADOW_SURE = 1f;
	private static final int KEEP_EVERY_MINUTES = 60;
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

	private final Minecraft mc;
	private final Mode mode;
	private final Config cfg;
	private Phase phase = Phase.WAIT_ARENA;
	private final FrameGrabber grabber = new FrameGrabber();
	private final Observer observer = new Observer();
	/** {@code /swordai debug}: save the next frame the network uses, as it sees it. */
	private boolean debugRequested;
	private final BlindGuard blind = new BlindGuard();
	private SelfTest selfTest;
	private boolean selfTestPending = true;
	private final Random rng = new Random();

	private final SchoolLearner learner;
	private final SchoolStages stages = new SchoolStages();
	private final Teacher teacher = new Teacher();
	private final DuelReader reader = new DuelReader();
	/**
	 * Whether it does each technique when its moment comes; in training the stages are told of every moment. In a
	 * paired check one for each side (A the school's network, B the installed model), told to nobody.
	 */
	private final Performance perf;
	private final Performance perfB = new Performance(null);
	/** What the game shows it did, by the same detector the bots read it with, per side (0 A or training, 1 B): chances seen and taken. */
	private final int[][] ownSeen = new int[2][Habits.COUNT];
	private final int[][] ownDid = new int[2][Habits.COUNT];
	private int barTicks;
	private final ExecutorService updater;
	private Future<SchoolPpo.Stats> pending;
	private int warmupLeft;
	/**
	 * The installed model as it plays the user's fights, its rules and all. In a paired check it plays side B; in
	 * training it runs alongside the network on every tick without pressing anything, and what it would press is
	 * the installed teacher's target (plan part 6: each old rule becomes a teacher, its pull fading).
	 */
	private LoadedFighter installed;
	/**
	 * The installed model as a sparring opponent (the user, 25 Oct: beat the teacher): a second copy (a Fighter is not
	 * thread-safe) playing the bot on the server (ArenaServer's rival, BotPilot: ReplicaBody with the SyntheticEye picture,
	 * measured 99% like the screen's) in SPAR_SHARE of the training fights, its view {@link #SPAR_LAG_EXTRA} ticks later than
	 * the agent's as the old self-play had it (Config.screenCopyLagTicks: the screen's side loses about two ticks). Every run of
	 * 25 Oct fought only scripted bots and came out level with the installed model (the long check of 26 Oct 02:04: -2.7, se 2.4).
	 */
	private LoadedFighter sparring;
	/**
	 * The league (run 36, 3 Nov): networks once installed in the user's game or screened against it, each with the installed
	 * model's rules as the user's game plays them, from LEAGUE_DIR; they play LEAGUE_SHARE of the training's sparring fights
	 * (the rest, and every check, the installed model). Past selves as opponents keep a learner from fitting one opponent
	 * (Vinyals et al. 2019, AlphaStar's league; Bansal et al. 2018). Runs 34 and 35 sparred only the 23 Oct model and came out
	 * level with the installed network on fresh pairs; sparring without the rules stayed at about -127 a minute all day.
	 * Their fights are logged as league-NAME, kept out of the sparring tally and out of the scenes.
	 */
	private final List<SchoolBotPilot> league = new ArrayList<>();
	private final List<String> leagueNames = new ArrayList<>();
	static final String LEAGUE_DIR = "league";
	static final float LEAGUE_SHARE = 0.5f;
	/** This fight's sparring opponent: 0 the installed model, above it a league network. */
	private int rivalNow;
	/**
	 * In a scene fight the acting network is told the decider's own choice in this share (the teacher's in the rest), so
	 * that the decider learns when to do what (Chooser.OUTCOME) where the focus is: the crits' scenes first, then the combo
	 * escape's (26 Oct 13:00; the full fights keep the learner's 0.85 of the teacher's).
	 */
	static final float SCENE_DECIDER_SHARE = 0.5f;
	static final float TEACHER_FORCING = 0.85f;
	static final float SPAR_SHARE = 0.4f; // run 18 0.3 (26 Oct 03:30); run 19-21 0.6 (the research of 05:20); 0.4 of the full fights from run 21 09:40 (the rest the technique bots, reviewed)
	/**
	 * The mirror check: with this file in the school folder, every fight of a check (but the fight basics) is against the
	 * server's copy of the installed model, A (the school network) and B (the installed model on the screen) on the same
	 * seeds. B against its own copy should come out about level if the server's copy is fair; A minus B is the school
	 * network's lead over the installed model, the screen-against-server bias cancelled.
	 */
	static final String SPAR_CHECK_FILE = "spar-check";
	/**
	 * A check's test-time sharpening: with this file in the school folder (lines "head temperature", heads by ActionSpace.NAMES,
	 * "all" for every head not named; 0 = the most likely key), the school network picks its keys at those temperatures in a
	 * check; the installed model and the training stay at 1.
	 */
	static final String PLAY_TEMPERATURE_FILE = "play-temperature";
	/**
	 * A check's first pair (its seeds): with this file in the school folder holding N, a check starts at pair N, so a
	 * confirmation runs on seeds its candidate was not picked on. Without it every check starts at pair 0.
	 */
	static final String CHECK_FROM_FILE = "check-from";
	/**
	 * The benchmark (docs/BENCHMARK.md, tools/bench-run.sh; the user, 6 Nov 23:40: one damage total against one old model cannot
	 * tell whether a model is good). With this file in the school folder a check plays a fixed suite of episodes instead of its
	 * pairs: side A only (the network in checkpoint.bin, with the rules as the user's game plays it), each episode on a seed of
	 * its own, recorded tick by tick in the text of the user's real fights (RoundRecorder) to swordai/bench/TAG/. The network it
	 * is compared with plays the same suite in a run of its own, and the two are compared episode by episode (paired). The file:
	 * "tag NAME" first, then optionally "network LABEL" (what to call the network in DONE), then the suite's lines in order
	 * (tools/bench/suite-v1.txt): "BOT EPISODES" (a name of Specialists.NAMES) or "h2h EPISODES [LEAGUE-NAME]"; "#" starts a
	 * comment. Without it a check is the paired check, as before.
	 */
	static final String BENCH_SUITE_FILE = "bench-suite.txt";
	/** Episode i of a suite plays on mix(BENCH_SEED + i): apart from the checks' seeds (0x5C400 on), so it never replays a check's pair. */
	static final long BENCH_SEED = 0xBE4C0L;
	/** An episode broken off (a pause, a stall, frames that stopped) is played again from its seed, at most this many times in all. */
	static final int BENCH_TRIES = 3;
	/** The finished suite's mark in its folder (what bench-run.sh waits for): how many episodes, which network, when. */
	static final String BENCH_DONE_FILE = "DONE";
	/**
	 * Run 30 (EXPERIMENTS.md), the take-over. The installed model's rules press the keys that decide most hits taken (42% of the
	 * ticks of a hit taken: W/S, sprint or jump), and the network is locked out of a key a rule presses: no policy gradient, no
	 * lesson. So (1) the network is taught what the rules press (RULED_PULL, SchoolPpo#teach) wherever they press it; (2) a share of
	 * the training fights is played without the rules (the network's own keys on every head, taught the rules' key as the target
	 * and trained by the damage), the share rising as its cross-entropy to the rules' keys falls; (3) with this file in the
	 * school folder a check plays side A without the rules, to see how far the network is from taking over.
	 */
	static final String RULES_OFF_CHECK_FILE = "rules-off-check";
	/** With this file in the school folder a real fight (FightSession) is played without the rules: the network alone. */
	static final String NO_RULES_FILE = "no-rules";
	/** An empty file of this name in the school folder switches hit and out on for a real fight (FightSession), with the rules over the network. */
	static final String HIT_AND_OUT_FILE = "hit-and-out";
	/** With this file in the school folder a benchmark plays side A with hit and out over the network (an A/B of the rule, 7 Nov). */
	static final String BENCH_HIT_AND_OUT_FILE = "bench-hit-and-out";
	static final float RULED_PULL = 0.3f;
	/**
	 * Run 30e: the pull on the rules' keys follows a budget instead of staying at RULED_PULL (the adaptive controller of the body keys' copying,
	 * SchoolStages#noteBodyCe, Ziegler et al. 2019): after every update it moves by at most BUDGET_STEP towards holding the smoothed cross-entropy
	 * to the rules' body keys at RULED_CE_TARGET. It tightens while the network is further from the rules than that and loosens once it is
	 * closer, so that what the damage reward finds is let stand: the network ends about that far from the rules, in the decisions where
	 * playing otherwise paid.
	 */
	static final float RULED_CE_TARGET = 0.25f; // 0.15 until 14:40 (30g: the installed model's own network choices are drawn, so its keys cost about 0.3 at best)
	static final float RULED_PULL_LEAST = 0.05f;
	static final float RULED_PULL_MOST = 0.6f;
	private float ruledPullNow = RULED_PULL;
	/**
	 * Run 37 (4 Nov): the actor's step at most half of where the adaptive rate settled (SchoolPpo: 1.0-1.5e-4, kl 0.03-0.05, a
	 * fifth to a quarter of the samples clipped). After both restarts of run 35's line A's own hourly column peaked for about
	 * four hours and slid after five (11.2, 11.6, 10.3, 9.4, then 4.0, 10.3, 4.2, -3.1), and on fresh pairs the peaks screened
	 * +3.0 and +1.8 and the end -2.7: the policy drifts off its best. A smaller step drifts less.
	 */
	static final float ACTOR_LR = 5e-5f;
	static final float MAX_ACTOR_LR = 7.5e-5f;
	// 0.10 and 0.40 in the first hour of run 30 (08:05-08:26): the baseline screen of 08:16 (the network of 07:54 without the rules,
	// pairs 0-65) came out -7.9 (3.7) against the installed model while the same network with the rules runs about +3 on those
	// seeds, so the gap to close is about 11 a fight and the network's own states are what it must learn from.
	static final float RULE_OFF_MIN = 0.25f;
	// Run 38 (5 Nov): 0.25 (0.70 from 1 Nov 11:35, 30e; 0.50 before). Four days with 70% of the full fights and scenes played without
	// the rules did not move the network alone (fresh scripted screens without the rules: -6.1 on 1 Nov, -5.9 on 5 Nov), while the
	// play with the rules, the one the checks and the user's game use, got the other 30% and stays at the installed's level (+2.5 to
	// +3.1): the take-over goes on at its least and the rest of the practice is where the network plays. Reverted 5 Nov 12:40: the
	// fresh screens at 3 and 6 hours (+2.4, +1.9) were no better than run 37's (+3.0, +3.1, +2.5); on a tie the take-over stays.
	static final float RULE_OFF_MAX = 0.70f;
	static final float RULE_OFF_CE_START = 0.60f;
	static final float RULE_OFF_CE_FULL = 0.20f;
	/** Smoothed cross-entropy to the rules' keys (-1 before the first update that had some) and how often its likeliest key was the rule's. */
	private float ruledCeEma = -1f;
	private float ruledAgreeEma;
	/** This fight is played without the installed model's rules (training: the share above; check: the flag file, side A only). */
	private boolean ruleOff;
	private boolean rulesOffCheck;
	/** Fights played without the rules since the last update: how many, damage, ticks, knockouts won and lost; and all of them since the start. */
	private int ruleOffFights;
	private float ruleOffDealt;
	private float ruleOffTaken;
	private int ruleOffTicks;
	private int ruleOffWon;
	private int ruleOffLost;
	private int ruleOffFightsAll;
	/** The same for their swings (as swingCount: swings, on them, hits landed, their second and third hits in a row). */
	private final long[] ruleOffSwings = new long[5];
	private boolean sparCheck;
	static final int SPAR_LAG_EXTRA = 2;
	private boolean sparFight;
	private int sparFights;
	private int sparWon;
	private int sparLost;
	private float sparDealt;
	private float sparTaken;
	private final BodyState installedBody = new BodyState();

	// ---- the fight --------------------------------------------------------------------------------
	private final StepFacts facts = new StepFacts();
	/**
	 * The ticks where the teacher wants a crit, per side (0 the network or the teacher, 1 the installed model), step by step
	 * of what a crit needs: falling, in reach, the sword charged, not sprinting as the server has it; then the swings
	 * made (and of them the ones the game would make crits), the hits and the crits landed. Whether the crits are real,
	 * and if not, which of their needs is missing (24 Oct: the teacher's own punish crits came 4 in 363).
	 */
	private final long[][] critTrace = new long[2][12];
	/** The keys the installed model's rules pressed on this tick, for the crit trace (who held W in the fall). */
	private int ruledNow;
	/**
	 * Per side, the answer to jumping in (the priority): their take-offs toward it, its hits landed on them rising (T8), its
	 * knockback hits landed on them falling (what K2 forbids), and the crits it took within ten ticks of one of those.
	 */
	private final long[][] jumpIn = new long[2][15];
	/**
	 * Its hits on a rising jumper by how close they were (under {@link #RISER_CLOSE} blocks), and the crits taken within ten
	 * ticks after each kind. The user, 25 Oct 17:12: a sprint hit does not knock a close rising jumper away, and there is lag.
	 */
	static final float RISER_CLOSE = 2.0f;
	private final int[] sinceRiserHit = {1000, 1000};
	private final boolean[] riserHitClose = new boolean[2];
	private int sinceFallingHit = 1000;
	/** Real punish crits rewarded since the last update, and crits landed outside that window. */
	private int punishCritsPaid;
	private int otherCrits;
	/** Ticks a crit could be made on (see {@link #CRIT_SWING}), the swings made on them, and whether the last decision was one. */
	private int critChances;
	private int critSwings;
	/** Ticks of a lift the sprint let go was taught on (see the punish crit teacher). */
	private int sprintLetGo;
	/** Ticks of a lift's fall the aim onto them was taught on. */
	private int liftAim;
	/** Ticks it was taught to turn toward an opponent off the screen or well off the crosshair; of them, off the screen. */
	private int findAim;
	private int findBlind;
	/** The side the opponent was last seen on (the sign of the yaw toward them), and the way it sweeps while they are not seen. */
	private int seenSide;
	private int sweep;
	/**
	 * Review of the old curriculum's stages (the user, 24 Oct 22:40: "during between each training it will still practice aim
	 * every few episodes to not forget, so do hit timing, and replay some part of older stages so let it review"). The installed
	 * model learned its aim, its tracking and its swing in those stages (Stages.defaults()); the school fought technique bots
	 * only, which hardly ever leave its view, and in the user's real fights (22:05) it lost a player who jumped out of view
	 * and swung at the air. This share of the training fights is one of those stages' episodes instead: that stage's bot, its
	 * keys and its rewards.
	 */
	// 0 from 25 Oct 03:25 (control run; was 0.25); 0.2 from 09:00, the user: "Review every skill while learning that so it won't
	// forgot stuff".
	static final float REVIEW_SHARE = 0.15f; // 0.19 on 1 Nov 04:25-08:05 (run 29, reverted: its five checks averaged +0.7), 0.1 until 26 Oct 09:40 // run 7 (EXPERIMENTS.md): the first technique family, the crits, with the basics reviewed in between (the user, 13:25: "letting it review in between")
	/** The share of the specialists' training fights in which the specialist does other things too (Specialists.varied). */
	static final float VARIED_SHARE = 0.5f;
	/**
	 * The basics first (the user, 24 Oct 22:50: "let it learn entire basic first, fight against a non moving and random moving
	 * and jumping opponent, because it FUCK UP on the basic already"): until every basic has passed its stage's bars once,
	 * this share of the training fights are basics; the rest stay technique fights so that those are not lost. After that,
	 * {@link #REVIEW_SHARE} ("after it is done with the basics make it constantly review it").
	 */
	static final float BASICS_SHARE = 0.1f; // run 7 (EXPERIMENTS.md): the first technique family, the crits, with the basics reviewed in between
	/** With all but the last basic passed, the basics' share comes down toward this. */
	static final float BASICS_SHARE_LEAST = 0.1f; // run 7 (EXPERIMENTS.md): the first technique family, the crits, with the basics reviewed in between
	/** Steps of a basic judged at a time against its stage's bars. */
	static final int PASS_STEPS = 4000;
	/** A basic that failed this many judgements in a row stops holding the rest back (and is still drawn three times as often). */
	static final int PASS_TRIES = 6;
	/** Swings with the crosshair on them below which a basic is not passed, whatever its stage asked (the user's whiffs). */
	static final float MIN_SWINGS_ON = 0.8f;
	/** The old stage each basic is: its number in the observation, as the installed model learned it. */
	static final int[] BASIC_OLD = {0, 1, 2, 3, 4, 4, 5, 6, 2, 8, 8, 8};
	/**
	 * After the basics, how often each is reviewed: tracking, vertical aim, the moving jumper and combo escape twice. The
	 * timer was 9 on 1 Nov 04:25-08:05 (run 29: the bot the network trails the installed model on; reverted, five checks +0.7).
	 */
	static final float[] REVIEW_WEIGHT = {1f, 2f, 2f, 1f, 1f, 2f, 1f, 2f, 2f, 3f, 3f, 2f};
	/** The fight basics (see fightBasic): the one that times its crit on its rhythm, the one that always jumps in, the spacer. */
	static final int TIMER = 9;
	static final int JUMP_IN = 10;
	static final int SPACER = 11;
	/** Of the fight basics, the share started with the opponent close beside or behind it, out of the picture. */
	static final float BESIDE_SHARE = 0.3f;
	/** A basic's episode is at most this long. */
	static final int REVIEW_TICKS = 400;
	/** The stages' rewards are paid by the tick (aim) and would outweigh the fights' damage in the advantages: scaled down. */
	static final float REVIEW_SCALE = 0.5f;
	private final StageSpec[] basics = basicStages();
	/** The basic this fight is, or -1 in a fight of the school. */
	private int review = -1;
	private final RewardModel reviewRewards = new RewardModel();
	/** Per basic: what its episodes showed since it was last judged, and how many there were. */
	private final Tally[] reviewTally = new Tally[BASIC_OLD.length];
	private final int[] reviewFights = new int[BASIC_OLD.length];
	/** Per basic: passed at its last judgement; passed at some judgement; judgements failed in a row. */
	private final boolean[] basicPassed = new boolean[BASIC_OLD.length];
	private final boolean[] basicPassedOnce = new boolean[BASIC_OLD.length];
	private final int[] basicFailed = new int[BASIC_OLD.length];
	/** Per basic: the crosshair on them at its last judgement (0 before one). */
	private final float[] basicOnTarget = new float[BASIC_OLD.length];
	/** The fight basics in the paired check, at the end of each round of its bots: both sides on the same draw. */
	static final int CHECK_FIGHTS = 3;
	static final String[] CHECK_NAMES = {"fight-timer", "fight-jumpin", "fight-spacer"};
	private int checkReview = -1;
	/** Every basic passed once (or tried PASS_TRIES times): from then on they are reviewed. Kept in basics.properties. */
	private boolean basicsDone;
	private int reviewHop;
	/** Per technique: swings its teacher asked for that a rule kept back, by the sword gate (0) and the whiff guard (1). */
	private final int[][] blockedSwings = new int[Technique.COUNT][2];
	/** Technique moments that won damage since the last update (see WON_BONUS). */
	private int momentsWon;
	private boolean critChanceAtClick;
	/** Ticks since their hit threw it up from the ground, -1 once it has landed (see OTHER_CRIT); on the ground the tick before. */
	private int liftedFor = -1;
	private int ticksSinceCrit = 1000;
	private boolean onGroundBefore = true;
	/**
	 * Each lift (thrown up from the ground by their hit) by the kind of that hit (Events: sprint, crit, plain): how many, how
	 * far toward a punish crit each got (came down in reach, with the sword back, not sprinting, the crosshair on them, a swing
	 * there, a crit), and how many the teacher took as a punish crit (B1). The user, 24 Oct 15:50: "punish crit didn't
	 * improve"; the game counted 0 or 1 of about 80 an update, the installed model 1-2% in the checks.
	 */
	private final int[][] lifts = new int[4][12];
	/** Lifts told the punish crit, and how far each got (0 all, then in reach ... crit as liftStage): [0] full fights, [1] punish drill. */
	private final int[][] toldStages = new int[2][7];
	private int liftKind = -1;
	private int liftStage;
	private boolean liftAimedNow;
	private boolean liftTaught;
	/** The network was told the punish crit (B1) in this lift: by the decision model's own choice, or the teacher's. */
	private boolean liftTold;
	private boolean liftToldByDecider;
	/** The technique reward owed to the next step, and what it paid since the last update; the pull shares it uses. */
	private float shaping;
	private float shapingPaid;
	private int momentsPaid;
	private int k2Broken;
	private float[] pullsNow;
	/**
	 * Per side (0 the network, 1 the installed model in a check): swings, swings with the crosshair on them, hits landed; and their
	 * second and third hits in a row with none of its own between (the user's real fights, 24 Oct 22:05: comboed, "like 3 sprint
	 * hits before it even press S or do any react").
	 */
	private final long[][] swingCount = new long[2][5];
	/** Their hits in a row in this fight, none of its own between. */
	private int theirRow;
	/** Ticks what it sees of them is behind the game this fight. */
	private int fightLag;
	/** A technique moment's own target and keys, kept for judging it when its teacher had no pull (NO_PULL_YIELDS). */
	private final float[] judgedTarget = new float[ActionSpace.TOTAL_LOGITS];
	private int judgedHeads;
	private int judgedTechnique;
	/** Ticks the golden rule (K2_GUARD) held the swing, and ticks the installed model's rules set keys (RULE_OVERLAY). */
	private int k2Held;
	/** In the air now on a jump that started at it from close (JUMP_IN_GUARD), not yet hit by it. */
	private boolean jumpInAir;
	private int ruleTicks;
	/** The school's sprint hits on one the server had falling (Truth.botFalling), and that flag as of the step. */
	private int k2Truth;
	private boolean lastTruthFalling;
	/** Per side (0 the school, 1 the installed model): fight ticks, ticks with the opponent off the screen, times they went off it. */
	private final long[] viewTicks = new long[2];
	private final long[] lostTicks = new long[2];
	private final long[] lostTimes = new long[2];
	private final boolean[] wasVisible = {true, true};
	/** The same outside its own combo as it saw them (falling on the screen), and whether the step's hit was in its combo. */
	private int k2Seen;
	private boolean lastInOwnCombo;
	/** The technique of the moment on the last tick, so a moment's start is told to the stages with the bot it came against. */
	private int lastMomentTechnique = -1;
	/** In a check, the school network's combo jump resets mistimed now and then, as they will be in a real fight. */
	private final dev.swordai.core.school.FairJump fairJump = new dev.swordai.core.school.FairJump(0x5EEDL);
	private int critWantedFor;
	private static final boolean[] CRIT_WANTED = new boolean[Technique.COUNT];
	/**
	 * The overlay: the installed model teaches the fighting between the techniques (and the camera), the file's teachers
	 * the technique moments. 24 Oct, in the game: the file's own ordinary fight, keys pressed exactly, lost 22 a fight to
	 * the installed model, and the student does not get past its teacher.
	 */
	static final boolean OVERLAY = true;
	/**
	 * The installed model's fight-time rules over this network (the user, 25 Oct 08:40: build it): on a tick one of its rules
	 * sets keys (answering a hit, backing out, its jump rules, the punish crit, the aim rule, ...), those keys are the rule's,
	 * and the network neither chooses nor learns them there. The network with the installed weights and no rules was 7.3 a
	 * fight behind the installed model with them (04:07 check), and every punish crit of the installed model was a rule's.
	 */
	static final boolean RULE_OVERLAY = true;
	/**
	 * The sprint let-go teacher's own pull (run 3 of EXPERIMENTS.md): after their hit lifts it, near them with the sword
	 * coming back and still sprinting, W and sprint let go so the crit can be made. Of 2060 lifts in run 2, 560 came down in
	 * reach, 306 with the sword back and only 86 of those not sprinting (the punish crits' leak); taught at the B1 pull,
	 * which was 0, it taught nothing on 4630 lift ticks. 0: as before.
	 */
	static final float LET_GO_PULL = 1f; // run 7 (EXPERIMENTS.md): the first technique family, the crits, with the basics reviewed in between (1 in run 3, 11:00-11:22, stopped: fundamentals first)
	/** The let-go is taught from the lift on, within this many blocks and with the sword this far back (3.5 and 0.5 until 26 Oct 18:10). */
	static final float LET_GO_REACH = 4.5f;
	static final float LET_GO_CHARGE = 0.3f;
	/**
	 * Run 5 (EXPERIMENTS.md): a teacher whose pull is zero (the basics swing, the crit swing, the find-them camera, the
	 * sprint let-go, the punish crit's aim) no longer puts its target on the step, so the installed model's stays and is
	 * learned. Until 25 Oct 12:00 they all wrote theirs at a pull of 0: the installed model's swing was never copied on the
	 * ordinary fight's ticks, nor its camera with the opponent off the screen or 25 degrees off (the investigation, 11:58).
	 */
	static final boolean NO_PULL_YIELDS = true;
	/**
	 * The crits' held swing not taught (run 8 of EXPERIMENTS.md if run 7 is worse): the punish crit (B1) teaches no swing on
	 * every tick until it falls in reach without a sprint, the standing crit (T2) while the sword comes back and on the way
	 * up, the double crits until the fall (PvpPlayer step, jumpedCrit). Taught in run 7, its swings off the techniques fell
	 * from 25-44 a minute to 11-19 and the damage dealt from 303-327 to 234-298, as "don't swing" spread from K2 at 08:22.
	 * With this on, those plans teach W, sprint, jump and the crit's own swing; when to hold is the damage's.
	 */
	/**
	 * The camera turned to them on the way down in a lift (the aim teacher, below): it runs only with the punish crit's pull,
	 * which was 0 until 28 Oct 06:26; on with it (06:26-06:41, 18:58-19:20) the turns to find them went from 0 to 9 an update.
	 */
	static final boolean LIFT_AIM = false;
	static final boolean CRIT_HOLD_UNTAUGHT = true; // run 8 (25 Oct 14:41)
	/**
	 * Run 10 (EXPERIMENTS.md): the crit's held swing taught again in the air, so only the walk-in's wait for the sword (on the
	 * ground, where it spread into the fight in run 7) stays untaught. Without it the network swung on the way up, the sword was
	 * not back by the fall, and its crits hardly landed (punish crit by the game 4 of 90, 25 Oct 15:34); the double crit (T1)
	 * starts only after a crit of its own lands, so it had no moments to learn from. The user, 15:38: "DOUBLE CRIT WHERE THE
	 * HECK IS IT LEARNING". The frozen best fighter (ANCHOR_TO_BEST) holds the rest of the fight in place.
	 */
	static final boolean CRIT_HOLD_IN_AIR = true; // run 10 (25 Oct 16:15)
	/**
	 * Run 12 (EXPERIMENTS.md): lifted by their hit and coming down near them with the sword coming back, the installed model's
	 * rules leave W and the sprint to the network. Its jump reset (JumpReset: W and sprint held on every airborne tick after the
	 * jump, up to 14 ticks), its JumpIn jumps and "meet" held W there; this game ends a sprint only when W is let go, and starts
	 * one again in the air with the sprint key. Of the falls in reach with the sword back still sprinting, W was the rules' on
	 * 8-17 an update and the network's on 0-1 (25 Oct 17:10, the crit trace), and the let-go and punish crit (B1) targets
	 * there were dropped as locked keys: punish crits landed 3-6% by the game. The jump on the hit (T9) stays the rules'.
	 */
	/**
	 * W and the sprint are the network's only in the punish crit's own ticks: lifted by their hit, falling, within
	 * LET_GO_AT_CRIT_REACH and the sword back (26 Oct 20:40). The whole lift (RULES_LET_GO_IN_LIFT) let it stop sprinting
	 * (84%) but took the rules' pressure with it (-82 a minute); here the rules keep W for the rest of the lift.
	 */
	static final boolean RULES_LET_GO_AT_CRIT = false; // on 20:37-21:06: not sprinting 49% -> 67%, punished flat (26%); the full fights -28..-37 a minute (before +10), combos got out of 49% -> 40%: off
	static final float LET_GO_AT_CRIT_REACH = 3.0f;
	static final float LET_GO_AT_CRIT_CHARGE = 0.85f;
	static final boolean RULES_LET_GO_IN_LIFT = false; // runs 12-16b (25 Oct 17:28 - 26 Oct 00:20); off in run 17: the combo escape eroded (their third hit 33% -> 52%); on again 26 Oct 19:12-19:36: not sprinting in reach with the sword back 41-49% -> 84%, but the aim there fell 78% -> 57% (punished 23-28%), the full fights went from +54..-23 to -82..-86 net a minute against every kind of opponent (dealt 340 -> 290) and combos got out of 47% -> 39%: off
	/**
	 * Learning without forgetting (run 9 of EXPERIMENTS.md, SchoolLearner#anchorToBase): the copying targets come from the
	 * frozen best fighter in ANCHOR_FILE (in the school folder), its own chances on the same inputs, instead of the installed
	 * model's one key; the installed model's rules still play over the network. The user, 25 Oct 14:43: "When it started to
	 * learn skill it become dumber and loses against teacher overtime fix it".
	 */
	static final boolean ANCHOR_TO_BEST = true; // run 9 (25 Oct 15:02), anchor = checked-1025-1347 (run 6, the best)
	static final String ANCHOR_FILE = "anchor.bin";
	private static final boolean[] CRIT_FAMILY = new boolean[Technique.COUNT];

	static {
		for (Technique t : new Technique[] {Technique.B1, Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2, Technique.C6}) {
			CRIT_FAMILY[t.ordinal()] = true;
		}
	}

	/** " B1 T1 T1a ... let-go": the techniques whose teacher has a pull now (the rest of the keys copied); for the dashboard. */
	/**
	 * Scene replay (the user, 26 Oct 08:50: "just give it the 60-120 frames that it actually needs to perform the action to
	 * train ... just tp the teacher to exact location where this scene will be taken"). The moments of the training fights where
	 * an opponent crits it are kept as scenes: both fighters as they were at the scene's start (ArenaServer's SceneRecorder),
	 * with the opponent that fought it. SCENE_SHARE of the training fights then start from a kept scene, both put back exactly,
	 * against the same opponent, for SCENE_WINDOW ticks: the lost moments fought again and again, without the walking in
	 * around them. Kept in SCENE_FILE (the newest SCENES_KEPT).
	 */
	static final float SCENE_SHARE = 0.3f; // run 47 (the user, 7 Nov 10:25: "about 30% of time", the rest normal training); was 0.6
	/**
	 * Run 47b (11:50): the user's 30% is of the TIME. A replay is about 2 s and a full fight about 15 s, so 30% of the fights was
	 * about 5% of the time: ~75 replays an hour for ~580 queued, each back about once in 8 hours, never won 3 times in a row.
	 * Now the chance of a replay is set each fight from the measured lengths (sceneTicksAvg, fullTicksAvg) so that replays take
	 * SCENE_TIME_SHARE of the training ticks (bounded by SCENE_CHANCE_MIN/MAX), and each kind replays only its SCENE_WORKING_SET
	 * oldest queued failures, so the same moments come back until they are learned and the next ones move in.
	 */
	/**
	 * Run 48 (the user, 7 Nov 14:35: "just make it replay that part over without letting it even touch other episode until it
	 * finally learn ... and punish any action ... until the action it should do is there ... while not forgetting other things"):
	 * one queued failure at a time (repeatScene: the oldest of the next kind in REPEAT_ORDER) is fought again back to back,
	 * REPEAT_SHARE of the training fights, until it is won SCENE_MASTERED times in a row (learned, put away) or REPEAT_MAX tries
	 * (logged as not learned: then the setup, not the network, is to be looked at; to the back of the queue). In it every tick
	 * before the deed costs REPEAT_TICK_COST; the deed pays REPEAT_DONE and a clear failure costs REPEAT_FAILED, and either ends
	 * the replay at once, so the next try comes straight away. The rest: full fights (new failures still come from them),
	 * learned replays tested again (REPEAT_TEST_SHARE of them), and the old stages' reviews at their own share.
	 */
	static final boolean REPEAT_UNTIL_LEARNED = true;
	static final float REPEAT_SHARE = 0.85f;
	static final float REPEAT_TEST_SHARE = 0.3f;
	static final int REPEAT_MAX = 150;
	/**
	 * Run 48c: and not before REPEAT_MIN_MINUTES. A try is ~2 s, so 150 tries were 4-5 minutes, only 4-5 of the network's updates
	 * (one every ~70 s): the punish, punish short and own combo replays were 'not learned' after that (15:30-15:45), too few
	 * updates to say anything.
	 */
	static final double REPEAT_MIN_MINUTES = 20.0;
	/** Run 48c: a decided try still lasts this long (tries of 3-9 ticks, several resets a second, put the server 2-5 s behind). */
	static final int REPEAT_MIN_TICKS = 20;
	static final float REPEAT_TICK_COST = 0.02f, REPEAT_DONE = 1.0f, REPEAT_FAILED = 0.5f;
	private final int[] REPEAT_ORDER = {KIND_DEFLECT, KIND_PUNISH2, KIND_PUNISH, KIND_OWN2, KIND_OWN, KIND_CRIT2, KIND_JUMPIN2, KIND_COMBO, KIND_CRIT,
			KIND_JUMPIN};
	private Scene repeatScene;
	private int repeatTries, repeatNext, repeatLearned, repeatGivenUp;
	private long repeatStartMs, repeatTriesSum;
	private boolean repeatResolved, repeatEndNow, repeatLiftSeen;
	private final java.util.ArrayDeque<String> repeatHistory = new java.util.ArrayDeque<>();
	static final float SCENE_TIME_SHARE = 0.5f; // run 47c (the user, 14:25: "add more replay"); 0.3 in 47b
	static final float SCENE_CHANCE_MIN = 0.1f, SCENE_CHANCE_MAX = 0.9f;
	static final int SCENE_WORKING_SET = 4; // run 47c: each moment back twice as often; 8 in 47b
	private float sceneTicksAvg = 40f, fullTicksAvg = 280f;

	/** The chance that a training fight is a replay, so that replays take SCENE_TIME_SHARE of the training time. */
	private float sceneChance() {
		float t = SCENE_TIME_SHARE;
		float p = t * fullTicksAvg / ((1f - t) * sceneTicksAvg + t * fullTicksAvg);
		return Math.max(SCENE_CHANCE_MIN, Math.min(SCENE_CHANCE_MAX, p));
	} // run 21: 0.5 from 09:20; 0.6 from 09:40 with the other techniques and the basics reviewed in between (the user, 09:27: "The AI need to review other technique and basic during other training so it won't forget")
	static final int SCENE_WINDOW = 100;
	/**
	 * Run 21 from 09:35 (the user: "train until it ALWAYS deals more damage than crit guy"): a scene is the moment an opponent
	 * crits it, from SCENE_BEFORE ticks before the crit to SCENE_WINDOW - SCENE_BEFORE after; it is fought again until it
	 * deals more than it takes there SCENE_MASTERED times in a row, then it is put away (mastered).
	 */
	static final int SCENE_BEFORE = 60;
	static final int SCENE_MASTERED = 3;
	/** Of the full fights left (where new scenes come from), the share against the bots that crit. */
	static final float CRIT_BOT_SHARE = 0.25f; // the rest: every technique's bots (the review of the other techniques)
	static final int[] CRIT_BOTS = {Specialists.PUNISH_CRITTER, Specialists.DOUBLE_CRITTER, Specialists.CRIT_BREAKER,
			Specialists.STANDING_CRITTER, Specialists.JUMP_IN_CRITTER};
	static final int SCENES_KEPT = 400;
	/**
	 * At most this many scenes are learned at a time; a new one comes in only as one is mastered, so each comes back every few
	 * minutes until it is won (with 400 at a time each came back about every half hour, 09:45).
	 */
	static final int SCENES_ACTIVE = 60;
	/**
	 * Jump-in scenes (the user, 26 Oct 10:00: "let it learn to counter jump in crit and not sprint hit ... I want it 90%+"; the
	 * file: T8, hit them while they rise; K2, never knockback-hit a falling player; B1, let it land and punish crit). A jump-in
	 * (their take-off toward it inside 6.5 blocks) is countered when, in the JUMPIN_WINDOW ticks after it, it lands no sprint
	 * hit on them while they fall (K2) and either takes no crit (T8: hit while they rise) or answers the crit with a crit of its
	 * own (B1: "let it land, punish crit", the file's answer to a falling jumper; counted a failure until 26 Oct 12:30). Every jump-in it does not counter in a full fight is kept as a scene from
	 * JUMPIN_BEFORE ticks before the take-off, JUMPIN_SCENE_TICKS long, fought again until countered SCENE_MASTERED times in a
	 * row; JUMPIN_SCENE_SHARE of the scene fights are these. A countered jump-in pays JUMPIN_COUNTERED.
	 */
	static final int JUMPIN_WINDOW = 40; // 30 until 26 Oct 12:30: the punish crit after their crit needs the time
	static final int JUMPIN_BEFORE = 20;
	static final int JUMPIN_SCENE_TICKS = 60;
	static final float JUMPIN_SCENE_SHARE = 0.5f;
	static final float JUMPIN_COUNTERED = 0.5f;
	static final int KIND_CRIT = 0;
	static final int KIND_JUMPIN = 1;
	/**
	 * Combo scenes: their second hit in a row on it that went on to a third, from COMBO_BEFORE ticks before the second hit,
	 * JUMPIN_SCENE_TICKS long; won when no second hit in a row lands from the scene's start (the third of the first time):
	 * it got out (C3 jump and hold S, T9 jump reset).
	 */
	static final int KIND_COMBO = 2;
	/**
	 * Punish scenes (26 Oct 17:10): their hit lifted it and it came down in reach (a punish crit chance, B1) but made no crit;
	 * from PUNISH_BEFORE ticks before their hit, PUNISH_SCENE_TICKS long; won by its own crit with no K2. The user: "just guide
	 * it to ALWAYS do it"; at 17:04 of its chances in reach it had the sword back on about half and was not sprinting on about
	 * half of those, and critted on one in seven.
	 */
	static final int KIND_PUNISH = 3;
	/**
	 * Own-combo scenes (run 44, block 5, 7 Nov): its own combo that died at one or two hits (they hit back, or no next hit
	 * within COMBO_GAP), from the tick after its first hit (they are knocked back, its sword empty: the holder's moment, T10,
	 * T11a-c, T2), OWN_SCENE_TICKS long; won by two more hits of its own in a row (a 3+ combo) before a hit taken. The user,
	 * 7 Nov 07:30: "it need always like 3+ combo and nearly starting combos at anytime". Against the training bots its 2-hit
	 * combos went on to 3 as often as against real players (37% vs 38%, tools/combo-bots.py), and real players hit back
	 * sooner (3 ticks median): the bots are not too perfect, so the moment itself is practised. OWN_SHARE of the focus's
	 * scene fights are these (about a fifth of the training fights); the other kinds share the rest.
	 */
	static final int KIND_OWN = 4;
	static final int OWN_SCENE_TICKS = 50;
	static final float OWN_SHARE = 0.4f;
	/**
	 * Own combos that died at two hits (run 44, the brainstorm of 7 Nov 08:05): from the tick after its second hit, OWN2_SCENE_TICKS
	 * long, won by one more hit before a hit taken: the exact 2 -> 3 moment, about four times as many an hour as the 2 -> 3
	 * moments of the full fights. OWN2_OF_OWN of the own-combo scene fights are these.
	 */
	static final int KIND_OWN2 = 5;
	static final int OWN2_SCENE_TICKS = 30;
	static final float OWN2_OF_OWN = 0.6f;
	/** PPO's weight on the ticks within POST_HIT_TICKS after its 2nd or later hit in a row, no hit taken (run 44, SchoolPpo policyWeight). */
	static final float POST_HIT_WEIGHT = 2f;
	static final int POST_HIT_TICKS = 15;
	/**
	 * Short crit scenes (run 46c; the user, 7 Nov 09:40: put it at the exact moment "with exact velocity and stuff and dont even
	 * give a head buffer ... 1-2 second per episode ... mix both"): every crit scene kept (KIND_CRIT, 3 s before their crit, 5 s
	 * long) also keeps this twin from CRIT2_BEFORE ticks before the crit, CRIT2_SCENE_TICKS long, won the same way (more dealt than
	 * taken). The own-combo scenes start on the tick after its hit already; the punish scenes half a second before their hit.
	 */
	static final int KIND_CRIT2 = 6;
	static final int CRIT2_BEFORE = 8;
	static final int CRIT2_SCENE_TICKS = 30;
	/** Short jump-in scenes (run 46c, the same request): 2 ticks before their take-off, 2 s, won as a jump-in scene. */
	static final int KIND_JUMPIN2 = 7;
	static final int JUMPIN2_BEFORE = 2;
	static final int JUMPIN2_SCENE_TICKS = 40;
	/**
	 * Run 47 (the user, 7 Nov 10:25: every failed attempt at a skill kept as a short replay, put back EXACTLY, seen about 30% of
	 * the time until it is done every time, then put away; new failures from the other 70% join the queue). A missed deflection
	 * (T4, docs/PVP-TECHNIQUES.md: "sprint-hit the opponent *immediately after they attack you*"): their swing began with it
	 * near (reach under DEFLECT_REACH), its own sword back, they not falling (the lag rule: never a faller), it not in its own
	 * combo, their swing not landing on it (a lift is the punish crit's moment, B1) - and no sprint hit while their sword was
	 * still empty (DEFLECT_CHARGE) within DEFLECT_WINDOW ticks. Kept from the tick their swing was seen, DEFLECT_SCENE_TICKS
	 * long; won by that sprint hit before any hit taken, within the window.
	 */
	static final int KIND_DEFLECT = 8;
	/**
	 * Run 47: the punish scene's exact twin, from PUNISH2_AFTER ticks after their hit (the lift; its client has the knockback
	 * by then: on the hit's own tick the server's moment is ahead of its client), PUNISH2_SCENE_TICKS long, won as a punish scene.
	 */
	static final int KIND_PUNISH2 = 9;
	static final int KINDS = 10;
	static final String[] KIND_NAMES = {"crit", "jump-in", "combo", "punish", "own combo", "own combo 2", "crit short", "jump-in short",
			"deflection", "punish short"};
	static final int DEFLECT_WINDOW = 8;
	static final int DEFLECT_SCENE_TICKS = 30;
	static final float DEFLECT_REACH = 3.5f;
	static final int PUNISH2_AFTER = 2;
	static final int PUNISH2_SCENE_TICKS = 30;
	static final int PUNISH_BEFORE = 10;
	static final int PUNISH_SCENE_TICKS = 40;
	static final int COMBO_BEFORE = 20;
	/**
	 * One technique at a time (the user, 26 Oct 10:55: "once they learn crit ... learn other technique one by one ... it is ok
	 * to make a run be trained for all case focusingly whole day (make sure to review older technique still as well)"): of the
	 * scene fights FOCUS_SHARE go to the focus's kinds, the rest to the earlier focuses' (their review); a focus moves on by
	 * itself once its bars (FOCUS_BAR) are met over the last FOCUS_EVENTS events in the full fights and FOCUS_REPLAYS scene
	 * fights: the crits: jump-ins countered, crit scenes won; the combo escape: combos got out of, combo scenes won. A learned
	 * focus is guarded in review: once its measures (counted again from its learning) fall under the bar it is the focus again
	 * (RETRAIN; the user, 11:00: "if any older technique falls below 90% investigate plus retrain to make it always 90% plus").
	 * docs/TRAINING-SOP.md. Kept in FOCUS_FILE: the focus and the furthest reached.
	 */
	static final float FOCUS_BAR = 0.9f;
	/**
	 * Run 35 (3 Nov): the combo scenes are fought with the crits' as one of the first focus's kinds (a quarter of its scene
	 * fights), its bars still the crits'. With the crits short of their bars for a week (punish crit told and landed 27%) the
	 * combo scenes came only in the fifth of scene fights left over, about one in twenty; the user, 2 Nov: it is "bad at upper
	 * cutting comboing opponent that controls hit perfectly", and need not rely on the "unstable punish crit". Combo scenes
	 * won 39% of the last 200; against the uppercutter A's net was below zero in the last four hourly checks.
	 */
	static final int[][] FOCUS_KINDS = {{KIND_CRIT, KIND_CRIT2, KIND_JUMPIN, KIND_JUMPIN2, KIND_PUNISH, KIND_PUNISH2, KIND_DEFLECT, KIND_COMBO,
			KIND_OWN, KIND_OWN2}, {KIND_COMBO, KIND_OWN, KIND_OWN2, KIND_DEFLECT, KIND_PUNISH, KIND_PUNISH2}};
	static final String[] FOCUS_NAMES = {"the crits (crit scenes, jump-in counter, punish crit)", "the combo escape"};
	static final float FOCUS_SHARE = 0.8f;
	static final int FOCUS_EVENTS = 300;
	static final int FOCUS_REPLAYS = 200;
	static final String FOCUS_FILE = "focus.txt";
	static final String SCENE_FILE = "scenes.txt";
	/**
	 * Mastered scenes reviewed in between (the user, 26 Oct 09:25: "review in between"): this share of the scene fights fight a
	 * mastered scene again; one lost goes back to be learned. The basics keep their own review (REVIEW_SHARE).
	 */
	static final float SCENE_REVIEW_SHARE = 0.15f;
	static final String MASTERED_FILE = "scenes-mastered.txt";

	private static final class Scene {
		int bot;
		long styleSeed;
		boolean varied;
		boolean spar;
		int agentLag;
		int botLag;
		float net;
		int wins;
		int replays;
		int kind;
		double[] state;

		int ticks() {
			return kind == KIND_CRIT ? SCENE_WINDOW : kind == KIND_PUNISH ? PUNISH_SCENE_TICKS : kind == KIND_OWN ? OWN_SCENE_TICKS : kind == KIND_OWN2 ? OWN2_SCENE_TICKS : kind == KIND_CRIT2 ? CRIT2_SCENE_TICKS : kind == KIND_JUMPIN2 ? JUMPIN2_SCENE_TICKS : kind == KIND_DEFLECT ? DEFLECT_SCENE_TICKS : kind == KIND_PUNISH2 ? PUNISH2_SCENE_TICKS : JUMPIN_SCENE_TICKS;
		}

		String line() {
			StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%d %d %d %d %d %d %.1f %d %d %d", bot, styleSeed, varied ? 1 : 0,
					spar ? 1 : 0, agentLag, botLag, net, wins, replays, kind));
			for (double v : state) {
				b.append(' ').append(v);
			}
			return b.toString();
		}

		static Scene parse(String line) {
			String[] f = line.trim().split(" ");
			Scene sc = new Scene();
			sc.bot = Integer.parseInt(f[0]);
			sc.styleSeed = Long.parseLong(f[1]);
			sc.varied = f[2].equals("1");
			sc.spar = f[3].equals("1");
			sc.agentLag = Integer.parseInt(f[4]);
			sc.botLag = Integer.parseInt(f[5]);
			sc.net = Float.parseFloat(f[6]);
			if (f.length == 7 + 28) {
				// The lost windows of 26 Oct 09:20-09:40, before the scenes were the crits: not kept.
				return null;
			}
			sc.wins = Integer.parseInt(f[7]);
			sc.replays = Integer.parseInt(f[8]);
			// Crit scenes kept 09:40-10:20 had no kind.
			int at = f.length == 9 + 28 ? 9 : 10;
			sc.kind = at == 10 ? Integer.parseInt(f[9]) : KIND_CRIT;
			sc.state = new double[f.length - at];
			for (int i = 0; i < sc.state.length; i++) {
				sc.state[i] = Double.parseDouble(f[at + i]);
			}
			return sc;
		}
	}

	private final List<Scene> scenes = new ArrayList<>();
	private final List<Scene> mastered = new ArrayList<>();
	/** The scene this fight starts from, or null; and whether it is a mastered one reviewed. */
	private Scene sceneNow;
	private boolean sceneReview;
	private int sceneReviews;
	private int sceneForgot;
	/**
	 * The replay queue for the panel (the user, 7 Nov 10:50: how many replays are queued, how many replayed, how many it learned,
	 * its success rate, and the learned ones tested again at random and put back when failed). Per kind, since REPLAY_FILE began
	 * (kept across restarts): replayed, won, learned (won SCENE_MASTERED in a row, put away), learned ones tested again, of those
	 * failed and put back in the queue.
	 */
	static final String REPLAY_FILE = "replay-queue.json";
	private final long[][] replayTotals = new long[KINDS][5];
	private final long[] histTick = new long[256];
	private final float[] histNet = new float[256];
	private long fightStyleSeed;
	private boolean fightVaried;
	private int sparBotLag;
	private int sceneFights;
	private int scenesHarvested;
	private float sceneNet;
	private float sceneNetBefore;
	private int sceneWins;
	/** Scene fights in which the decider chose (not told the teacher's), and those won. */
	private int sceneDecided;
	private int sceneDecidedWon;
	private int scenesMastered;
	private int scenesMasteredAll;
	/** Each side's jump-in exchange: ticks since their take-off (-1: none), a crit taken, a sprint hit on them falling. */
	private final int[] exAt = {-1, -1};
	private final boolean[] exCrit = new boolean[2];
	private final boolean[] exK2 = new boolean[2];
	private final boolean[] exPunish = new boolean[2];
	/** Its own crit anywhere in the exchange (before or after theirs). */
	private final boolean[] exOwnCrit = new boolean[2];
	/** Their hit landed on it in the exchange; its own crit after that hit (the punish crit). */
	private final boolean[] exHit = new boolean[2];
	private final boolean[] exAnswer = new boolean[2];
	private final int[] exFrom = new int[2];
	/** A jump-in not countered in this fight, to keep as a scene at keepJumpInAt: from fight tick jumpInFrom. */
	private int jumpInFrom = -1;
	private int keepJumpInAt;
	/** In a scene fight: a crit taken, a sprint hit on them falling. */
	private boolean sceneCrit;
	private boolean sceneK2;
	private boolean scenePunish;
	private boolean sceneOwnCrit;
	private boolean sceneHit;
	private boolean sceneAnswer;
	private int focus;
	private int focusReached;
	/** Rolling outcomes for the focus bars: per kind, the last scene fights won; the full fights' jump-ins and combos. */
	private final Rolling[] sceneRoll = new Rolling[KINDS];
	{
		for (int k = 0; k < KINDS; k++) {
			sceneRoll[k] = new Rolling(FOCUS_REPLAYS);
		}
	}
	private int ownKind = KIND_OWN;
	private int ownSecondAt;
	/** Own combos (run 44): the first one of this full fight that died short, to keep from ownFrom at keepOwnAt (-1: none). */
	private int ownFrom = -1;
	private int keepOwnAt;
	private int ownRow;
	private int ownFirstAt;
	/** In an own-combo scene: its hits in a row since the start, and whether a hit was taken before the second. */
	private int sceneOwnHits;
	private boolean sceneOwnBroken;
	/** Its punish crit chances in the full fights (their hit lifted it, it came down in reach): critted or not. */
	private final Rolling punishRoll = new Rolling(FOCUS_EVENTS);
	/**
	 * The punish crit's bar (the user's method, 27 Oct): of the lifts in the full fights in which the network was told the
	 * punish crit and came down in reach, how many it critted. Told it, it must land it; when to be told is the decision
	 * model's to learn. punishRoll counts every chance whatever was chosen, mostly the jump reset's.
	 */
	private final Rolling toldB1Roll = new Rolling(FOCUS_EVENTS);
	/** A punish chance missed in this fight, to keep as a scene at keepPunishAt: from fight tick punishFrom. */
	private int punishFrom = -1;
	private int keepPunishAt;
	/** The fight tick their hit lifted it. */
	private int liftAt;
	private final Rolling jumpInRoll = new Rolling(FOCUS_EVENTS);
	/**
	 * The installed AI's jump-ins (sparring) apart: on fresh seeds its jump-ins were countered 40% while the bots' were 76%
	 * (26 Oct 12:05), and one measure over both hid it. The crits' bar needs both.
	 */
	private final Rolling jumpInSparRoll = new Rolling(FOCUS_EVENTS);
	private final Rolling comboRoll = new Rolling(FOCUS_EVENTS);
	/** Fight tick of their second hit in a row now (-1: none), and a combo scene to keep at keepComboAt from comboFrom. */
	private int secondHitAt = -1;
	private int comboFrom = -1;
	private int keepComboAt;
	/** In a scene fight: their most hits in a row from its start. */
	private int sceneRow;
	private final int[] kindFights = new int[KINDS];
	private final int[] kindWon = new int[KINDS];

	/** The last n outcomes. */
	private static final class Rolling {
		private final boolean[] ring;
		private int count;
		private int next;
		private int good;

		Rolling(int n) {
			ring = new boolean[n];
		}

		void add(boolean ok) {
			if (count == ring.length) {
				good -= ring[next] ? 1 : 0;
			} else {
				count++;
			}
			ring[next] = ok;
			good += ok ? 1 : 0;
			next = (next + 1) % ring.length;
		}

		boolean full() {
			return count == ring.length;
		}

		void clear() {
			count = 0;
			next = 0;
			good = 0;
		}

		/** Oldest first, "1" good and "0" not; "-" when empty. */
		String save() {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < count; i++) {
				b.append(ring[(next - count + i + ring.length) % ring.length] ? '1' : '0');
			}
			return b.length() == 0 ? "-" : b.toString();
		}

		void load(String v) {
			clear();
			for (int i = 0; i < v.length(); i++) {
				if (v.charAt(i) == '1' || v.charAt(i) == '0') {
					add(v.charAt(i) == '1');
				}
			}
		}

		float share() {
			return count == 0 ? 0f : good / (float) count;
		}

		String line() {
			return String.format(Locale.ROOT, "%.0f%% of %d", 100f * share(), count);
		}
	}

	private void loadFocus() {
		try {
			Path f = SwordFiles.schoolDir().resolve(FOCUS_FILE);
			if (Files.exists(f)) {
				String[] v = Files.readString(f).trim().split(" ");
				focus = Math.max(0, Math.min(FOCUS_KINDS.length - 1, Integer.parseInt(v[0])));
				focusReached = v.length > 1 ? Math.max(focus, Math.min(FOCUS_KINDS.length - 1, Integer.parseInt(v[1]))) : focus;
				// The rolling measures (a check restarts the session every 45 minutes; the bars take hours).
				Rolling[] all = rollings();
				for (int i = 0; i < all.length && 2 + i < v.length; i++) {
					all[i].load(v[2 + i]);
				}
			}
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("school: the focus could not be read: {}", e.getMessage());
		}
	}

	/** A focus's measures: the full fights' and its scenes'. */
	private Rolling[] measures(int f) {
		return f == 0 ? new Rolling[] {jumpInRoll, sceneRoll[KIND_CRIT], jumpInSparRoll, toldB1Roll, sceneRoll[KIND_PUNISH]}
				: new Rolling[] {comboRoll, sceneRoll[KIND_COMBO]};
	}

	private static boolean allFull(Rolling[] m) {
		for (Rolling r : m) {
			if (!r.full()) {
				return false;
			}
		}
		return true;
	}

	private static boolean allAbove(Rolling[] m) {
		for (Rolling r : m) {
			if (r.share() < FOCUS_BAR) {
				return false;
			}
		}
		return true;
	}

	/** Moves the focus on once its bars are met; a learned one that falls under them is retrained first. */
	private void checkFocus() {
		for (int f = 0; f < focusReached; f++) {
			if (f == focus) {
				continue;
			}
			Rolling[] m = measures(f);
			if (allFull(m) && !allAbove(m)) {
				SwordAi.LOGGER.info("school: RETRAIN {}: in review it fell under {}% ({}, {}); investigate (tools/hits-report.py, its scenes)",
						FOCUS_NAMES[f], Math.round(100 * FOCUS_BAR), m[0].line(), m[1].line());
				setFocus(f);
				return;
			}
		}
		Rolling[] m = measures(focus);
		if (!allFull(m) || !allAbove(m)) {
			return;
		}
		int next = focus < focusReached ? focusReached : focus + 1;
		if (next >= FOCUS_KINDS.length) {
			return;
		}
		SwordAi.LOGGER.info("school: {} learned ({}, {}); now {}", FOCUS_NAMES[focus], m[0].line(), m[1].line(), FOCUS_NAMES[next]);
		// Counted again from here, so that its review judges it afresh.
		for (Rolling r : m) {
			r.clear();
		}
		setFocus(next);
	}

	private void setFocus(int f) {
		focus = f;
		focusReached = Math.max(focusReached, f);
		saveFocus();
	}

	private Rolling[] rollings() {
		return new Rolling[] {jumpInRoll, comboRoll, sceneRoll[KIND_CRIT], sceneRoll[KIND_JUMPIN], sceneRoll[KIND_COMBO], jumpInSparRoll,
				punishRoll, sceneRoll[KIND_PUNISH], toldB1Roll};
	}

	private void saveFocus() {
		StringBuilder b = new StringBuilder(focus + " " + focusReached);
		for (Rolling r : rollings()) {
			b.append(' ').append(r.save());
		}
		try {
			Files.writeString(SwordFiles.schoolDir().resolve(FOCUS_FILE), b);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the focus could not be saved: {}", e.getMessage());
		}
	}

	/** Whether a scene kind belongs to a focus learned already (the furthest reached is still being learned). */
	private boolean learnedKind(int kind) {
		for (int f = 0; f < focusReached; f++) {
			for (int k : FOCUS_KINDS[f]) {
				if (k == kind) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean inFocus(int kind) {
		for (int k : FOCUS_KINDS[focus]) {
			if (k == kind) {
				return true;
			}
		}
		return false;
	}

	private int jumpInScenes;
	private int jumpInScenesWon;
	private int jumpInScenesMastered;
	private int trainJumpIns;
	private int trainJumpInsCountered;

	private int countKind(int kind) {
		int n = 0;
		for (Scene sc : scenes) {
			n += sc.kind == kind ? 1 : 0;
		}
		return n;
	}

	/** Fight ticks of the crits taken in this fight whose scene is not kept yet. */
	private final int[] critAt = new int[16];
	private int critsPending;

	/** A specialist's style from its own seed, so that a scene can bring the same opponent back. */
	private static dev.swordai.core.pvp.Style styleOf(int bot, long styleSeed, boolean varied) {
		Random r = new Random(styleSeed);
		return bot == Specialists.MIXER ? Specialists.mixer(r) : varied ? Specialists.varied(bot, r) : Specialists.draw(bot, r);
	}

	private void loadScenes() {
		retireWrongVelocityScenes();
		loadReplayTotals();
		loadScenes(SCENE_FILE, scenes);
		// Run 48d: punish twins that begin with the agent not rising had no lift to punish from: moved aside (kept).
		List<Scene> noLift = new ArrayList<>();
		for (Scene sc : scenes) {
			if (sc.kind == KIND_PUNISH2 && sc.state[4] <= 0.05) {
				noLift.add(sc);
			}
		}
		if (!noLift.isEmpty()) {
			scenes.removeAll(noLift);
			StringBuilder b = new StringBuilder();
			for (Scene sc : noLift) {
				b.append(sc.line()).append(System.lineSeparator());
			}
			try {
				Files.writeString(SwordFiles.schoolDir().resolve("scenes-punish-short-no-lift.txt"), b.toString(),
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("school: the punish twins with no lift could not be kept aside: {}", e.getMessage());
			}
			SwordAi.LOGGER.info("school: {} punish short replays without the lift moved aside", noLift.size());
		}
		loadScenes(MASTERED_FILE, mastered);
		loadFocus();
	}

	/**
	 * Run 46e (7 Nov): every scene kept before it put the agent back with the server's no-input velocity (67% of the 538 kept
	 * ones exactly 0 sideways, the bot 2%; the real median speed ~0.15 a tick), so its moment was not the one that happened.
	 * They cannot be mended (a scene keeps one tick), so they are moved aside once (kept, not deleted) and new ones refill.
	 */
	private static void retireWrongVelocityScenes() {
		Path dir = SwordFiles.schoolDir();
		Path done = dir.resolve("scenes-velocity-fix.done");
		if (Files.exists(done)) {
			return;
		}
		try {
			for (String name : new String[] {SCENE_FILE, MASTERED_FILE}) {
				Path f = dir.resolve(name);
				if (Files.exists(f)) {
					Files.move(f, dir.resolve(name + ".old-wrong-velocity"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.writeString(done, "scenes before 7 Nov run 46e moved aside: the agent's velocity was the server's, not its own\n");
			SwordAi.LOGGER.info("school: scenes kept before the velocity fix moved aside (.old-wrong-velocity); new ones refill");
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the old scenes could not be moved aside: {}", e.getMessage());
		}
	}

	private void loadReplayTotals() {
		Path file = SwordFiles.schoolDir().resolve(REPLAY_FILE);
		if (!Files.exists(file)) {
			return;
		}
		try {
			com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (o.has("repeat") && o.getAsJsonObject("repeat").has("resumeKind")) {
				repeatNext = Math.floorMod(o.getAsJsonObject("repeat").get("resumeKind").getAsInt(), REPEAT_ORDER.length);
			}
			for (com.google.gson.JsonElement e : o.getAsJsonArray("kinds")) {
				com.google.gson.JsonObject k = e.getAsJsonObject();
				int i = java.util.Arrays.asList(KIND_NAMES).indexOf(k.get("name").getAsString());
				if (i >= 0) {
					String[] keys = {"replayed", "won", "learned", "tested", "forgot"};
					for (int j = 0; j < keys.length; j++) {
						replayTotals[i][j] = k.has(keys[j]) ? k.get(keys[j]).getAsLong() : 0;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("school: the replay totals could not be read: {}", e.getMessage());
		}
	}

	/** The replay queue as the panel shows it (tools/dashboard), written at every update. */
	private void writeReplayStatus() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("time", java.time.LocalDateTime.now().withNano(0).toString());
		o.addProperty("share", SCENE_TIME_SHARE);
		o.addProperty("chance", sceneChance());
		o.addProperty("workingSet", SCENE_WORKING_SET);
		o.addProperty("replayTicks", sceneTicksAvg);
		o.addProperty("fightTicks", fullTicksAvg);
		o.addProperty("learnedAfter", SCENE_MASTERED);
		o.addProperty("testShare", SCENE_REVIEW_SHARE);
		o.addProperty("deflectChances", deflectChances);
		o.addProperty("deflectMade", deflectMade);
		o.addProperty("deflectMissed", deflectMissed);
		o.addProperty("punishRolling", punishRoll.line());
		com.google.gson.JsonArray kinds = new com.google.gson.JsonArray();
		for (int k = 0; k < KINDS; k++) {
			int learnedNow = 0;
			for (Scene sc : mastered) {
				learnedNow += sc.kind == k ? 1 : 0;
			}
			com.google.gson.JsonObject j = new com.google.gson.JsonObject();
			j.addProperty("name", KIND_NAMES[k]);
			j.addProperty("queued", countKind(k));
			j.addProperty("learnedNow", learnedNow);
			j.addProperty("replayed", replayTotals[k][0]);
			j.addProperty("won", replayTotals[k][1]);
			j.addProperty("learned", replayTotals[k][2]);
			j.addProperty("tested", replayTotals[k][3]);
			j.addProperty("forgot", replayTotals[k][4]);
			j.addProperty("recent", sceneRoll[k].line());
			kinds.add(j);
		}
		o.add("kinds", kinds);
		com.google.gson.JsonObject r = new com.google.gson.JsonObject();
		r.addProperty("now", repeatScene == null ? "" : KIND_NAMES[repeatScene.kind]);
		r.addProperty("tries", repeatTries);
		r.addProperty("minutes", repeatScene == null ? 0.0 : (System.currentTimeMillis() - repeatStartMs) / 60000.0);
		r.addProperty("learned", repeatLearned);
		r.addProperty("givenUp", repeatGivenUp);
		r.addProperty("avgTries", repeatLearned == 0 ? 0.0 : (double) repeatTriesSum / repeatLearned);
		r.addProperty("max", REPEAT_MAX);
		// Run 48e: where the turn of kinds is, so a restart (each hourly check, each benchmark) goes on with the same kind instead
		// of starting again at the deflection (the punish replays were cut off by every restart before their 20 minutes).
		r.addProperty("resumeKind", repeatScene != null ? (repeatNext - 1 + REPEAT_ORDER.length) % REPEAT_ORDER.length : repeatNext);
		com.google.gson.JsonArray hist = new com.google.gson.JsonArray();
		for (String h : repeatHistory) {
			hist.add(h);
		}
		r.add("history", hist);
		o.add("repeat", r);
		Path file = SwordFiles.schoolDir().resolve(REPLAY_FILE);
		try {
			Path tmp = file.resolveSibling(REPLAY_FILE + ".tmp");
			Files.writeString(tmp, o.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the replay queue could not be written: {}", e.getMessage());
		}
	}

	private void loadScenes(String name, List<Scene> into) {
		Path file = SwordFiles.schoolDir().resolve(name);
		if (!Files.exists(file)) {
			return;
		}
		try {
			List<String> lines = Files.readAllLines(file);
			for (String line : lines.subList(Math.max(0, lines.size() - SCENES_KEPT), lines.size())) {
				// Every kept scene comes back; SCENES_ACTIVE only holds back new ones (keepScene). Until 26 Oct 15:40 the
				// load stopped at SCENES_ACTIVE of a kind and the save after it dropped the rest: the mastered scenes lost
				// again in review (57 jump-in scenes at the 15:31 restart, after the counter became the punish crit).
				Scene sc = line.isBlank() ? null : Scene.parse(line);
				if (sc != null) {
					into.add(sc);
				}
			}
		} catch (IOException | RuntimeException e) {
			SwordAi.LOGGER.warn("school: the kept scenes in {} could not be read: {}", name, e.getMessage());
		}
	}

	/**
	 * One training tick of a full fight: a crit taken now is kept as a scene once SCENE_WINDOW - SCENE_BEFORE more ticks have
	 * gone by (or the fight ends): from SCENE_BEFORE ticks before it, with what it dealt and took over the window.
	 */
	private void harvest(long tick, float net, boolean critTaken, boolean fightOver) {
		int k = fightTicks % histTick.length;
		histTick[k] = tick;
		histNet[k] = net;
		if (critTaken && critsPending < critAt.length && (critsPending == 0 || fightTicks - critAt[critsPending - 1] >= 20)) {
			critAt[critsPending++] = fightTicks;
		}
		while (critsPending > 0 && (fightOver || fightTicks >= critAt[0] + SCENE_WINDOW - SCENE_BEFORE)) {
			int start = Math.max(1, critAt[0] - SCENE_BEFORE + 1);
			int shortStart = Math.max(1, critAt[0] - CRIT2_BEFORE);
			System.arraycopy(critAt, 1, critAt, 0, --critsPending);
			keepScene(start, KIND_CRIT);
			// Its short twin (run 46c), the same moment with no head room.
			keepScene(shortStart, KIND_CRIT2);
		}
		if (jumpInFrom >= 0 && (fightOver || fightTicks >= keepJumpInAt)) {
			keepScene(Math.max(1, jumpInFrom - JUMPIN_BEFORE), KIND_JUMPIN);
			// Its short twin (run 46c): from just before their take-off.
			keepScene(Math.max(1, jumpInFrom - JUMPIN2_BEFORE), KIND_JUMPIN2);
			jumpInFrom = -1;
		}
		if (comboFrom >= 0 && (fightOver || fightTicks >= keepComboAt)) {
			keepScene(Math.max(1, comboFrom - COMBO_BEFORE), KIND_COMBO);
			comboFrom = -1;
		}
		if (punishFrom >= 0 && (fightOver || fightTicks >= keepPunishAt)) {
			keepScene(Math.max(1, punishFrom - PUNISH_BEFORE), KIND_PUNISH);
			// Its exact twin (run 47): from just after their hit, the lift already on. Run 48d: from the first tick its recorded
			// motion is really rising (the client gets the knockback a tick or more after the server's hit: 43% of the twins kept
			// at a fixed 2 ticks began standing on the ground, so their lift was not there and they could not be won).
			int liftFrom = liftStartAfter(punishFrom);
			if (liftFrom > 0 && liftFrom < fightTicks) {
				keepScene(liftFrom, KIND_PUNISH2);
			}
			punishFrom = -1;
		}
		if (deflectFrom >= 0 && (fightOver || fightTicks >= keepDeflectAt)) {
			if (deflectFrom < fightTicks) {
				keepScene(Math.max(1, deflectFrom), KIND_DEFLECT);
			}
			deflectFrom = -1;
		}
		if (ownFrom >= 0 && (fightOver || fightTicks >= keepOwnAt)) {
			keepScene(Math.max(1, ownFrom), ownKind);
			ownFrom = -2; // one a fight
		}
	}

	/**
	 * Its own combo row in a full training fight (run 44): a combo that ends at one or two hits marks an own-combo scene from
	 * the tick after its first hit. Called with this tick's hits, before harvest.
	 */
	/**
	 * Run 47: deflection chances in training fights (see KIND_DEFLECT), and the deflection scenes' result. Called each training
	 * tick before this tick's hit becomes its last hit (lastOwnHitAt).
	 */
	private void trackDeflection(Truth truth) {
		float botCharge = truth.botCharge();
		boolean theirSwing = lastBotChargeSeen >= 0.9f && botCharge < 0.2f;
		lastBotChargeSeen = botCharge;
		boolean deflectHit = facts.hitsDealt > 0 && facts.sprintHit && botCharge < DEFLECT_CHARGE;
		if (deflectChanceAt >= 0) {
			int since = fightTicks - deflectChanceAt;
			if (facts.hitsTaken > 0 && facts.hitsDealt == 0 && since <= 2) {
				// Their swing landed: a lift, the punish crit's moment (B1), not a deflection.
				deflectChanceAt = -1;
			} else if (deflectHit) {
				deflectMade++;
				deflectChanceAt = -1;
			} else if (facts.hitsTaken > 0 || since >= DEFLECT_WINDOW) {
				deflectMissed++;
				if (sceneNow == null && review < 0 && !drillFight && !ruleOff && deflectFrom < 0) {
					deflectFrom = deflectChanceAt;
					keepDeflectAt = deflectChanceAt + DEFLECT_SCENE_TICKS;
				}
				deflectChanceAt = -1;
			}
		}
		if (theirSwing && deflectChanceAt < 0 && sceneNow == null && facts.hitsTaken == 0 && facts.reachDist <= DEFLECT_REACH
				&& mc.player.getAttackStrengthScale(0f) >= 0.9f && !truth.botFalling() && !reader.duel.them.falling() && fightTicks - lastOwnHitAt > COMBO_GAP
				&& liftedFor < 0) {
			deflectChanceAt = fightTicks;
			deflectChances++;
		}
		if (sceneNow != null && sceneNow.kind == KIND_DEFLECT && !sceneDeflectOver) {
			if (deflectHit && facts.hitsTaken == 0) {
				sceneDeflected = true;
				sceneDeflectOver = true;
			} else if (facts.hitsTaken > 0 || fightTicks >= DEFLECT_WINDOW) {
				sceneDeflectOver = true;
			}
		}
	}

	/** Run 48: true when this fight is to be the repeated replay (one is chosen if none is). */
	private boolean repeatPick(boolean drill) {
		if (!REPEAT_UNTIL_LEARNED || mode != Mode.TRAIN || drill) {
			return false;
		}
		if (repeatScene == null || !scenes.contains(repeatScene)) {
			repeatScene = null;
			for (int i = 0; i < REPEAT_ORDER.length && repeatScene == null; i++) {
				int k = REPEAT_ORDER[(repeatNext + i) % REPEAT_ORDER.length];
				for (Scene sc : scenes) {
					if (sc.kind == k) {
						repeatScene = sc;
						repeatNext = (repeatNext + i + 1) % REPEAT_ORDER.length;
						break;
					}
				}
			}
			if (repeatScene != null) {
				repeatTries = 0;
				repeatStartMs = System.currentTimeMillis();
				SwordAi.LOGGER.info("school repeat: now a {} replay, again and again until won {} times in a row", KIND_NAMES[repeatScene.kind],
						SCENE_MASTERED);
			}
		}
		return repeatScene != null && rng.nextFloat() < REPEAT_SHARE;
	}

	private boolean repeatActive() {
		return REPEAT_UNTIL_LEARNED && repeatScene != null;
	}

	/** Run 48: +1 the deed done, -1 clearly failed, 0 not yet, for the kinds with a clear deed (the rest are judged at the end). */
	private int repeatOutcome() {
		switch (sceneNow.kind) {
			case KIND_DEFLECT:
				return sceneDeflectOver ? (sceneDeflected ? 1 : -1) : 0;
			case KIND_PUNISH:
			case KIND_PUNISH2:
				if (sceneK2) {
					return -1;
				}
				if (sceneOwnCrit) {
					return 1;
				}
				repeatLiftSeen |= liftedFor >= 0;
				return repeatLiftSeen && liftedFor < 0 ? -1 : 0;
			case KIND_OWN:
				return sceneOwnBroken ? -1 : sceneOwnHits >= 2 ? 1 : 0;
			case KIND_OWN2:
				return sceneOwnBroken ? -1 : sceneOwnHits >= 1 ? 1 : 0;
			default:
				return 0;
		}
	}

	/** Run 48: after a try of the repeated replay: learned (put away), or given up after REPEAT_MAX tries. */
	private void repeatAfter() {
		repeatTries++;
		double minutes = (System.currentTimeMillis() - repeatStartMs) / 60000.0;
		String line = null;
		if (!scenes.contains(repeatScene)) {
			repeatLearned++;
			repeatTriesSum += repeatTries;
			line = String.format(Locale.ROOT, "%s replay learned after %d tries, %.1f min", KIND_NAMES[repeatScene.kind], repeatTries, minutes);
			repeatScene = null;
		} else if (repeatTries >= REPEAT_MAX && minutes >= REPEAT_MIN_MINUTES) {
			scenes.remove(repeatScene);
			scenes.add(repeatScene);
			repeatGivenUp++;
			line = String.format(Locale.ROOT, "%s replay NOT learned after %d tries, %.1f min (to the back of the queue)", KIND_NAMES[repeatScene.kind],
					repeatTries, minutes);
			repeatScene = null;
		}
		if (line != null) {
			SwordAi.LOGGER.info("school repeat: " + line);
			repeatHistory.addFirst(java.time.LocalTime.now().withNano(0) + " " + line);
			while (repeatHistory.size() > 12) {
				repeatHistory.removeLast();
			}
		}
	}

	/** Run 48d: the first fight tick 1-5 after {@code hit} with the agent's recorded motion rising (in the lift), or -1. */
	private int liftStartAfter(int hit) {
		for (int t = hit + 1; t <= hit + 5 && t < fightTicks; t++) {
			double[] st = ArenaServer.sceneAt(histTick[t % histTick.length]);
			if (st != null && st[4] > 0.05) {
				return t;
			}
		}
		return -1;
	}

	private boolean punishScene() {
		return sceneNow != null && (sceneNow.kind == KIND_PUNISH || sceneNow.kind == KIND_PUNISH2);
	}

	private void trackOwnCombo(boolean dealt, boolean taken) {
		boolean ends = taken || dealt && fightTicks - lastOwnHitAt > COMBO_GAP || !dealt && ownRow > 0 && fightTicks - lastOwnHitAt > COMBO_GAP;
		if (ends && ownRow >= 1 && ownRow <= 2 && ownFrom == -1 && ownFirstAt + 1 < fightTicks) {
			// Died at two hits: the 2 -> 3 moment from the tick after the second (KIND_OWN2); at one: from after the first.
			ownKind = ownRow == 2 ? KIND_OWN2 : KIND_OWN;
			ownFrom = (ownRow == 2 ? ownSecondAt : ownFirstAt) + 1;
			keepOwnAt = ownFrom + (ownRow == 2 ? OWN2_SCENE_TICKS : OWN_SCENE_TICKS);
		}
		if (ends) {
			ownRow = 0;
		}
		if (dealt && !taken) {
			if (ownRow == 0) {
				ownFirstAt = fightTicks;
			}
			ownRow++;
			if (ownRow == 2) {
				ownSecondAt = fightTicks;
			}
		}
	}

	/** Keeps the scene from fight tick {@code start} to now. */
	private void keepScene(int start, int kind) {
		// A league fight's scene would be fought again against the installed model, a different opponent from its start on.
		if (rivalNow > 0 || fightTicks - start >= histTick.length - 1 || countKind(kind) >= SCENES_ACTIVE) {
			return;
		}
		float sum = 0f;
		for (int t = start; t <= fightTicks; t++) {
			sum += histNet[t % histTick.length];
		}
		double[] state = ArenaServer.sceneAt(histTick[start % histTick.length]);
		if (state == null) {
			return;
		}
		Scene sc = new Scene();
		sc.bot = fightBot;
		sc.styleSeed = fightStyleSeed;
		sc.varied = fightVaried;
		sc.spar = sparFight;
		sc.agentLag = fightLag;
		sc.botLag = sparBotLag;
		sc.net = sum;
		sc.kind = kind;
		sc.state = state;
		scenes.add(sc);
		scenesHarvested++;
		try {
			Files.writeString(SwordFiles.schoolDir().resolve(SCENE_FILE), sc.line() + System.lineSeparator(),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: a scene could not be kept: {}", e.getMessage());
		}
	}

	/** The crit scenes since the last update: kept, fought again, won, mastered, against how they went the first time. */
	private String sceneLine() {
		String line = String.format(Locale.ROOT, " crit scenes kept %d (%d new), fought again %d%s, mastered %d (%d in all)", scenes.size(),
				scenesHarvested, sceneFights, sceneFights == 0 ? "" : String.format(Locale.ROOT, ", won %d (%.0f%%): net %.1f a scene (the first time %.1f)",
						sceneWins, 100f * sceneWins / sceneFights, sceneNet / sceneFights, sceneNetBefore / sceneFights),
				scenesMastered, scenesMasteredAll) + String.format(Locale.ROOT, "; mastered ones reviewed %d, lost again %d (%d mastered kept)",
				sceneReviews, sceneForgot, mastered.size())
				+ String.format(Locale.ROOT, "; the decider chose in %d of them, won %d (%.0f%%), told the teacher's won %d of %d", sceneDecided,
						sceneDecidedWon, sceneDecided == 0 ? 0f : 100f * sceneDecidedWon / sceneDecided, sceneWins - sceneDecidedWon,
						sceneFights - sceneDecided)
				+ String.format(Locale.ROOT, "; jump-in scenes kept %d, fought again %d, countered %d (%.0f%%), mastered %d; jump-ins countered in the full fights %d of %d (%.0f%%)",
						countKind(KIND_JUMPIN), jumpInScenes, jumpInScenesWon, jumpInScenes == 0 ? 0f : 100f * jumpInScenesWon / jumpInScenes,
						jumpInScenesMastered, trainJumpInsCountered, trainJumpIns, trainJumpIns == 0 ? 0f : 100f * trainJumpInsCountered / trainJumpIns);
		writeReplayStatus();
		StringBuilder kindsLine = new StringBuilder();
		for (int k = 0; k < KINDS; k++) {
			kindsLine.append(String.format(Locale.ROOT, "; %s scenes kept %d, won %d of %d (last %s)", KIND_NAMES[k], countKind(k), kindWon[k],
					kindFights[k], sceneRoll[k].line()));
			kindFights[k] = 0;
			kindWon[k] = 0;
		}
		line += String.format(Locale.ROOT, " | focus: %s (furthest %s); jump-ins countered in the full fights, the bots' last %s, the installed AI's last %s; punish crit chances critted (whatever was chosen), last %s; told the punish crit and landed it, last %s (bar %d%%); their combos got out of, last %s%s",
				FOCUS_NAMES[focus], FOCUS_NAMES[focusReached], jumpInRoll.line(), jumpInSparRoll.line(), punishRoll.line(), toldB1Roll.line(),
				Math.round(100 * FOCUS_BAR), comboRoll.line(), kindsLine);
		jumpInScenes = 0;
		jumpInScenesWon = 0;
		jumpInScenesMastered = 0;
		trainJumpIns = 0;
		trainJumpInsCountered = 0;
		sceneReviews = 0;
		sceneForgot = 0;
		scenesHarvested = 0;
		sceneFights = 0;
		sceneWins = 0;
		sceneDecided = 0;
		sceneDecidedWon = 0;
		sceneNet = 0f;
		sceneNetBefore = 0f;
		scenesMastered = 0;
		saveScenes();
		saveFocus();
		return line;
	}

	private void saveScenes() {
		saveScenes(SCENE_FILE, scenes);
		saveScenes(MASTERED_FILE, mastered);
	}

	private static void saveScenes(String name, List<Scene> list) {
		StringBuilder b = new StringBuilder();
		for (Scene sc : list) {
			b.append(sc.line()).append(System.lineSeparator());
		}
		try {
			Files.writeString(SwordFiles.schoolDir().resolve(name), b);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the kept scenes could not be saved in {}: {}", name, e.getMessage());
		}
	}

	/**
	 * The share of the full fights played without the rules (run 30): RULE_OFF_MIN from the start, rising to RULE_OFF_MAX as the
	 * smoothed cross-entropy to the rules' keys falls from RULE_OFF_CE_START to RULE_OFF_CE_FULL.
	 */
	private float ruleOffShare() {
		if (ruledCeEma < 0f) {
			return RULE_OFF_MIN;
		}
		float f = (RULE_OFF_CE_START - ruledCeEma) / (RULE_OFF_CE_START - RULE_OFF_CE_FULL);
		return RULE_OFF_MIN + (RULE_OFF_MAX - RULE_OFF_MIN) * Math.max(0f, Math.min(1f, f));
	}

	/** The take-over since the last update: the rules' keys, and the fights played without the rules (their damage kept apart from the rest). */
	private String takeOverLine() {
		String fights = ruleOffFights == 0 ? "no fights without the rules"
				: String.format(Locale.ROOT, "%d fights without the rules, dealt %.0f taken %.0f a minute (net %+.0f), knockouts %d-%d", ruleOffFights,
						ruleOffDealt * 1200f / Math.max(1, ruleOffTicks), ruleOffTaken * 1200f / Math.max(1, ruleOffTicks),
						(ruleOffDealt - ruleOffTaken) * 1200f / Math.max(1, ruleOffTicks), ruleOffWon, ruleOffLost);
		String swings = ruleOffSwings[0] == 0 ? "" : String.format(Locale.ROOT, ", swings %d, %.0f%% on them, their combos' third hit %d of %d", ruleOffSwings[0],
				100f * ruleOffSwings[1] / ruleOffSwings[0], ruleOffSwings[4], ruleOffSwings[3]);
		java.util.Arrays.fill(ruleOffSwings, 0L);
		String line = String.format(Locale.ROOT, " rules' keys ce %.3f (smoothed), agree %.0f%%, pull %.2f; share of full fights without the rules %.0f%%; %s%s; %d such fights in all",
				ruledCeEma, 100f * ruledAgreeEma, ruledPullNow, 100f * ruleOffShare(), fights, swings, ruleOffFightsAll);
		ruleOffFights = 0;
		ruleOffDealt = 0f;
		ruleOffTaken = 0f;
		ruleOffTicks = 0;
		ruleOffWon = 0;
		ruleOffLost = 0;
		return line;
	}

	/**
	 * A fight broken off before its end, with its opponent (run 36b: of the sparring fights after the league came in, 27% were
	 * logged as the league's though half were drawn so; a fight broken off is not logged, so a league that stalls the game
	 * would show as just that).
	 */
	private void logCut(String why) {
		if (fightTicks > 0) {
			SwordAi.LOGGER.info("school fight cut: {} after {} ticks, against {}", why, fightTicks,
					sparFight ? sparName() : review >= 0 ? "a review" : Specialists.NAMES[fightBot]);
		}
	}

	/** This sparring fight's opponent as the log names it: the installed model, or league-NAME. */
	private String sparName() {
		return rivalNow > 0 && rivalNow <= leagueNames.size() ? "league-" + leagueNames.get(rivalNow - 1) + " (sparring)" : "installed-model (sparring)";
	}

	/** The sparring fights against the installed model since the last update: how many, won-lost, net damage a fight. */
	private String sparLine() {
		if (sparring == null) {
			return " off | scenes:" + sceneLine();
		}
		String line = sparFights == 0 ? " none yet" : String.format(Locale.ROOT, " %d fights, knockouts %d-%d, dealt %.0f taken %.0f, net %.1f a fight",
				sparFights, sparWon, sparLost, sparDealt, sparTaken, (sparDealt - sparTaken) / sparFights);
		sparFights = 0;
		sparWon = 0;
		sparLost = 0;
		sparDealt = 0f;
		sparTaken = 0f;
		return line + " | scenes:" + sceneLine();
	}

	private String taughtNowLine() {
		float[] p = stages.techPulls();
		StringBuilder b = new StringBuilder();
		for (int t = 0; t < p.length; t++) {
			if (p[t] > 0f) {
				b.append(' ').append(Technique.ALL[t].id);
			}
		}
		if (LET_GO_PULL > 0f) {
			b.append(" let-go");
		}
		return b.length() == 0 ? " none" : b.toString();
	}

	private static boolean pulled(float[] pulls, int technique) {
		return !NO_PULL_YIELDS || technique >= 0 && technique < pulls.length && pulls[technique] > 0f;
	}

	/**
	 * The golden rule (K2) as a rule: no sprint hit while the opponent is falling by the time the hit gets there (seen lag and
	 * a tick for the click). The user, 25 Oct 08:50: "The falling player who jump in crit on this AI cases should be the
	 * priority to learn to counter it and requires a 99% accuracy". The file: "Don't KB-hit them (K2); let it land, punish
	 * crit (B1)". Taught it broke 26-52 times a check, the installed model 24-31; taught harder it stopped swinging (08:22).
	 */
	// Off from 09:35: in every check of the night, holding the swing on falling jumpers lost to the jump-in critters (fight-jumpin
	// -26 to -49 with 5-9 such hits a check, -9 to +7 with 29-33; the installed model makes 20-36 and takes 2-8 crits within ten
	// ticks of them). The user, 09:25: the guard made it forget "sprint hit on jump critter", combos and deflections.
	static final boolean K2_GUARD = false;
	/**
	 * Ticks past the seen lag the golden rule looks ahead: the click's tick, and the game's glide of another player toward
	 * where it was told it is (its height, and so its fall, shown late). At one, 5 of 145 jump-ins were still knockback-hit
	 * falling (25 Oct 09:11 check).
	 */
	static final int K2_MARGIN = 2;
	/**
	 * Run 39 (5 Nov 22:50), the golden rule (K2) as the user set it: a jump-in is knocked away only early in its rise, never
	 * one falling, with a buffer for the lag. Their 198 duels by the tick of the AI's swing after the take-off it saw: up to
	 * tick 1, 0 crits taken in 66 jumps; tick 2, 1 in 44; tick 3 on, 0.24 to 0.43 a jump and more health lost than with no
	 * swing at all (the swing shows as a hit 2 ticks later, their knockback later still). So the swing is let through up to
	 * JUMP_IN_SWING_TICKS, the next tick is the buffer, and later it is held until they land; never in its own combo
	 * (SchoolFighter.K2_COMBO_TICKS) nor once their hit has landed (the punish crit, B1). The same in the user's fights
	 * (FightSession) and here, so that what it practises is what it plays. The swing held is the rule's, not taught.
	 */
	static final boolean JUMP_IN_GUARD = true;
	/** The same rule in training (run 39); its own switch so that training can go back without the user's fights losing it. */
	static final boolean JUMP_IN_GUARD_TRAINING = true;
	static final int JUMP_IN_SWING_TICKS = 1;
	/**
	 * Run 40's hold (6 Nov 06:20): through their hit and in its own combo, only a crit swing let through (critSwingNow).
	 * Reverted 7 Nov 00:10: over three checks its next hit after a crit taken was a crit 51% (95 of 186; B 49%, run 39 46%),
	 * under the block's 55%, and A-B -0.4 against run 39's +0.6. Off: run 39's hold (let go once their hit lands, never in
	 * its own combo), the user's rule of 5 Nov.
	 */
	static final boolean JUMP_IN_HOLD_THROUGH_HIT = false;

	/**
	 * Run 40 (6 Nov 06:45): the hold lasts until they land, through their hit too, and in its own combo: the user asked for the
	 * punish crit on nearly every jump-in. Run 39's nine checks (hits.csv): after a crit taken its next hit was a crit 46%; of the
	 * rest 37% came after it had landed and 13% while it still rose, and in 68-76% of those it had swung within 4 ticks before
	 * their crit, so its sword was not back for the lift's fall. Of 354 sprint hits on them falling outside its combo they hit back
	 * 68%, crit 28%; 391 of all such hits came right after their hit (the hold had let go there). The document: a jumper falling
	 * and critting is not knockback-hit (K2), it is let land and punished with a crit (B1). So the only swing let through is one
	 * that will be a crit: falling, not sprinting, the sword back (critSwingNow).
	 */
	static boolean critSwingNow(net.minecraft.world.entity.player.Player me) {
		return !me.onGround() && me.getDeltaMovement().y < 0 && !me.isSprinting() && me.getAttackStrengthScale(0f) >= 0.9f;
	}

	static {
		for (Technique t : new Technique[] {Technique.B1, Technique.T1, Technique.T1A, Technique.T1B, Technique.T1C, Technique.T2,
				Technique.C6, Technique.K3}) {
			CRIT_WANTED[t.ordinal()] = true;
		}
	}
	private final SessionClock clock = new SessionClock();
	private final AimMotor motor;
	private final MixIdea idea = new MixIdea();
	private final SchoolRollout.Step step = new SchoolRollout.Step();
	private final int[] action = new int[ActionSpace.HEADS];
	private final int[] lastAction = new int[ActionSpace.HEADS];
	private final float[] oldProbs = new float[ActionSpace.TOTAL_LOGITS];
	private boolean hasLastAction;
	private boolean clicked;
	private boolean clickResolved;
	private boolean clickOnTarget;
	private float chargeAtClick;
	private boolean sprintAtClick;
	private boolean critAtClick;
	private boolean actedThisTick;
	private float turnYawThisTick;
	private float turnPitchThisTick;
	private Player currentBot;
	private int lastHurt;
	private boolean hurtNow;
	private int waitTicks;
	private int settleTicks;
	private int requestedEpisodeId;
	private long lastRunServerTick = -1;
	private int missingBotTicks;
	private int frozenTicks;
	private long resumeAtSequence;
	private float speed = 1f;
	private boolean speedApplied;

	private int fightBot;
	private Style fightStyle;
	private int fightTicks;
	private float fightDealt;
	private float fightTaken;
	private int fightTeacherTicks;
	/** Paired check: fights so far, and whether this one is the installed model's. */
	private long checkFights;
	private boolean installedPlays;
	private long checkSeed;

	// ---- the benchmark (BENCH_SUITE_FILE) ---------------------------------------------------------
	/** One episode of the suite: a bot of Specialists, or -1 for head-to-head against the league network {@code rival} (null: none there). */
	private record BenchEpisode(int bot, String rival) {
	}

	private final List<BenchEpisode> bench = new ArrayList<>();
	/** The suite's tag (its folder's name), or null: no benchmark, the check is the paired check. */
	private String benchTag;
	private String benchNetwork = "?";
	private String benchNetworkHash = "?";
	private Path benchDir;
	private String benchStarted;
	/** The episode playing now (-1 outside a benchmark); the next to play; the last one begun and how often it was begun. */
	private int benchNow = -1;
	private int benchNext;
	private int benchBegun = -1;
	private int benchTries;
	/** Episodes written in this run, found written already (a suite broken off and started again under its tag), left out. */
	private int benchWritten;
	private int benchHad;
	private int benchNoRival;
	private int benchBroken;
	private final RoundRecorder benchRecorder = new RoundRecorder();
	/** What the recorder reads the opponent's sword from (its arm), filled on every tick of an episode. */
	private final BodyState benchBody = new BodyState();
	/** The rules that had keys on the last decision (Fighter#rules), as a real fight's file has them; 0 without the rules. */
	private int benchRules;
	private int benchSwingsSeen;

	// ---- what is counted between updates ----------------------------------------------------------
	private final float[] botDealt = new float[Specialists.KINDS];
	private final float[] botTaken = new float[Specialists.KINDS];
	private final int[] botTicks = new int[Specialists.KINDS];
	private final int[] botFights = new int[Specialists.KINDS];
	private final int[] botWins = new int[Specialists.KINDS];
	private final int[] botLosses = new int[Specialists.KINDS];
	/** Per technique: ticks its teacher spoke, and ticks the network's likeliest keys were the teacher's on all of them. */
	private final int[] spoke = new int[Technique.COUNT];
	private final int[] agreed = new int[Technique.COUNT];
	/** Per technique: how often the bots went into it. */
	private final int[] botUsed = new int[Technique.COUNT];
	private int lastBotTechnique = -1;
	private long stepsAtLastUpdate;
	private long millisAtLastUpdate = System.currentTimeMillis();
	private long lastKeptMillis = System.currentTimeMillis();
	private String lastUpdateLine = "no update yet";
	private int stalledCuts;

	/**
	 * In the check, side A is the teacher itself instead of the school's network: its keys pressed as they are, the
	 * installed model's camera. Whether the teacher wins in the game at all, as the toy fight cannot say (24 Oct).
	 */
	private final boolean teacherPlaysA;
	/** And outside the technique moments the installed model plays, its keys as well: the file's techniques laid over it. */
	private final boolean overlay;
	/** The teacher's techniques in its checks, when not every family reached so far. */
	private Technique[] checkTaught;

	SchoolSession(Minecraft mc, Mode mode) throws IOException {
		this(mc, mode, false, false);
	}

	SchoolSession(Minecraft mc, Mode mode, boolean teacherPlaysA, boolean overlay) throws IOException {
		this.mc = mc;
		this.mode = mode;
		this.teacherPlaysA = teacherPlaysA && mode == Mode.CHECK;
		this.overlay = overlay && this.teacherPlaysA;
		// For the teacher's checks: SWORDAI_CHECK_FAMILIES (say "0" or "0,1") picks the families it uses, to see what each costs.
		String families = this.teacherPlaysA ? System.getenv("SWORDAI_CHECK_FAMILIES") : null;
		if (families != null && !families.isBlank()) {
			java.util.List<Technique> t = new java.util.ArrayList<>();
			for (String f : families.split(",")) {
				java.util.Collections.addAll(t, SchoolStages.STAGES[Integer.parseInt(f.trim())].techniques);
			}
			checkTaught = t.toArray(new Technique[0]);
		}
		this.cfg = SwordFiles.loadConfig();
		this.motor = new AimMotor(cfg.aimResponse);
		this.perf = new Performance(mode == Mode.TRAIN ? stages::moment : null);
		Files.createDirectories(SwordFiles.schoolDir());
		learner = new SchoolLearner(System.nanoTime(), ROLLOUT + FIGHT_TICKS, 0.99f, 0.95f, cfg.resolvedTrainerThreads());
		Path teacherFile = installedCheckpoint();
		if (Files.exists(SwordFiles.schoolCheckpoint())) {
			learner.load(SwordFiles.schoolCheckpoint());
			if (Files.exists(teacherFile)) {
				learner.loadOldTeacher(teacherFile, false);
			}
		} else {
			if (!Files.exists(teacherFile)) {
				learner.close();
				throw new IOException("no installed model to start the school from: put it in " + SwordFiles.installedCopy());
			}
			// The school starts from the installed network, widened, which plays as it did (plan part 7).
			learner.loadOldTeacher(teacherFile, true);
			warmupLeft = CRITIC_WARMUP;
		}
		Path anchor = SwordFiles.schoolDir().resolve(ANCHOR_FILE);
		if (mode == Mode.TRAIN && ANCHOR_TO_BEST && Files.exists(anchor)) {
			learner.loadBase(anchor);
			learner.anchorToBase(true);
			SwordAi.LOGGER.info("school: copying targets from the frozen best fighter {} (learning without forgetting)", anchor.getFileName());
		}
		loadBasics();
		if (mode == Mode.TRAIN) {
			loadScenes();
		}
		if (Files.exists(SwordFiles.schoolStages())) {
			stages.load(SwordFiles.schoolStages());
			stages.fadeFromNow();
		}
		Path from = SwordFiles.schoolDir().resolve(CHECK_FROM_FILE);
		if (mode == Mode.CHECK && Files.exists(from)) {
			checkFights = 2L * Long.parseLong(Files.readString(from).trim());
			SwordAi.LOGGER.info("school check: starting at pair {}", checkFights / 2);
		}
		Path suite = SwordFiles.schoolDir().resolve(BENCH_SUITE_FILE);
		if (mode == Mode.CHECK && !this.teacherPlaysA && Files.exists(suite)) {
			try {
				readBench(suite);
			} catch (IOException e) {
				learner.close();
				throw e;
			}
		}
		Path temps = SwordFiles.schoolDir().resolve(PLAY_TEMPERATURE_FILE);
		if (mode == Mode.CHECK && Files.exists(temps)) {
			float[] perHead = readHeadTemperatures(temps);
			learner.playTemperatures(perHead);
			SwordAi.LOGGER.info("school check: the school network picks at temperatures {} ({})", java.util.Arrays.toString(perHead),
					String.join(" ", ActionSpace.NAMES));
		}
		Config icfg = SwordFiles.readConfig(SwordFiles.installedCopy().resolve("config.json"));
		if (icfg != null && Files.exists(teacherFile)) {
			installed = LoadedFighter.loadFrom(teacherFile, icfg, SwordFiles.installedCopy().resolve("curriculum.properties"));
			installedBody.patchedUp = installed.patchedUp;
			sparCheck = mode == Mode.CHECK && Files.exists(SwordFiles.schoolDir().resolve(SPAR_CHECK_FILE));
			rulesOffCheck = mode == Mode.CHECK && Files.exists(SwordFiles.schoolDir().resolve(RULES_OFF_CHECK_FILE));
			if (SPAR_SHARE > 0f && mode == Mode.TRAIN || sparCheck) {
				sparring = LoadedFighter.loadFrom(teacherFile, icfg, SwordFiles.installedCopy().resolve("curriculum.properties"));
			}
			Path leagueDir = SwordFiles.schoolDir().resolve(LEAGUE_DIR);
			// The benchmark's head-to-head episodes play a league network in the league's slot (docs/BENCHMARK.md, part 2): only the
			// networks its h2h lines name are loaded, each a SchoolBotPilot with the installed model's rules as in training.
			List<String> benchRivals = bench.stream().filter(e -> e.bot() < 0 && e.rival() != null).map(BenchEpisode::rival).distinct().toList();
			if ((sparring != null && mode == Mode.TRAIN || !benchRivals.isEmpty()) && Files.isDirectory(leagueDir)) {
				try (java.util.stream.Stream<Path> files = Files.list(leagueDir)) {
					for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".bin")).sorted().toList()) {
						if (benchTag != null && !benchRivals.contains(f.getFileName().toString().replace(".bin", ""))) {
							continue;
						}
						try {
							league.add(SchoolBotPilot.load(f, teacherFile, icfg, SwordFiles.installedCopy().resolve("curriculum.properties"),
								SwordFiles.schoolDir().resolve(PLAY_TEMPERATURE_FILE)));
							leagueNames.add(f.getFileName().toString().replace(".bin", ""));
						} catch (IOException | RuntimeException e) {
							SwordAi.LOGGER.warn("school: league network {} could not be loaded: {}", f.getFileName(), e.getMessage());
						}
					}
				}
				if (benchTag != null) {
					SwordAi.LOGGER.info("school bench: the league networks of its head-to-head episodes: {}", leagueNames);
				} else {
					SwordAi.LOGGER.info("school: the league, {} of the sparring fights: {}", LEAGUE_SHARE, leagueNames);
				}
			}
			for (String r : benchRivals) {
				if (!leagueNames.contains(r)) {
					SwordAi.LOGGER.warn("school bench: the league network {} of its head-to-head episodes is not in {}: those episodes are left out",
							r, leagueDir);
				}
			}
			// The swing waits for the sword as the installed model's does here (a full hit, a tick before the very end of the
			// wait; online has its own longer wait): both sides of the check alike, and the school as it would play once
			// installed. The dev config's 1.0 had the school swing a tick later than the model it is checked against (24 Oct).
			cfg.swingMinCharge = icfg.swingMinCharge;
		} else if (mode == Mode.CHECK) {
			learner.close();
			throw new IOException("no installed model with its settings in " + SwordFiles.installedCopy());
		}
		updater = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "swordai-school-update");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		int pictureFov = Math.min(cfg.visionFov, cfg.fov);
		ViewGeometry.setFov(pictureFov);
		float crop = ViewGeometry.cropFor(pictureFov, cfg.fov);
		grabber.setCrop(crop);
		boolean fast = cfg.trainSpeed > 1f;
		OptionsGuard.apply(mc, cfg.fov, fast);
		if (fast) {
			WindowGuard.shrink(mc, crop, cfg.speedWindowRows);
		}
		KeyedSkin.setShowReal(false);
		KeyedSkin.setActive(true);
		grabber.setEnabled(true);
		IntegratedServer server = mc.getSingleplayerServer();
		UUID me = mc.player.getUUID();
		LoadedFighter spar = sparring;
		List<SchoolBotPilot> leagueNow = List.copyOf(league);
		server.execute(() -> {
			ArenaServer.start(server, me, cfg);
			// In training the league comes only with the sparring copy; in a benchmark's check it comes alone, the installed model's
			// slot (0) left empty: ArenaServer plays a slot only in a fight whose bot moves as SELF, and only the h2h episodes do.
			if (spar != null || !leagueNow.isEmpty()) {
				dev.swordai.server.SparPilot[] rivals = new dev.swordai.server.SparPilot[1 + leagueNow.size()];
				rivals[0] = spar != null ? new dev.swordai.server.BotPilot(spar.fighter, spar.frameAgeTicks, spar.patchedUp) : null;
				for (int i = 0; i < leagueNow.size(); i++) {
					rivals[1 + i] = leagueNow.get(i);
				}
				ArenaServer.setRivals(rivals);
			}
		});
	}

	/** The installed network: the copy kept for the school. */
	private static Path installedCheckpoint() {
		return SwordFiles.installedCopy().resolve("checkpoint.bin");
	}

	String startLine() {
		return String.format(Locale.ROOT, "%s the technique school at %s (%d steps so far)%s.", overlay ? "Checking the teacher over the installed model against"
				: teacherPlaysA ? "Checking the teacher against"
				: mode == Mode.CHECK ? "Checking" : "Training",
				stages.current().name, stages.schoolSteps, warmupLeft > 0 ? ", starting from the installed network" : "");
	}

	// ---- AgentSession ---------------------------------------------------------------------------

	@Override
	public boolean stopped() {
		return phase == Phase.STOPPED;
	}

	@Override
	public boolean inControl() {
		return phase != Phase.STOPPED && phase != Phase.PAUSED;
	}

	@Override
	public void worldDrawn() {
		grabber.worldDrawn(mc);
	}

	@Override
	public float viewYawLag(float partialTick) {
		return -(1f - Math.max(0f, Math.min(1f, partialTick))) * turnYawThisTick;
	}

	@Override
	public float viewPitchLag(float partialTick) {
		return -(1f - Math.max(0f, Math.min(1f, partialTick))) * turnPitchThisTick;
	}

	@Override
	public boolean requestDebugDump() {
		debugRequested = true;
		return true;
	}

	@Override
	public String describe() {
		return "technique school (" + mode.name().toLowerCase(Locale.ROOT) + "), " + phase.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		lines.add(describe() + String.format(Locale.ROOT, ", x%.1f", speed));
		lines.add(stages.describe());
		lines.add("last update: " + lastUpdateLine);
		return lines;
	}

	@Override
	public void tick() {
		actedThisTick = false;
		turnYawThisTick = 0f;
		turnPitchThisTick = 0f;
		if (phase == Phase.STOPPED) {
			return;
		}
		try {
			tickUnsafe();
		} catch (RuntimeException | Error e) {
			fail("internal error: " + e, e);
		}
	}

	private void tickUnsafe() {
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
			stop("left the world");
			return;
		}
		Truth truth = ArenaServer.truth();
		if (truth.error() != null) {
			fail(truth.error(), null);
			return;
		}
		if (grabber.failure() != null) {
			fail(grabber.failure(), null);
			return;
		}
		int hurt = mc.player.hurtTime;
		hurtNow = hurt > lastHurt;
		lastHurt = hurt;
		SeenLag.follow(truth.botUuid());
		SeenLag.tick(phase == Phase.RUN);

		Screen screen = mc.screen;
		boolean interrupted = mc.isPaused() || (screen != null && !(screen instanceof ChatScreen));
		if (interrupted) {
			if (phase != Phase.UPDATING) {
				if (phase != Phase.PAUSED && phase != Phase.WAIT_ARENA) {
					suspendFight();
					phase = Phase.PAUSED;
				}
				return;
			}
		} else if (phase == Phase.PAUSED) {
			if (selfTestPending) {
				selfTest = new SelfTest();
				phase = Phase.SELFTEST;
			} else {
				beginFight(truth);
			}
			return;
		}
		mc.options.pauseOnLostFocus = false;
		OptionsGuard.hold(mc);
		if (phase != Phase.RUN) {
			lastRunServerTick = -1;
		}
		switch (phase) {
			case WAIT_ARENA -> {
				if (truth.active() && truth.botReady()) {
					selfTest = new SelfTest();
					phase = Phase.SELFTEST;
				} else if (++waitTicks > ARENA_TIMEOUT_TICKS) {
					fail("the arena did not come up within 30 seconds", null);
				}
			}
			case SELFTEST -> {
				keepView();
				AgentController.release(mc);
				if (selfTest.tick(mc, truth, grabber, observer)) {
					say("Vision self-test: " + selfTest.verdict());
					boolean fatal = selfTest.fatal();
					selfTest = null;
					selfTestPending = false;
					if (fatal) {
						fail("vision self-test failed, so the school was not started", null);
					} else {
						KeyedSkin.setShowReal(cfg.showOpponentSkin);
						beginFight(truth);
					}
				}
			}
			case WAIT_RESET -> {
				AgentController.release(mc);
				if (truth.episodeId() >= requestedEpisodeId) {
					phase = Phase.SETTLE;
					settleTicks = SETTLE_TICKS;
				} else if (++waitTicks > RESET_TIMEOUT_TICKS) {
					fail("the server did not start the next fight", null);
				}
			}
			case SETTLE -> {
				keepView();
				AgentController.release(mc);
				if (--settleTicks <= 0 && scenePutBackReady()) {
					// Nothing that happened while it was held is scored.
					CombatLog.clear();
					phase = Phase.RUN;
					if (sceneNow != null) {
						// A scene acts on the tick it is put back: a tick with every key let go would lose its sprint.
						runTick(truth);
					}
				}
			}
			case RUN -> {
				keepView();
				runTick(truth);
			}
			case UPDATING -> {
				AgentController.release(mc);
				if (pending.isDone()) {
					finishUpdate();
					if (phase == Phase.UPDATING) {
						if (interrupted) {
							phase = Phase.PAUSED;
						} else {
							beginFight(truth);
						}
					}
				}
			}
			case WAIT_FRAMES -> {
				AgentController.release(mc);
				if (grabber.sequence() >= resumeAtSequence) {
					beginFight(truth);
				}
			}
			default -> {
			}
		}
	}

	@Override
	public void endTick() {
		if (phase != Phase.RUN || !clicked || clickResolved) {
			return;
		}
		clickResolved = true;
		clickOnTarget = onOpponent();
		if (clickOnTarget && installed != null) {
			installed.fighter.hitDealt();
		}
		if (benchNow >= 0) {
			benchRecorder.swing(ownSwingKind());
		}
	}

	@Override
	public boolean beforePlayerMoves() {
		if (!actedThisTick || phase != Phase.RUN) {
			return false;
		}
		actedThisTick = false;
		if (installedPlays) {
			AgentController.hold(mc, installed.fighter.action());
			if (clicked && installed.fighter.answerForced() && !onOpponent()) {
				// As in a real fight: the rule's swing is only made at an opponent under the crosshair now.
				installed.fighter.answerCancelled();
				clicked = false;
			}
			return clicked;
		}
		AgentController.hold(mc, action);
		return clicked;
	}

	private boolean onOpponent() {
		return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == currentBot;
	}

	private void keepView() {
		if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		mc.player.getInventory().setSelectedSlot(0);
	}

	// ---- fights ---------------------------------------------------------------------------------

	/** Reads {@link #PLAY_TEMPERATURE_FILE}: "all t" then "head t" lines; heads not named take "all" (1 if absent). */
	static float[] readHeadTemperatures(Path file) throws IOException {
		float[] t = new float[ActionSpace.HEADS];
		float all = 1f;
		java.util.Map<String, Float> named = new java.util.HashMap<>();
		for (String line : Files.readAllLines(file)) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 2 || parts[0].startsWith("#")) {
				continue;
			}
			float v = Float.parseFloat(parts[1]);
			if (parts[0].equals("all")) {
				all = v;
			} else {
				named.put(parts[0], v);
			}
		}
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			t[h] = named.getOrDefault(ActionSpace.NAMES[h], all);
		}
		return t;
	}

	private void beginFight(Truth truth) {
		sceneNow = null;
		critsPending = 0;
		jumpInFrom = -1;
		punishFrom = -1;
		deflectFrom = -1;
		deflectChanceAt = -1;
		lastBotChargeSeen = -1f;
		sceneDeflected = false;
		sceneDeflectOver = false;
		repeatResolved = false;
		repeatEndNow = false;
		repeatLiftSeen = false;
		exAt[0] = -1;
		exAt[1] = -1;
		sceneCrit = false;
		sceneK2 = false;
		scenePunish = false;
		sceneOwnCrit = false;
		sceneHit = false;
		sceneAnswer = false;
		secondHitAt = -1;
		comboFrom = -1;
		sceneRow = 0;
		sceneRowMost = 0;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			stop("left the world");
			return;
		}
		if (!speedApplied) {
			speedApplied = true;
			applySpeed(cfg.trainSpeed);
		}
		long seed;
		benchNow = -1;
		if (mode == Mode.CHECK && benchTag != null) {
			// The benchmark (BENCH_SUITE_FILE): the suite's next episode, on its own seed, side A only; the network it is compared
			// with plays the same episode in its own run. No review of the old stages: each episode is the situation its bot makes.
			int i = nextBenchEpisode();
			if (i < 0) {
				finishBench();
				return;
			}
			BenchEpisode ep = bench.get(i);
			benchNow = i;
			installedPlays = false;
			seed = mix(BENCH_SEED + i);
			checkSeed = seed;
			Random draw = new Random(seed);
			// An h2h episode's bot is played by the league network (plan.style is dropped below); the mixer's style only fills the slot.
			fightBot = ep.bot() >= 0 ? ep.bot() : Specialists.MIXER;
			checkReview = -1;
			fightStyle = fightBot == Specialists.MIXER ? Specialists.mixer(draw) : Specialists.draw(fightBot, draw);
			learner.seedSampling(seed * 0x2545F4914F6CDD1DL);
		} else if (mode == Mode.CHECK) {
			long fight = checkFights++;
			long pair = fight / 2;
			installedPlays = (fight & 1) == 1;
			seed = mix(0x5C400L + pair);
			checkSeed = seed;
			Random draw = new Random(seed);
			fightBot = checkBot(pair);
			int slot = (int) (pair % checkCycle());
			checkReview = slot >= checkCycle() - CHECK_FIGHTS ? TIMER + slot - (checkCycle() - CHECK_FIGHTS) : -1;
			fightStyle = fightBot == Specialists.MIXER ? (pair % checkCycle() == checkCycle() - 1 - CHECK_FIGHTS ? Specialists.heldOutMixer(draw) : Specialists.mixer(draw))
					: Specialists.draw(fightBot, draw);
			learner.seedSampling(seed * 0x2545F4914F6CDD1DL);
		} else {
			installedPlays = false;
			fightBot = rng.nextFloat() < CRIT_BOT_SHARE ? CRIT_BOTS[rng.nextInt(CRIT_BOTS.length)] : stages.drawBot(rng);
			// Half of the specialists do other things as well, at random (see Specialists.varied); drawn from a seed of its own,
			// so that a scene of this fight can bring the same opponent back.
			fightStyleSeed = rng.nextLong();
			fightVaried = rng.nextFloat() < VARIED_SHARE;
			fightStyle = styleOf(fightBot, fightStyleSeed, fightVaried);
			seed = rng.nextLong();
		}
		review = mode == Mode.CHECK ? checkReview : drawBasic();
		Random lagDraw = new Random(seed ^ 0x1A6L);
		// What it sees of them comes 1 to 4 ticks late, as on a real server (the user's fights, 24 Oct: 55-82 ms each way); 0 to 3
		// and mostly 0 or 1 before, so a jump-in crit seen late was sprint-hit and a combo answered late.
		int agentLag = 1 + lagDraw.nextInt(4);
		EpisodePlan plan;
		sparFight = false;
		rivalNow = 0;
		if (review >= 0) {
			// The old stage's own episode: its bot (standing, strafing, hopping out of view, fighting) at a skill drawn from
			// the middle of the stage up.
			Random dress = mode == Mode.CHECK ? new Random(seed ^ 0xF16B7L) : rng;
			plan = new EpisodePlan(basics[review], 0.5f + 0.5f * dress.nextFloat());
			if (review >= TIMER) {
				dressFight(plan.bot, review, dress);
				// A server's: its knockback reaches the opponent a few ticks late, and the agent's a tick or two.
				plan.botPing = dress.nextInt(4);
				plan.agentKnockLag = dress.nextInt(3);
			}
			if (reviewTally[review] == null) {
				reviewTally[review] = new Tally();
			}
			reviewRewards.reset();
			reviewHop = truth.hopId();
		} else {
			boolean drill = mode == Mode.TRAIN && rng.nextFloat() < DRILL_SHARE;
			sceneReview = false;
			if (repeatPick(drill)) {
				sceneNow = repeatScene;
			} else if (mode == Mode.TRAIN && !drill && (!scenes.isEmpty() || !mastered.isEmpty())
					&& rng.nextFloat() < (repeatActive() ? (mastered.isEmpty() ? 0f : REPEAT_TEST_SHARE) : sceneChance())) {
				// While one is repeated (run 48) the other fights are full fights or a learned replay tested again.
				sceneReview = !mastered.isEmpty() && (repeatActive() || scenes.isEmpty() || rng.nextFloat() < SCENE_REVIEW_SHARE);
				List<Scene> from = sceneReview ? mastered : scenes;
				if (!sceneReview) {
					// The focus's kinds (equally), or the earlier focuses' (their review); whatever has scenes.
					boolean focusNow = rng.nextFloat() < FOCUS_SHARE;
					List<Integer> kinds = new ArrayList<>();
					for (int k = 0; k < KINDS; k++) {
						boolean earlier = !inFocus(k) && learnedKind(k);
						if (countKind(k) > 0 && (focusNow ? inFocus(k) : earlier)) {
							kinds.add(k);
						}
					}
					if (kinds.isEmpty()) {
						for (int k = 0; k < KINDS; k++) {
							if (countKind(k) > 0) {
								kinds.add(k);
							}
						}
					}
					int kind;
					// Run 44: the own-combo scenes take OWN_SHARE of the focus's scene fights, the other kinds share the rest.
					boolean own = kinds.contains(KIND_OWN), own2 = kinds.contains(KIND_OWN2);
					if (focusNow && (own || own2) && (kinds.size() == (own ? 1 : 0) + (own2 ? 1 : 0) || rng.nextFloat() < OWN_SHARE)) {
						kind = own2 && (!own || rng.nextFloat() < OWN2_OF_OWN) ? KIND_OWN2 : KIND_OWN;
					} else {
						kinds.remove(Integer.valueOf(KIND_OWN));
						kinds.remove(Integer.valueOf(KIND_OWN2));
						if (kinds.isEmpty()) {
							kinds.add(KIND_OWN);
						}
						// The punish scenes keep their share of before (a quarter; the user: punish crit about 50% of its
						// chances), the crit, jump-in and combo-escape scenes give up theirs.
						if (focusNow && kinds.contains(KIND_PUNISH) && kinds.size() > 1) {
							kinds.add(KIND_PUNISH);
						}
						// Run 47: the deflection's failures likewise (the user's first example with the punish crit).
						if (focusNow && kinds.contains(KIND_DEFLECT) && kinds.size() > 1) {
							kinds.add(KIND_DEFLECT);
						}
						kind = kinds.get(rng.nextInt(kinds.size()));
					}
					List<Scene> ofKind = new ArrayList<>();
					List<Scene> ofSpar = new ArrayList<>();
					for (Scene sc : scenes) {
						if (sc.kind == kind) {
							(sc.spar ? ofSpar : ofKind).add(sc);
						}
					}
					// The installed AI's scenes as often as the bots' (its jump-ins were countered 40%, the bots' 76%).
					if (!ofSpar.isEmpty() && (ofKind.isEmpty() || rng.nextBoolean())) {
						ofKind = ofSpar;
					}
					from = ofKind;
					// Run 47b: its oldest queued failures only, until learned (scenes keeps them oldest first).
					if (from.size() > SCENE_WORKING_SET) {
						from = from.subList(0, SCENE_WORKING_SET);
					}
				}
				sceneNow = from.get(rng.nextInt(from.size()));
			}
			if (sceneNow != null) {
				fightBot = sceneNow.bot;
				fightStyleSeed = sceneNow.styleSeed;
				fightVaried = sceneNow.varied;
				fightStyle = styleOf(fightBot, fightStyleSeed, fightVaried);
				agentLag = sceneNow.agentLag;
			}
			plan = EpisodePlan.school(fightStyle, drill ? DRILL_NEAR : NEAREST, drill ? DRILL_FAR : FARTHEST);
			sparFight = sceneNow != null ? sceneNow.spar && sparring != null
					: !drill && sparring != null && (mode == Mode.TRAIN ? rng.nextFloat() < SPAR_SHARE : sparCheck);
			rivalNow = sparFight && sceneNow == null && mode == Mode.TRAIN && !league.isEmpty() && rng.nextFloat() < LEAGUE_SHARE
					? 1 + rng.nextInt(league.size()) : 0;
			if (benchNow >= 0) {
				// The benchmark: its h2h episodes against their league network, the rest against their bot (the mirror check's
				// SPAR_CHECK_FILE is not for it). The league network sees it SPAR_LAG_EXTRA ticks late, as in training's sparring.
				rivalNow = bench.get(benchNow).bot() < 0 ? benchRivalIndex(bench.get(benchNow)) : 0;
				sparFight = rivalNow > 0;
			}
			plan.rivalIndex = rivalNow;
			if (sparFight) {
				// The installed model plays the bot (ArenaServer's rival): no scripted style.
				plan.style = null;
				plan.bot.movement = dev.swordai.core.curriculum.BotProfile.SELF;
				plan.botLag = sceneNow != null ? sceneNow.botLag : Math.min(8, agentLag + SPAR_LAG_EXTRA);
			}
			sparBotLag = plan.botLag;
			if (sceneNow != null) {
				plan.scene = sceneNow.state;
			}
			if (drill) {
				plan.drillLift = true;
				plan.drillAgentTicker = 2 + rng.nextInt(7);
			}
		}
		drillFight = plan.drillLift;
		// The take-over (RULES_OFF_CHECK_FILE): the full fights and, since run 30f, the scenes too (the reviews and the drills stay as they were).
		// The no-rules screens' hit log (1 Nov 12:46): the network without the rules trails the installed model in the combos from the third
		// hit (13 vs 7 taken a fight) and in the air (15 vs 4), exactly the scenes' contexts, which were all played with the rules, so the
		// network never practised its own answer there.
		ruleOff = mode == Mode.CHECK ? rulesOffCheck && !installedPlays
			// Run 47c (the user, 14:25: many replays and still not learned): every replay is played without the rules, so the network
			// presses every key of the moment itself and gets the gradient for it (with the rules on, about half the replays, the
			// rules pressed the deciding keys and those got none).
			: review < 0 && !drillFight && (sceneNow != null && mode == Mode.TRAIN || rng.nextFloat() < ruleOffShare());
		drillTicker = plan.drillLift ? plan.drillAgentTicker : sceneNow != null ? SceneRecorder.agentTicker(sceneNow.state) : -1;
		drillFights += plan.drillLift ? 1 : 0;
		plan.seed = seed;
		plan.seedYaw = mode == Mode.CHECK;
		plan.agentLag = agentLag;
		SeenLag.set(agentLag, 0f, 0);
		requestedEpisodeId = truth.episodeId() + 1;
		putBackAsked = false;
		if (benchNow >= 0 && rivalNow > 0) {
			// The league network's own draws from the episode's seed too (SchoolBotPilot#seedSampling), on the server thread it plays on.
			SchoolBotPilot rival = league.get(rivalNow - 1);
			long rivalSeed = seed * 0x5851F42D4C957F2DL;
			server.execute(() -> {
				rival.seedSampling(rivalSeed);
				ArenaServer.reset(plan);
			});
		} else {
			server.execute(() -> ArenaServer.reset(plan));
		}
		if (benchNow >= 0) {
			startBenchEpisode();
		}
		AgentController.release(mc);
		mc.player.getInventory().setSelectedSlot(0);
		// One teacher against every bot: every technique of the families reached so far.
		teacher.ordinaryFight = !OVERLAY;
		// A punish scene is the punish crit's drill (the user's method, 27 Oct): the teacher punishes every lift it can, and the
		// network is always told so (the decision model's output forced to B1), so that it learns to land it whenever told.
		boolean punishDrill = punishScene() || drillFight;
		teacher.punishFirst = punishDrill;
		teacher.start(checkTaught != null ? checkTaught : stages.taught(), cfg.swingMinCharge, seed ^ 0x7EAC4L);
		teacher.seenLag(agentLag);
		fightLag = agentLag;
		learner.teacherForcing = punishDrill ? 1f : sceneNow != null ? 1f - SCENE_DECIDER_SHARE : TEACHER_FORCING;
		learner.resetIntent();
		shaping = 0f;
		lastMomentTechnique = -1;
		fairJump.reset();
		// The punish scene's exact twin starts PUNISH2_AFTER ticks into the lift (run 47).
		liftedFor = sceneNow != null && sceneNow.kind == KIND_PUNISH2 ? PUNISH2_AFTER : -1;
		jumpInAir = false;
		onGroundBefore = true;
		liftKind = -1;
		liftStage = 0;
		liftTaught = false;
		liftTold = false;
		liftToldByDecider = false;
		reader.reset();
		perf.reset();
		perfB.reset();
		observer.resetEpisode();
		clock.resetEpisode();
		clock.botAttacks = true;
		motor.reset();
		idea.clear();
		if (installed != null) {
			installed.fighter.reset();
			// Hit and out (SchoolStages#HIT_AND_OUT): for the side the school trains, not for the installed model that plays against it in a check.
			// A benchmark can switch it on with BENCH_HIT_AND_OUT_FILE in the school folder (7 Nov: the same network with and without,
			// the document's T10 "S right after the sprint hit", measured in one benchmark run each).
			installed.fighter.hitAndOut((dev.swordai.core.school.SchoolStages.HIT_AND_OUT
					|| mode == Mode.CHECK && benchTag != null && Files.exists(SwordFiles.schoolDir().resolve(BENCH_HIT_AND_OUT_FILE))) && !installedPlays);
		}
		hasLastAction = false;
		clicked = false;
		theirRow = 0;
		seenSide = 0;
		sweep = 0;
		frozenTicks = 0;
		fightTicks = 0;
		// An own-combo scene starts the tick after its first hit: a hit within COMBO_GAP is that combo's follow-up (COMBO_HIT).
		boolean ownScene = sceneNow != null && (sceneNow.kind == KIND_OWN || sceneNow.kind == KIND_OWN2);
		lastOwnHitAt = ownScene ? -1 : -1000;
		rewardRow = !ownScene ? 0 : sceneNow.kind == KIND_OWN2 ? 2 : 1;
		awayTicks = 0;
		ownJumpAir = false;
		ownFrom = -1;
		ownRow = 0;
		ownFirstAt = 0;
		sceneOwnHits = 0;
		sceneOwnBroken = false;
		java.util.Arrays.fill(rowDealt, 0);
		java.util.Arrays.fill(rowTaken, 0);
		java.util.Arrays.fill(sinceSwing, 999);
		fightDealt = 0f;
		fightTaken = 0f;
		fightTeacherTicks = 0;
		lastBotTechnique = -1;
		waitTicks = 0;
		phase = Phase.WAIT_RESET;
	}

	/** Paired check: the bots it goes through, pair by pair: this stage's, then the real player twice (the second held out). */
	private int checkCycle() {
		return stages.current().bots.length + SchoolStages.PRIORITY_BOTS.length + 2 + CHECK_FIGHTS;
	}

	/** The stage's bots, the bots that jump in (the priority, in every check), and the real player twice. */
	private int checkBot(long pair) {
		int[] bots = stages.current().bots;
		int i = (int) (pair % checkCycle());
		if (i < bots.length) {
			return bots[i];
		}
		i -= bots.length;
		return i < SchoolStages.PRIORITY_BOTS.length ? SchoolStages.PRIORITY_BOTS[i] : Specialists.MIXER;
	}

	private static long mix(long n) {
		long z = n * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	// ---- the benchmark (BENCH_SUITE_FILE, docs/BENCHMARK.md) ---------------------------------------

	/**
	 * Reads BENCH_SUITE_FILE into the episode list, in file order: episode i is the i-th of all its lines' episodes, so its seed
	 * and its file's number stay the same whatever a run leaves out. An h2h line without a name plays the first network of the
	 * league folder (by name). A file it cannot read stops the check before it starts: a suite read otherwise than the
	 * reference's would put other episodes on its seeds.
	 */
	private void readBench(Path file) throws IOException {
		Path leagueDir = SwordFiles.schoolDir().resolve(LEAGUE_DIR);
		String firstRival = null;
		if (Files.isDirectory(leagueDir)) {
			try (java.util.stream.Stream<Path> files = Files.list(leagueDir)) {
				firstRival = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".bin")).sorted()
						.map(n -> n.substring(0, n.length() - 4)).findFirst().orElse(null);
			}
		}
		for (String raw : Files.readAllLines(file)) {
			int hash = raw.indexOf('#');
			String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
			if (line.isEmpty()) {
				continue;
			}
			String[] p = line.split("\\s+");
			if (benchTag == null) {
				// The tag names a folder: nothing that could climb out of swordai/bench.
				if (p.length != 2 || !p[0].equals("tag") || !p[1].matches("[A-Za-z0-9_][A-Za-z0-9._-]*")) {
					throw new IOException(file + ": the first line must be \"tag NAME\" (letters, digits, . _ -), not \"" + line + "\"");
				}
				benchTag = p[1];
				continue;
			}
			if (p[0].equals("network")) {
				benchNetwork = line.substring("network".length()).trim();
				continue;
			}
			boolean h2h = p[0].equals("h2h");
			int bot = java.util.Arrays.asList(Specialists.NAMES).indexOf(p[0]);
			int n = -1;
			if (p.length >= 2) {
				try {
					n = Integer.parseInt(p[1]);
				} catch (NumberFormatException e) {
					n = -1;
				}
			}
			if (!h2h && bot < 0 || n < 0 || p.length > (h2h ? 3 : 2)) {
				throw new IOException(file + ": not a suite line: \"" + line + "\" (BOT EPISODES with a bot of Specialists.NAMES, or h2h EPISODES [LEAGUE-NAME])");
			}
			String rival = !h2h ? null : p.length == 3 ? (p[2].endsWith(".bin") ? p[2].substring(0, p[2].length() - 4) : p[2]) : firstRival;
			if (h2h && rival == null) {
				SwordAi.LOGGER.warn("school bench: no network in {} for the head-to-head line \"{}\": its {} episodes are left out", leagueDir, line, n);
			}
			for (int k = 0; k < n; k++) {
				bench.add(new BenchEpisode(h2h ? -1 : bot, rival));
			}
		}
		if (benchTag == null) {
			throw new IOException(file + ": no \"tag NAME\" line");
		}
		benchDir = SwordFiles.schoolDir().getParent().resolve("bench").resolve(benchTag);
		Files.createDirectories(benchDir);
		benchStarted = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		benchNetworkHash = sha256(SwordFiles.schoolCheckpoint());
		SwordAi.LOGGER.info("school bench {}: {} episodes, side A only (network {}, sha256 {}), recorded to {}", benchTag, bench.size(),
				benchNetwork, benchNetworkHash, benchDir);
	}

	/** The file's SHA-256 in hex (as sha256sum prints it), or "?": which network played a benchmark, whatever it was called. */
	private static String sha256(Path file) {
		try (java.io.InputStream in = Files.newInputStream(file)) {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0; ) {
				md.update(buf, 0, n);
			}
			return java.util.HexFormat.of().formatHex(md.digest());
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			return "?";
		}
	}

	/** An episode's file: its number, then its bot, or h2h. */
	private static String benchFile(int i, BenchEpisode ep) {
		return String.format(Locale.ROOT, "%04d-%s.txt.gz", i, ep.bot() < 0 ? "h2h" : Specialists.NAMES[ep.bot()]);
	}

	/** An h2h episode's slot among ArenaServer's rivals (1 + its place in the league), 0 if its network is not loaded. */
	private int benchRivalIndex(BenchEpisode ep) {
		return ep.rival() == null ? 0 : leagueNames.indexOf(ep.rival()) + 1;
	}

	/**
	 * The benchmark's next episode to play, or -1 when the suite is done. An episode broken off (a pause, a stall, frames that
	 * stopped, a bot not there) is played again from its seed, BENCH_TRIES times in all; an h2h episode without its league network,
	 * and one written already (a suite broken off and started again under the same tag), are passed over.
	 */
	private int nextBenchEpisode() {
		while (benchNext < bench.size()) {
			int i = benchNext;
			BenchEpisode ep = bench.get(i);
			if (ep.bot() < 0 && benchRivalIndex(ep) == 0) {
				benchNoRival++;
			} else if (Files.exists(benchDir.resolve(benchFile(i, ep)))) {
				benchHad++;
			} else {
				benchTries = i == benchBegun ? benchTries + 1 : 1;
				benchBegun = i;
				if (benchTries <= BENCH_TRIES) {
					return i;
				}
				SwordAi.LOGGER.warn("school bench: episode {} was broken off {} times: left out", i, BENCH_TRIES);
				benchBroken++;
			}
			benchNext++;
		}
		return -1;
	}

	/** An episode begins (or begins again after a break): its record from nothing, the hit and jump rows of a broken-off try dropped. */
	private void startBenchEpisode() {
		BenchEpisode ep = bench.get(benchNow);
		benchRecorder.start(ep.bot() < 0 ? "league-" + ep.rival() : Specialists.NAMES[ep.bot()]);
		benchBody.resetOpponentSwing();
		benchSwingsSeen = dev.swordai.OwnSwing.count();
		benchRules = 0;
		hitRows.setLength(0);
		jumpRows.setLength(0);
		jumpAt[0] = jumpAt[1] = -1;
		jumpWasGround[0] = jumpWasGround[1] = true;
		jumpLastDist[0] = jumpLastDist[1] = 99f;
	}

	/**
	 * One tick of an episode for its file (RoundRecorder, the user's real fights' format): the keys pressed, the rules that had
	 * keys, and the hits as the server counted them this tick (CombatLog, as the check counts them).
	 */
	private void benchTick(LocalPlayer me, Player bot, Truth truth, int[] keys) {
		observer.fillBody(benchBody, me, bot, truth.floorY());
		benchRecorder.tick(me, bot, benchBody, keys, benchRules, facts.hitsDealt > 0, facts.hitsTaken > 0);
	}

	/** What its swing came to as this game made it out (OwnSwing), in the words of a real fight's file (FightSession#endTick). */
	private String ownSwingKind() {
		int made = dev.swordai.OwnSwing.count();
		if (made == benchSwingsSeen) {
			return "miss";
		}
		benchSwingsSeen = made;
		if (dev.swordai.OwnSwing.charge() <= 0.9f) {
			return "early";
		} else if (dev.swordai.OwnSwing.crit()) {
			return "crit";
		} else if (dev.swordai.OwnSwing.sprinting()) {
			return "sprint";
		} else if (dev.swordai.OwnSwing.sweep()) {
			return "sweep";
		}
		return "plain";
	}

	/**
	 * An episode is over: its file (won, lost, or open at the time limit), its line in the log ("school bench", not "school pair":
	 * the pair counters of the check scripts must not count it), its hit and jump rows in its folder. Not written, it is played again.
	 */
	private void endBenchEpisode(boolean won, boolean lost, String result) {
		BenchEpisode ep = bench.get(benchNow);
		String line = benchRecorder.finishTo(benchDir, benchFile(benchNow, ep), won ? "won" : lost ? "lost" : "open");
		SwordAi.LOGGER.info(String.format(Locale.ROOT, "school bench %04d %s: dealt %.1f, taken %.1f, ticks %d%s%s", benchNow,
				ep.bot() < 0 ? "h2h league-" + ep.rival() : Specialists.NAMES[ep.bot()], fightDealt, fightTaken, fightTicks, result,
				line == null ? " (its file could not be written: played again)" : ""));
		if (line != null) {
			flushHits();
			benchWritten++;
			benchNext = benchNow + 1;
		}
	}

	/**
	 * The suite is played: DONE in its folder (what bench-run.sh waits for), "key value" lines saying how many episodes are there,
	 * which network played them and when; then the check stops as one stopped by command does, and bench-run.sh puts the
	 * training back.
	 */
	private void finishBench() {
		long files = 0;
		try (java.util.stream.Stream<Path> s = Files.list(benchDir)) {
			files = s.filter(p -> p.getFileName().toString().matches("\\d{4}-.+\\.txt\\.gz")).count();
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school bench: could not count the episodes in {}: {}", benchDir, e.getMessage());
		}
		String done = String.format(Locale.ROOT, "tag %s%nsuite-episodes %d%nepisodes %d%nwritten-now %d%nwritten-before %d%n"
						+ "left-out-no-league %d%nleft-out-broken-off %d%nnetwork %s%nnetwork-sha256 %s%nleague %s%nstarted %s%nfinished %s%n",
				benchTag, bench.size(), files, benchWritten, benchHad, benchNoRival, benchBroken, benchNetwork, benchNetworkHash,
				leagueNames.isEmpty() ? "-" : String.join(" ", leagueNames), benchStarted,
				java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
		try {
			Files.writeString(benchDir.resolve(BENCH_DONE_FILE), done);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school bench: could not write {}: {}", benchDir.resolve(BENCH_DONE_FILE), e.getMessage());
		}
		say(String.format(Locale.ROOT, "The benchmark %s is done: %d of its %d episodes in %s (%d written now, %d left out without their league network, "
				+ "%d broken off).", benchTag, files, bench.size(), benchDir, benchWritten, benchNoRival, benchBroken));
		stop("the benchmark is done");
	}

	/** The number a check's hit and jump logs give this fight: its pair, or in a benchmark its episode. */
	private long logPair() {
		return benchNow >= 0 ? benchNow : (checkFights - 1) / 2;
	}

	/** Where a check's hit and jump logs go: the school folder, or a benchmark's own folder (rows numbered by episode). */
	private Path logDir() {
		return benchNow >= 0 ? benchDir : SwordFiles.schoolDir();
	}

	private void runTick(Truth truth) {
		long now = truth.tick();
		long gap = lastRunServerTick < 0 ? 1 : now - lastRunServerTick;
		lastRunServerTick = now;
		if (gap > STALL_TICKS) {
			// The game stood still while the server went on: nothing of this fight says how it fights.
			stalledCuts++;
			logCut("the game stood still " + gap + " server ticks");
			lastRunServerTick = -1;
			if (mode == Mode.CHECK) {
				checkFights--;
			}
			suspendFight();
			beginFight(truth);
			return;
		}
		Player bot = truth.botUuid() == null ? null : mc.level.getPlayerByUUID(truth.botUuid());
		if (bot == null) {
			AgentController.release(mc);
			if (++missingBotTicks > MISSING_BOT_TICKS) {
				missingBotTicks = 0;
				logCut("the bot was not there");
				suspendFight();
				beginFight(truth);
			}
			return;
		}
		missingBotTicks = 0;
		currentBot = bot;
		if (!observer.capture(grabber)) {
			AgentController.release(mc);
			return;
		}
		if (observer.stale()) {
			if (++frozenTicks >= FROZEN_FRAME_TICKS) {
				logCut("the picture froze");
				suspendFight();
				frozenTicks = 0;
				resumeAtSequence = grabber.sequence() + FRAMES_TO_RESUME;
				phase = Phase.WAIT_FRAMES;
				return;
			}
		} else {
			frozenTicks = 0;
		}
		if (debugRequested) {
			debugRequested = false;
			try {
				say("Saved what it sees to " + DebugDump.dump(grabber, observer.frame));
			} catch (IOException | RuntimeException e) {
				say("Could not save the debug images: " + e);
			}
		}
		step(truth, bot);
		if (KeyedSkin.showReal() && blind.note(facts)) {
			SwordAiClient.skinBlindsTheAgent(grabber.grabCounts());
		}
	}

	/** One decision: scores the last step, then chooses and carries out the next. */
	private void step(Truth truth, Player bot) {
		LocalPlayer me = mc.player;
		facts.clear();
		observer.fillGeometry(facts, me, bot);
		facts.onGround = me.onGround();
		clock.ticksSinceHitDealt++;
		clock.ticksSinceHitTaken++;
		// Before this tick's hit: whether a hit now lands within its own combo (SchoolFighter.K2_COMBO_TICKS).
		boolean inOwnCombo = clock.ticksSinceHitDealt <= dev.swordai.core.school.SchoolFighter.K2_COMBO_TICKS;
		lastInOwnCombo = inOwnCombo;
		CombatLog.drain(facts);
		boolean shownDealt = hasLastAction && clicked && clickOnTarget;
		if (hasLastAction) {
			long[] sc = ruleOff && mode == Mode.TRAIN ? ruleOffSwings : swingCount[installedPlays ? 1 : 0];
			sc[0] += clicked ? 1 : 0;
			sc[1] += clicked && clickOnTarget ? 1 : 0;
			sc[2] += facts.hitsDealt;
			if (facts.hitsDealt > 0) {
				theirRow = 0;
			}
			if (facts.hitsTaken > 0) {
				theirRow++;
				sc[3] += theirRow == 2 ? 1 : 0;
				sc[4] += theirRow == 3 ? 1 : 0;
			}
			if (mode == Mode.TRAIN && review < 0 && !ruleOff) {
				trackCombo(facts.hitsDealt > 0, facts.hitsTaken > 0);
			}
		}
		if (shownDealt) {
			clock.ticksSinceHitDealt = 0;
		}
		if (facts.hitsTaken > 0) {
			clock.ticksSinceHitTaken = 0;
		}
		// Thrown up by their hit from the ground, until it lands again (Habits' punish crit chance).
		liftedFor = facts.hitsTaken > 0 && onGroundBefore ? 0 : liftedFor < 0 ? -1 : liftedFor + 1;
		if (liftedFor > 3 && me.onGround() || liftedFor > PUNISH_WINDOW) {
			liftedFor = -1;
		}
		onGroundBefore = me.onGround();
		clock.takenStreak.tick(shownDealt ? 1 : 0, facts.hitsTaken);
		if (installed != null && hurtNow) {
			installed.fighter.hitTaken();
		}
		// The server's word on its crits (see DuelReader.tick): a crit it confirmed this tick or the two before.
		ticksSinceCrit = facts.hitsDealt > 0 && facts.critDealt ? 0 : Math.min(ticksSinceCrit + 1, 1000);
		reader.tick(me, bot, hasLastAction && clicked, chargeAtClick, sprintAtClick, critAtClick, ticksSinceCrit <= 2);
		if (liftedFor == 0) {
			endLift();
			liftKind = reader.events.theyHit;
			liftAt = fightTicks;
		} else if (liftedFor < 0) {
			endLift();
		}
		int botTechnique = truth.botTechnique();
		if (botTechnique >= 0 && botTechnique != lastBotTechnique && botTechnique != Technique.BASE.ordinal()) {
			botUsed[botTechnique]++;
		}
		lastBotTechnique = botTechnique;

		// The new observation: the picture, its body, the memory of the fight; and for the critic the truth.
		clock.episodeFraction = Math.min(1f, fightTicks / (float) FIGHT_TICKS);
		// In a review, the old stage's keys and number, as the installed model saw them when it learned that stage.
		if (!hasLastAction && drillTicker >= 0) {
			// The drill's sword on this side too: the swing gate and what it sees go by the client's own. Set before the first
			// look (until 7 Nov it came after it), plus the server ticks since the server set its own (the client's first tick
			// of the episode comes a tick or two later, and the server's clock has counted on meanwhile).
			long since = ArenaServer.agentTickerSetAt >= 0 ? Math.max(0, Math.min(5, truth.tick() - ArenaServer.agentTickerSetAt)) : 0;
			((dev.swordai.mixin.LivingEntityAccessor) me).swordai$setAttackStrengthTicker(drillTicker + (int) since);
			if (clientClockSets++ < 20) {
				SwordAi.LOGGER.info("school: the agent's sword clock on its client set to {} + {} server ticks since the server set it",
						drillTicker, since);
			}
			drillTicker = -1;
		}
		observer.fillObs(step.obs.base, mc, me, bot, truth, facts, hasLastAction ? lastAction : null,
				review >= 0 ? basics[review].headMask : ActionSpace.ALL_HEADS, review >= 0 ? BASIC_OLD[review] : 9, clock, motor, idea);
		System.arraycopy(reader.memory(), 0, step.obs.mem, 0, step.obs.mem.length);
		step.obs.setOpponent(botTechnique, review >= 0 || sparFight ? null : fightStyle.p, review >= 0 || sparFight ? -1 : fightBot);

		lastTruthFalling = truth.botFalling();
		if (hasLastAction) {
			fightTicks++;
			fightDealt += facts.damageDealt;
			fightTaken += facts.damageTaken;
			boolean ko = facts.koDealt || facts.koTaken;
			boolean timeUp = review >= 0 ? fightTicks >= Math.min(basics[review].bot.attacks ? FIGHT_TICKS : REVIEW_TICKS, basics[review].episodeTicks)
					|| reviewRewards.lostTooLong(basics[review])
					: fightTicks >= (sceneNow != null ? sceneNow.ticks() : FIGHT_TICKS) || repeatEndNow && fightTicks >= REPEAT_MIN_TICKS;
			if (mode == Mode.TRAIN && review < 0 && sceneNow == null && !drillFight && !ruleOff) {
				trackOwnCombo(facts.hitsDealt > 0, facts.hitsTaken > 0);
				harvest(truth.tick(), facts.damageDealt - facts.damageTaken, facts.critsTaken > 0, ko || timeUp);
			}
			// An own-combo scene (run 44): two more hits in a row, each within COMBO_GAP of the last (the first of the scene's
			// start), before a hit taken.
			if (mode == Mode.TRAIN && sceneNow != null && (sceneNow.kind == KIND_OWN && sceneOwnHits < 2 || sceneNow.kind == KIND_OWN2 && sceneOwnHits < 1)
					&& !sceneOwnBroken) {
				if (facts.hitsTaken > 0 || fightTicks - Math.max(lastOwnHitAt, 0) > COMBO_GAP) {
					sceneOwnBroken = true;
				} else if (facts.hitsDealt > 0) {
					sceneOwnHits++;
				}
			}
			boolean full = mode == Mode.TRAIN && learner.rolloutRoom() <= 1;
			if (mode == Mode.TRAIN && review >= 0) {
				// A review: the old stage's own rewards, on the facts it scores (see Trainer).
				fillReviewFacts(truth, me);
				float reward = REVIEW_SCALE * reviewRewards.step(basics[review], facts, reviewTally[review]) + shaping;
				shapingPaid += shaping;
				shaping = 0f;
				learner.complete(reward, ko, !ko && (timeUp || full), step.obs);
			} else if (mode == Mode.TRAIN) {
				float reward = PER_HEALTH * (DEALT_WEIGHT * facts.damageDealt - facts.damageTaken) + (facts.koDealt ? KNOCKOUT : 0f) - (facts.koTaken ? KNOCKOUT : 0f);
				if (facts.hitsDealt > 0 && facts.critDealt) {
					if (liftedFor >= 0) {
						reward += PUNISH_CRIT;
						punishCritsPaid++;
						drillPunishCrits += drillFight ? 1 : 0;
						liftStage = 6;
					} else {
						reward += OTHER_CRIT;
						otherCrits++;
					}
				}
				// Falling as it saw them, or on the server already (seen late: "it might be landing already while client still
				// doesn't", the user, 24 Oct 23:05).
				// Not in its own combo (25 Oct 09:25, the user: the guard made it forget to combo).
				if (facts.hitsDealt > 0 && facts.sprintHit && !(K2_COMBO_EXEMPT && inOwnCombo) && (reader.duel.them.falling() || truth.botFalling())) {
					reward -= K2_BROKEN;
					k2Broken++;
				}
				if (clicked && !clickOnTarget) {
					reward -= AIR_SWING;
				}
				if (clicked && critChanceAtClick && (!CRIT_SWING_IN_LIFTS_ONLY || liftedFor >= 0)) {
					reward += CRIT_SWING;
					critSwings++;
				}
				// Run 43 (AIR_JUMP_HIT): hit in the air out of its own jump.
				if (facts.hitsTaken > 0 && !facts.onGround && ownJumpAir) {
					reward -= AIR_JUMP_HIT;
					airJumpHits++;
				}
				// Made to miss (run 44, MADE_MISS): a bot's swing at it within reach that missed, since the last tick.
				int whiffsNow = dev.swordai.server.TechniqueBot.whiffs();
				if (whiffsNow > lastWhiffs && !sparFight) {
					reward += MADE_MISS;
					madeMiss++;
				}
				lastWhiffs = whiffsNow;
				// The deflection start (run 45, DEFLECT_HIT): a sprint hit opening its combo while their sword is empty.
				if (facts.hitsDealt > 0 && facts.sprintHit && facts.hitsTaken == 0 && fightTicks - lastOwnHitAt > COMBO_GAP
						&& truth.botCharge() < DEFLECT_CHARGE && !(reader.duel.them.falling() || truth.botFalling())
						// Not in a lift by their hit: there the punish crit is the answer (B1), not a sprint counter (punish diagnosis).
						&& liftedFor < 0) {
					reward += DEFLECT_HIT;
					deflectStarts++;
				}
				trackDeflection(truth);
				if (sceneNow != null && sceneNow == repeatScene && !sceneReview && !repeatResolved) {
					// Run 48: until the deed every tick costs; the deed or a clear failure ends the try.
					reward -= REPEAT_TICK_COST;
					int out = repeatOutcome();
					if (out != 0) {
						reward += out > 0 ? REPEAT_DONE : -REPEAT_FAILED;
						repeatResolved = true;
						repeatEndNow = true;
					}
				}
				// The chase crit (run 44, CHASE_CRIT): before the combo count below moves its last hit to now.
				if (facts.hitsDealt > 0 && facts.critDealt && facts.hitsTaken == 0 && lastOwnHitAt > -1000
						&& fightTicks - lastOwnHitAt <= CHASE_GAP && awayTicks >= AWAY_TICKS) {
					reward += CHASE_CRIT;
					chaseCrits++;
				}
				awayTicks = facts.hitsDealt > 0 || facts.hitsTaken > 0 ? 0 : awayTicks + (reader.duel.theyClose < -0.05 ? 1 : 0);
				// The combo reward (run 42, COMBO_HIT): a trade (both hit this tick) or a hit taken ends its combo.
				if (facts.hitsTaken > 0) {
					lastOwnHitAt = -1000;
					rewardRow = 0;
				} else if (facts.hitsDealt > 0) {
					if (fightTicks - lastOwnHitAt <= COMBO_GAP) {
						rewardRow = Math.max(rewardRow, 1) + 1;
						reward += COMBO_HIT + (rewardRow >= 3 ? COMBO_HIT3 : 0f);
						comboHitsPaid++;
						combo3Paid += rewardRow >= 3 ? 1 : 0;
					} else {
						rewardRow = 1;
					}
					lastOwnHitAt = fightTicks;
				}
				reward += shaping;
				shapingPaid += shaping;
				shaping = 0f;
				learner.complete(reward, ko, !ko && (timeUp || full), step.obs);
			}
			if (ko || timeUp || full) {
				if (benchNow >= 0) {
					// The episode's last tick too, with the keys still held: its knockout hit is the episode's last damage (a real
					// fight's file ends the tick before the death, which is read from the screen).
					benchTick(me, bot, truth, lastAction);
				}
				endFight(facts.koDealt, facts.koTaken);
				AgentController.release(mc);
				if (mode == Mode.TRAIN && learner.rolloutSize() >= ROLLOUT) {
					startUpdate();
				} else {
					beginFight(truth);
				}
				return;
			}
		}

		// ---- decide and act ----
		float charge = me.getAttackStrengthScale(0f);
		chargeAtClick = charge;
		sprintAtClick = truth.agentSprinting();
		critAtClick = facts.falling && !me.onGround() && !sprintAtClick && charge > 0.9f;
		liftAimedNow = false;
		if (liftedFor >= 0 && liftKind > 0 && facts.falling && !me.onGround() && facts.reachDist <= 3.0f) {
			int stage = charge <= 0.9f ? 1 : sprintAtClick ? 2 : !onOpponent() ? 3 : 4;
			liftStage = Math.max(liftStage, stage);
			liftAimedNow = stage == 4;
		}
		int keys = SwingGate.keys(ActionSpace.ALL_HEADS, charge, cfg.swingMinCharge);
		boolean chargeLocked = !ActionSpace.unlocked(keys, ActionSpace.ATTACK);
		// The installed model's guard, the same for this network (24 Oct 17:10): no swing at an opponent plainly out of reach
		// or off the screen (SwingGate.outOfReach). Without it, in the 16:31 check 37% of its swings hit nothing, the
		// installed model's 9%, and that was the whole gap in damage dealt.
		// Never with the opponent under the crosshair: the distance read off the screen is rough, and at 17:15 it held back a
		// tenth to a fifth of the deflection's (T4) swings that would have landed.
		// Since 22:15 no swing at all without the opponent under the crosshair in reach, as the game picks it: the distance read
		// off the screen let through swings at an opponent aimed at but out of reach, again and again in the user's real fights
		// (24 Oct 22:05: "constantly hit opponent when aim and not in reach"). A swing that would hit nothing is not made.
		if (cfg.whiffGuard && !onOpponent()) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
		}
		if (review >= 0) {
			// The keys the old stage plays with (the camera alone in the aim stages).
			keys &= basics[review].headMask;
		}
		teacher.tick(reader.duel, reader.events, onOpponent());
		liftTaught |= liftedFor >= 0 && teacher.technique() == Technique.B1;
		if (liftedFor >= 0 && learner.told() == Technique.B1) {
			liftTold = true;
			liftToldByDecider |= !learner.toldTheTeachers();
		}
		// No technique moments against an old stage's bot that does not hit: the installed model (which learned these stages)
		// teaches there. Against one that hits (sprint-hit, combo escape) the file's answers are taught as in a fight: the
		// installed model never backed out of a combo of its own (a rule held S for it), and in the combo escape reviews it got
		// out of 1 of 33 combos, S held 0-11% (24 Oct 23:00).
		step.teacherHeads = review >= 0 && !basics[review].bot.attacks ? 0 : teacher.target(step.teacher);
		step.teacherTechnique = step.teacherHeads != 0 ? teacher.technique().ordinal() : -1;
		// The punish crit's lesson is its swing alone, the jump reset's its jump (SchoolStages#taughtHeads).
		if (step.teacherTechnique >= 0) {
			step.teacherHeads &= dev.swordai.core.school.SchoolStages.taughtHeads(step.teacherTechnique);
		}
		// A technique's swing a rule kept the network from: the sword not back enough for the gate, or the whiff guard.
		int ao = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		if (mode == Mode.TRAIN && step.teacherTechnique >= 0 && (step.teacherHeads & (1 << ActionSpace.ATTACK)) != 0
				&& step.teacher[ao + 1] > step.teacher[ao] && !ActionSpace.unlocked(keys, ActionSpace.ATTACK)) {
			blockedSwings[step.teacherTechnique][chargeLocked ? 0 : 1]++;
		}
		if (installedPlays) {
			observer.fillBody(installedBody, me, bot, truth.floorY());
			installed.fighter.crosshairOnOpponent(onOpponent());
			int[] chosen = installed.fighter.decide(observer.frame, installedBody);
			System.arraycopy(chosen, 0, action, 0, ActionSpace.HEADS);
			perfB.tick(step.teacherTechnique, step.teacherHeads, step.teacher, action, ActionSpace.ALL_HEADS, facts.damageDealt, facts.damageTaken);
			ruledNow = installed.fighter.ruledHeads();
			traceCrit(1, action[ActionSpace.ATTACK] == 1);
			traceJumpIn(1);
			logHits(1, action[ActionSpace.ATTACK] == 1, truth.botFalling());
			applyAction(installed.fighter.yawRate(), installed.fighter.pitchRate());
			clicked = action[ActionSpace.ATTACK] == 1;
			showBar(truth);
			return;
		}
		if (teacherPlaysA) {
			// The teacher's own keys, as they are; the installed model's camera.
			observer.fillBody(installedBody, me, bot, truth.floorY());
			installed.fighter.crosshairOnOpponent(onOpponent());
			int[] chosen = installed.fighter.decide(observer.frame, installedBody);
			System.arraycopy(chosen, 0, action, 0, ActionSpace.HEADS);
			dev.swordai.core.pvp.Keys k = teacher.keys();
			boolean techniqueMoment = step.teacherHeads != 0 && step.teacherTechnique != Technique.BASE.ordinal();
			// In the overlay the ordinary fight keeps the installed model's own keys; the technique moments are the teacher's.
			if (!overlay || techniqueMoment) {
				action[ActionSpace.FORWARD] = k.forward > 0 ? 1 : k.forward < 0 ? 2 : 0;
				action[ActionSpace.STRAFE] = k.strafe < 0 ? 1 : k.strafe > 0 ? 2 : 0;
				action[ActionSpace.SPRINT] = k.sprint && k.forward > 0 ? 1 : 0;
				action[ActionSpace.JUMP] = k.jump ? 1 : 0;
				action[ActionSpace.ATTACK] = k.attack && onOpponent() && ActionSpace.unlocked(keys, ActionSpace.ATTACK) ? 1 : 0;
			}
			perf.tick(step.teacherTechnique, step.teacherHeads, step.teacher, action, ActionSpace.ALL_HEADS, facts.damageDealt, facts.damageTaken);
			ruledNow = 0;
			traceCrit(0, action[ActionSpace.ATTACK] == 1);
			traceJumpIn(0);
			logHits(0, action[ActionSpace.ATTACK] == 1, truth.botFalling());
			applyAction(installed.fighter.yawRate(), installed.fighter.pitchRate());
			clicked = action[ActionSpace.ATTACK] == 1;
			// The fight's clock runs from its first action on (the school's network sets this on its side).
			System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
			hasLastAction = true;
			showBar(truth);
			return;
		}
		step.teacherWeight = 0f;
		step.letGoPull = 0f;
		step.jumpPull = 0f;
		// A teacher with no pull leaves its keys to the installed model (NO_PULL_YIELDS): its target would replace the installed
		// model's on those keys and teach nothing. The technique's moment is still judged on its own keys (perf, below).
		float[] pulls = stages.techPulls();
		int base = Technique.BASE.ordinal();
		boolean yielded = mode == Mode.TRAIN && step.teacherHeads != 0 && !pulled(pulls, step.teacherTechnique);
		// A crit's held swing (waiting for the fall) is not taught (CRIT_HOLD_UNTAUGHT): only its swing when the crit is due.
		int ao2 = ActionSpace.OFFSETS[ActionSpace.ATTACK];
		boolean holdUntaught = mode == Mode.TRAIN && CRIT_HOLD_UNTAUGHT && !yielded && step.teacherTechnique >= 0
				&& CRIT_FAMILY[step.teacherTechnique] && (step.teacherHeads & (1 << ActionSpace.ATTACK)) != 0
				&& step.teacher[ao2] >= step.teacher[ao2 + 1] && !(CRIT_HOLD_IN_AIR && !me.onGround());
		if (yielded || holdUntaught) {
			judgedHeads = step.teacherHeads;
			judgedTechnique = step.teacherTechnique;
			System.arraycopy(step.teacher, 0, judgedTarget, 0, ActionSpace.TOTAL_LOGITS);
			// Its keys only: the technique stays the step's label (what the network is told, what the decider learns).
			step.teacherHeads = yielded ? 0 : step.teacherHeads & ~(1 << ActionSpace.ATTACK);
		}
		boolean judgedApart = yielded || holdUntaught;
		if (mode == Mode.TRAIN && stages.oldPull() > 0f && (installed != null || learner.hasOldTeacher())) {
			if (installed != null) {
				// What the installed model would press here, rules and all: its choice, as a target.
				observer.fillBody(installedBody, me, bot, truth.floorY());
				installed.fighter.crosshairOnOpponent(onOpponent());
				int[] would = installed.fighter.decide(observer.frame, installedBody);
				for (int h = 0; h < ActionSpace.HEADS; h++) {
					int off = ActionSpace.OFFSETS[h];
					int n = ActionSpace.SIZES[h];
					for (int k = 0; k < n; k++) {
						oldProbs[off + k] = k == would[h] ? SHADOW_SURE : (1f - SHADOW_SURE) / (n - 1);
					}
				}
			} else {
				learner.oldChoices(step.obs, oldProbs);
			}
			// The installed model teaches the camera; the body is the file's ordinary fight and techniques (one teacher
			// for it: two that disagree in the same spot teach neither, 24 Oct 01:50).
			int upTo = OVERLAY && step.teacherHeads == 0 ? ActionSpace.HEADS - 1 : ActionSpace.PITCH;
			for (int h = 0; h <= upTo; h++) {
				if ((step.teacherHeads & (1 << h)) == 0) {
					System.arraycopy(oldProbs, ActionSpace.OFFSETS[h], step.teacher, ActionSpace.OFFSETS[h], ActionSpace.SIZES[h]);
				}
			}
			step.teacherWeight = 1f;
		}
		if (mode == Mode.TRAIN && OVERLAY && step.teacherHeads == 0 && pulled(pulls, base)) {
			// The basics teacher: the swing outside the technique moments (see AIR_SWING).
			// The golden rule (K2) holds here too: no knockback hit (sprinting) on one who is falling.
			dev.swordai.core.pvp.Body them = reader.duel.them;
			boolean fallingByTheHit = !them.onGround && them.vy - 0.08 * fightLag < 0.0;
			boolean swing = ActionSpace.unlocked(keys, ActionSpace.ATTACK) && onOpponent()
					&& !(K2_GUARD && fallingByTheHit && reader.duel.me.sprinting && clock.ticksSinceHitDealt > dev.swordai.core.school.SchoolFighter.K2_COMBO_TICKS);
			int off = ActionSpace.OFFSETS[ActionSpace.ATTACK];
			step.teacher[off] = swing ? 0f : 1f;
			step.teacher[off + 1] = swing ? 1f : 0f;
			step.teacherHeads = 1 << ActionSpace.ATTACK;
			step.teacherTechnique = Technique.BASE.ordinal();
		}
		// Not on a K2 moment: its lesson is the swing held back, and a second teacher there would teach both.
		critChanceAtClick = mode == Mode.TRAIN && OVERLAY && critAtClick && facts.reachDist <= 3.0f && onOpponent()
				&& ActionSpace.unlocked(keys, ActionSpace.ATTACK) && step.teacherTechnique != Technique.K2.ordinal();
		if (critChanceAtClick && pulled(pulls, step.teacherTechnique >= 0 ? step.teacherTechnique : base)) {
			// The crit teacher (see CRIT_SWING): over whatever else is taught, a crit that can be made now is made.
			int off = ActionSpace.OFFSETS[ActionSpace.ATTACK];
			step.teacher[off] = 0f;
			step.teacher[off + 1] = 1f;
			step.teacherHeads |= 1 << ActionSpace.ATTACK;
			if (step.teacherTechnique < 0) {
				step.teacherTechnique = Technique.BASE.ordinal();
			}
			critChances++;
		}
		// Finding them: off the screen or well off the crosshair, the camera is turned straight toward where they are. In the
		// user's real fights (24 Oct 22:05) a player who jumped out of its view was lost, the camera shaking left and right and
		// never turning back; the bots of the school hardly ever leave its view, so nothing taught it.
		// Off the screen, by what it can know (the user, 24 Oct 22:58: "aim practice needs to let it learn so that it knows
		// where to look when opponent is not in sight"): turned hard toward the side they were last seen on, the same way
		// until they are seen again, the camera levelled. Straight toward where they really are (22:13-23:00), the way to
		// turn flipped from tick to tick with them behind it, and nothing it sees said which: taught to shake left and right.
		if (facts.enemyVisible) {
			sweep = 0;
			if (Math.abs(facts.yawErrDeg) > 1f) {
				seenSide = facts.yawErrDeg > 0f ? 1 : -1;
			}
		}
		if (mode == Mode.TRAIN && OVERLAY && !onOpponent() && pulled(pulls, step.teacherTechnique >= 0 ? step.teacherTechnique : base)) {
			double dx = bot.getX() - me.getX();
			double dz = bot.getZ() - me.getZ();
			double dy = bot.getY() + bot.getBbHeight() * 0.5 - me.getEyeY();
			float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			float yawOff = (wantYaw - me.getYRot()) % 360f;
			yawOff = yawOff > 180f ? yawOff - 360f : yawOff < -180f ? yawOff + 360f : yawOff;
			if (!facts.enemyVisible || Math.abs(yawOff) > 25f) {
				if (!facts.enemyVisible) {
					if (sweep == 0) {
						sweep = seenSide != 0 ? seenSide : 1;
					}
					nearestBin(step.teacher, ActionSpace.YAW, ActionSpace.YAW_BINS, sweep * 180f);
					nearestBin(step.teacher, ActionSpace.PITCH, ActionSpace.PITCH_BINS, -me.getXRot());
					findBlind++;
				} else {
					nearestBin(step.teacher, ActionSpace.YAW, ActionSpace.YAW_BINS, yawOff);
					nearestBin(step.teacher, ActionSpace.PITCH, ActionSpace.PITCH_BINS, wantPitch - me.getXRot());
				}
				step.teacherHeads |= (1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH);
				if (step.teacherTechnique < 0) {
					step.teacherTechnique = Technique.BASE.ordinal();
				}
				findAim++;
			}
		}
		// The punish crit teacher (B1: "their hit knocks you up; you crit them on the way down"). Of the lifts after their
		// sprint hits, a third came down in reach, most with the sword back, and all but one still sprinting (24 Oct 16:15):
		// near them with the sword coming back, W and the sprint key are let go on the way, so that no sprint is left when
		// the crit can be made. S held stays (it ends the sprint as well); the K2 moment's own lesson stays its own.
		// By its own sprint as the client shows it (what the network sees and the B1 teacher goes by), not the server's a tick
		// behind: on that tick the two teachers asked W off and W on, and B1 was off on W/S up to a third of its ticks (20:50).
		// From the lift on (LET_GO_REACH, LET_GO_CHARGE): taught only within 3.5 blocks and the sword half back until 26 Oct 18:10,
		// the let-go came too late, and the sprint ends a tick after W: 17:09-17:50, of 457 falls in reach with the sword back
		// after their sprint hits, 263 were still sprinting (W let go on most of them, a tick late).
		if (mode == Mode.TRAIN && OVERLAY && liftedFor >= 0 && !me.onGround() && me.isSprinting() && facts.reachDist <= LET_GO_REACH
				&& (LET_GO_PULL > 0f || pulled(pulls, Technique.B1.ordinal()))
				&& charge > LET_GO_CHARGE && step.teacherTechnique != Technique.K2.ordinal()) {
			int f = ActionSpace.OFFSETS[ActionSpace.FORWARD];
			boolean back = (step.teacherHeads & (1 << ActionSpace.FORWARD)) != 0 && step.teacher[f + 2] > Math.max(step.teacher[f], step.teacher[f + 1]);
			if (!back) {
				// S, not W let go of: W pressed again within the double-tap time starts the sprint again (CritTrade#LET_GO).
				step.teacher[f] = 0f;
				step.teacher[f + 1] = 0f;
				step.teacher[f + 2] = 1f;
			}
			int sp = ActionSpace.OFFSETS[ActionSpace.SPRINT];
			step.teacher[sp] = 1f;
			step.teacher[sp + 1] = 0f;
			step.teacherHeads |= (1 << ActionSpace.FORWARD) | (1 << ActionSpace.SPRINT);
			if (LET_GO_PULL > 0f) {
				// Its own pull on these two keys (SchoolPpo#teach); the step keeps the ordinary fight's label, so the installed
				// model still teaches the other keys here.
				step.letGoPull = LET_GO_PULL;
				if (step.teacherTechnique < 0) {
					step.teacherTechnique = Technique.BASE.ordinal();
				}
			} else if (step.teacherTechnique < 0 || step.teacherTechnique == Technique.BASE.ordinal()) {
				step.teacherTechnique = Technique.B1.ordinal();
			}
			sprintLetGo++;
		}
		// The jump at every moment as the file's teacher presses it, held back as much as pressed (SchoolStages#JUMP_EVERYWHERE).
		if (dev.swordai.core.school.SchoolStages.JUMP_EVERYWHERE && mode == Mode.TRAIN && review < 0
				&& dev.swordai.core.school.SchoolStages.runTaught(Technique.T9.ordinal())) {
			int jo = ActionSpace.OFFSETS[ActionSpace.JUMP];
			boolean jump = teacher.keys().jump;
			step.teacher[jo] = jump ? 0f : 1f;
			step.teacher[jo + 1] = jump ? 1f : 0f;
			step.teacherHeads |= 1 << ActionSpace.JUMP;
			step.jumpPull = dev.swordai.core.school.SchoolStages.JUMP_PULL;
			if (step.teacherTechnique < 0) {
				step.teacherTechnique = Technique.BASE.ordinal();
			}
		}
		// The punish crit's aim: coming down onto them after their hit's lift, the camera turned onto their body. Of the falls
		// in reach with the sword back and no sprint, the crosshair was on them on 0 to 2 of 3 to 7 an update (24 Oct 20:15),
		// and nothing but the installed model, its pull fading, taught the camera there. Taught, not judged: the moment is
		// judged on its keys as before.
		// And every crit's fall of the crit family (24 Oct 20:40, the user: the double crit around them at 14%, off on the swing
		// a quarter of its ticks: going around them in the air the crosshair comes off them).
		boolean critFall = step.teacherTechnique == Technique.T1.ordinal() || step.teacherTechnique == Technique.T1A.ordinal()
				|| step.teacherTechnique == Technique.T1B.ordinal() || step.teacherTechnique == Technique.T1C.ordinal()
				|| step.teacherTechnique == Technique.T2.ordinal() || step.teacherTechnique == Technique.C6.ordinal();
		if (LIFT_AIM && mode == Mode.TRAIN && OVERLAY && (liftedFor >= 0 || critFall) && facts.falling && !me.onGround() && facts.reachDist <= 3.5f
				&& pulled(pulls, step.teacherTechnique >= 0 && step.teacherTechnique != base ? step.teacherTechnique : Technique.B1.ordinal())
				&& charge > 0.5f && step.teacherTechnique != Technique.K2.ordinal()) {
			double dx = bot.getX() - me.getX();
			double dz = bot.getZ() - me.getZ();
			double dy = bot.getY() + bot.getBbHeight() * 0.5 - me.getEyeY();
			float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			float yawOff = (wantYaw - me.getYRot()) % 360f;
			yawOff = yawOff > 180f ? yawOff - 360f : yawOff < -180f ? yawOff + 360f : yawOff;
			nearestBin(step.teacher, ActionSpace.YAW, ActionSpace.YAW_BINS, yawOff);
			nearestBin(step.teacher, ActionSpace.PITCH, ActionSpace.PITCH_BINS, wantPitch - me.getXRot());
			step.teacherHeads |= (1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH);
			if (step.teacherTechnique < 0 || step.teacherTechnique == Technique.BASE.ordinal()) {
				step.teacherTechnique = Technique.B1.ordinal();
			}
			liftAim++;
		}
		// Out of view and far, a fight's steps teach nothing; finding them again is what the tracking review is for.
		step.policyWeight = review < 0 && !facts.enemyVisible && facts.reachDist > SKIP_FARTHER ? 0f : 1f;
		// Run 44: the 2 -> 3 moments weigh more in PPO (POST_HIT_WEIGHT).
		if (step.policyWeight > 0f && mode == Mode.TRAIN && rewardRow >= 2 && lastOwnHitAt > -1000 && fightTicks - lastOwnHitAt <= POST_HIT_TICKS) {
			step.policyWeight = POST_HIT_WEIGHT;
		}
		step.botTechnique = botTechnique;
		step.botKeys = truth.botKeys();
		step.kind = review >= 0 ? -1 : fightBot;
		dev.swordai.core.pvp.Body them = reader.duel.them;
		// And on every moment the teacher calls K2 (its sprint key may be about to be pressed): the swing it holds back is the
		// rule's, not taught, so that "don't swing" cannot spread from it (the 08:22 check).
		// Never in its own combo (SchoolFighter.K2_COMBO_TICKS): there the K2 moment teaches nothing either, the damage decides.
		boolean inCombo = clock.ticksSinceHitDealt <= dev.swordai.core.school.SchoolFighter.K2_COMBO_TICKS;
		if (inCombo && step.teacherTechnique == Technique.K2.ordinal()) {
			step.teacherHeads &= ~(1 << ActionSpace.ATTACK);
		}
		if (them.onGround || !JUMP_IN_HOLD_THROUGH_HIT && clock.ticksSinceHitTaken == 0) {
			jumpInAir = false;
		} else if (them.sinceJump == 0 && reader.duel.theyClose > 0.05 && reader.duel.flat < 6.5) {
			jumpInAir = true;
		}
		boolean k2Hold = JUMP_IN_GUARD_TRAINING && (JUMP_IN_HOLD_THROUGH_HIT || !inCombo) && jumpInAir && them.sinceJump > JUMP_IN_SWING_TICKS
				&& !(JUMP_IN_HOLD_THROUGH_HIT && critSwingNow(me))
				|| K2_GUARD && !inCombo && ((me.isSprinting() || reader.duel.me.sprinting)
				&& dev.swordai.core.pvp.PvpPlayer.fallingByTheHit(them, fightLag + K2_MARGIN) || step.teacherTechnique == Technique.K2.ordinal());
		if (k2Hold) {
			keys &= ~ActionSpace.mask(ActionSpace.ATTACK);
			// Neither "swing" nor "don't" is taught on a tick the rule holds.
			step.teacherHeads &= ~(1 << ActionSpace.ATTACK);
			k2Held++;
		}
		boolean installedDecided = step.teacherWeight > 0f && installed != null;
		int ruled = 0;
		// The heads the rules press on this tick (or would, in a fight played without them): the target of RULED_PULL.
		int ruledTaught = 0;
		benchRules = 0;
		if (RULE_OVERLAY && installed != null && (review < 0 || basics[review].bot.attacks) && !(ruleOff && mode == Mode.CHECK)) {
			if (!installedDecided) {
				observer.fillBody(installedBody, me, bot, truth.floorY());
				installed.fighter.crosshairOnOpponent(onOpponent());
				installed.fighter.decide(observer.frame, installedBody);
				installedDecided = true;
			}
			// For a benchmark episode's file: the rules that had keys, as a real fight with the rules on records them (FightSession).
			benchRules = ruleOff ? 0 : installed.fighter.rules();
			// Only heads still open: a rule's swing waits for the sword, the crosshair and the golden rule like the network's.
			ruled = installed.fighter.ruledHeads() & keys;
			int ruledRaw = ruled;
			if (RULES_LET_GO_IN_LIFT && liftedFor >= 0 && !me.onGround() && facts.reachDist <= 3.5f && charge > 0.5f) {
				// Lifted by their hit and coming down near them: W and the sprint are the network's (RULES_LET_GO_IN_LIFT).
				ruled &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
			}
			// Told the punish crit in a lift, the keys are the network's: in the punish drill always (it learns them there; with the
			// rules' keys the drill taught nothing), elsewhere only with PUNISH_TOLD_YIELD (from 23:06 to 00:10 on 27 Oct everywhere,
			// when an untrained network critted 4% of its told lifts to the rules' 23%).
			// In the drill from the start of the scene (B1_HOLD, the swing held for their hit), elsewhere for the lift.
			boolean drill = punishScene() || drillFight;
			boolean drillKeys = drill || dev.swordai.core.school.SchoolStages.PUNISH_TOLD_YIELD;
			if (drillKeys && (drill || liftedFor >= 0 && !me.onGround()) && learner.told() == Technique.B1) {
				ruled &= ~dev.swordai.core.school.SchoolStages.PUNISH_KEYS;
			}
			if (RULES_LET_GO_AT_CRIT && liftedFor >= 0 && facts.falling && !me.onGround() && facts.reachDist <= LET_GO_AT_CRIT_REACH
					&& charge >= LET_GO_AT_CRIT_CHARGE) {
				// The punish crit's own ticks: W and the sprint are the network's (RULES_LET_GO_AT_CRIT).
				ruled &= ~ActionSpace.mask(ActionSpace.FORWARD, ActionSpace.SPRINT);
			}
			if (dev.swordai.core.school.SchoolStages.RULES_YIELD && clock.ticksSinceHitTaken > dev.swordai.core.school.SchoolStages.YIELD_AFTER_HIT) {
				// The rules give way to a technique being learned, on its keys: in training where its teacher teaches them, in
				// a check (as in a real fight) while the decider has chosen it (SchoolStages#runTaught).
				int body = ActionSpace.ALL_HEADS & ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
				int give = mode == Mode.TRAIN ? step.teacherHeads & body
						: dev.swordai.core.school.SchoolStages.runTaught(learner.told().ordinal()) ? body : 0;
				ruled &= ~give;
			}
			// A fight played without the rules: the network presses every key itself; the installed model's keys are only the target. Since run
			// 30g that is every open body key, not only the ones a rule pressed: the screens' hit log (14:24) has the network alone jumping 79-87%
			// of the ticks of their combos' hits against the installed model's 94-96%, pressing S 13-18% against 3-4%, and hit in the air 244-376
			// times against 67, which is the keys no rule presses, where its copying target was the frozen run 6 network's chances (ANCHOR_TO_BEST),
			// not the installed model's own network.
			// Run 47c: no copying pull in a replay. The replays are the failures of the rules and the network together, and the pull
			// to the installed model's keys (RULED_PULL) dragged it back to the answer that failed there; a replay learns by its reward.
			ruledTaught = ruleOff ? (sceneNow != null && mode == Mode.TRAIN ? 0 : keys & ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH)) : ruled;
			if (ruleOff) {
				ruled = 0;
			}
			keys &= ~ruled;
		}
		// The body's keys only (run 30b): the camera is a fine choice among 15 and 11 bins (the aim rule picks one from the camera's
		// push), and in the first updates of run 30b it was 53% of the rules' head-ticks at cross-entropy 3.4 to 5.1 and 20%
		// agreement, while the network without the rules already lands 97% of its swings to the installed model's 89%.
		int bodyKeys = ActionSpace.ALL_HEADS & ~ActionSpace.mask(ActionSpace.YAW, ActionSpace.PITCH);
		step.ruledHeads = mode == Mode.TRAIN && review < 0 ? ruledTaught & bodyKeys & ~step.teacherHeads : 0;
		step.ruleBits = step.ruledHeads != 0 ? installed.fighter.rules() : 0;
		if (step.ruledHeads != 0) {
			// The rule's key, a sure target on each head it pressed (SchoolPpo#teach with Settings#ruledPull).
			int[] rule = installed.fighter.action();
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if ((step.ruledHeads & (1 << h)) != 0) {
					int off = ActionSpace.OFFSETS[h];
					for (int k = 0; k < ActionSpace.SIZES[h]; k++) {
						step.teacher[off + k] = k == rule[h] ? 1f : 0f;
					}
				}
			}
		}
		if (ruled != 0) {
			// The rule's keys first: the network's jump and swing are chosen knowing them (SchoolNet#sample).
			int[] rule = installed.fighter.action();
			for (int h = 0; h < ActionSpace.HEADS; h++) {
				if ((ruled & (1 << h)) != 0) {
					action[h] = rule[h];
				}
			}
			ruleTicks++;
		}
		// Told the punish crit in a lift, its teacher's swing is taught though a rule presses it (SchoolStages#PUNISH_SHADOW).
		step.shadowHeads = dev.swordai.core.school.SchoolStages.PUNISH_SHADOW && mode == Mode.TRAIN && liftedFor >= 0 && !me.onGround()
				&& learner.told() == Technique.B1 && step.teacherTechnique == Technique.B1.ordinal()
				&& (step.teacherHeads & ActionSpace.mask(ActionSpace.ATTACK)) != 0
				? ruled & dev.swordai.core.school.SchoolStages.PUNISH_SHADOW_KEYS : 0;
		learner.act(step, keys, ruled, mode == Mode.TRAIN, cfg.playTemperature, action);
		// Run 43: a take-off on the ground by the network (not a rule's jump) marks the air until it stands again.
		if (me.onGround()) {
			ownJumpAir = action[ActionSpace.JUMP] == 1 && (ruled & (1 << ActionSpace.JUMP)) == 0;
		}
		if (mode == Mode.CHECK) {
			fairJump.apply(action, me.onGround(), clock.ticksSinceHitTaken, clock.takenStreak.hits());
		}
		// Judged on the keys: the camera a teacher shows (the punish crit's aim) is taught, not counted for done.
		// Not in a fight played without the rules (run 30): there it is judged on keys the rules pressed in the checks, and the
		// technique bars, the crit and jump-in lines would mix two ways of playing. Its swings and damage have their own line.
		if (ruleOff && mode == Mode.TRAIN) {
			// nothing counted
		} else if (judgedApart) {
			perf.tick(judgedTechnique, judgedHeads & ~((1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH)), judgedTarget, action, keys,
					facts.damageDealt, facts.damageTaken);
		} else {
			perf.tick(step.teacherTechnique, step.teacherHeads & ~((1 << ActionSpace.YAW) | (1 << ActionSpace.PITCH)), step.teacher, action, keys,
					facts.damageDealt, facts.damageTaken);
		}
		if (mode == Mode.TRAIN && !ruleOff) {
			techniqueReward();
		}
		ruledNow = ruled;
		// Run 48f (7 Nov 18:50): and in every replay. traceJumpIn keeps the replay's own result (its crit, a K2, a hit taken, an
		// answer); skipped in rule-off fights, so since 47c (every replay rule-off) no punish replay could be won (0 of 659, 13
		// of them with a real crit) and every jump-in replay counted as won.
		if (!(ruleOff && mode == Mode.TRAIN) || sceneNow != null) {
			traceCrit(0, action[ActionSpace.ATTACK] == 1);
			traceJumpIn(0);
			logHits(0, action[ActionSpace.ATTACK] == 1, truth.botFalling());
		}
		if (step.teacherHeads != 0 && !ruleOff) {
			fightTeacherTicks++;
			int t = step.teacherTechnique;
			spoke[t]++;
			if (agrees(learner.lastLogits(), step.teacher, step.teacherHeads)) {
				agreed[t]++;
			}
		}
		motor.step(ActionSpace.yawDegrees(action), ActionSpace.pitchDegrees(action));
		applyAction(motor.yawRate(), motor.pitchRate());
		if (benchNow >= 0) {
			benchTick(me, bot, truth, action);
		}
		if (installedDecided) {
			// The installed model watched: it goes on from what was really pressed (see Fighter#pressed).
			installed.fighter.pressed(action, motor);
		}
		clicked = action[ActionSpace.ATTACK] == 1;
		if (liftAimedNow && clicked) {
			liftStage = Math.max(liftStage, 5);
		}
		traceDrillLift(me, truth, action, ruled);
		System.arraycopy(action, 0, lastAction, 0, ActionSpace.HEADS);
		hasLastAction = true;
		showBar(truth);
	}

	/** What it is doing, on the screen above the hotbar: the stage, the technique whose moment it is, its keys, the bot's. */
	private void showBar(Truth truth) {
		if (++barTicks < 4 || mc.gui == null) {
			return;
		}
		barTicks = 0;
		int t = step.teacherTechnique;
		String now = t >= 0 ? Technique.ALL[t].id + " " + Technique.ALL[t].title : "ordinary fight";
		int bt = truth.botTechnique();
		String bot = bt >= 0 && bt != Technique.BASE.ordinal() ? Technique.ALL[bt].id + " " + Technique.ALL[bt].title : "ordinary fight";
		String who = mode == Mode.CHECK ? (installedPlays ? "CHECK B (installed)" : teacherPlaysA ? "CHECK A (teacher)" : "CHECK A (school)")
				: "School " + stages.current().name;
		// The decider's choice, and what the network was told (the teacher's technique in most training fights).
		String decider = installedPlays || teacherPlaysA ? "" : " | decider: " + learner.chosen().id
				+ (learner.told() != learner.chosen() ? ", told " + learner.told().id : "");
		mc.gui.setOverlayMessage(Component.literal(String.format(Locale.ROOT, "%s | moment: %s%s | AI keys: %s | %s: %s | %.0f-%.0f",
				who, now, decider, keysOf(installedPlays && !teacherPlaysA ? installed.fighter.action() : action),
				review >= 0 ? "review " + basics[review].name : Specialists.NAMES[fightBot], bot,
				fightDealt, fightTaken)), false);
	}

	/** What this step earns for doing the technique it was told (see {@link #IMITATE}); paid with the step's reward. */
	private void techniqueReward() {
		if (pullsNow == null) {
			pullsNow = stages.techPulls();
		}
		boolean told = learner.toldTheTeachers();
		int t = perf.lastTechnique;
		if (t >= 0 && t != lastMomentTechnique && review < 0) {
			stages.sawMoment(fightBot, t);
		}
		lastMomentTechnique = t;
		// Learned from the fight (SchoolStages#FROM_THE_FIGHT): pressing the teacher's keys pays nothing; the game's results do.
		boolean fromTheFight = dev.swordai.core.school.SchoolStages.FROM_THE_FIGHT;
		if (told && t >= 0 && perf.lastMatch >= 0f && !fromTheFight) {
			shaping += IMITATE * perf.lastMatch * Math.min(SHARE_MOST, pullsNow[t]);
		}
		for (int i = 0; i < Technique.COUNT; i++) {
			int paid = perf.takePaid(i);
			int won = perf.takeWon(i);
			if (told && paid + won > 0 && i != Technique.BASE.ordinal()) {
				shaping += fromTheFight ? 0f : (DONE_BONUS * paid + WON_BONUS * won) * Math.min(SHARE_MOST, pullsNow[i]);
				momentsPaid += paid;
				momentsWon += won;
			}
		}
	}

	/** The facts the old stages' rewards score that a fight of the school does not fill (as the Trainer fills them). */
	private void fillReviewFacts(Truth truth, LocalPlayer me) {
		int hop = truth.hopId();
		facts.discontinuity = hop != reviewHop;
		reviewHop = hop;
		facts.serverSprinting = truth.agentSprinting();
		facts.clicked = clicked;
		facts.clickOnTarget = clicked && clickOnTarget;
		facts.chargeAtClick = chargeAtClick;
		facts.charge = me.getAttackStrengthScale(0f);
		facts.forwardAxis = ActionSpace.forwardAxis(lastAction);
		facts.strafeAxis = ActionSpace.strafeAxis(lastAction);
		facts.sprintKey = lastAction[ActionSpace.SPRINT] == 1;
		facts.yawTurnDeg = turnYawThisTick;
		facts.pitchTurnDeg = turnPitchThisTick;
		facts.agentPitchDeg = me.getXRot();
		facts.jumpKey = lastAction[ActionSpace.JUMP] == 1;
	}

	/**
	 * " Horizontal tracking 3 (on them 62%, ...);" for each basic played; a basic with PASS_STEPS steps gathered is judged
	 * against its stage's bars and starts again.
	 */
	private String reviewLine() {
		StringBuilder b = new StringBuilder(basicsDone ? " [basics done: reviewed]" : " [basics first]");
		for (int i = 0; i < reviewTally.length; i++) {
			Tally t = reviewTally[i];
			if (t == null || t.steps == 0) {
				continue;
			}
			b.append(String.format(Locale.ROOT, " %s %d (on them %.0f%%, off by %.1f deg, in view %.0f%%, found again in %.0f ticks, lost %d/%d",
					basics[i].name, reviewFights[i], 100f * t.onTarget(), t.aimError(), 100f * t.visible(), t.meanReacquire(),
					t.lostEpisodes, t.episodes));
			if (t.clicks > 0) {
				b.append(String.format(Locale.ROOT, ", swings on them %.0f%%", 100f * t.clickAccuracy()));
			}
			if (t.longCombosTaken > 0) {
				b.append(String.format(Locale.ROOT, ", combos got out of %d/%d (backed out and hit back), their combo stopped %.0f%% of hits",
						t.comboEscapes, t.longCombosTaken, 100f * t.outRate()));
			}
			if (basics[i].bot.attacks) {
				b.append(String.format(Locale.ROOT, ", crits taken %d, knockouts %d-%d", t.critsTaken, t.wins, t.losses));
			}
			b.append(String.format(Locale.ROOT, ", damage %.0f/%.0f", t.damageDealt, t.damageTaken));
			if (t.steps < PASS_STEPS) {
				b.append(String.format(Locale.ROOT, ", %d of %d steps to its judgement);", t.steps, PASS_STEPS));
				continue;
			}
			String blocker = basicBlocker(basics[i], t);
			basicOnTarget[i] = t.onTarget();
			basicPassed[i] = blocker == null;
			boolean first = basicPassed[i] && !basicPassedOnce[i];
			basicPassedOnce[i] |= basicPassed[i];
			if (first) {
				saveBasics();
			}
			basicFailed[i] = basicPassed[i] ? 0 : basicFailed[i] + 1;
			b.append(basicPassed[i] ? ", PASSED);" : ", not passed: " + blocker + ");");
			reviewTally[i] = new Tally();
			reviewFights[i] = 0;
		}
		if (!basicsDone) {
			boolean all = true;
			for (int i = 0; i < basics.length; i++) {
				all &= basicPassedOnce[i] || basicFailed[i] >= PASS_TRIES;
			}
			if (all) {
				basicsDone = true;
				saveBasics();
				b.append(" ALL BASICS PASSED: from here on they are reviewed in a quarter of the fights;");
			}
		}
		return b.toString();
	}

	/** The basics: the old curriculum's first stages, the hit timing against one that stands and one that moves and jumps. */
	private static StageSpec[] basicStages() {
		List<StageSpec> old = Stages.defaults();
		StageSpec standing = Stages.defaults().get(4);
		standing.name = "Hit timing: standing";
		standing.bot.movement = dev.swordai.core.curriculum.BotProfile.STAND;
		standing.bot.jumpChance = 0f;
		// Somewhere else every six seconds, so that it has to be walked up to again.
		standing.bot.hopIntervalTicks = 120;
		StageSpec jumping = Stages.defaults().get(4);
		jumping.name = "Hit timing: moving and jumping";
		jumping.bot.jumpChance = 0.08f;
		// Tracking as a player is lost in a fight: close, sprinting sideways round it and jumping, never put somewhere else,
		// so that it leaves the screen at an edge (or over the top) and is to be turned after.
		StageSpec close = Stages.defaults().get(2);
		close.name = "Tracking: close, round and jumping";
		close.agentMaxPillar = 0;
		close.bot.movement = dev.swordai.core.curriculum.BotProfile.STRAFE;
		close.bot.sprint = true;
		close.bot.minDistance = 1.5f;
		close.bot.maxDistance = 3.2f;
		close.bot.maxBearingDeg = 175f;
		close.bot.maxPillar = 0;
		close.bot.jumpChance = 0.06f;
		close.bot.hopIntervalTicks = 0;
		close.bot.farChance = 0f;
		return new StageSpec[] {old.get(0), old.get(1), old.get(2), old.get(3), standing, jumping, old.get(5), old.get(6), close,
				fightBasic("Fight: times its crit on your rhythm"), fightBasic("Fight: always jumps in to crit"), fightBasic("Fight: the spacer")};
	}

	/**
	 * The old curriculum's last opponents as basics (the user, 24 Oct 23:05: "it must escape any combo and counter any timed
	 * crit, that opponent hit you with crit when you sprint hit them ... and also the best way is to not be timed"): the duel
	 * stage's rewards (damage, crits and combos taken), to the knockout; who the opponent is, is drawn per fight (dressFight).
	 */
	private static StageSpec fightBasic(String name) {
		StageSpec st = Stages.defaults().get(8);
		st.name = name;
		st.bot.movement = dev.swordai.core.curriculum.BotProfile.FIGHT;
		st.bot.fixedCopy = false;
		return st;
	}

	/** The opponent of a fight basic for one fight, as the old trainer had them (Trainer: KIND_TIMER, KIND_JUMPIN, KIND_SPACER). */
	private static void dressFight(dev.swordai.core.curriculum.BotProfile b, int basic, Random rng) {
		b.movement = dev.swordai.core.curriculum.BotProfile.FIGHT;
		b.sprint = true;
		b.rushChance = 0f;
		b.dodgeChance = 0f;
		b.fleeChance = 0f;
		b.jumpChance = 0f;
		b.antic = "";
		b.turnRateDeg = 60f;
		b.followUpTicks = 0;
		b.followUpReach = 0f;
		b.followUpWhiff = 0f;
		b.swingSlip = 0f;
		b.farChance = 0f;
		b.maxPillar = 0;
		b.minBearingDeg = 0f;
		b.maxBearingDeg = dev.swordai.core.curriculum.BotProfile.DRILL_BEARING_DEG;
		if (basic == TIMER) {
			// It finds the agent's rhythm (it swings the moment its sword is back) and times its crit to land as the agent's
			// sword is not back yet, or as it comes in; hit, it punish-crits. The user's friend who beat it on 18 October.
			b.timer = true;
			b.punish = true;
			b.jumpResetChance = 0.8f + 0.2f * rng.nextFloat();
			b.critChance = 1f;
			b.critPress = 0f;
			b.wtap = true;
			b.reactionTicks = rng.nextInt(2);
			b.aimNoiseDeg = 0.5f + rng.nextFloat();
			b.attackChargeMin = 0.92f;
			b.preferredRange = 3.4f;
			b.minDistance = dev.swordai.core.curriculum.BotProfile.DRILL_MIN_DISTANCE;
			b.maxDistance = dev.swordai.core.curriculum.BotProfile.DRILL_MAX_DISTANCE;
		} else if (basic == JUMP_IN) {
			// Every attack a jump in and a crit on the way down, from right in front to a running jump's farthest.
			b.jumpIn = true;
			b.jumpInAlways = true;
			b.critChance = 1f;
			b.critPress = 1f;
			b.punish = false;
			b.wtap = false;
			b.jumpResetChance = 0f;
			b.reactionTicks = 0;
			b.aimNoiseDeg = 0.5f + rng.nextFloat();
			b.attackChargeMin = 0.95f;
			b.preferredRange = 2.9f;
			b.jumpInFrom = 1.8f + 3.8f * rng.nextFloat();
			b.minDistance = 2.5f;
			b.maxDistance = 6.5f;
		} else {
			// At the edge of its reach: crits from there and backs out with S, or stands and hits whatever comes into reach.
			b.spacer = true;
			int style = rng.nextInt(3);
			b.critChance = style == 0 ? 0f : style == 1 ? 1f : 0.3f + 0.4f * rng.nextFloat();
			b.critPress = 0f;
			b.wtap = false;
			b.reactionTicks = 0;
			b.aimNoiseDeg = 0.3f + 0.7f * rng.nextFloat();
			b.attackChargeMin = 0.92f + 0.08f * rng.nextFloat();
			b.jumpResetChance = 0.5f * rng.nextFloat();
			b.minDistance = 3.5f;
			b.maxDistance = 6f;
		}
		if (rng.nextFloat() < BESIDE_SHARE) {
			// Close by and out of the picture: off to a side, or behind (to be found first).
			b.minBearingDeg = 70f;
			b.maxBearingDeg = 180f;
			b.minDistance = 1.8f;
			b.maxDistance = 3.0f;
		}
	}

	/** Which basic the next training fight is, or -1 for a fight of the school. The ones not passed three times as often. */
	private int drawBasic() {
		// Before they are all passed, the more of them passed the fewer: the techniques lost ground at 80% (24 Oct 23:20: T4
		// 93 -> 72, T10 91 -> 74, T6a 100 -> 74, mostly on the swing), and the user: "MUST not let it lose its skill".
		int open = 0;
		for (boolean passed : basicPassedOnce) {
			open += passed ? 0 : 1;
		}
		float share = basicsDone ? REVIEW_SHARE : BASICS_SHARE_LEAST + (BASICS_SHARE - BASICS_SHARE_LEAST) * open / basics.length;
		if (mode != Mode.TRAIN || rng.nextFloat() >= share) {
			return -1;
		}
		float[] w = new float[basics.length];
		float total = 0f;
		for (int i = 0; i < basics.length; i++) {
			// Not passed: six times as often; a camera or footwork basic passed but with the crosshair on them under 80%, twice
			// (the user, 24 Oct 23:40: "basic skill below 80% should be heavily focused").
			boolean low = !ActionSpace.unlocked(basics[i].headMask, ActionSpace.ATTACK) && basicOnTarget[i] < 0.9f; // 0.8 until 26 Oct 11:10 (the SOP: every skill 90%)
			w[i] = (basicsDone ? REVIEW_WEIGHT[i] : 1f) * (basicPassed[i] ? 1f : 6f) * (low ? 2f : 1f);
			total += w[i];
		}
		float u = rng.nextFloat() * total;
		for (int i = 0; i < basics.length; i++) {
			u -= w[i];
			if (u < 0f) {
				return i;
			}
		}
		return basics.length - 1;
	}

	/** Why a basic's steps do not pass its stage's bars (the ones that can be told from the steps alone), or null. */
	private static String basicBlocker(StageSpec st, Tally t) {
		dev.swordai.core.curriculum.Promotion p = st.promote;
		// Against one that hits: more damage dealt than taken, whatever else.
		if (st.bot.attacks && t.damageDealt < t.damageTaken) {
			return String.format(Locale.ROOT, "damage dealt %.0f < taken %.0f", t.damageDealt, t.damageTaken);
		}
		if (p.minOnTarget > 0f && t.onTarget() < p.minOnTarget) {
			return String.format(Locale.ROOT, "on them %.0f%% < %.0f%%", 100f * t.onTarget(), 100f * p.minOnTarget);
		}
		if (p.minStill > 0f && t.still() < p.minStill) {
			return String.format(Locale.ROOT, "steady aim %.0f%% < %.0f%%", 100f * t.still(), 100f * p.minStill);
		}
		if (p.maxAimError > 0f && t.aimError() > p.maxAimError) {
			return String.format(Locale.ROOT, "off by %.1f deg > %.1f", t.aimError(), p.maxAimError);
		}
		if (p.minInRange > 0f && t.inRange() < p.minInRange) {
			return String.format(Locale.ROOT, "in reach %.0f%% < %.0f%%", 100f * t.inRange(), 100f * p.minInRange);
		}
		float swingsOn = Math.max(p.minClickAccuracy, MIN_SWINGS_ON);
		if (t.clicks >= 20 && t.clickAccuracy() < swingsOn) {
			return String.format(Locale.ROOT, "swings on them %.0f%% < %.0f%%", 100f * t.clickAccuracy(), 100f * swingsOn);
		}
		if (p.maxSpamRate > 0f && t.spamRate() > p.maxSpamRate) {
			return String.format(Locale.ROOT, "swings before the sword was back %.0f%% > %.0f%%", 100f * t.spamRate(), 100f * p.maxSpamRate);
		}
		if (p.minFullCharge > 0f && t.fullCharge() < p.minFullCharge) {
			return String.format(Locale.ROOT, "full-charge hits %.0f%% < %.0f%%", 100f * t.fullCharge(), 100f * p.minFullCharge);
		}
		if (p.minHitsPerMinute > 0f && t.hitsPerMinute() < p.minHitsPerMinute) {
			return String.format(Locale.ROOT, "hits a minute %.1f < %.0f", t.hitsPerMinute(), p.minHitsPerMinute);
		}
		if (p.minSprintHits > 0f && t.sprintHitFraction() < p.minSprintHits) {
			return String.format(Locale.ROOT, "sprint hits %.0f%% < %.0f%%", 100f * t.sprintHitFraction(), 100f * p.minSprintHits);
		}
		if (p.minCombo > 0f && t.meanCombo() < p.minCombo) {
			return String.format(Locale.ROOT, "combo %.1f < %.1f", t.meanCombo(), p.minCombo);
		}
		if (p.minTapRate > 0f && t.tapRate() < p.minTapRate) {
			return String.format(Locale.ROOT, "W-taps %.0f%% < %.0f%%", 100f * t.tapRate(), 100f * p.minTapRate);
		}
		if (p.minHitRatio > 0f && t.hitRatio() < p.minHitRatio) {
			return String.format(Locale.ROOT, "hits dealt:taken %.2f < %.2f", t.hitRatio(), p.minHitRatio);
		}
		// Out of their combo is what counts: by S held (C3) or by a jump reset and a hit back (T9), as the file has both.
		boolean gotOut = p.minOutRate > 0f && t.outRate() >= p.minOutRate;
		if (p.minBackRate > 0f && !gotOut && t.backRate() < p.minBackRate) {
			return String.format(Locale.ROOT, "S held in their combo %.0f%% < %.0f%%", 100f * t.backRate(), 100f * p.minBackRate);
		}
		if (p.minOutRate > 0f && t.outRate() < p.minOutRate) {
			return String.format(Locale.ROOT, "out of their combo %.0f%% < %.0f%%", 100f * t.outRate(), 100f * p.minOutRate);
		}
		return null;
	}

	private void loadBasics() {
		try {
			Path f = SwordFiles.schoolDir().resolve("basics.properties");
			if (Files.exists(f)) {
				java.util.Properties p = new java.util.Properties();
				try (var in = Files.newInputStream(f)) {
					p.load(in);
				}
				basicsDone = Boolean.parseBoolean(p.getProperty("done", "false"));
				for (String name : p.getProperty("passed", "").split("\\|")) {
					for (int i = 0; i < basics.length; i++) {
						if (basics[i].name.equals(name.trim())) {
							basicPassedOnce[i] = true;
							basicPassed[i] = true;
						}
					}
				}
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("basics.properties not read", e);
		}
	}

	private void saveBasics() {
		try {
			java.util.Properties p = new java.util.Properties();
			p.setProperty("done", Boolean.toString(basicsDone));
			StringBuilder passed = new StringBuilder();
			for (int i = 0; i < basics.length; i++) {
				if (basicPassedOnce[i]) {
					passed.append(passed.length() == 0 ? "" : "|").append(basics[i].name);
				}
			}
			p.setProperty("passed", passed.toString());
			try (var out = Files.newOutputStream(SwordFiles.schoolDir().resolve("basics.properties"))) {
				p.store(out, "the school's basics (SchoolSession.BASICS_SHARE)");
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("basics.properties not written", e);
		}
	}

	/** "120 swings, 90 with the crosshair on them (75%), 80 hits landed (67%)". */
	private String swingLine(int side) {
		long[] c = swingCount[side];
		return String.format(Locale.ROOT, "%d swings, %d with the crosshair on them (%.0f%%), %d hits landed (%.0f%%); their combos: second hits in a row %d, "
				+ "a third after %d (%.0f%%)", c[0], c[1], 100.0 * c[1] / Math.max(1, c[0]), c[2], 100.0 * c[2] / Math.max(1, c[0]), c[3], c[4],
				100.0 * c[4] / Math.max(1, c[3]));
	}

	private static String keysOf(int[] a) {
		int fwd = a[ActionSpace.FORWARD];
		int side = a[ActionSpace.STRAFE];
		return (fwd == 1 ? "W" : fwd == 2 ? "S" : "-") + (side == 1 ? "A" : side == 2 ? "D" : "-") + (a[ActionSpace.SPRINT] == 1 ? " sprint" : "")
				+ (a[ActionSpace.JUMP] == 1 ? " jump" : "") + (a[ActionSpace.ATTACK] == 1 ? " swing" : "");
	}

	/** What the game showed it did: " jump reset (T9) 3/12 (25%);" for each habit it had chances at. */
	private String ownLine(int side) {
		StringBuilder b = new StringBuilder();
		for (int h = 0; h < Habits.COUNT; h++) {
			if (ownSeen[side][h] > 0) {
				b.append(String.format(Locale.ROOT, " %s %d/%d (%.0f%%);", Habits.NAMES[h], ownDid[side][h], ownSeen[side][h],
						100f * ownDid[side][h] / ownSeen[side][h]));
			}
		}
		return b.toString();
	}

	/** Per technique: " T7 12/30 done, 8 paid;". */
	private static String perfLine(Performance p) {
		StringBuilder b = new StringBuilder();
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (p.moments[i] > 0) {
				b.append(String.format(Locale.ROOT, " %s %d/%d done (%.0f%%), %d paid;", t.id, p.done[i], p.moments[i], 100f * p.done[i] / p.moments[i], p.paid[i]));
			}
		}
		return b.toString();
	}

	/** Whether the network's likeliest key is the teacher's on every head it taught. */
	private static boolean agrees(float[] logits, float[] target, int heads) {
		for (int h = 0; h < ActionSpace.HEADS; h++) {
			if ((heads & (1 << h)) == 0) {
				continue;
			}
			int off = ActionSpace.OFFSETS[h];
			int best = 0;
			for (int k = 1; k < ActionSpace.SIZES[h]; k++) {
				if (logits[off + k] > logits[off + best]) {
					best = k;
				}
			}
			// One of the keys it asks for (either strafe; stop or S, where those are alike).
			if (target[off + best] < Teacher.ANY) {
				return false;
			}
		}
		return true;
	}

	private void applyAction(float yawRate, float pitchRate) {
		LocalPlayer me = mc.player;
		float yawBefore = me.getYRot();
		float pitchBefore = me.getXRot();
		AgentController.apply(mc, installedPlays ? installed.fighter.action() : action, yawRate, pitchRate);
		turnYawThisTick = me.getYRot() - yawBefore;
		turnPitchThisTick = me.getXRot() - pitchBefore;
		actedThisTick = true;
		clickResolved = false;
		clickOnTarget = false;
		if (installedPlays) {
			hasLastAction = true;
		}
	}

	private void endFight(boolean won, boolean lost) {
		// Full fights only (7 Nov, run 48b): a replay without the rules goes on to the replay's own tally below (won, 3 in a row,
		// learned). Since run 30f (1 Nov) about half the replays were played without the rules and returned here uncounted, and
		// since 47c (every replay without the rules) none was counted: the repeated deflection replay was fought ~700 times with
		// no try or win recorded.
		if (mode == Mode.TRAIN && ruleOff && sceneNow == null) {
			// A fight played without the rules (run 30) has its tally and line apart: it is not one of the full fights the bars,
			// the lost-case harvest and the damage curve count, as the network is still learning to press the rules' keys itself.
			ruleOffFights++;
			ruleOffFightsAll++;
			ruleOffDealt += fightDealt;
			ruleOffTaken += fightTaken;
			ruleOffTicks += fightTicks;
			ruleOffWon += won ? 1 : 0;
			ruleOffLost += lost ? 1 : 0;
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school fight %s (without the rules): dealt %.1f, taken %.1f, ticks %d%s",
					sparFight ? sparName() : Specialists.NAMES[fightBot], fightDealt, fightTaken, fightTicks,
					won ? ", knockout" : lost ? ", knocked out" : ""));
			hasLastAction = false;
			clicked = false;
			java.util.Arrays.fill(recentCrits, 0);
			sinceFallingHit = 1000;
			return;
		}
		// Full fights only: a scene starts at a moment it was crit and so loses by design; with them in, the damage curve fell
		// the moment the scenes came in (26 Oct 09:41) while the full fights had not got worse. The scenes have their own line.
		if (review < 0 && sceneNow == null && fightBot >= 0 && fightBot < Specialists.KINDS) {
			botDealt[fightBot] += fightDealt;
			botTaken[fightBot] += fightTaken;
			botTicks[fightBot] += fightTicks;
			botFights[fightBot]++;
			botWins[fightBot] += won ? 1 : 0;
			botLosses[fightBot] += lost ? 1 : 0;
		}
		int side = installedPlays ? 1 : 0;
		for (int h = 0; h < Habits.COUNT; h++) {
			ownSeen[side][h] += reader.own.seen(h);
			ownDid[side][h] += reader.own.did(h);
		}
		if (mode == Mode.TRAIN && review < 0 && fightTicks > 0) {
			// The measured lengths behind sceneChance (run 47b).
			if (sceneNow != null) {
				sceneTicksAvg += 0.02f * (fightTicks - sceneTicksAvg);
			} else if (!drillFight) {
				fullTicksAvg += 0.02f * (fightTicks - fullTicksAvg);
			}
		}
		String result = won ? ", knockout" : lost ? ", knocked out" : "";
		if (mode == Mode.CHECK && benchNow >= 0) {
			endBenchEpisode(won, lost, result);
		} else if (mode == Mode.CHECK) {
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school pair %d %s %s%s: dealt %.1f, taken %.1f, ticks %d%s", (checkFights - 1) / 2,
					installedPlays ? "B" : "A", review >= TIMER ? CHECK_NAMES[review - TIMER] : sparFight ? "installed-model" : Specialists.NAMES[fightBot],
					review < 0 && fightStyle.heldOut ? " (held out)" : "", fightDealt, fightTaken, fightTicks, result));
			flushHits();
			if (installedPlays && ((checkFights / 2) % 10 == 0)) {
				checkSummary();
			}
		} else if (review >= 0) {
			reviewRewards.endEpisode(reviewTally[review], won, lost);
			reviewFights[review]++;
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school review %s: dealt %.1f, taken %.1f, ticks %d%s", basics[review].name,
					fightDealt, fightTaken, fightTicks, result));
		} else if (sceneNow != null) {
			sceneFights++;
			sceneNet += fightDealt - fightTaken;
			sceneNetBefore += sceneNow.net;
			boolean sceneWon = sceneNow.kind == KIND_PUNISH || sceneNow.kind == KIND_PUNISH2 ? !sceneK2 && sceneOwnCrit
					: sceneNow.kind == KIND_DEFLECT ? sceneDeflected
					: sceneNow.kind == KIND_JUMPIN || sceneNow.kind == KIND_JUMPIN2 ? !sceneK2 && (!sceneHit || sceneAnswer)
					: sceneNow.kind == KIND_COMBO ? sceneRowMost < 2
					: sceneNow.kind == KIND_OWN ? sceneOwnHits >= 2 && !sceneOwnBroken
					: sceneNow.kind == KIND_OWN2 ? sceneOwnHits >= 1 && !sceneOwnBroken : fightDealt > fightTaken;
			if (!sceneReview) {
				sceneRoll[sceneNow.kind].add(sceneWon);
				kindFights[sceneNow.kind]++;
				kindWon[sceneNow.kind] += sceneWon ? 1 : 0;
				checkFocus();
			}
			if (sceneNow.kind == KIND_JUMPIN && !sceneReview) {
				jumpInScenes++;
				jumpInScenesWon += sceneWon ? 1 : 0;
				jumpInScenesMastered += sceneWon && sceneNow.wins + 1 >= SCENE_MASTERED ? 1 : 0;
			}
			sceneWins += sceneWon ? 1 : 0;
			flushSceneHits(sceneWon);
			sceneSerial++;
			if (!learner.toldTheTeachers()) {
				sceneDecided++;
				sceneDecidedWon += sceneWon ? 1 : 0;
			}
			sceneNow.replays++;
			long[] tot = replayTotals[sceneNow.kind];
			if (sceneReview) {
				tot[3]++;
				tot[4] += sceneWon ? 0 : 1;
			} else {
				tot[0]++;
				tot[1] += sceneWon ? 1 : 0;
				tot[2] += sceneNow.wins + (sceneWon ? 1 : 0) >= SCENE_MASTERED ? 1 : 0;
			}
			if (sceneReview) {
				sceneReviews++;
				if (!sceneWon) {
					// Forgotten: back to be learned again.
					sceneForgot++;
					mastered.remove(sceneNow);
					sceneNow.wins = 0;
					scenes.add(sceneNow);
				}
			} else {
				sceneNow.wins = sceneWon ? sceneNow.wins + 1 : 0;
				if (sceneNow.wins >= SCENE_MASTERED) {
					scenes.remove(sceneNow);
					mastered.add(sceneNow);
					if (mastered.size() > SCENES_KEPT) {
						mastered.remove(0);
					}
					scenesMastered++;
					scenesMasteredAll++;
				}
			}
			if (sceneNow == repeatScene && !sceneReview) {
				repeatAfter();
			}
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school scene %s (lost %.1f there the first time): dealt %.1f, taken %.1f, ticks %d%s; %s scene%s, %s, %s, %08x",
					sceneNow.spar ? "installed-model" : Specialists.NAMES[fightBot], -sceneNow.net, fightDealt, fightTaken, fightTicks, result,
					KIND_NAMES[sceneNow.kind], sceneReview ? " (mastered, tested again)" : "", sceneWon ? "won" : "lost",
					learner.toldTheTeachers() ? "the teacher's choices" : "the decider's", sceneKey(sceneNow)));
		} else if (sparFight) {
			if (rivalNow == 0) {
				sparFights++;
				sparDealt += fightDealt;
				sparTaken += fightTaken;
				sparWon += won ? 1 : 0;
				sparLost += lost ? 1 : 0;
			}
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school fight %s: dealt %.1f, taken %.1f, ticks %d%s", sparName(), fightDealt,
					fightTaken, fightTicks, result));
		} else {
			stages.fight(fightBot, fightDealt, fightTaken, fightTicks);
			SwordAi.LOGGER.info(String.format(Locale.ROOT, "school fight %s: dealt %.1f, taken %.1f, ticks %d%s, teacher spoke on %d ticks; %s",
					Specialists.NAMES[fightBot], fightDealt, fightTaken, fightTicks, result, fightTeacherTicks, fightStyle.describe()));
		}
		hasLastAction = false;
		clicked = false;
		java.util.Arrays.fill(recentCrits, 0);
		sinceFallingHit = 1000;
	}

	/** The fight is broken off (a pause, frames that stopped): keys let go, the step in progress dropped. */
	private void suspendFight() {
		AgentController.release(mc);
		if (mode == Mode.TRAIN && pending == null) {
			learner.interrupt();
		}
		hasLastAction = false;
		clicked = false;
		motor.reset();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.setIdle(true));
		}
	}

	// ---- learning -------------------------------------------------------------------------------

	/** Paired check: what each side did when the moments came, and what the game showed it did. */
	private void checkSummary() {
		SwordAi.LOGGER.info("school check after {} pairs | A (school) techniques:{} | B (installed) techniques:{} | A keys a minute off/on moments: {} | B: {} "
				+ "| A by the game:{} | B by the game:{} | A crit moments: {} | B crit moments: {} | A jumping in: {} | B jumping in: {} | A swings: {} | B swings: {}",
				checkFights / 2, perfLine(perf), perfLine(perfB), perf.keysLine(), perfB.keysLine(), ownLine(0), ownLine(1), critLine(0), critLine(1),
				jumpInLine(0), jumpInLine(1), swingLine(0), swingLine(1));
		SwordAi.LOGGER.info("school check rules | A: the golden rule (K2) held the swing on {} ticks, the installed model's rules set keys on {} ticks; "
				+ "sprint hits outside its combos on one falling: on the server {}, as seen {} | lost view: A {} of {} ticks off the screen ({} times), "
				+ "B {} of {} ticks ({} times)", k2Held, ruleTicks, k2Truth, k2Seen, lostTicks[0], viewTicks[0], lostTimes[0], lostTicks[1], viewTicks[1],
				lostTimes[1]);
		k2Held = 0;
		ruleTicks = 0;
		k2Truth = 0;
		k2Seen = 0;
	}

	/**
	 * Every hit of a check, dealt or taken, with what the side was doing then (the user, 26 Oct 08:40: look at the fights it
	 * loses and at what it does that deals less damage). Appended to HIT_LOG in the school folder at the end of each fight;
	 * tools/hits-report.py reads it.
	 */
	static final String HIT_LOG = "hits.csv";
	static final String HIT_HEADER = "pair,side,bot,tick,dealt,taken,hitsDealt,crit,sprintHit,hitCharge,punishCrit,uppercut,hitsTaken,critsTaken,"
			+ "sprintsTaken,onGround,falling,sprinting,sprintKey,charge,reach,forward,strafe,jumpKey,sinceSwing,myRow,theirRow,themFalling,"
			+ "themRising,oppCharge,knockedUp,walkedInOnCooldown,ruled,situation";
	private final StringBuilder hitRows = new StringBuilder();
	private final int[] rowDealt = new int[2];
	private final int[] rowTaken = new int[2];
	private final int[] sinceSwing = {999, 999};

	/**
	 * Each take-off of a check within 8 blocks of the bot, with what came of it (the user, 2 Nov: does it jump in from outside
	 * 3 blocks and land at the edge of reach, so that it is falling once in their reach and cannot be knocked away). One row a
	 * take-off: the distance then and at the landing, the tick of the jump at which it was first hit (rising or falling), the
	 * tick of its own first hit and whether that was a crit (0: none). Appended to JUMP_LOG with the hit log; tools/jumpins.py
	 * reads the user's duels the same way.
	 */
	static final String JUMP_LOG = "jumps.csv";
	static final String JUMP_HEADER = "pair,side,bot,tick,takeOff,toward,landed,hitRisingAt,hitFallingAt,ownHitAt,ownCrit";
	private final StringBuilder jumpRows = new StringBuilder();
	private final boolean[] jumpWasGround = {true, true};
	private final int[] jumpSinceTaken = {999, 999};
	private final float[] jumpLastDist = {99f, 99f};
	/** The fight tick of the take-off being followed, -1 for none; and what has come of it so far. */
	private final int[] jumpAt = {-1, -1};
	private final float[] jumpFrom = new float[2];
	private final float[] jumpToward = new float[2];
	private final int[] jumpHitRising = new int[2];
	private final int[] jumpHitFalling = new int[2];
	private final int[] jumpOwnHit = new int[2];
	private final boolean[] jumpOwnCrit = new boolean[2];

	private void trackJump(int side, String bot) {
		LocalPlayer me = mc.player;
		boolean ground = facts.onGround;
		float dist = facts.reachDist;
		if (jumpAt[side] < 0) {
			if (jumpWasGround[side] && !ground && me.getY() - me.yo > 0.2 && jumpSinceTaken[side] > 3 && facts.hitsTaken <= 0 && dist < 8f) {
				jumpAt[side] = fightTicks;
				jumpFrom[side] = dist;
				jumpToward[side] = jumpLastDist[side] < 90f ? jumpLastDist[side] - dist : 0f;
				jumpHitRising[side] = jumpHitFalling[side] = jumpOwnHit[side] = 0;
				jumpOwnCrit[side] = false;
			}
		} else {
			int since = fightTicks - jumpAt[side];
			boolean outcome = jumpHitRising[side] > 0 || jumpHitFalling[side] > 0 || jumpOwnHit[side] > 0;
			if (!outcome && facts.hitsTaken > 0 && !ground) {
				if (facts.falling) {
					jumpHitFalling[side] = since;
				} else {
					jumpHitRising[side] = since;
				}
			} else if (!outcome && facts.hitsDealt > 0) {
				jumpOwnHit[side] = since;
				jumpOwnCrit[side] = facts.critDealt;
			}
			if (ground || since > 20) {
				jumpRows.append(String.format(Locale.ROOT, "%d,%s,%s,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d%n",
						logPair(), side == 0 ? "A" : "B", bot, jumpAt[side], jumpFrom[side], jumpToward[side],
						ground ? dist : -1f, jumpHitRising[side], jumpHitFalling[side], jumpOwnHit[side], jumpOwnCrit[side] ? 1 : 0));
				jumpAt[side] = -1;
			}
		}
		jumpSinceTaken[side] = facts.hitsTaken > 0 ? 0 : Math.min(jumpSinceTaken[side] + 1, 999);
		jumpWasGround[side] = ground;
		jumpLastDist[side] = dist;
	}

	private void logHits(int side, boolean swung, boolean botFalling) {
		if (mode != Mode.CHECK && sceneNow == null) {
			return;
		}
		if (mode == Mode.CHECK) {
			trackJump(side, review >= TIMER ? CHECK_NAMES[review - TIMER] : sparFight ? (benchNow >= 0 ? "h2h" : "installed-model") : Specialists.NAMES[fightBot]);
		}
		sinceSwing[side] = swung ? 0 : Math.min(sinceSwing[side] + 1, 999);
		if (facts.hitsDealt <= 0 && facts.hitsTaken <= 0) {
			return;
		}
		if (facts.hitsDealt > 0) {
			rowDealt[side]++;
			rowTaken[side] = 0;
		}
		if (facts.hitsTaken > 0) {
			rowTaken[side]++;
			rowDealt[side] = 0;
		}
		String bot = review >= TIMER ? CHECK_NAMES[review - TIMER] : sparFight ? (benchNow >= 0 ? "h2h" : "installed-model") : Specialists.NAMES[fightBot];
		String situation = step.teacherTechnique >= 0 ? Technique.values()[step.teacherTechnique].name() : "-";
		hitRows.append(String.format(Locale.ROOT,
				"%d,%s,%s,%d,%.1f,%.1f,%d,%d,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%d,%s%n",
				mode == Mode.CHECK ? logPair() : sceneSerial, side == 0 ? "A" : "B", bot, fightTicks, facts.damageDealt, facts.damageTaken, facts.hitsDealt,
				facts.critDealt ? 1 : 0, facts.sprintHit ? 1 : 0, facts.hitCharge, facts.punishCrit ? 1 : 0, facts.uppercut ? 1 : 0,
				facts.hitsTaken, facts.critsTaken, facts.sprintsTaken, facts.onGround ? 1 : 0, facts.falling ? 1 : 0,
				mc.player.isSprinting() ? 1 : 0, action[ActionSpace.SPRINT] == 1 ? 1 : 0, mc.player.getAttackStrengthScale(0f), facts.reachDist,
				action[ActionSpace.FORWARD] == 1 ? 1 : action[ActionSpace.FORWARD] == 2 ? -1 : 0,
				action[ActionSpace.STRAFE] == 1 ? -1 : action[ActionSpace.STRAFE] == 2 ? 1 : 0, action[ActionSpace.JUMP] == 1 ? 1 : 0, sinceSwing[side], rowDealt[side], rowTaken[side], reader.duel.them.falling() || botFalling ? 1 : 0,
				reader.duel.them.rising() ? 1 : 0, facts.oppCharge, facts.knockedUp ? 1 : 0, facts.walkedInOnCooldown ? 1 : 0, ruledNow,
				situation));
	}

	/**
	 * The hits of the scene fights in training (the user, 26 Oct 13:20: why the crit scenes against the bots are not won
	 * more, and which mastered ones are lost again): the hit log's columns after the scene's kind, whether it was won, a
	 * mastered one tested again, the decider's own choices or the teacher's, and the scene's key (the same scene in every
	 * replay). tools/scene-hits-report.py reads it.
	 */
	static final String SCENE_HIT_LOG = "scene-hits.csv";
	private int sceneSerial;

	/** The same in every replay of a scene: its opponent, style and state. */
	private static int sceneKey(Scene sc) {
		return 31 * (31 * sc.bot + Long.hashCode(sc.styleSeed)) + java.util.Arrays.hashCode(sc.state);
	}

	private void flushSceneHits(boolean won) {
		if (hitRows.length() == 0) {
			return;
		}
		String lead = String.format(Locale.ROOT, "%s,%d,%d,%d,%08x,", KIND_NAMES[sceneNow.kind], won ? 1 : 0, sceneReview ? 1 : 0,
				learner.toldTheTeachers() ? 0 : 1, sceneKey(sceneNow));
		StringBuilder rows = new StringBuilder();
		for (String row : hitRows.toString().split("\\R")) {
			if (!row.isEmpty()) {
				rows.append(lead).append(row).append(System.lineSeparator());
			}
		}
		hitRows.setLength(0);
		Path file = SwordFiles.schoolDir().resolve(SCENE_HIT_LOG);
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, "kind,won,review,decider,scene," + HIT_HEADER + System.lineSeparator());
			}
			Files.writeString(file, rows, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the scene hit log could not be written: {}", e.getMessage());
		}
	}

	private void flushHits() {
		// A jump still in the air when the fight ends is dropped; the next fight starts from the ground.
		jumpAt[0] = jumpAt[1] = -1;
		jumpWasGround[0] = jumpWasGround[1] = true;
		jumpLastDist[0] = jumpLastDist[1] = 99f;
		if (jumpRows.length() > 0) {
			Path jumps = logDir().resolve(JUMP_LOG);
			try {
				if (!Files.exists(jumps)) {
					Files.writeString(jumps, JUMP_HEADER + System.lineSeparator());
				}
				Files.writeString(jumps, jumpRows, java.nio.file.StandardOpenOption.APPEND);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("school: the jump log could not be written: {}", e.getMessage());
			}
			jumpRows.setLength(0);
		}
		if (hitRows.length() == 0) {
			return;
		}
		Path file = logDir().resolve(HIT_LOG);
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, HIT_HEADER + System.lineSeparator());
			}
			Files.writeString(file, hitRows, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the hit log could not be written: {}", e.getMessage());
		}
		hitRows.setLength(0);
	}

	/** One tick of a side's crit moments (see {@link #critTrace}). */
	private void traceCrit(int side, boolean swung) {
		boolean wanted = step.teacherTechnique >= 0 && CRIT_WANTED[step.teacherTechnique];
		// A hit lands a tick or two after the swing: the moment's few ticks after it still count.
		critWantedFor = wanted ? 3 : Math.max(0, critWantedFor - 1);
		long[] c = critTrace[side];
		if (critWantedFor > 0) {
			c[7] += facts.hitsDealt;
			c[8] += facts.critDealt ? 1 : 0;
		}
		if (!wanted) {
			return;
		}
		c[0]++;
		boolean falling = facts.falling && !mc.player.onGround();
		if (falling) {
			c[1]++;
			if (facts.reachDist <= 3.0f) {
				c[2]++;
				if (chargeAtClick > 0.9f) {
					c[3]++;
					c[4] += sprintAtClick ? 0 : 1;
					// Still sprinting: W held by a rule, by the network, or let go and the sprint not yet ended.
					if (sprintAtClick) {
						int fwd = action[ActionSpace.FORWARD];
						if (fwd != 1) {
							c[11]++;
						} else if ((ruledNow & (1 << ActionSpace.FORWARD)) != 0) {
							c[9]++;
						} else {
							c[10]++;
						}
					}
				}
			}
		}
		if (swung) {
			c[5]++;
			c[6] += critAtClick ? 1 : 0;
		}
	}

	/** A head's target: the bin nearest {@code degrees}, sure. */
	private static void nearestBin(float[] target, int head, float[] bins, float degrees) {
		int off = ActionSpace.OFFSETS[head];
		int best = 0;
		for (int k = 1; k < bins.length; k++) {
			if (Math.abs(bins[k] - degrees) < Math.abs(bins[best] - degrees)) {
				best = k;
			}
		}
		for (int k = 0; k < bins.length; k++) {
			target[off + k] = k == best ? 1f : 0f;
		}
	}

	/** " T4 2/5; B1 0/1" (sword gate / whiff guard), and starts the counts again. */
	private String blockedLine() {
		StringBuilder b = new StringBuilder();
		for (int t = 0; t < Technique.COUNT; t++) {
			if (blockedSwings[t][0] + blockedSwings[t][1] > 0) {
				b.append(' ').append(Technique.ALL[t].id).append(' ').append(blockedSwings[t][0]).append('/').append(blockedSwings[t][1]).append(';');
			}
			blockedSwings[t][0] = 0;
			blockedSwings[t][1] = 0;
		}
		return b.length() == 0 ? " none" : b.toString();
	}

	/**
	 * The punish drill's lifts told B1, tick by tick: where it is, and its keys against the teacher's (school/punish-traces.txt).
	 * 27-28 Oct the drill critted 1 of 28 lifts in reach; the counts say where a lift stopped, not what it pressed.
	 */
	private final StringBuilder liftTrace = new StringBuilder();
	static final String PUNISH_TRACE_FILE = "punish-traces.txt";

	private void traceDrillLift(LocalPlayer me, Truth truth, int[] action, int ruled) {
		boolean drill = punishScene() || drillFight;
		if (!drill || liftedFor < 0 || !liftTold || mode != Mode.TRAIN) {
			return;
		}
		dev.swordai.core.pvp.Keys t = teacher.keys();
		liftTrace.append(String.format(Locale.ROOT, "%2d reach %.2f %s charge %.2f %s | net %s%s%s%s | teacher %s%s%s%s %s | ruled %s%n",
				liftedFor, facts.reachDist, me.onGround() ? "ground " : facts.falling ? "falling" : "rising ", me.getAttackStrengthScale(0f),
				truth.agentSprinting() ? "sprinting" : "walking  ", action[ActionSpace.FORWARD] == 1 ? "W" : action[ActionSpace.FORWARD] == 2 ? "S" : "-",
				action[ActionSpace.SPRINT] == 1 ? "R" : "-", action[ActionSpace.JUMP] == 1 ? "J" : "-", action[ActionSpace.ATTACK] == 1 ? "A" : "-",
				t.forward > 0 ? "W" : t.forward < 0 ? "S" : "-", t.sprint ? "R" : "-", t.jump ? "J" : "-", t.attack ? "A" : "-", t.technique.id,
				Integer.toBinaryString(ruled)));
	}

	private void writeLiftTrace() {
		if (liftTrace.length() == 0) {
			return;
		}
		try {
			Path f = SwordFiles.schoolDir().resolve(PUNISH_TRACE_FILE);
			if (Files.exists(f) && Files.size(f) > 4_000_000) {
				Files.move(f, SwordFiles.schoolDir().resolve(PUNISH_TRACE_FILE + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			String head = String.format(Locale.ROOT, "lift %s, %s, reached stage %d (1 in reach, 2 sword back, 3 not sprinting, 4 aimed, 5 swung, 6 crit)%n",
					liftKind == 1 ? "sprint hit" : liftKind == 2 ? "crit" : "plain hit", drillFight ? "drill fight" : "punish scene", liftStage);
			Files.writeString(f, head + liftTrace + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			SwordAi.LOGGER.warn("school: the punish trace could not be written: {}", e.getMessage());
		}
		liftTrace.setLength(0);
	}

	private void endLift() {
		writeLiftTrace();
		// A punish chance (it came down in reach after their hit) in a full fight: the punish crit's bar, and a scene if missed.
		// A chance once it came down in reach with the sword back (stage 2): with the sword not back there is no crit to make.
		// Counted from stage 1 (in reach) 17:09-18:10, when 45% of those had the sword down from their trade.
		// Told the punish crit and came down in reach: landed or not (counted from stage 1, as a swing on the way up after
		// being told is the commonest way to miss it).
		if (liftTold && liftKind > 0 && liftStage >= 1 && mode == Mode.TRAIN && review < 0 && sceneNow == null && !drillFight) {
			toldB1Roll.add(liftStage >= 6);
		}
		if (liftKind > 0 && liftStage >= 2 && mode == Mode.TRAIN && review < 0 && sceneNow == null && !drillFight) {
			boolean punished = liftStage >= 6;
			punishRoll.add(punished);
			if (!punished && !drillFight && punishFrom < 0) {
				punishFrom = liftAt;
				keepPunishAt = liftAt + PUNISH_SCENE_TICKS - PUNISH_BEFORE;
			}
		}
		if (liftKind > 0 && liftKind < lifts.length) {
			lifts[liftKind][0]++;
			for (int k = 1; k <= liftStage; k++) {
				lifts[liftKind][k]++;
			}
			lifts[liftKind][7] += liftTaught ? 1 : 0;
			lifts[liftKind][8] += liftTold ? 1 : 0;
			lifts[liftKind][9] += liftTold && liftStage >= 6 ? 1 : 0;
			lifts[liftKind][10] += liftToldByDecider ? 1 : 0;
			lifts[liftKind][11] += liftTold && liftStage >= 3 ? 1 : 0;
			if (liftTold) {
				int[] t = toldStages[punishScene() || drillFight ? 1 : 0];
				for (int k = 0; k <= Math.min(6, liftStage); k++) {
					t[k]++;
				}
			}
		}
		liftKind = -1;
		liftStage = 0;
		liftTaught = false;
		liftTold = false;
		liftToldByDecider = false;
	}

	/** "after sprint hits 40: in reach 6, sword back 3, not sprinting 1, aimed 1, swung 1, crit 0, teacher B1 0; after crits ...". */
	private String liftLine() {
		StringBuilder b = new StringBuilder();
		String[] names = {"", "sprint hits", "crits", "plain hits"};
		for (int k = 1; k < lifts.length; k++) {
			int[] c = lifts[k];
			b.append(String.format(Locale.ROOT, "%s after %s %d: in reach %d, sword back %d, not sprinting %d, aimed %d, swung %d, crit %d, teacher B1 %d, "
					+ "told B1 %d (by the decider %d), not sprinting when told %d, critted when told %d",
					k == 1 ? "" : ";", names[k], c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[10], c[11], c[9]));
			java.util.Arrays.fill(c, 0);
		}
		for (int w = 0; w < 2; w++) {
			int[] t = toldStages[w];
			b.append(String.format(Locale.ROOT, "; told B1 in %s: lifts %d, in reach %d, sword back %d, not sprinting %d, aimed %d, swung %d, crit %d",
					w == 0 ? "the full fights" : "the punish drill", t[0], t[1], t[2], t[3], t[4], t[5], t[6]));
			java.util.Arrays.fill(t, 0);
		}
		return b.toString();
	}

	private void traceJumpIn(int side) {
		// Lost view: ticks with the opponent off the screen, and how often they went off it (the gate's fourth fundamental).
		viewTicks[side]++;
		if (!facts.enemyVisible) {
			lostTicks[side]++;
			lostTimes[side] += wasVisible[side] ? 1 : 0;
		}
		wasVisible[side] = facts.enemyVisible;
		long[] c = jumpIn[side];
		dev.swordai.core.pvp.Body them = reader.duel.them;
		if (them.sinceJump == 0 && reader.duel.theyClose > 0.05 && reader.duel.flat < 6.5) {
			if (exAt[side] >= 0) {
				finishJumpIn(side);
			}
			c[0]++;
			exAt[side] = 0;
			exCrit[side] = false;
			exK2[side] = false;
			exPunish[side] = false;
			exOwnCrit[side] = false;
			exHit[side] = false;
			exAnswer[side] = false;
			exFrom[side] = fightTicks;
		}
		boolean k2Now = facts.hitsDealt > 0 && facts.sprintHit && them.falling();
		if (exAt[side] >= 0) {
			exCrit[side] |= facts.critsTaken > 0;
			exK2[side] |= k2Now;
			exPunish[side] |= exCrit[side] && facts.hitsDealt > 0 && facts.critDealt;
			exOwnCrit[side] |= facts.hitsDealt > 0 && facts.critDealt;
			exAnswer[side] |= exHit[side] && facts.hitsDealt > 0 && facts.critDealt;
			exHit[side] |= facts.hitsTaken > 0 && facts.sprintsTaken == 0;
			if (++exAt[side] >= JUMPIN_WINDOW) {
				finishJumpIn(side);
			}
		}
		if (sceneNow != null) {
			sceneCrit |= facts.critsTaken > 0;
			sceneK2 |= k2Now;
			scenePunish |= sceneCrit && facts.hitsDealt > 0 && facts.critDealt;
			sceneOwnCrit |= facts.hitsDealt > 0 && facts.critDealt;
			sceneAnswer |= sceneHit && facts.hitsDealt > 0 && facts.critDealt;
			sceneHit |= facts.hitsTaken > 0 && facts.sprintsTaken == 0;
		}
		sinceFallingHit = Math.min(sinceFallingHit + 1, 1000);
		sinceRiserHit[side] = Math.min(sinceRiserHit[side] + 1, 1000);
		if (facts.hitsDealt > 0 && them.rising()) {
			c[1]++;
			riserHitClose[side] = facts.reachDist < RISER_CLOSE;
			c[riserHitClose[side] ? 5 : 6]++;
			sinceRiserHit[side] = 0;
		} else if (facts.critsTaken > 0 && sinceRiserHit[side] <= 10) {
			c[riserHitClose[side] ? 7 : 8] += facts.critsTaken;
		}
		if (side == 0 && facts.hitsDealt > 0 && facts.sprintHit && !lastInOwnCombo) {
			k2Truth += lastTruthFalling ? 1 : 0;
			k2Seen += them.falling() ? 1 : 0;
		}
		if (facts.hitsDealt > 0 && facts.sprintHit && them.falling()) {
			c[2]++;
			sinceFallingHit = 0;
			// Their crit may come first, as they fall into it: the ten ticks before count too.
			c[3] += critsInLastTen();
		} else if (facts.critsTaken > 0 && sinceFallingHit <= 10) {
			c[3] += facts.critsTaken;
		}
		c[4] += facts.critsTaken;
		recentCrits[(int) (critClock++ % recentCrits.length)] = facts.critsTaken;
	}

	/**
	 * Their combo in a training fight: a second hit in a row starts one; a third ends it lost (a combo scene to keep), its own
	 * hit or JUMPIN_WINDOW ticks without a third end it got out of. In a scene fight, their most hits in a row since its start.
	 */
	private void trackCombo(boolean dealt, boolean taken) {
		if (sceneNow != null) {
			sceneRow = taken ? sceneRow + 1 : dealt ? 0 : sceneRow;
			sceneRowMost = Math.max(sceneRowMost, sceneRow);
			return;
		}
		if (secondHitAt >= 0 && (dealt || fightTicks - secondHitAt > JUMPIN_WINDOW)) {
			comboRoll.add(true);
			secondHitAt = -1;
		}
		if (taken && theirRow == 2) {
			secondHitAt = fightTicks;
		} else if (taken && theirRow == 3 && secondHitAt >= 0) {
			comboRoll.add(false);
			if (!drillFight && comboFrom < 0) {
				comboFrom = secondHitAt;
				keepComboAt = secondHitAt + JUMPIN_SCENE_TICKS - COMBO_BEFORE;
			}
			secondHitAt = -1;
		}
	}

	private int sceneRowMost;

	/**
	 * A jump-in exchange is over (see JUMPIN_WINDOW). It counts once their hit landed on it, or it knockback-hit them falling
	 * (K2); it is countered when their hit was answered by its own crit (the punish crit, B1) and no K2. One where they never
	 * hit it is not counted either way: nothing to punish (26 Oct 16:30; 14:41-16:30 every jump-in counted, and 17 of 22 had no
	 * crit either side, so 90% was out of reach whatever it did). Before that the counter was only by its own crit, and no
	 * sprint hit on them falling. The user, 26 Oct 14:30: "a falling player or even a rising player with lag ... will still
	 * land a crit because knock back won't get them instantly out of reach so it must be a punish crit". The check hit log
	 * agreed: after its hit on them rising (T8) their crit still came within 20 ticks 25% of the time (the installed model
	 * 32%), and it critted back 5 of 42 times. Until then a T8 hit with no crit after it counted as countered.
	 */
	private void finishJumpIn(int side) {
		long[] c = jumpIn[side];
		boolean counted = exHit[side] || exK2[side];
		boolean countered = counted && !exK2[side] && exAnswer[side];
		c[9] += counted ? 1 : 0;
		c[10] += countered ? 1 : 0;
		c[11] += exHit[side] && !exAnswer[side] && !exK2[side] ? 1 : 0;
		c[12] += exK2[side] ? 1 : 0;
		c[13] += countered && exCrit[side] ? 1 : 0;
		c[14] += counted ? 0 : 1;
		exAt[side] = -1;
		// The counter (the punish crit) pays in the scenes too (26 Oct 16:10): the jump-in scenes are where it is drilled.
		if (side == 0 && mode == Mode.TRAIN && review < 0 && countered) {
			shaping += JUMPIN_COUNTERED;
		}
		if (side == 0 && mode == Mode.TRAIN && review < 0 && sceneNow == null && counted) {
			trainJumpIns++;
			trainJumpInsCountered += countered ? 1 : 0;
			(sparFight ? jumpInSparRoll : jumpInRoll).add(countered);
			if (!countered && !drillFight && jumpInFrom < 0) {
				jumpInFrom = exFrom[side];
				keepJumpInAt = exFrom[side] + JUMPIN_SCENE_TICKS - JUMPIN_BEFORE;
			}
		}
	}

	private final int[] recentCrits = new int[10];
	private long critClock;

	private int critsInLastTen() {
		int n = 0;
		for (int c : recentCrits) {
			n += c;
		}
		java.util.Arrays.fill(recentCrits, 0);
		return n;
	}

	private String jumpInLine(int side) {
		long[] c = jumpIn[side];
		return String.format(Locale.ROOT, "their jump-ins %d, of those with their crit or plain hit on it or a K2: punished with its crit %d of %d (%.0f%%; their crit punished in %d; "
				+ "their hit not punished in %d, no hit of theirs (not counted) %d, a sprint hit on them falling in %d); "
				+ "its hits on them rising (T8) %d; its knockback hits on them falling (K2 broken) %d, crits taken "
				+ "within ten ticks %d; crits taken in all %d; hits on them rising close %d (crits taken after %d), further %d (crits taken after %d)",
				c[0], c[10], c[9], c[9] == 0 ? 0f : 100f * c[10] / c[9], c[13], c[11], c[14], c[12], c[1], c[2], c[3], c[4], c[5], c[7], c[6], c[8]);
	}

	private String critLine(int side) {
		long[] c = critTrace[side];
		return String.format(Locale.ROOT, "%d ticks, falling %d, and in reach %d, and charged %d, and not sprinting (server) %d; swings %d (crits at the click %d); "
				+ "hits %d, crits %d; sprinting with the sword back: W by a rule %d, W by the network %d, W let go %d",
				c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11]);
	}

	private void startUpdate() {
		phase = Phase.UPDATING;
		mc.getSingleplayerServer().execute(() -> ArenaServer.setIdle(true));
		long steps = learner.rolloutSize();
		stages.addSteps(steps);
		SchoolPpo.Settings set = new SchoolPpo.Settings();
		set.techPulls = stages.techPulls();
		set.oldPull = stages.oldPull();
		set.oldBodyPull = stages.bodyPull();
		set.installedBody = OVERLAY;
		set.ruledPull = ruledPullNow;
		set.criticOnly = warmupLeft > 0;
		set.actorLr = ACTOR_LR;
		set.maxActorLr = MAX_ACTOR_LR;
		if (warmupLeft > 0) {
			warmupLeft--;
		}
		float predictorLr = 1e-3f;
		Path checkpoint = SwordFiles.schoolCheckpoint();
		Path stagesFile = SwordFiles.schoolStages();
		pending = updater.submit(() -> {
			SchoolPpo.Stats stats = learner.update(set, predictorLr);
			learner.save(checkpoint);
			stages.save(stagesFile);
			return stats;
		});
	}

	private void finishUpdate() {
		SchoolPpo.Stats stats;
		try {
			stats = pending.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("the school's update was interrupted", e);
			return;
		} catch (java.util.concurrent.ExecutionException e) {
			fail("the school's update failed: " + e.getCause(), e.getCause());
			return;
		} finally {
			pending = null;
		}
		if (stats.oldBodyCe > 0f) {
			stages.noteBodyCe(stats.oldBodyCe);
		}
		if (stats.ruledSteps > 0) {
			ruledCeEma = ruledCeEma < 0f ? stats.ruledCe : 0.9f * ruledCeEma + 0.1f * stats.ruledCe;
			ruledAgreeEma = ruledAgreeEma == 0f ? stats.ruledAgree : 0.9f * ruledAgreeEma + 0.1f * stats.ruledAgree;
			float budget = Math.max(-1f, Math.min(1f, (ruledCeEma - RULED_CE_TARGET) / RULED_CE_TARGET));
			ruledPullNow = Math.max(RULED_PULL_LEAST, Math.min(RULED_PULL_MOST, ruledPullNow * (float) Math.exp(dev.swordai.core.school.SchoolStages.BUDGET_STEP * budget)));
		}
		long nowMillis = System.currentTimeMillis();
		long steps = learner.totalSteps;
		float perSecond = (steps - stepsAtLastUpdate) * 1000f / Math.max(1L, nowMillis - millisAtLastUpdate);
		stepsAtLastUpdate = steps;
		millisAtLastUpdate = nowMillis;
		StringBuilder bots = new StringBuilder();
		for (int b = 0; b < Specialists.KINDS; b++) {
			if (botFights[b] == 0) {
				continue;
			}
			float minutes = botTicks[b] / 1200f;
			bots.append(String.format(Locale.ROOT, " %s %d fights dealt %.1f taken %.1f a minute (net %+.1f) ko %d-%d;", Specialists.NAMES[b], botFights[b],
					botDealt[b] / minutes, botTaken[b] / minutes, (botDealt[b] - botTaken[b]) / minutes, botWins[b], botLosses[b]));
		}
		StringBuilder taught = new StringBuilder();
		StringBuilder used = new StringBuilder();
		for (Technique t : Technique.ALL) {
			int i = t.ordinal();
			if (spoke[i] > 0) {
				taught.append(String.format(Locale.ROOT, " %s %d (%.0f%% as taught)", t.id, spoke[i], 100f * agreed[i] / spoke[i]));
			}
			if (botUsed[i] > 0) {
				used.append(' ').append(t.id).append(' ').append(botUsed[i]);
			}
		}
		stages.adapt();
		pullsNow = stages.techPulls();
		lastUpdateLine = String.format(Locale.ROOT, "update %d, %d steps (%.0f a second): %s", learner.updates, steps, perSecond, stats);
		SwordAi.LOGGER.info("school {} | {} | predictor {} | decider {} | teachers' examples again: {} | per bot:{} | its moments by technique (network's likeliest keys):{} | done since the last update:{} "
				+ "| why not done:{} | jumps, swings, S a minute off/on moments: {} | by the game:{} | crit moments: {} | jumping in: {} | real punish crits rewarded {} ({} in {} drill fights), other crits {}, combo follow-up hits rewarded {} (3rd+ {}), chase crits {}, made to miss {}, deflection starts {}, deflection chances {} made {} missed {}, hits taken in the air from its own jump {} "
				+ "| crit chances {}, swung on {}, sprint let go taught on {} lift ticks, aim on {}, turned to find them on {} (off the screen {}) | their hits: {} | punish chances by their hit:{} | swings taught but kept back by a rule (sword gate/whiff guard):{} | technique reward {} for {} moments paid ({} won damage), K2 broken {} (the golden rule held the swing on {} ticks, the installed model's rules set keys on {}) | swings: {} | review of the old stages:{} | bots went into:{} | taught now:{} | sparring the installed model:{} | take-over:{}", lastUpdateLine, stages.describe(), learner.predictorLine(),
				learner.chooserLine(), learner.replayLine(), bots, taught, perfLine(perf), perf.whyLine(),
				perf.keysLine(), ownLine(0), critLine(0), jumpInLine(0), punishCritsPaid, drillPunishCrits, drillFights, otherCrits, comboHitsPaid, combo3Paid, chaseCrits, madeMiss, deflectStarts, deflectChances, deflectMade, deflectMissed, airJumpHits,
				critChances, critSwings, sprintLetGo, liftAim, findAim, findBlind, reader.takeTheirHits(), liftLine(), blockedLine(), String.format(Locale.ROOT, "%.1f", shapingPaid), momentsPaid, momentsWon, k2Broken, k2Held, ruleTicks, swingLine(0), reviewLine(), used, taughtNowLine(), sparLine(), takeOverLine());
		java.util.Arrays.fill(swingCount[0], 0L);
		punishCritsPaid = 0;
		comboHitsPaid = 0;
		combo3Paid = 0;
		chaseCrits = 0;
		madeMiss = 0;
		deflectStarts = 0;
		deflectChances = 0;
		deflectMade = 0;
		deflectMissed = 0;
		airJumpHits = 0;
		drillPunishCrits = 0;
		drillFights = 0;
		otherCrits = 0;
		critChances = 0;
		critSwings = 0;
		sprintLetGo = 0;
		liftAim = 0;
		findAim = 0;
		findBlind = 0;
		shapingPaid = 0f;
		momentsPaid = 0;
		momentsWon = 0;
		k2Broken = 0;
		k2Held = 0;
		ruleTicks = 0;
		k2Truth = 0;
		k2Seen = 0;
		java.util.Arrays.fill(critTrace[0], 0L);
		java.util.Arrays.fill(jumpIn[0], 0L);
		float dealtAll = 0f;
		float takenAll = 0f;
		int ticksAll = 0;
		for (int b = 0; b < Specialists.KINDS; b++) {
			dealtAll += botDealt[b];
			takenAll += botTaken[b];
			ticksAll += botTicks[b];
		}
		float minutesAll = Math.max(1, ticksAll) / 1200f;
		say(String.format(Locale.ROOT, "%s %dk steps: done %s | damage a minute %.0f dealt, %.0f taken", stages.current().name, stages.stageSteps / 1000,
				stages.masteryBrief(), dealtAll / minutesAll, takenAll / minutesAll));
		perf.clear();
		for (int[] row : ownSeen) {
			java.util.Arrays.fill(row, 0);
		}
		for (int[] row : ownDid) {
			java.util.Arrays.fill(row, 0);
		}
		java.util.Arrays.fill(botDealt, 0f);
		java.util.Arrays.fill(botTaken, 0f);
		java.util.Arrays.fill(botTicks, 0);
		java.util.Arrays.fill(botFights, 0);
		java.util.Arrays.fill(botWins, 0);
		java.util.Arrays.fill(botLosses, 0);
		java.util.Arrays.fill(spoke, 0);
		java.util.Arrays.fill(agreed, 0);
		java.util.Arrays.fill(botUsed, 0);
		if (stages.passed()) {
			String from = stages.current().name;
			String to = stages.advance();
			say("The school passed " + from + "; on to " + to + ": " + stages.current().family + ".");
			try {
				stages.save(SwordFiles.schoolStages());
				Files.copy(SwordFiles.schoolCheckpoint(), SwordFiles.schoolDir().resolve("checkpoint-after-" + from + ".bin"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not keep the school's state", e);
			}
		}
		if (nowMillis - lastKeptMillis >= KEEP_EVERY_MINUTES * 60_000L) {
			lastKeptMillis = nowMillis;
			try {
				Files.copy(SwordFiles.schoolCheckpoint(), SwordFiles.schoolDir().resolve("kept-" + LocalTime.now().format(HHMM) + ".bin"),
						StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				SwordAi.LOGGER.warn("could not keep an hourly copy", e);
			}
		}
	}

	private void applySpeed(float wanted) {
		float s = Math.max(1f, wanted);
		speed = s;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ArenaServer.setSpeed(s));
		}
		SwordAiClient.setTickMillis(s > 1f ? 50f / s : 0f);
		grabber.setSpeed(s);
	}

	// ---- stopping -------------------------------------------------------------------------------

	@Override
	public void stop(String reason) {
		if (phase == Phase.STOPPED) {
			return;
		}
		Phase was = phase;
		phase = Phase.STOPPED;
		if (mode == Mode.CHECK) {
			checkSummary();
		}
		try {
			AgentController.release(mc);
			if (pending != null) {
				// The update saves when it is done.
				try {
					pending.get(120, TimeUnit.SECONDS);
				} catch (Exception e) {
					SwordAi.LOGGER.warn("the school's last update did not finish", e);
				}
			} else if (mode == Mode.TRAIN && was != Phase.WAIT_ARENA && was != Phase.SELFTEST) {
				// What was gathered since the last update is not learned from; the stages are kept.
				stages.save(SwordFiles.schoolStages());
			}
		} catch (IOException e) {
			SwordAi.LOGGER.warn("could not save the school's stages", e);
		} finally {
			updater.shutdownNow();
			learner.close();
			if (installed != null) {
				installed.close();
			}
			grabber.close();
			KeyedSkin.setActive(false);
			SeenLag.off();
			SwordAiClient.setTickMillis(0f);
			WindowGuard.restore(mc);
			OptionsGuard.restore(mc);
			IntegratedServer server = mc.getSingleplayerServer();
			LoadedFighter spar = sparring;
			sparring = null;
			List<SchoolBotPilot> leagueNow = List.copyOf(league);
			league.clear();
			if (server != null) {
				// The sparring copy plays on the server thread: closed there, once the arena has stopped.
				server.execute(() -> {
					ArenaServer.stop(server);
					if (spar != null) {
						spar.close();
					}
					leagueNow.forEach(SchoolBotPilot::close);
				});
			} else {
				if (spar != null) {
					spar.close();
				}
				leagueNow.forEach(SchoolBotPilot::close);
			}
		}
		say("Stopped (" + reason + ").");
	}

	private void fail(String message, Throwable error) {
		SwordFiles.writeError(message, error);
		if (error != null) {
			SwordAi.LOGGER.error("SwordAI stopped: " + message, error);
		}
		say("Stopped because of a problem: " + message);
		stop("error");
	}

	private void say(String message) {
		SwordAi.LOGGER.info(message);
		Runnable show = () -> {
			if (mc.gui != null) {
				mc.gui.getChat().addMessage(Component.literal("[SwordAI] " + message));
			}
		};
		if (mc.isSameThread()) {
			show.run();
		} else {
			mc.execute(show);
		}
	}
}
