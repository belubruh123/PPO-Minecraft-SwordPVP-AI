package dev.swordai.core.pvp;

/**
 * Two sword fighters on flat ground with the game's movement and hit rules, near enough to tick-test
 * the techniques (and to try a bot against a bot in a second): walking, sprinting and jumping with the
 * game's speeds, friction and gravity; the sword's cooldown; sprint hits with their extra knockback and
 * the attacker's slow-down; crits (falling, charged, not sprinting) at one and a half times the damage;
 * the knockback lift on the ground only; the half second in which a fighter just hit takes no more of
 * the same.
 *
 * <p>Order of a tick: both fighters choose their keys from what they knew at the end of the last tick;
 * both move; then the swings are made. A hit's knockback is therefore used by the victim's next move,
 * which is the move a jump reset is pressed on, as in the game.
 *
 * <p>Not the game: no armour, no hitbox aiming (a swing in reach always connects), no lag unless given.
 */
public final class ToyFight {
	public static final double WALK = 0.1;
	public static final double SPRINT_FACTOR = 1.3;
	public static final double AIR = 0.02;
	public static final double GROUND_FRICTION = 0.546;
	public static final double AIR_FRICTION = 0.91;
	public static final double JUMP = 0.42;
	public static final double SPRINT_JUMP = 0.2;
	public static final double KNOCKBACK = 0.4;
	public static final double SPRINT_KNOCKBACK = 0.5;
	public static final float SWORD_DAMAGE = 7f;

	public final Body[] body = {new Body(), new Body()};
	public final Duel[] view = {new Duel(), new Duel()};
	public final Events[] events = {new Events(), new Events()};
	private final Events[] next = {new Events(), new Events()};
	private final int[] ticker = {100, 100};
	/** What each carries into its next move (the game's delta movement); {@link Body#vx} is the move it was seen to make. */
	private final double[][] vel = new double[2][3];
	private final double[] fall = new double[2];
	private final float[] lastHurt = new float[2];
	public final float[] dealt = new float[2];
	/** Ticks late a hit's knockback reaches each fighter (its connection); 0 for at once. */
	public final int[] knockLag = new int[2];
	/** Knockback on its way to each fighter: the tick it arrives, its direction, and whether it is a sprint hit's. */
	private final java.util.ArrayDeque<double[]>[] coming = newQueues();
	public final int[][] hits = new int[2][4];
	public int tick;

	/** The two a distance apart along x, facing each other, swords back. */
	public void place(double distance) {
		for (int i = 0; i < 2; i++) {
			Body b = body[i];
			b.x = i == 0 ? 0 : distance;
			b.y = 0;
			b.z = 0;
			b.vx = b.vy = b.vz = 0;
			vel[i][0] = vel[i][1] = vel[i][2] = 0;
			b.onGround = true;
			b.sprinting = false;
			b.sinceSwing = 1000;
			b.sinceHurt = 1000;
			b.sinceJump = 1000;
			b.airTicks = 0;
			ticker[i] = 100;
			fall[i] = 0;
			lastHurt[i] = 0f;
			events[i].clear();
			next[i].clear();
			dealt[i] = 0f;
			coming[i].clear();
			java.util.Arrays.fill(hits[i], 0);
		}
		tick = 0;
		look();
		refresh();
	}

	@SuppressWarnings("unchecked")
	private static java.util.ArrayDeque<double[]>[] newQueues() {
		return new java.util.ArrayDeque[] {new java.util.ArrayDeque<double[]>(), new java.util.ArrayDeque<double[]>()};
	}

	private void look() {
		for (int i = 0; i < 2; i++) {
			Body me = body[i];
			Body them = body[1 - i];
			me.yaw = dev.swordai.core.vision.ViewGeometry.yawTo(them.x - me.x, them.z - me.z);
		}
	}

	private void refresh() {
		for (int i = 0; i < 2; i++) {
			body[i].charge = Math.min(1f, (ticker[i] + 0.5f) / Body.SWORD_TICKS);
		}
		for (int i = 0; i < 2; i++) {
			view[i].me.copyFrom(body[i]);
			view[i].them.copyFrom(body[1 - i]);
			view[i].measure();
		}
	}

	/** One tick with these keys; afterwards {@link #events} hold what happened in it, for each side. */
	public void step(Keys a, Keys b) {
		Keys[] keys = {a, b};
		look();
		for (int i = 0; i < 2; i++) {
			while (!coming[i].isEmpty() && coming[i].peekFirst()[0] <= tick) {
				double[] kb = coming[i].pollFirst();
				knock(i, kb[1], kb[2], kb[3] > 0);
			}
			move(i, keys[i]);
		}
		for (int i = 0; i < 2; i++) {
			next[i].clear();
		}
		for (int i = 0; i < 2; i++) {
			if (keys[i].attack) {
				swing(i);
			}
		}
		for (int i = 0; i < 2; i++) {
			events[i].copyFrom(next[i]);
			ticker[i]++;
			Body me = body[i];
			me.sinceSwing = Math.min(me.sinceSwing + 1, 1000);
			me.sinceHurt = Math.min(me.sinceHurt + 1, 1000);
			if (events[i].iSwung) {
				me.sinceSwing = 0;
			}
			if (events[i].theyHit != Events.NONE) {
				me.sinceHurt = 0;
			}
		}
		tick++;
		refresh();
	}

