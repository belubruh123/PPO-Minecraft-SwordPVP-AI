package dev.swordai.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import dev.swordai.core.env.Delay;
import dev.swordai.core.pvp.Body;
import dev.swordai.core.pvp.Duel;
import dev.swordai.core.pvp.Events;
import dev.swordai.core.pvp.Keys;
import dev.swordai.core.pvp.PvpPlayer;
import dev.swordai.core.pvp.Style;
import dev.swordai.core.pvp.Technique;
import dev.swordai.core.vision.ViewGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * The sparring bot of the technique school: a Carpet fake player played by a {@link PvpPlayer} with a
 * drawn {@link Style}, through real player physics and the real attack code. This class only turns
 * the game into the player's view (the other fighter as late as the style's connection, the hits as
 * its own game would feel them) and its keys back into the game.
 *
 * <p>Timing. A hit's knockback reaches this bot {@link Style#ping} ticks late, at least one (no real
 * player has none); see {@link KnockbackLag}. It is applied at the end of a server tick, right before
 * {@link #tick}, which is when the bot feels it: so a jump pressed on feeling it (a jump reset) goes
 * into the very move the knockback is in, as it does for a person. With no delay at all the game
 * would move the bot with the knockback before the bot could press anything.
 *
 * <p>Server thread only.
 */
public final class TechniqueBot {
	/** What the bot did on the last tick, for the trainer: its technique and keys. Read from any thread. */
	private static volatile int lastTechnique;
	private static volatile int lastKeys;

	private final PvpPlayer player = new PvpPlayer();
	private final Duel view = new Duel();
	private final Events events = new Events();
	private final Keys keys = new Keys();
	private final Random rng = new Random();
	private final Body botBody = new Body();
	private final Body agentNow = new Body();
	private final Delay<Body> seenAgent = new Delay<>(ReplicaBody.MAX_LAG_TICKS);
	/** Hits on the bot still on their way to it (its connection), and what it has seen of the agent, late. */
	private final ArrayDeque<int[]> hitsComing = new ArrayDeque<>();
	private final ArrayDeque<int[]> seenComing = new ArrayDeque<>();
	private Style style = new Style();
	private long now;
	private boolean active;
	private boolean agentWasOnGround = true;
	private boolean agentWasSwinging;
	private int agentLastSwingTime;
	/** Ticks since the agent's arm was last seen to begin a swing: what its sword is judged by, as a person judges it. */
	private int agentSinceSwing = 1000;
	private boolean botWasOnGround = true;
	private Vec3 agentLast;
	private Vec3 botLast;
	private float noiseYaw;
	private float noisePitch;
	private float noiseYawGoal;
	private float noisePitchGoal;
	private int noiseTicks;
	/** The bot's own swing on the last tick, for its next tick's events. */
	private boolean swungLast;

	public static int lastTechnique() {
		return lastTechnique;
	}

	public static int lastKeys() {
		return lastKeys;
	}

	/** A new fight with this style; its chances drawn from {@code seed}. */
	void start(Style s, long seed) {
		style = s;
		rng.setSeed(seed ^ 0x7EC4L);
		player.start(s, seed);
		seenAgent.clear();
		seenAgent.setTicks(Math.max(0, s.lag));
		hitsComing.clear();
		seenComing.clear();
		now = 0;
		active = true;
		agentLast = null;
		botLast = null;
		agentWasOnGround = true;
		botWasOnGround = true;
		agentWasSwinging = false;
		agentSinceSwing = 1000;
		swungLast = false;
		events.clear();
		lastTechnique = Technique.BASE.ordinal();
		lastKeys = 0;
	}

	void stop() {
		active = false;
	}

	boolean active() {
		return active;
	}

	Style style() {
		return style;
	}

	PvpPlayer player() {
		return player;
	}

	/** How late the bot's knockback comes, ticks: never less than one; see the class comment. */
	int ping() {
		return Math.max(1, style.ping);
	}

	/** A hit of the agent's landed on the bot, of this kind ({@link Events}): it feels it when the knockback reaches it. */
	void tookHit(int kind) {
		hitsComing.addLast(new int[] {(int) (now + ping()), kind});
	}

	/** A hit of the bot's landed on the agent: it sees it land as late as it sees the agent. */
	void landedHit(int kind) {
		seenComing.addLast(new int[] {(int) (now + Math.max(0, style.lag)), kind});
	}

	void tick(ServerPlayer bot, ServerPlayer agent) {
		now++;
		EntityPlayerActionPack pack = ((ServerPlayerInterface) bot).getActionPack();
		fill(botBody, bot, botLast, botWasOnGround);
		botLast = bot.position();
		fill(agentNow, agent, agentLast, agentWasOnGround);
		agentLast = agent.position();
		// The agent's arm, seen beginning a swing (a hit or a miss).
		boolean began = agent.swinging && (!agentWasSwinging || agent.swingTime < agentLastSwingTime);
		agentWasSwinging = agent.swinging;
		agentLastSwingTime = agent.swingTime;
		agentSinceSwing = began ? 0 : Math.min(agentSinceSwing + 1, 1000);
		agentNow.sinceSwing = agentSinceSwing;
		agentNow.charge = Math.min(1f, agentSinceSwing / Body.SWORD_TICKS);
		Body seen = seenAgent.pass(agentNow.copy());

		view.me.copyFrom(botBody);
		view.them.copyFrom(seen);
		view.measure();
		events.clear();
		events.iSwung = swungLast;
		events.theySwung = seen.sinceSwing == 0;
		while (!hitsComing.isEmpty() && hitsComing.peekFirst()[0] <= now) {
			events.theyHit = hitsComing.removeFirst()[1];
		}
		while (!seenComing.isEmpty() && seenComing.peekFirst()[0] <= now) {
			events.iHit = seenComing.removeFirst()[1];
		}
		agentWasOnGround = agent.onGround();
		botWasOnGround = bot.onGround();

		player.tick(view, events, keys);
		lastTechnique = keys.technique.ordinal();
		lastKeys = (keys.forward > 0 ? 1 : 0) | (keys.forward < 0 ? 2 : 0) | (keys.strafe < 0 ? 4 : 0) | (keys.strafe > 0 ? 8 : 0)
				| (keys.sprint ? 16 : 0) | (keys.jump ? 32 : 0) | (keys.attack ? 64 : 0);

		Vec3 botEye = bot.getEyePosition();
		Vec3 agentEye = new Vec3(seen.x, seen.eyeY(), seen.z);
		aim(bot, pack, agentEye.x - botEye.x, agentEye.y - 0.25 - botEye.y, agentEye.z - botEye.z);
		// Its keys, in its own frame: Carpet's strafing is positive to the left.
		pack.setForward(keys.forward);
		pack.setStrafing(-keys.strafe);
		boolean sprint = keys.sprint && keys.forward > 0;
		pack.setSprinting(sprint);
		if (!sprint && bot.isSprinting()) {
			// Letting go of W or of the sprint ends it on the spot; a swing on this tick is then no sprint hit.
			bot.setSprinting(false);
		}
		if (keys.jump && bot.onGround()) {
			pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
		}
		swungLast = false;
		if (keys.attack) {
			swing(bot, agent, seen);
		}
	}

	/** The swing as a client makes it: at what is under the crosshair within reach, or at nothing (a miss, which uses the sword up too). */
	private void swing(ServerPlayer bot, ServerPlayer agent, Body seen) {
		Vec3 botEye = bot.getEyePosition();
		double reach = bot.entityInteractionRange();
		// Where it sees the agent: its box moved to where the agent was seen.
		AABB box = agent.getBoundingBox().move(seen.x - agent.getX(), seen.y - agent.getY(), seen.z - agent.getZ()).inflate(agent.getPickRadius());
		Vec3 end = botEye.add(bot.getLookAngle().scale(reach));
		boolean canHit = box.contains(botEye) || box.clip(botEye, end).isPresent();
		swungLast = true;
		if (canHit) {
			bot.attack(agent);
		} else {
			bot.resetAttackStrengthTicker();
		}
		bot.swing(InteractionHand.MAIN_HAND);
	}

	private void fill(Body b, ServerPlayer p, Vec3 last, boolean wasOnGround) {
		b.x = p.getX();
		b.y = p.getY();
		b.z = p.getZ();
		if (last != null) {
			b.vx = p.getX() - last.x;
			b.vy = p.getY() - last.y;
			b.vz = p.getZ() - last.z;
		} else {
			b.vx = b.vy = b.vz = 0;
		}
		b.yaw = p.getYRot();
		b.pitch = p.getXRot();
		b.onGround = p.onGround();
		b.sprinting = p.isSprinting();
		b.charge = p.getAttackStrengthScale(0.5f);
		b.sinceHurt = p.hurtTime > 0 ? 10 - p.hurtTime : 1000;
		boolean tookOff = wasOnGround && !p.onGround() && b.vy > 0.3;
		b.sinceJump = tookOff ? 0 : Math.min(b.sinceJump + 1, 1000);
		b.airTicks = p.onGround() ? 0 : b.airTicks + 1;
		b.health = p.getHealth();
	}

	/** Turns towards the agent at the style's rate, with slowly drifting aim error. */
	private void aim(ServerPlayer bot, EntityPlayerActionPack pack, double dx, double dy, double dz) {
		if (--noiseTicks <= 0) {
			noiseTicks = 6;
			noiseYawGoal = (float) rng.nextGaussian() * style.aimNoise;
			noisePitchGoal = (float) rng.nextGaussian() * style.aimNoise * 0.5f;
		}
		noiseYaw += (noiseYawGoal - noiseYaw) * 0.35f;
		noisePitch += (noisePitchGoal - noisePitch) * 0.35f;
		float wantYaw = keys.lookAtThem ? ViewGeometry.yawTo(dx, dz) + noiseYaw : keys.yaw;
		float wantPitch = keys.lookAtThem ? ViewGeometry.pitchTo(dx, dy, dz) + noisePitch : keys.pitch;
		float rate = style.turnRate;
		float yaw = bot.getYRot() + Math.max(-rate, Math.min(rate, ViewGeometry.wrapDegrees(wantYaw - bot.getYRot())));
		float pitch = bot.getXRot() + Math.max(-rate, Math.min(rate, wantPitch - bot.getXRot()));
		pack.look(yaw, pitch);
		bot.setYHeadRot(bot.getYRot());
	}
}
