package dev.swordai.client;

import dev.swordai.core.config.Config;
import dev.swordai.core.env.BodyState;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.school.SchoolFighter;
import dev.swordai.core.school.SchoolLearner;
import dev.swordai.server.ReplicaBody;
import dev.swordai.server.SparPilot;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A technique school network playing the arena's bot as the user's game plays it (FightSession): SchoolFighter deciding, with
 * the installed model's rules over it from its own copy of the installed model, the memory of the opponent's habits read from
 * the server's players (DuelReader), and the fake player run as if it had a game client (ReplicaBody, as BotPilot does for the
 * installed model). For the school's league (run 36): BotPilot plays only the installed model's kind of network, and the
 * league's networks are school networks.
 *
 * <p>Created on the client thread, then used by the server thread only.
 */
final class SchoolBotPilot implements SparPilot, AutoCloseable {
	private final LoadedFighter rules;
	private final SchoolLearner learner;
	private final SchoolFighter school;
	private final ReplicaBody body;
	private final DuelReader reader = new DuelReader();
	private boolean clicked;
	private float chargeAtClick;
	private boolean sprintAtClick;
	private boolean critAtClick;
	private boolean groundBefore = true;
	private int lastHurt;
	/** Lifted by their hit (taken on the ground), counted until it is down again (as FightSession's). */
	private int liftedFor = -1;

	private SchoolBotPilot(LoadedFighter rules, SchoolLearner learner, SchoolFighter school) {
		this.rules = rules;
		this.learner = learner;
		this.school = school;
		this.body = new ReplicaBody(rules.frameAgeTicks);
	}

	/**
	 * @param network   the school network
	 * @param installed the installed model's checkpoint, settings and curriculum: the rules, as in the user's game
	 * @param temps     the checks' head temperatures (SchoolSession.PLAY_TEMPERATURE_FILE), if the file is there
	 */
	static SchoolBotPilot load(Path network, Path installed, Config cfg, Path curriculum, Path temps) throws IOException {
		LoadedFighter rules = LoadedFighter.loadFrom(installed, cfg, curriculum);
		SchoolLearner l = new SchoolLearner(System.nanoTime(), 1, 0.99f, 0.95f, 1);
		try {
			l.load(network);
			if (temps != null && Files.exists(temps)) {
				l.playTemperatures(SchoolSession.readHeadTemperatures(temps));
			}
		} catch (IOException | RuntimeException e) {
			l.close();
			rules.close();
			throw e;
		}
		// The arena is a singleplayer server: the swing waits as it does there (FightSession).
		SchoolFighter school = new SchoolFighter(l, cfg.aimResponse, cfg.swingMinCharge, cfg.playTemperature);
		school.whiffGuard(cfg.whiffGuard);
		school.rulesFrom(rules.fighter);
		rules.fighter.hitAndOut(cfg.hitAndOutRule || Files.exists(SwordFiles.schoolDir().resolve(SchoolSession.HIT_AND_OUT_FILE)));
		return new SchoolBotPilot(rules, l, school);
	}

	@Override
	public void setLag(int ticks) {
		body.setLag(ticks);
	}

	@Override
	public void reset() {
		school.reset();
		rules.fighter.reset();
		body.reset();
		reader.reset();
		clicked = false;
		groundBefore = true;
		lastHurt = 0;
		liftedFor = -1;
	}

	@Override
	public void hitDealt() {
		school.hitDealt();
		rules.fighter.hitDealt();
	}

	@Override
	public void hitTaken() {
		school.hitTaken();
		rules.fighter.hitTaken();
	}

	@Override
	public void idle(ServerPlayer bot) {
		body.idle(bot);
	}

	@Override
	public String swingSummary() {
		return body.swingSummary();
	}

	@Override
	public void tick(ServerPlayer bot, ServerPlayer opponent, double floorY) {
		body.sense(bot, opponent, floorY);
		boolean takenNow = bot.hurtTime > lastHurt;
		liftedFor = takenNow && groundBefore ? 0 : liftedFor < 0 ? -1 : liftedFor + 1;
		if (liftedFor > 3 && bot.onGround() || liftedFor > SchoolSession.PUNISH_WINDOW) {
			liftedFor = -1;
		}
		groundBefore = bot.onGround();
		lastHurt = bot.hurtTime;
		reader.tick(bot, opponent, clicked, chargeAtClick, sprintAtClick, critAtClick);
		boolean onThem = body.aimedAt(bot, opponent);
		school.crosshairOnOpponent(onThem);
		rules.fighter.crosshairOnOpponent(onThem);
		// The rules see the body as the installed model was shown it, the school network its health as it is (FightSession).
		BodyState state = body.state();
		state.patchedUp = rules.patchedUp;
		rules.fighter.decide(body.frame(), state);
		state.patchedUp = false;
		school.inLift(liftedFor >= 0 && !bot.onGround());
		int[] action = school.decide(body.frame(), state, reader.memory());
		chargeAtClick = bot.getAttackStrengthScale(0f);
		sprintAtClick = bot.isSprinting();
		critAtClick = !bot.onGround() && bot.getY() < bot.yo && !sprintAtClick && chargeAtClick > 0.9f;
		body.act(bot, opponent, action, school.yawRate(), school.pitchRate());
		clicked = action[ActionSpace.ATTACK] == 1;
	}

	@Override
	public void close() {
		learner.close();
		rules.close();
	}
}