	private void move(int i, Keys k) {
		Body me = body[i];
		double[] v = vel[i];
		double prevY = me.y;
		// As the game's client has it: the sprint key starts a sprint, only letting go of W (or a sprint hit) ends it.
		if (k.forward <= 0) {
			me.sprinting = false;
		} else if (k.sprint) {
			me.sprinting = true;
		}
		me.sinceJump = Math.min(me.sinceJump + 1, 1000);
		if (k.jump && me.onGround) {
			v[1] = JUMP;
			if (me.sprinting) {
				v[0] += me.lookX() * SPRINT_JUMP;
				v[2] += me.lookZ() * SPRINT_JUMP;
			}
			me.sinceJump = 0;
		}
		double f = k.forward * 0.98;
		double s = k.strafe * 0.98;
		double len = Math.sqrt(f * f + s * s);
		if (len > 1.0) {
			f /= len;
			s /= len;
		}
		double speed = (me.onGround ? WALK : AIR) * (me.sprinting ? SPRINT_FACTOR : 1.0);
		// Forward along the look; right is the look turned clockwise seen from above.
		double lx = me.lookX();
		double lz = me.lookZ();
		double rx = -lz;
		double rz = lx;
		v[0] += (f * lx + s * rx) * speed;
		v[2] += (f * lz + s * rz) * speed;
		me.vx = v[0];
		me.vy = v[1];
		me.vz = v[2];
		me.x += v[0];
		me.y += v[1];
		me.z += v[2];
		if (me.y <= 0) {
			me.vy = -prevY;
			me.y = 0;
			v[1] = 0;
			me.onGround = true;
		} else {
			me.onGround = false;
		}
		v[0] *= me.onGround ? GROUND_FRICTION : AIR_FRICTION;
		v[2] *= me.onGround ? GROUND_FRICTION : AIR_FRICTION;
		if (!me.onGround) {
			v[1] = (v[1] - 0.08) * 0.98;
		}
		fall[i] = me.onGround ? 0 : me.y < prevY ? fall[i] + (prevY - me.y) : fall[i];
		me.airTicks = me.onGround ? 0 : me.airTicks + 1;
	}

	private void swing(int i) {
		Body me = body[i];
		Body them = body[1 - i];
		next[i].iSwung = true;
		next[1 - i].theySwung = true;
		float charge = Math.min(1f, (ticker[i] + 0.5f) / Body.SWORD_TICKS);
		ticker[i] = 0;
		if (Duel.eyeToBox(me, them) > Duel.REACH) {
			return;
		}
		boolean crit = charge > Body.CHARGED && fall[i] > 0 && !me.onGround && !me.sprinting;
		boolean knock = charge > Body.CHARGED && me.sprinting;
		float damage = SWORD_DAMAGE * (0.2f + charge * charge * 0.8f) * (crit ? 1.5f : 1f);
		int j = 1 - i;
		if (them.sinceHurt < 10) {
			if (damage <= lastHurt[j]) {
				return;
			}
			damage -= lastHurt[j];
		}
		lastHurt[j] = damage + (them.sinceHurt < 10 ? lastHurt[j] : 0f);
		dealt[i] += damage;
		int kind = Events.kindOf(charge, me.sprinting, crit);
		hits[i][kind]++;
		next[i].iHit = kind;
		next[j].theyHit = kind;
		double dx = them.x - me.x;
		double dz = them.z - me.z;
		double d = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
		dx /= d;
		dz /= d;
		if (knock) {
			vel[i][0] *= 0.6;
			vel[i][2] *= 0.6;
			me.sprinting = false;
		}
		if (knockLag[j] > 0) {
			coming[j].addLast(new double[] {tick + knockLag[j], dx, dz, knock ? 1 : 0});
		} else {
			knock(j, dx, dz, knock);
		}
	}

	/** The knockback goes into what the victim carries into its next move; it lifts only one standing on the ground. */
	private void knock(int j, double dx, double dz, boolean sprintHit) {
		Body them = body[j];
		double[] v = vel[j];
		if (sprintHit) {
			v[0] = v[0] / 2 + dx * SPRINT_KNOCKBACK;
			v[2] = v[2] / 2 + dz * SPRINT_KNOCKBACK;
			if (them.onGround) {
				v[1] = Math.min(0.4, v[1] / 2 + 0.4);
			}
		}
		v[0] = v[0] / 2 + dx * KNOCKBACK;
		v[2] = v[2] / 2 + dz * KNOCKBACK;
		if (them.onGround) {
			v[1] = Math.min(0.4, v[1] / 2 + 0.4);
		}
	}
}
