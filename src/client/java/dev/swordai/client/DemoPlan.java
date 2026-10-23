package dev.swordai.client;

import dev.swordai.core.config.Config;
import dev.swordai.core.curriculum.StageSpec;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * {@code /swordai demo [kind]}: the user fights the training's scripted opponents themselves, in the
 * training's own arena and on its own terms, while each step is recorded the way the network would
 * have seen and done it ({@code DemoFile}), to be learned from ({@code Config.demoWeight}). The user,
 * 22 October: "I can do later but make it so I can start when I want".
 *
 * <p>The fights are set up as the training's frozen checks set them up (spacers only, or the hard mix),
 * at normal speed, with nothing learned and nothing saved. Training that was running when it began
 * goes on by itself when it ends.
 */
final class DemoPlan {
	/**
	 * What can be asked for: the spacer that comes in for its hits, the one that stands at the edge of its reach
	 * (left out of "spacer": the user, 22 October, "it just keep stand there and not fight me"), the checks'
	 * hard mix, or one other kind.
	 */
	static final List<String> KINDS = List.of("spacer", "stander", "mix", "jumper", "timer", "punisher", "whiffer", "circler", "corner", "noknock", "trader", "backer");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	final String kind;
	/** Training was running and goes on once this ends. */
	final boolean resume;
	private int fights;

	DemoPlan(String kind, boolean resume) {
		this.kind = kind;
		this.resume = resume;
	}

	/** The fights of this plan, in the configuration just read: as in the frozen checks, at normal speed. */
	void apply(Config cfg) {
		cfg.trainSpeed = 1f;
		// The user sees a player, not the colour key the network's pictures are taken with.
		cfg.showOpponentSkin = true;
		cfg.actorLr = 0f;
		cfg.criticLr = 0f;
		cfg.adaptiveLr = false;
		cfg.minActorLr = 0f;
		cfg.silWeight = 0f;
		cfg.demoWeight = 0f;
		cfg.mutateShare = 0f;
		cfg.evalSeed = 0;
		cfg.traceFights = false;
		cfg.hardOpponents = false;
		cfg.replayLostShare = 0f;
		cfg.selfCritShare = 1f;
		cfg.oldModelShare = kind.equals("mix") ? 0.5f : 0f;
		// One kind: every fight is the crit stage's (with self-play on, that is where rehearsal goes; Curriculum.pickEpisodeStage).
		cfg.rehearsalProbability = kind.equals("mix") ? 0.6f : 1f;
		for (StageSpec s : cfg.stages) {
			if (!s.name.equals("Crits")) {
				continue;
			}
			s.drillShare = 0f;
			s.critDrillShare = 0f;
			s.spacerShare = kind.equals("stander") ? 1f : share("spacer", 0.12f);
			s.timerShare = share("timer", 0.12f);
			s.whifferShare = share("whiffer", 0.08f);
			s.punisherShare = share("punisher", 0.1f);
			s.jumperShare = share("jumper", 0.1f);
			s.traderShare = share("trader", 0.04f);
			s.runnerShare = kind.equals("mix") ? 0.04f : 0f;
			s.circlerShare = share("circler", 0.08f);
			s.cornerShare = share("corner", 0.06f);
			s.noKnockShare = share("noknock", 0.08f);
			s.oddShare = kind.equals("mix") ? 0.04f : 0f;
			s.backerShare = share("backer", 0.04f);
		}
	}

	/** The share of this kind: all of them when it is the one asked for, its share of the mix in the mix. */
	private float share(String of, float inMix) {
		return kind.equals(of) ? 1f : kind.equals("mix") ? inMix : 0f;
	}

	/** Spacer fights: whether the one that stands is among them (the mix), all of them (stander) or none (spacer). */
	int spacerStyle(java.util.Random rng) {
		return kind.equals("stander") ? 0 : kind.equals("spacer") ? 1 + rng.nextInt(2) : rng.nextInt(3);
	}

	/** Where the next fight is written. */
	Path nextFile(String opponent) {
		fights++;
		return SwordFiles.demos().resolve(String.format(Locale.ROOT, "%s-%s-%03d.bin.gz", LocalDateTime.now().format(STAMP),
				opponent.replaceAll("[^A-Za-z0-9]+", "_"), fights));
	}
}
