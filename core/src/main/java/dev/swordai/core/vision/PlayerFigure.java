package dev.swordai.core.vision;

/**
 * The player model as the game draws it, worked out from a {@link PlayerPose}: six boxes (head,
 * body, two arms, two legs) with the sizes, pivots and animation of the game's own model, each
 * including its outer skin layer. The numbers are the game's (1.21.11: {@code HumanoidModel},
 * {@code PlayerModel}, {@code LivingEntityRenderer}); the only things left out are what the
 * opponent holds and poses that never occur in a sword fight (swimming, gliding, riding, sleeping).
 *
 * <p>Not thread-safe.
 */
public final class PlayerFigure {
	public static final int HEAD = 0;
	public static final int BODY = 1;
	public static final int RIGHT_ARM = 2;
	public static final int LEFT_ARM = 3;
	public static final int RIGHT_LEG = 4;
	public static final int LEFT_LEG = 5;

	/** The model is drawn at 15/16 scale; its units are sixteenths of a block. */
	static final double SCALE = 0.9375;
	private static final double UNIT = SCALE / 16.0;
	/** The model's origin sits this many blocks above the feet (before scaling). */
	private static final double ORIGIN_HEIGHT = 1.501;
	/** A crouching player is drawn two model units lower. */
	private static final double CROUCH_DROP = 2.0 / 16.0;
	private static final float DEG = (float) (Math.PI / 180.0);
	private static final float PI = (float) Math.PI;

	final Part[] parts = {
			// The hat layer is half a unit larger than the head; the other layers a quarter.
			new Part(true, 0f, 0f, 0f, -4f, -8f, -4f, 8f, 8f, 8f, 0.5f),
			new Part(false, 0f, 0f, 0f, -4f, 0f, -2f, 8f, 12f, 4f, 0.25f),
			new Part(false, -5f, 2f, 0f, -3f, -2f, -2f, 4f, 12f, 4f, 0.25f),
			new Part(false, 5f, 2f, 0f, -1f, -2f, -2f, 4f, 12f, 4f, 0.25f),
			new Part(false, -1.9f, 12f, 0f, -2f, 0f, -2f, 4f, 12f, 4f, 0.25f),
			new Part(false, 1.9f, 12f, 0f, -2f, 0f, -2f, 4f, 12f, 4f, 0.25f),
	};

	private final double[] model = new double[9];
	private final double[] root = new double[9];
	private final double[] tmp = new double[3];

	/**
	 * Poses the model and places it in the world, relative to an eye.
	 *
	 * @param eyeX where the viewer's eye is; the boxes come out relative to it
	 */
	public void pose(PlayerPose p, double eyeX, double eyeY, double eyeZ) {
		for (Part part : parts) {
			part.rest();
		}
		Part head = parts[HEAD];
		Part body = parts[BODY];
		Part rightArm = parts[RIGHT_ARM];
		Part leftArm = parts[LEFT_ARM];
		Part rightLeg = parts[RIGHT_LEG];
		Part leftLeg = parts[LEFT_LEG];

		head.xRot = p.headPitch * DEG;
		head.yRot = ViewGeometry.wrapDegrees(p.headYaw - p.bodyYaw) * DEG;

		// The walk cycle: arms and legs swing against each other.
		float swing = p.walkPos * 0.6662f;
		float amount = Math.min(1f, p.walkSpeed);
		rightArm.xRot = (float) Math.cos(swing + PI) * 2f * amount * 0.5f;
		leftArm.xRot = (float) Math.cos(swing) * 2f * amount * 0.5f;
		rightLeg.xRot = (float) Math.cos(swing) * 1.4f * amount;
		leftLeg.xRot = (float) Math.cos(swing + PI) * 1.4f * amount;
		rightLeg.yRot = 0.005f;
		leftLeg.yRot = -0.005f;
		rightLeg.zRot = 0.005f;
		leftLeg.zRot = -0.005f;

		// The hand with something in it is held a little forward and swings half as far.
		Part mainArm = p.rightHanded ? rightArm : leftArm;
		if (p.holdsItem) {
			mainArm.xRot = mainArm.xRot * 0.5f - PI / 10f;
		}

		float attack = p.attackTime;
		if (attack > 0f) {
			body.yRot = (float) Math.sin(Math.sqrt(attack) * 2.0 * Math.PI) * 0.2f;
			if (!p.rightHanded) {
				body.yRot = -body.yRot;
			}
			rightArm.z = (float) Math.sin(body.yRot) * 5f;
			rightArm.x = (float) -Math.cos(body.yRot) * 5f;
			leftArm.z = (float) -Math.sin(body.yRot) * 5f;
			leftArm.x = (float) Math.cos(body.yRot) * 5f;
			rightArm.yRot += body.yRot;
			leftArm.yRot += body.yRot;
			leftArm.xRot += body.yRot;
			float eased = 1f - (1f - attack) * (1f - attack) * (1f - attack) * (1f - attack);
			float lift = (float) Math.sin(eased * PI);
			float reach = (float) Math.sin(attack * PI) * -(head.xRot - 0.7f) * 0.75f;
			mainArm.xRot -= lift * 1.2f + reach;
			mainArm.yRot += body.yRot * 2f;
			mainArm.zRot += (float) Math.sin(attack * PI) * -0.4f;
		}

		if (p.crouching) {
			body.xRot = 0.5f;
			rightArm.xRot += 0.4f;
			leftArm.xRot += 0.4f;
			rightLeg.z += 4f;
			leftLeg.z += 4f;
			head.y += 4.2f;
			body.y += 3.2f;
			leftArm.y += 3.2f;
			rightArm.y += 3.2f;
		}

		// The arms never hang quite still.
		float sway = (float) Math.cos(p.age * 0.09f) * 0.05f + 0.05f;
		float nod = (float) Math.sin(p.age * 0.067f) * 0.05f;
		rightArm.zRot += sway;
		rightArm.xRot += nod;
		leftArm.zRot -= sway;
		leftArm.xRot -= nod;

		// From the model's own frame to the world: turned to face the body's yaw, and flipped, since
		// the model is built upside down with its face towards -Z.
		double turn = Math.toRadians(180.0 - p.bodyYaw);
		double c = Math.cos(turn);
		double s = Math.sin(turn);
		root[0] = -c;
		root[1] = 0;
		root[2] = s;
		root[3] = 0;
		root[4] = -1;
		root[5] = 0;
		root[6] = s;
		root[7] = 0;
		root[8] = c;

		double feetX = p.x - eyeX;
		double feetY = p.y - eyeY - (p.crouching ? CROUCH_DROP : 0.0);
		double feetZ = p.z - eyeZ;
		for (Part part : parts) {
			part.rotation(model);
			// Centre of the box in the model's frame, in model units.
			double mx = part.x + model[0] * part.midX + model[1] * part.midY + model[2] * part.midZ;
			double my = part.y + model[3] * part.midX + model[4] * part.midY + model[5] * part.midZ;
			double mz = part.z + model[6] * part.midX + model[7] * part.midY + model[8] * part.midZ;
			tmp[0] = mx * UNIT;
			tmp[1] = (my / 16.0 - ORIGIN_HEIGHT) * SCALE;
			tmp[2] = mz * UNIT;
			part.centre[0] = feetX + root[0] * tmp[0] + root[1] * tmp[1] + root[2] * tmp[2];
			part.centre[1] = feetY + root[3] * tmp[0] + root[4] * tmp[1] + root[5] * tmp[2];
			part.centre[2] = feetZ + root[6] * tmp[0] + root[7] * tmp[1] + root[8] * tmp[2];
			multiply(root, model, part.axes);
			part.half[0] = part.halfX * UNIT;
			part.half[1] = part.halfY * UNIT;
			part.half[2] = part.halfZ * UNIT;
		}
	}

