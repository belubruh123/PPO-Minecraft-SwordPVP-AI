import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed

e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/nn/GradCheckTest.java')
e.rep("""|| p.name.startsWith("tactic.in")""", """|| p.name.startsWith("tactic.in") || p.name.startsWith("opp.")""")
e.rep("""	@Test
	void criticNetGrown() {""", """	@Test
	void actorNetReadingTheOpponent() {
		Random rng = new Random(61);
		ActorNet net = new ActorNet(62, true, true, 16, 1, 24, Tactic.COUNT, OpponentReader.Z);
		wakeGrown(net.params, rng);
		ActorNet.Workspace ws = net.newWorkspace();
		byte[] peri = randomBytes(rng, ObsSpec.PERI_SIZE, 0.8f);
		byte[] fovea = randomBytes(rng, ObsSpec.FOVEA_SIZE, 0.6f);
		float[] vec = randomFloats(rng, ObsSpec.VEC, 0.3f);
		vec[ObsSpec.VEC_SCALARS + ObsSpec.SC_ON_GROUND] = 1f;
		float[] opp = new float[OpponentReader.Z];
		for (int i = 0; i < opp.length; i++) {
			opp[i] = (float) Math.tanh(rng.nextGaussian());
		}
		float[] c = randomFloats(rng, ActionSpace.TOTAL_LOGITS, 0f);

		net.forward(ws, peri, fovea, vec, Tactic.BACK, opp);
		System.arraycopy(c, 0, ws.dLogits, 0, c.length);
		net.backward(ws, peri, fovea, vec);
		check(net.params, ws.grads, () -> {
			net.forward(ws, peri, fovea, vec, Tactic.BACK, opp);
			return dot(c, ws.logits);
		}, 63, 25);
	}

	@Test
	void criticNetGrown() {""")
e.rep("""import dev.swordai.core.rl.ObsSpec;
""", """import dev.swordai.core.rl.ObsSpec;
import dev.swordai.core.rl.OpponentReader;
""")
e.save()

e = Ed('D:/swordai/core/src/test/java/dev/swordai/core/rl/GrowthTest.java')
e.rep("""	@Test
	void aGrownCriticValuesAsTheOneItWasGrownFrom() throws IOException {""", """	@Test
	void anActorHandedTheOpponentReadingChoosesAsBeforeUntilItLearns() throws IOException {
		Random rng = new Random(57);
		ActorNet small = new ActorNet(58, true, true, 64, 2, 256, Tactic.COUNT);
		shake(small.params, rng);
		ActorNet big = new ActorNet(59, true, true, 64, 2, 256, Tactic.COUNT, OpponentReader.Z);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(save(small.params)))) {
			big.params.read(in);
		}
		assertEquals(4, big.params.missing(), "only the reader's two inputs are missing");
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
			float[] opp = new float[OpponentReader.Z];
			for (int i = 0; i < opp.length; i++) {
				opp[i] = (float) Math.tanh(rng.nextGaussian());
			}
			small.forward(a, peri, fovea, vec, trial % Tactic.COUNT);
			big.forward(b, peri, fovea, vec, trial % Tactic.COUNT, opp);
			for (int i = 0; i < ActionSpace.TOTAL_LOGITS; i++) {
				assertEquals(a.logits[i], b.logits[i], 1e-5f, "logit " + i + " in trial " + trial);
			}
		}
	}

	@Test
	void aGrownCriticValuesAsTheOneItWasGrownFrom() throws IOException {""")
e.save()
