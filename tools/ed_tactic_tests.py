import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/rl/TacticNet.java')
e.rep("""	private void backward(Workspace ws, float[] vec) {""", """	/** Back-propagates {@code ws.dLogits} into {@code ws.grads}; must follow {@link #forward} on the same workspace and input. */
	void backward(Workspace ws, float[] vec) {""")
e.save()

e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/nn/GradCheckTest.java')
e.rep("""			if (p.name.contains(".down.") || p.name.startsWith("vec.wide.out") || p.name.startsWith("critic.task")""",
"""			if (p.name.contains(".down.") || p.name.startsWith("vec.wide.out") || p.name.startsWith("critic.task") || p.name.startsWith("tactic.in")""")
e.rep("""	@Test
	void criticNetGrown() {""", """	@Test
	void actorNetToldATactic() {
		Random rng = new Random(51);
		ActorNet net = new ActorNet(52, true, true, 16, 1, 24, Tactic.COUNT);
		wakeGrown(net.params, rng);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec, Tactic.SPACE);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec, Tactic.SPACE);
			return dot(c, ws.logits);
		}, 53, 25);
	}

	@Test
	void criticNetGrown() {""")
e.save()

e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/GrowthTest.java')
e.rep("""	@Test
	void aGrownCriticValuesAsTheOneItWasGrownFrom() throws IOException {""", """	@Test
	void anActorToldATacticChoosesAsBeforeUntilItLearns() throws IOException {
		Random rng = new Random(47);
		ActorNet small = new ActorNet(48, true, true, 64, 2, 256);
		shake(small.params, rng);
		ActorNet big = new ActorNet(49, true, true, 64, 2, 256, Tactic.COUNT);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(2, big.params.missing(), "only the tactic input is missing");
		ActorNet.Workspace a = small.newWorkspace();
		ActorNet.Workspace b = big.newWorkspace();
		for (int trial = 0; trial < 20; trial++) {
			byte[] peri = new byte[ObsSpec.PERI_SIZE];
			byte[] fovea = new byte[ObsSpec.FOVEA_SIZE];
			rng.nextBytes(peri);
			rng.nextBytes(fovea);
			float[] vec = new float[ObsSpec.VEC];
			for (int i = 0; i < vec.length; i++) {
				vec[i] = rng.nextFloat() < 0.3f ? 0f : (float) rng.nextGaussian();
			}
			vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = trial % 2;
			small.forward(a, peri, fovea, vec);
			big.forward(b, peri, fovea, vec, trial % (Tactic.COUNT + 1) - 1);
			for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
				assertEquals(a.logits[i], b.logits[i], 1e-5f, "logit " + i + " in trial " + trial);
			}
		}
	}

	@Test
	void aGrownCriticValuesAsTheOneItWasGrownFrom() throws IOException {""")
e.save()
