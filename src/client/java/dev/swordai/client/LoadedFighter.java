package dev.swordai.client;

import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.Curriculum;
import dev.swordai.core.rl.ActionSpace;
import dev.swordai.core.rl.Fighter;
import dev.swordai.core.rl.Learner;
import dev.swordai.core.vision.ViewGeometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The saved network, loaded to play rather than to learn. */
final class LoadedFighter implements AutoCloseable {
	final Config cfg;
	final Curriculum curriculum;
	final Fighter fighter;
	/** How old the frame is that the network decides on when it plays the user's character, in ticks. */
	final float frameAgeTicks;
	/**
	 * It plays with what it learned in a stage where it could not lose health, so it is shown its health
	 * the way it was shown there; see {@code BodyState.patchedUp}.
	 */
	final boolean patchedUp;
	private final Learner learner;

	private LoadedFighter(Config cfg, Curriculum curriculum, Learner learner, Fighter fighter, float frameAgeTicks, boolean patchedUp) {
		this.cfg = cfg;
		this.curriculum = curriculum;
		this.learner = learner;
		this.fighter = fighter;
		this.frameAgeTicks = frameAgeTicks;
		this.patchedUp = patchedUp;
	}

	/**
	 * @throws IOException when there is nothing trained yet, or it cannot be read
	 */
	static LoadedFighter load() throws IOException {
		Path checkpoint = SwordFiles.checkpoint();
		if (!Files.exists(checkpoint)) {
			throw new IOException("nothing has been trained yet; run /swordai train in a singleplayer world first");
		}
		Config cfg = SwordFiles.loadConfig();
		// The picture it gets, whether from the screen or drawn for a fake player, has the field of
		// view it learned with.
		ViewGeometry.setFov(Math.min(cfg.visionFov, cfg.fov));
		Curriculum curriculum = new Curriculum(cfg.stages);
		if (Files.exists(SwordFiles.curriculum())) {
			curriculum.load(SwordFiles.curriculum());
		}
		Learner learner = new Learner(cfg, System.nanoTime(), 1);
		try {
			learner.load(checkpoint);
		} catch (IOException | RuntimeException e) {
			learner.close();
			throw e;
		}
		// It plays with the controls of the stage it has reached; the rest it has never used. And it is
		// handed a strafe and hit-timing plan exactly when its training in that stage hands it one: a
		// network that is in the middle of learning to follow the plan has it switched on all the time.
		int headMask = curriculum.current().headMask;
		// And it is pushed towards a jump exactly as its training pushes it right now.
		learner.setJumpNudge(curriculum.jumpNudge());
		learner.setBackNudge(Report.backRule(cfg, curriculum) ? 0f : curriculum.backNudge());
		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, System.nanoTime());
		fighter.backOutRule(Report.backRule(cfg, curriculum));
		fighter.planHolds(curriculum.current().reward.mixTiming > 0f);
		return new LoadedFighter(cfg, curriculum, learner, fighter, SwordFiles.readFrameAgeMillis() / 50f, curriculum.current().bot.agentImmortal);
	}

	/**
	 * Loads a saved copy of the network to play the opponent in self-play. The field of view and the
	 * config are the running session's.
	 *
	 * @param headMask      the controls it plays with
	 * @param frameAgeTicks how old the agent's own frames are when it decides on them, in ticks
	 */
	static LoadedFighter loadRival(Path checkpoint, Config cfg, Curriculum curriculum, int headMask, float frameAgeTicks) throws IOException {
		Learner learner = new Learner(cfg, System.nanoTime(), 1);
		try {
			learner.load(checkpoint);
		} catch (IOException | RuntimeException e) {
			learner.close();
			throw e;
		}
		// The copy picks its actions exactly as the agent does while it trains.
		learner.setJumpNudge(curriculum.jumpNudge());
		learner.setBackNudge(Report.backRule(cfg, curriculum) ? 0f : curriculum.backNudge());
		Fighter fighter = new Fighter(learner, cfg, headMask, curriculum.current().ideas, 1f, System.nanoTime());
		fighter.backOutRule(Report.backRule(cfg, curriculum));
		fighter.planHolds(curriculum.current().reward.mixTiming > 0f);
		// A copy that plays the opponent in training is shown its health as it is: that is its training.
		return new LoadedFighter(cfg, curriculum, learner, fighter, frameAgeTicks, false);
	}

	boolean canAttack() {
		return ActionSpace.unlocked(fighter.headMask(), ActionSpace.ATTACK);
	}

	/** What it has learned so far, for the message shown when a fight starts. */
	String skillLine() {
		String stage = "stage " + (curriculum.stageIndex() + 1) + "/" + curriculum.stageCount() + " (" + curriculum.current().name + ")";
		if (!canAttack()) {
			return "It has only reached " + stage + " and has not learned to hit yet, so it will aim and move but not swing.";
		}
		String mix = fighter.ideasLearned() ? "strafe and hit timing plan: on, as in its training"
				: fighter.assists() ? "strafe and hit timing mix-ups: not part of its training yet, so they are done for it by rule"
				: "strafe and hit timing plan: off, as in its training";
		return "Playing with what it knows at " + stage + "; " + mix + ".";
	}

	@Override
	public void close() {
		learner.close();
	}
}