	private static void multiply(double[] a, double[] b, double[] out) {
		for (int r = 0; r < 3; r++) {
			for (int col = 0; col < 3; col++) {
				out[r * 3 + col] = a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col];
			}
		}
	}

	/** One box of the model. */
	static final class Part {
		final boolean isHead;
		private final float restX;
		private final float restY;
		private final float restZ;
		/** Middle of the box from its pivot, and half its size including the outer layer; model units. */
		final float midX;
		final float midY;
		final float midZ;
		final float halfX;
		final float halfY;
		final float halfZ;

		// The pose: pivot in model units and the turn about it.
		float x;
		float y;
		float z;
		float xRot;
		float yRot;
		float zRot;

		/** Where the box ended up: its middle relative to the eye, in blocks. */
		final double[] centre = new double[3];
		/** Row-major 3x3: column j is the world direction of the box's own axis j. */
		final double[] axes = new double[9];
		final double[] half = new double[3];

		Part(boolean isHead, float pivotX, float pivotY, float pivotZ, float minX, float minY, float minZ,
				float sizeX, float sizeY, float sizeZ, float outerLayer) {
			this.isHead = isHead;
			this.restX = pivotX;
			this.restY = pivotY;
			this.restZ = pivotZ;
			this.midX = minX + sizeX * 0.5f;
			this.midY = minY + sizeY * 0.5f;
			this.midZ = minZ + sizeZ * 0.5f;
			this.halfX = sizeX * 0.5f + outerLayer;
			this.halfY = sizeY * 0.5f + outerLayer;
			this.halfZ = sizeZ * 0.5f + outerLayer;
		}

		void rest() {
			x = restX;
			y = restY;
			z = restZ;
			xRot = 0f;
			yRot = 0f;
			zRot = 0f;
		}

		/** The part's turn as a matrix: about X first, then Y, then Z, as the game applies them. */
		void rotation(double[] m) {
			double cx = Math.cos(xRot);
			double sx = Math.sin(xRot);
			double cy = Math.cos(yRot);
			double sy = Math.sin(yRot);
			double cz = Math.cos(zRot);
			double sz = Math.sin(zRot);
			m[0] = cz * cy;
			m[1] = cz * sy * sx - sz * cx;
			m[2] = cz * sy * cx + sz * sx;
			m[3] = sz * cy;
			m[4] = sz * sy * sx + cz * cx;
			m[5] = sz * sy * cx - cz * sx;
			m[6] = -sy;
			m[7] = cy * sx;
			m[8] = cy * cx;
		}
	}
}
