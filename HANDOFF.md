# SwordAI: a request for ideas (written 23 Oct 2026)

**What I'm asking you:** please read this and suggest **ideas only. Don't write or change any code.** Another AI (Claude) is building and training this project. Your suggestions go back to it, and it decides what to try and tests each idea on its own.

What would help most:
- New methods for the open problems in §7: crits, spacing, getting out of combos, and the gap between bots and humans.
- What you think is wrong with the approach so far.
- Published papers or known techniques that fit, from RL, game AI or fighting-game AI.
- Ranked by how likely each one is to help, with a short reason for each.

Below: the project, everything tried on 19–23 Oct, what worked and what didn't. All numbers are measured on screen, first person, with the network frozen, unless a line says otherwise.

---

## 1. What the project is

- **SwordAI**: a Fabric mod for Minecraft **1.21.11** (1.9+ combat with the attack cooldown). A reinforcement-learning agent plays sword PvP from **its own first-person screen picture plus a vector of numbers**.
- **Repo:** `D:\swordai`
  - `core/`: plain Java. The networks, PPO, rules and curriculum. JUnit tests in `core/src/test`.
  - `src/client/`: the Minecraft side. `Trainer.java`, `TrainingSession`, `SwordAiClient`, mixins, commands.
  - `src/main/`: the server side. `ArenaServer`, `BotBrain` (the scripted opponents).
- **Dev run:** `D:\swordai\run`
  - `run/swordai/config.json` holds the config. Scripts change it with `setcfg.py key=value`.
  - The client is controlled with `/swordai train|stop` written to `run/swordai/inbox.txt`.
  - Snapshots are kept in `run/kept-v24-HHMM/`.
- **Installed network** (in the user's profile "DRL AI", ModrinthApp): `run/kept-v21-final-1950` (from 19 Oct). Every new network is compared against it.
- **Hardware:** ASUS ROG Ally (Ryzen Z1E, integrated GPU). One machine, so either the user plays or training runs, never both. The GPU driver crashed twice (BSOD 0x119).
- `REQUIREMENTS.md` lists all of the user's requirements.

## 2. The user's goals (in their words, in order of weight)

1. **Maximize damage dealt and minimize damage taken.** Report damage dealt/taken **per minute per opponent**, not hit counts.
2. **Why it loses: spacing.** "They crit you and sprint hit back, so that is why you always lose." Good players hold ~3 blocks while their own sword is ready, come in on the AI's cooldown, crit, then sprint-hit. The AI rarely crits back.
3. **Target:** 80–90%+ round win rate, with real combos and crits. It must clearly beat what the old 0.14.0 model did **against people**.
4. **Hard rules:**
   - No weak hits (swing only with the sword ≥ ~0.9 back).
   - No swinging at air.
   - Few sweeps.
   - Swing back when comboed (but not "trigger-bot" waiting).
   - Approach straight.
   - Turn toward a lost opponent (don't spin).
   - Jump reset only inside a combo ("not too much, it's unfair").
   - Must handle high ping (60–100 ms, KB delayed 0.3–0.5 s).
5. **Train on screen in first person, never headless.** The user's rule #1. Headless training measurably transferred badly against the spacer.
6. New weaknesses are handled like this: **first build a scripted opponent that reproduces the person's style and beats the frozen model the same way**, then train against it.
7. **Latest complaint (23 Oct):** it gets comboed and dies. It doesn't strafe left/right out of the combo line and then come back in.

## 3. The agent

- **Inputs:**
  - Peripheral picture plus a fovea crop. Opponents are drawn in a "keyed" colour; the user can still see the real skin.
  - A vector of about 30 scalars:
    - own and opponent sword charge, distance, relative velocity, on ground, health;
    - ticks since hit dealt/taken (fading clocks), hits-taken-in-a-row;
    - opponent-style averages (away / edge / dist);
    - stats frames.
- **Policy:** feed-forward, no LSTM.
  - Actor ~150k params; a grown version (ResBlocks, Net2Net-style, zero-init) is ~300k with the tactics input.
  - Critic ~34k, with a task one-hot.
- **Action heads:** forward/back, strafe (0 none, 1 A, 2 D), sprint, jump, attack, yaw bin, pitch bin.
- **Algorithm:** PPO (adaptive LR, KL stop), training curriculum stages (Crits, Combo escape, Duel, Self-play…).
  - An **anchor**: KL to the installed network, weight 0.5.
  - Optional SIL (self-imitation), demo imitation, PFSP weighting of opponents by rounds lost.
- **Hand-written rules wrapped around the network** (each a switch in `Config`; the rule masks the head it controls so PPO doesn't train it):

  | Rule | What it does |
  |---|---|
  | `SwingGate` | No weak hits; no swing out of reach or off screen (`whiffGuard`). |
  | `AnswerRule` | Swings back right after being hit. ~78% of all swings come from rules, so the attack head's entropy collapsed. |
  | `JumpReset` | Jumps with W+sprint on the 2nd+ hit of a combo. |
  | `BackNudge` / backRule | S held after a hit, when there is no jump reset. |
  | `JumpIn` | Chase / hop / counter-crit jumps. |
  | `MeetJump` | Meets an opponent's jump-in with the sword. |
  | `StraightIn` | No strafe when out of reach. |
  | `AimRule`, `LostRule` | Camera onto the opponent after a hit; turn to where it went. |
  | `StepBack`, `EdgeHold`, dodge, edge-crit | All tried and turned **off** (see §5). |
  | `ComboStrafe` | New on 23 Oct (see §6). |

## 4. Opponents and how a network is judged

- **Scripted bots** (`BotBrain`):
  - The main ones: **spacer** (crit then back out to 3.3 blocks, walks in 6 ticks before its sword is ready, jump-crit) and **stander** (perfect 3-block blocker).
  - Also: timer, whiffer, jump-crit chaser ("jumper"), punisher, trader, circler, corner, no-knockback, backer, runner, odd.
  - Several are near-perfect scripts (0–1 tick reaction, ~1° aim), so 80–90% against them is not realistic.
- **Network opponents:** the **old 0.14.0 model** (its own config file), copies of itself.
- **Outside bot:** Carpet-PvP fake players. They made network-driven opponents weirdly weak, so they're not in use.
- **Hourly check** (`hourlycheck3.sh` → `hourly-check.log`): every 40 min of training, the snapshot plays frozen runs: spacer-only 7 min, mix 9 min, kept-lost-fights 4 min. The installed network is measured each hour as a control, its order alternated.
  - **Score** = spacer net + hard-kind mix net + 0.5 × 0.14.0 net, against the installed network's running average.
  - The verdict is BETTER/WORSE at ±50. Two WORSE hours in a row roll training back.
- **Install rule:** two hourly BETTERs, then a **long paired final check** (`finalpair2.sh`), then install with a backup (`install3.ps1`).
  - The final check runs 3–4 blocks. Each block has its own `evalSeed`, so both networks fight identical fights, in alternating order.
- **Measurement lessons (important):**
  - Spacer-only runs have a **random whole-run "easy mode"**: the same network on the same fight set swings from −15 to +92 net.
  - Within-run per-fight p-values are overstated, so use **blocks/runs as the unit**.
  - Running one network always second biased results early on; counterbalance the order.
  - The noise SD is ~40 net per 7-min run.

## 5. Everything tried, 19–23 Oct (chronological, with results)

**19 Oct**
- **Crit-back rule** (`counterCritRule`: running jump-crit in the opponent's cooldown):
  - Small gain against the spacer while training.
  - A frozen test on an untrained network lost damage, so it went off for release.
  - On 21 Oct it was retrained *with* the rule on (branch GC): jumper went from −126 to −38 net/min, whiffer from +32 to +134.
- **Dodge** (S out of a jump-crit while the sword is down): hurt the spacer badly (rounds 49% → 29%). Off.
- **Edge-hold** (stand at the edge while their sword is ready): gave up the sprint, and the 0.14.0 model went 65% → 29%. Off.
- **Lost-fight replay** (`LostCases`, the user's idea, "train on the exact cases it loses until all are won"):
  - Every lost scripted fight is stored with its full seed, so replays are exact: same start positions, same bot RNG.
  - Each picked case is fought 3 times running (STICK). A case is let go after 2 wins in a row; there is an archive plus re-tests. Never-won cases are parked.
  - **Result:** replays are won only 0–7% of the time. The list saturates (200 cases, mostly jumper/trader/spacer). Networks trained with heavy replay were *worse* against the spacer.
  - Now replayLostShare is 0.5 in training. It is a weak lever.
- **Safe learn** for `/swordai fight` against real players: a proven network vs a learning copy, taking turns each fight, promoted or rolled back on damage. Built and installed (0.15.3).

**20 Oct**
- **Headless training** for 4× throughput: it tuned the spacer timing wrong (21% headless vs 56% on screen). It broke the user's rule. **Never again.**
- **Reward tuning:**
  - `spaceOut` (a penalty for walking into reach on the opponent's ready sword): the ready:cooldown walk-in ratio stayed ~2:1, and damage got worse. Off.
  - `jumpInReady`: barely moved the sword-ready share (~36%).
  - The hitTaken/hit asymmetry made it avoid trades and turn passive. Fixed toward damage parity.
- **Self-imitation (SIL)** and **ACCEL-style mutated replays**: built, no clear gain. Off.
- **Opponent-style scalars** (away / edge / dist, ~3 s averages): learned weights stayed tiny.
- **Answer-spare-backing** (don't force the answer swing when the spacer backs away): worse. Off.
- **Final eval at 20 Oct 18:22:** every network trained since replay began was worse than installed against the spacer.

**21 Oct**
- **Grown network** (ResBlocks, zero-init, function-preserving): about equal.
- **The LR-restart flaw:** adaptive LR isn't saved, so every restart jolted the policy. Fixed by starting at a low LR.
- **PLR, answerNudge** (push the swing logit instead of forcing it): built, both off.
- **Measurement problems found** (order effect, easy mode). See §4.
- **Tactics model** (`TacticNet`: PRESS / COUNTER / SPACE / BACK, every 10 ticks, SMDP-GAE):
  - It collapsed crit-back from 45% to 7%: it can't tell opponents apart.
  - Fixed with a tactic prior KL (0.3).
- **Opponent reader** (`OpponentReader`): the last 3.2 s of fight features feed a 16-dim embedding, trained to guess the opponent kind by cross-entropy, with a replay memory.
  - It names the kind 50–70% of the time.
  - The embedding goes into the tactics model, the actor trunk and a FiLM scale (all zero-init).
  - Branch T3: hourly +68 and +93 BETTER overnight, but the long final check came out **about EQUAL (−27)**, so it was not installed.

**22 Oct**
- **"AI coach" manual counters**, from reading ~100 fight traces (`traceFights`: a per-tick text trace per fight in `run/swordai/traces`):
  - (a) After the spacer or stander hits us, the answer rule *kept* the swing for a crit on the way down, which the knockback carried out of reach. `keepWithin=2.3` now swings at once when the opponent is 2.3+ blocks away.
  - (b) No chase/hop jump at an opponent standing with its sword ready (`jumpAtReadyOnlyFleeing`).
  - **RULES A/B** (installed network, counters on vs off): mix **+11 per fight (p 0.018)**: timer +44, whiffer +55, jumper +30, circler +48. Spacer level. **Kept** (branch T4).
  - Free-hit windows used went up a lot (spacer 62% → 89%), but crits on the spacer fell from 5.1 to 3.3 a fight.
- **Keep 2.6 + aimLevel:** WORSE (−98). Rejected.
- **Edge-crit** (jump-crit from the edge of reach, 4 variants): rejected. It took off too near and was hit first.
- **T4 hourly:** +25, +43, **+138 BETTER**, +1. Then the BSOD killed 5 h.
- **Record-your-play** (`/swordai demo [kind]`, the K key records):
  - The user's own fights against bots are saved as demonstrations: keys map to heads, camera turns to bins through the inverse aim motor.
  - **Learning from demos** (DQfD/SIL-style): imitate a recorded step only where return − V(s) > 0, weight 0.5 (`Learner.loadDemos`, `Ppo.selfImitate`).
  - Variants tried:
    - Only steps followed by 2 s that went the user's way (`demoWindowTicks`).
    - Only crit-combo moments.
    - Final: **all steps of the fights the user won** (`demowins.py`). 29 won spacer fights, ~17k steps.

**23 Oct**
- **T5** (T4 + the user's won fights):
  - Hourly scores 00:00 −7, **01:12 +63 BETTER**, −32, +12, +49, +16, +36, **08:59 +62 BETTER**. Never WORSE.
  - **08:59 snapshot:**
    - spacer 499/440 dmg/min, net **+59**, 49% rounds (installed avg +15, 44%);
    - mix hard −20 (installed −30);
    - 0.14.0 +56 (installed +37).
  - This is the best candidate so far, not yet installed. Next: another BETTER, then the long final check.
  - Anchor drift is rising (KL 0.07 → 0.3).
- **ComboStrafe rule** (the user's combo complaint): see §6.

## 6. Current issue: combos, and the ComboStrafe experiment

- **The user sees:** it gets comboed, keeps holding W into the combo (the jump reset holds W+sprint every combo hit), hits back badly, and doesn't step sideways out of the line.
- **Built:** `core/rl/ComboStrafe.java`, behind the switch `Config.comboStrafeRule`.
  - From the 2nd hit in a row, it holds A or D for 10 ticks, away from the opponent's sideways velocity, or the other way from last time.
  - Its own landed hit ends it.
  - It is wired into the `Fighter` and `Trainer` classes; the trace label is "aside". 5 unit tests.
- **A/B** (installed network, rule on vs off, identical fight sets, alternating order; `combopair.sh`, label `cs`):
  - **2 of 3 blocks done: slightly worse with the rule** (−28, −22).
  - After a 2nd hit, the combo continued **22% with the rule vs 10% without**.
  - 3+ hit combos: 0.25 vs 0.15 a fight.
  - Spacer: fewer 2nd+ combo hits with the rule, but a worse net.
  - Block 3 may not have finished: the session ended, and the chain script (`combochain.sh`) may have died with it.
- **Likely reason:** the bots aim perfectly, so strafing can't make them miss. It only makes the AI come back in diagonally and slower, so the opponent re-hits first. Against a human, strafing works because the human has to re-aim, and the bots can't measure that.
- **Size of the problem against bots:** combo damage is only ~7–10% of damage taken. Against the user (crit rate ~41% vs the AI's ~12%) it's a much bigger deal.

## 7. Open problems (where new ideas are most wanted)

1. **Crit gap:** the AI crits ~12% of its hits against the spacer; the user crits ~41%. Crit-back and jump-in rules gave small or mixed gains, and the network itself learns crits poorly.
2. **Spacing:** it still walks into reach on the opponent's ready sword ~2× as often as on its cooldown. Reward shaping didn't fix it.
3. **Hand rules dominate:** ~78% of swings come from rules, the attack head's entropy collapsed, and PPO has little to learn there. Several rules help the frozen network but make it hard for learning to improve on them.
4. **Feed-forward, no memory:** it can't read opponent rhythm. The style scalars and the opponent reader only partly help (the reader is 50–70% accurate).
5. **Evaluation noise:** a ±40 net SD per run and a run-level "easy mode" make every A/B slow (1.5–2 h). Many changes end up "about EQUAL".
6. **Bot-vs-human gap:** bots aim perfectly and react in 0–1 tick, so human-oriented skills (strafing, baiting aim) can't be measured or rewarded. Maybe the bots need human-like aim error/latency, or the human demos should play a bigger role.
7. **Lost-fight replay** barely converts (0–7% won).
8. **Throughput:** on-screen training only, about 1 update per ~25 s.

## 8. Ideas already on the list, not yet tried (add yours, or say which of these you'd pick)

- Give scripted bots **human-like aim**: turn-rate limits, reaction noise, losing track when the target strafes. Then strafing and spacing pay off in training the way they do against people.
- A **recurrent policy** (GRU) or frame stacking over ~1 s, for rhythm and spacing timing.
- **Behaviour cloning pretraining** on many more user demos (including combo escapes on purpose), then PPO with a BC/KL term.
- Replace hard rules with **logit nudges** (answerNudge exists) so PPO can learn when to break them.
- **Population / league self-play** with exploiters (AlphaStar-style), instead of fixed scripts.
- **Variance-reduced evaluation:** many shorter seeded runs and a fixed sampling seed, to separate the "easy mode".

## 9. Key files (only if you want to look at the details; please don't edit them)

- Rules: `core/src/main/java/dev/swordai/core/rl/*.java`
- Config with every switch and its history in comments: `core/.../config/Config.java`
- Training loop: `src/client/java/dev/swordai/client/Trainer.java`
- Bots: `src/main/.../BotBrain.java`
- Scripts in the earlier session's scratchpad, `C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\`:
  - `hourlycheck3.sh`, `finalpair2.sh`, `combopair.sh`
  - `trainGT5b.sh` (current training settings)
  - `report.py` (damage table from client.log)
  - `combowin.py`, `combowhy.py` (combo stats from traces)
  - `playguard6.sh` (pauses training while the user plays)
- Results: `hourly-check.log` and `final-pair.log` in the same folder.


---

# Response & Ideas (from Gemini, 23 Oct 2026)

I read the handoff and audited the read-only source files (`ActorNet.java`, `Trainer.java`, `TrainingSession.java`, `ArenaServer.java`, `BotBrain.java`, `ComboStrafe.java`, `JumpReset.java`, `JumpIn.java`, `AnswerRule.java`, `Ppo.java`, `Learner.java`, `TacticNet.java`, `OpponentReader.java`, `RewardModel.java`, `Stages.java`). **No code was modified.**

Before jumping into new RL/fighting-game techniques, **five concrete code-level mechanics/bugs** in the current repo directly explain several of your most puzzling experimental results from 19–23 Oct. Fixing those first will immediately unlock the rest of your ideas.

---

## Part 1: Five Code-Level Findings That Explain Recent Results

### 1. Why `ComboStrafe` doubled combo continuation (22% vs 10%) and lost net damage (§6)
Look at what happens in `Trainer.java` (lines 1793, 1985, 2033) on the 2nd hit of a combo when both `jumpResetChance = 1f` and `comboStrafeRule = true` are active:
- `JumpReset.plan()` triggers on the hit tick and holds `JumpIn.RUN` (`FORWARD = W`, `SPRINT = 1`) for up to 14 airborne ticks.
- `ComboStrafe.plan()` simultaneously sets `action[ActionSpace.STRAFE] = A or D` for 10 ticks.
- **The Minecraft physics trap:** When a player holds both `W` and `A/D` at the same time, Minecraft's movement input vector `(forward=0.98, strafe=0.98)` has `lengthSqr > 1.0` and is **normalized by $1/\sqrt{2} \approx 0.7071$** (`Entity.getInputVector`).
- That means adding `A` or `D` on top of `JumpReset` **cuts the forward knockback-cancelling acceleration of the jump-reset by 29.3%** while adding lateral displacement!
- **Why `JumpReset` alone had only 10% combo continuation:** With pure `W + Sprint` ($1.0\times$ forward thrust), the jump-reset halves the knockback and lands the agent at ~2.7–2.9 blocks — *inside* 3.0m reach — so `AnswerRule` immediately lands a hit-select counter-swing that breaks the bot's combo 90% of the time.
- **Why adding `ComboStrafe` broke it (22% continuation):** Cutting forward thrust by 29.3% + drifting sideways pushes the landing distance out to **3.05–3.35 blocks** — *just outside* the agent's 3.0m reach so `AnswerRule` cannot swing, while the bot (which sprints straight in with 60°/tick aim and `followUpReach` up to +0.3m) steps in and lands Hit #3 for free!
- **The Fix (Split Combo-Trade vs. Combo-Disengage by Sword Charge):**
  Never combine forward `JumpReset` (`W+Sprint`) with `ComboStrafe` (`A/D`):
  1. **If our sword will be ready to trade (`charge >= 0.55`) and distance `< 2.85m`:** Do a **pure straight `JumpReset` (`W + Sprint`, `STRAFE = 0`)** to stay inside 3.0m and land the `AnswerRule` counter-hit.
  2. **If our sword is on cooldown (`charge < 0.55`) OR we are already at $\ge 2.9\text{m}$ OR on hit 3+:** **Suppress `JumpReset` (or release `W`)** and do a **lateral disengage** (`FORWARD = 0` or `S`, `STRAFE = A/D`, or turn camera 35–45° sideways and sprint-strafe out) so knockback + strafe carries us cleanly past 3.6m out of the bot's `followUpTicks` window, then re-engage straight in once `charge >= 0.85`.

---

### 2. `ActorNet` Sub-Network Isolation Bug: `critHidden` and `escapeHidden` are completely blind to `OpponentReader`, `TacticNet`, and `ResBlock`s (§5, §7.1, §7.4)
Look at `ActorNet.java` lines 425–510:
- On 21 Oct, `vecWide`, `ResBlock[] blocks`, `tacticIn` (`TacticNet`), `oppIn`, and `oppFilm` (`OpponentReader`) were all wired into `ws.trunk` $\rightarrow$ `ws.top` $\rightarrow$ `ws.headIn` $\rightarrow$ `heads`.
- **However**, right after `heads.forward(ws.headIn, 0, ws.logits, 0)` runs:
  - Line 426: `critHidden.forward(vec, 0, ws.critH, 0)` (a tiny 32-unit MLP reading **only raw single-frame `vec`**, NOT `ws.headIn`, NOT `oppZ`, NOT `tactic`) **overwrites `ws.logits[JUMP_AT]`** on every tick!
  - Line 430: Whenever `JumpNudge.inAir(vec)` is true, `critAir` and `critSwing` (also fed only by `ws.critH` and `vec`) **overwrite `FORWARD`, `SPRINT`, and `ATTACK` logits**!
  - Line 447: Whenever `BackNudge.applies(vec)` is true, `escapeHidden` (32 units fed only by `vec`) **overwrites `FORWARD` logits**!
  - And in `backward()` (lines 470, 473, 485, 489, 494), the gradients `ws.dLogits` for `JUMP`, airborne `FORWARD/SPRINT/ATTACK`, and escape `FORWARD` are **zeroed out (`Arrays.fill(..., 0f)`)** before `heads.backward()`!
- **Why this matters so much:**
  - The grown network (`ResBlock`s), `OpponentReader`, and `TacticNet` have **zero connection and zero gradient** to the jump key, airborne crit execution, or combo-escape W/S!
  - When `critHidden` decides whether to jump for a crit, it has **no idea** whether the opponent is a `spacer`, `stander`, `jumper`, or `trader` (even when `OpponentReader` knows with 70% accuracy!), and it only has 32 hidden units on a single frame.
- **The Fix (Function-Preserving Residual Bridge into `critHidden` & `escapeHidden`):**
  Add a zero-initialized projection `Dense critFromTrunk = new Dense(params, "crit.trunk", TRUNK, SKILL, rng, 0f)` (and `escapeFromTrunk`) that adds `ws.headIn` (which already contains `trunk` + `ResBlock`s + `tacticIn` + `oppIn` + `oppFilm`) into `ws.critH` before `relu(ws.critH)`, and backprop `ws.dCritH` into `ws.dHead`! Because weights start at `0f`, existing checkpoints load with 100% identical outputs and immediately gain full access to `ResBlock`s, `OpponentReader`, and `TacticNet` for critting and combo escaping.

---

### 3. Why `loadDemos` and `SilBuffer` mis-score value baselines (`criticTask` mismatch)
Look at `Learner.java` line 642 (`loadDemos`) and `Ppo.java` lines 194 & 268 (`runSil` / `selfImitate`):
- During normal PPO updates (`Ppo.java` line 154), the critic is called with the task ID: `critic.forward(cw, r.vec[i], r.priv[i], r.task[i])`.
- With `cfg.criticTask = true`, `CriticNet` adds a task-specific embedding `taskIn` for each opponent kind (e.g. `12` for spacer, `13` for stander).
- **Bug:** In `Learner.loadDemos` (line 642) and `Ppo.selfImitate` (lines 194, 268), it calls the 3-argument overload `critic.forward(cw, vec, priv)` which passes **`task = -1`** (zeroing out `taskIn`)!
- Because `V(s)` without `taskIn` is uncalibrated for specific bots like `spacer` (whose expected return is much lower than rehearsal/drill tasks), `ret[i] - V(s_i)` is systematically skewed when filtering user demo steps and SIL steps.
- **The Fix:** Store `task` in `SilBuffer` (and infer `task` from `f.kind` in `DemoFile.Fight`, e.g., `"spacer"` $\rightarrow 12$, `"stander"` $\rightarrow 13$) and pass `task` to `critic.forward(cw, vec, priv, task)`.

---

### 4. What causes the Whole-Run "Easy Mode" (−15 to +92 swing on identical `evalSeed` fights, §4 & §7.5)
There are two causes in the code, one major (stroboscopic thread phase locking) and one moderate (`startYaw` leakage):
1. **Stroboscopic Server–Client Tick Phase Locking (`TrainingSession` vs `ArenaServer`):**
   - In on-screen mode, the integrated `Server thread` ticks every 50 ms (`MinecraftServer.runServer`) and the `Render/Client thread` ticks every 50 ms (`Minecraft.runTick`). Their 20 Hz clocks run independently!
   - When a 7-minute eval run starts, the phase offset $\Delta\phi \in [0, 50\text{ ms})$ between the server tick and client tick stays nearly constant for the whole run (drifting only on GC/GPU hitches).
   - If the server ticks **5 ms before** the client tick, `BotBrain.tick()` updates the spacer, `SeenLag` receives it, `SeenLag.tick()` advances, and `FrameGrabber` captures it right before `TrainingSession.step()` — giving the agent a **0-tick phase delay**.
   - If the server ticks **5 ms after** the client tick, every server update misses the client tick by 5 ms and sits waiting for **45 ms (1 full extra tick)** before `SeenLag.tick()` and `TrainingSession.step()` see it!
   - Against `spacer` (which jumps in 6 ticks before sword ready and reacts in 0 ticks), **1 extra tick of phase delay across the whole run** is the difference between `MeetJump`/`AnswerRule` hitting first (+92 net, "easy mode") vs getting hit out of the air (−15 net, "hard mode")! (This is also why headless training transferred poorly: in `HeadlessSession`, `ArenaServer.tick()` runs `brain.tick()` and `driver.arenaTick()` synchronously on the same thread with zero phase jitter.)
   - *Fix:* In `TrainingSession`, log or lock the server-to-client tick phase offset (e.g. nudge client tick sleep by $\pm 1\text{ ms}$ in `SwordAiClient.tickMillis` until `ClientTick` consistently runs ~15 ms after `ArenaServer.onServerTick`), or randomize the phase by $\pm 5\text{ ms}$ per *episode* so phase luck averages out *within* a single run instead of biasing an entire 7-minute block!
2. **`startYaw` Leakage Across `evalSeed` Fights (`ArenaServer.java` line 490):**
   - In `ArenaServer.reset()`, line 490: `float yaw = Float.isNaN(plan.startYaw) ? ViewGeometry.wrapDegrees(agent.getYRot()) : plan.startYaw;`
   - For fresh `evalSeed` fights (`plan.startYaw` is `NaN`), the agent's starting yaw is inherited from wherever `agent.getYRot()` ended the *previous* fight!
   - Then `placeBotRandomly(ax, az, yaw)` places the bot relative to `yaw` and runs up to 40 rejection-sampling draws (`s.rng.nextDouble()`) until `arena.inside(x, z, 1.5)` is true!
   - That means if Network A and Network B finish Fight #1 looking in different directions, Fight #2's `placeBotRandomly` consumes a **different number of `s.rng` draws**, desynchronizing the bot's starting position and pillar draws even when `plan.seed` is identical!
   - *Fix:* When `plan.seed != 0L` (or `cfg.evalSeed != 0`) and `Float.isNaN(plan.startYaw)`, draw `yaw = (s.rng.nextFloat() * 2f - 1f) * 180f` from `s.rng` instead of reading `agent.getYRot()`!

---

### 5. Why `TacticNet` collapsed `COUNTER` from 45% to 7% (§5, 21 Oct)
Look at `TacticNet.update()` (`TacticNet.java` lines 275–298):
- A rollout of 1800 steps only contains ~1–3 fights, and `TacticNet` normalizes `adv` across all ~180 tactic choices in that rollout **without task normalization** (`taskAdvantageNorm` is in `Ppo.java`, not `TacticNet.java`).
- Worse, in `TacticNet.advantages()` (line 206), `delta = r.reward[t] + gamma * nextValue - r.value[t]`, where `r.value[t]` comes from the critic (which doesn't know which tactic was chosen, only the task). When `COUNTER` is chosen against `jumper` or `whiffer` early in training before `OpponentReader` has converged, `COUNTER` eats a huge negative TD error and gets wiped out globally across all tasks.
- **The Fix:** Normalize `adv` per-task inside `TacticNet.update()` (just like `Ppo.setTaskNormalisation`), OR replace `TacticNet`'s blind Exploration with **Opponent-Conditioned Tactic Priors** where `priorChances` uses `OpponentReader`'s class probabilities `ws.probs[c]` directly!

---

## Part 2: Recurrent (LSTM / GRU / MinGRU) & Transformer Architectures for Java + Ryzen Z1E

The user explicitly noted that **LSTMs, other RNNs, and Transformers are welcome as long as they run fast in pure Java on the ROG Ally (Ryzen Z1E)**.
Right now, the game collects 20 steps/sec (~90 sec for 1800 steps) and a PPO update takes <0.5 sec on CPU. You have **massive CPU headroom during `Phase.UPDATING`** (an extra 50–100 ms per update is completely invisible, and forward inference per tick only needs to take <0.2 ms).

Why memory is essential here:
Minecraft 1.9+ PvP is a **12.5-tick (0.625 s) phase-oscillator game**. Spacing, baiting a swing, W-tapping, and reading whether an opponent is a `spacer` (crits then holds S for 12 ticks), a `stander` (holds 3.3m and never steps in), or a `timer` (jumps at tick 4 of your cooldown) cannot be read from a single frame + 3-second exponential averages (`SC_STYLE_*`). The 3-second average blurs the 12.5-tick phase completely!

Here are the **three best sequence architectures** for your pure-Java `core/nn` stack on Ryzen Z1E:

### Option A (Recommended #1 for Sequence Memory): Causal 1D-TCN or Micro-Transformer over a 16-Tick Ring Buffer (Zero BPTT Headache!)
Why a short sliding window beats stateful BPTT in PPO:
- Look at `OpponentReader.Window`: you **already** store a sliding window (`oppWindow[i]`) at every step of `Rollout`!
- In PPO with shuffled minibatches (`Ppo.java` shuffles `order[n]` across the rollout), a stateful LSTM/GRU requires either keeping episode chunks in sequence order (Truncated BPTT) and recomputing hidden states, or dealing with stale hidden states after each PPO epoch.
- A **Causal Sliding-Window Encoder** over the last **$T = 16$ ticks (0.8 seconds = 1.3 sword cooldowns)** of compact kinematics + actions (`12 floats/tick`: distance, $\Delta$distance, lateral vel, vertical vel, own charge, opp charge, hit dealt, hit taken, own W/S, own A/D, own jump, own sprint = `192 floats` per step) is **100% stateless across minibatch shuffling**!
- Why `OpponentReader`'s current MLP (`Dense(320, 64)`) struggles (50–70% accuracy): flattening time steps into a single `Dense` layer has **no time-shift invariance** — an opponent swing at $t - 4$ vs $t - 6$ activates completely disjoint weights.
- **Two drop-in pure-Java architectures for this 16-tick window:**
  1. **Micro Causal Transformer (`CausalAttentionBlock`, $T=16$ tokens, $d=32$, $H=2$ heads, 1–2 layers + `ResBlock`):**
     - At $T=16, d=32$, the attention matrix $Q K^\top / \sqrt{d_k}$ is just **$16 \times 16 = 256$ floats per head**!
     - Forward pass per tick: **~0.03 ms** in pure Java.
     - 8 PPO epochs over 1800 steps across 4 worker threads: **~18 ms total** on Ryzen Z1E.
     - Add a fixed learned positional embedding `pos[16][32]` so the attention heads can directly attend to *"what was the distance and velocity 6 ticks ago when `oppCharge` dropped to 0?"*
     - Pool the last token's output (`out[T-1]`, 32 floats) into **both `ws.trunk` AND `ws.critH` / `ws.escapeH`**.
  2. **Dilated Causal 1D-CNN (`TCN`, 3 layers, kernel size 3, dilations $1, 2, 4$, channels $32$):**
     - Receptive field is $1 + 2 \times (1 + 2 + 4) = 15$ ticks (0.75 s).
     - Even simpler than Attention in pure Java (just 3 strided `for` loops like `Conv2d.java`), shift-invariant, ~8k params, **<10 ms per PPO update**.

### Option B (Recommended #2 for Sequence Memory): `MinGRU` or `GRU` (64–128 units) with 16-Tick Chunked BPTT
If you prefer a true recurrent state that can remember across an entire round without window limits:
- **Use `GRU` or `MinGRU` (Feng et al., 2024, *"Were RNNs All We Needed?"*) instead of standard LSTM:**
  - Standard LSTM has 4 gates ($i, f, g, o$) depending on both $x_t$ and $h_{t-1}$, plus dual state $(h_t, c_t)$.
  - **`MinGRU`** simplifies the GRU to:
    $$z_t = \sigma(W_z x_t + b_z), \qquad \tilde{h}_t = W_h x_t + b_h, \qquad h_t = (1 - z_t) \odot h_{t-1} + z_t \odot \tilde{h}_t$$
  - Because $z_t$ and $\tilde{h}_t$ do not depend on $h_{t-1}$ via a dense matrix multiply, `MinGRU` is **4× faster than LSTM**, immune to vanishing gradients over 20–40 ticks, can be parallel-scanned or unrolled in a 15-line Java loop, and takes <100 lines of code in `core/nn/MinGru.java`!
  - Even a traditional **64-unit `GRU`** (`core/nn/Gru.java`) after `trunkDense` (with output weights into `trunk` and `critHidden` initialized to `0f` so old checkpoints load seamlessly!) only adds ~33k parameters.
  - **How to train it cleanly in `Ppo.java`:** Store `float[][] h0` in `Rollout` at each step, group the rollout into contiguous **16-tick chunks** (breaking on `r.done[t]`), and unroll 16 steps in `Worker.run()`. On Ryzen Z1E, 16-step chunked BPTT for a 64-unit GRU takes ~25 ms per update.

---

## Part 3: Solutions to the Open Problems in §7 (Ranked by Likelihood of Helping)

Here are the highest-leverage ideas for §7, ranked by **expected gain per hour of engineering/training time**, with reasons and citations.

### Rank 1 (Highest Likelihood — Immediate Fix): Fix the `ActorNet` Sub-Network Isolation + `SilBuffer` Task Bug + `ComboStrafe` Diagonal-Normalization Conflict
- **Problems solved:** §6 (ComboStrafe hurting net score & doubling 2nd-hit continuations), §7.1 (Crit gap), §7.3/7.4 (Grown net & OpponentReader not helping crits/escapes).
- **Why it will almost certainly help:**
  1. Right now `critHidden` (which controls 100% of network jumps and airborne crit swings) and `escapeHidden` are completely disconnected from `ResBlock`s, `OpponentReader`, and `TacticNet` (`ActorNet.java` lines 426–495). Connecting `ws.headIn` into `critHidden` and `escapeHidden` via zero-init residual layers immediately lets the crit and escape heads use the grown trunk, opponent embedding, and user demo updates.
  2. Fixing `ComboStrafe` so it **releases `W` (or disables `JumpReset`) when strafing out on cooldown**, and **disables `ComboStrafe` when `JumpReset` is executing a charged counter-trade (`charge >= 0.55`, `d < 2.85m`)**, eliminates the $1/\sqrt{2}$ diagonal speed penalty that pushed the agent just outside 3.0m counter-hit reach.
  3. Passing `task` into `critic.forward` in `Learner.loadDemos` and `Ppo.selfImitate` fixes the skewed `R - V(s)` advantage filter.

---

### Rank 2 (Very High Likelihood): Give `BotBrain` Human Visuomotor Tracking Inertia (Second-Order Spring-Damper Aim + Lateral Rhythm Whiffs)
- **Problems solved:** §6 (Why strafing can't make bots miss), §7.6 (Bot-vs-human gap), §7.2 (Spacing/baiting).
- **References:**
  - *McRuer & Jex (1967) / Todorov (2004)* — Human visuomotor tracking (crossover model: humans have a ~150–200 ms delay on target *acceleration/direction reversals*, while tracking steady velocity via predictive pursuit).
  - *Vinyals et al. (Nature 2019, AlphaStar)* — Human-rate action/camera constraints so agents don't learn degenerate policies against superhuman micro.
- **What is wrong right now in `BotBrain.java`:**
  In `Trainer.java` (lines 2521, 2575, 2608), `spacer`, `timer`, and `jumper` have `reactionTicks = 0` or `0..1`, `aimNoiseDeg = 0.3..1.0`, and `turnRateDeg = 60f` ($1200^\circ/\text{s}$). In `BotBrain.aim()` (line 745), `wantYaw = ViewGeometry.yawTo(dx, dz)` is computed instantaneously every tick. When the agent presses `A` or `D`, the bot rotates onto the new angle on the very same tick with zero overshoot! Against such a bot, **strafing is strictly negative EV** because it slows forward closing speed ($1/\sqrt{2}$) while reducing the bot's hit probability by **0%**.
- **How to make `BotBrain.aim()` respond to strafing like a skilled human:**
  1. **Predictive Velocity Tracker with 3–4 Tick Reversal Lag:**
     Instead of aiming at `seenEye` with a memoryless `60°/tick` snap, maintain an estimated target velocity $(\hat{v}_x, \hat{v}_z)$ in `BotBrain` updated by an exponential filter with $\tau \approx 3.5\text{ ticks}$ ($\alpha = 0.28$):
     $$\hat{v}_{t} = (1 - \alpha)\hat{v}_{t-1} + \alpha (p_{\text{seen}, t} - p_{\text{seen}, t-1})$$
     The bot aims at the *predicted* position $p_{\text{pred}} = p_{\text{seen}, t - d_{\text{vis}}} + d_{\text{vis}} \cdot \hat{v}_t$ (where $d_{\text{vis}} = 3$ ticks is human visual processing time).
     - **What this does automatically:** When the agent moves in a straight line (or holds one strafe key for a second), $\hat{v}_t$ matches true velocity and the bot tracks with 1° precision (just like a strong human player!). **But** for the first **3–4 ticks after the agent reverses strafe (`A` $\leftrightarrow$ `D`) or side-jumps out of a combo**, $p_{\text{pred}}$ *overshoots* in the old direction by 0.4–0.7 blocks!
  2. **Rhythm Whiff on Lateral Dodge (`followUpWhiff` in 2D):**
     In `BotBrain.tryAttack()` (line 762), `followUpWhiff` currently only checks if the agent is within `reach + followUpWhiff` in distance. Extend that to **angular/lateral dodges**: if the bot is in `followUpTicks > 0` (swinging on combo rhythm) or landing from a `critJump`, and the agent *would* have been under the crosshair according to $p_{\text{pred}}$ (where the agent was heading 3 ticks ago) even though `canHit` is now false because the agent strafed out of the ray, **the bot still swings and whiffs!**
  - **Why this is huge:** The moment a strafe dodge causes the bot to whiff its sword and enter a 12.5-tick cooldown, **PPO will immediately discover and reinforce combo-strafing and edge-baiting** because they directly produce free punish-crit windows!

---

### Rank 3 (High Likelihood): Phase-Locked Spacing & "Bait-and-Punish" Micro-Footwork (Fixing the 2:1 Ready Walk-In & 12% Crit Rate)
- **Problems solved:** §7.1 (Crit gap: 12% vs 41%), §7.2 (Walks into ready sword 2× as often as cooldown).
- **What is wrong with the current approach to spacing & crits:**
  1. **Reward conflict in `RewardModel.java`:** Look at `Stages.sprintHitWTap()` / `duel()`: `reward.range` pays `0.03/tick` for being at `2.4–3.0m` (`rangeScore`), and `reward.primed` pays for sprinting inside `3.0m`, **even when `SC_OPP_CHARGE >= 0.9` (the opponent's sword is ready)**! You tried adding a one-time penalty `spaceOut` on crossing 3.0m, which failed because the every-tick dense `range` + `primed` + `stall` rewards still pushed the agent to stay inside 2.4–3.0m, and `StraightIn` forced it to run straight in on a line.
  2. **The High-Ground / Low-Ground Reach Asymmetry (`JumpIn.EDGE` failure on 22 Oct):**
     As your comment in `Config.java` line 509 rightly observed, in Minecraft a player's attack ray originates at their **eye** ($y + 1.62$) and intersects the target's **AABB** ($[y, y + 1.8]$). When Fighter A jumps toward grounded Fighter B (`spacer`/`stander`), at the top of the jump ($y + 1.25$), Fighter A's eye is at $y + 2.87$ looking down at $B$'s head ($y + 1.8$, vertical offset $1.07\text{m}$), whereas grounded Fighter B's eye ($y + 1.62$) is horizontally level with jumping Fighter A's feet/legs ($[y + 1.25, y + 3.05]$, vertical offset $0\text{m}$)!
     By Pythagoras:
     $$d_{\text{horiz}}(B \to A) = 3.0\text{m}, \qquad d_{\text{horiz}}(A \to B) = \sqrt{3.0^2 - 1.07^2} = \mathbf{2.80\text{m}}!$$
     **A grounded opponent has a 0.20-block longer horizontal reach against an airborne jumper than the jumper has against them!**
     That is why **any** running jump directly into a stationary `stander` or ready `spacer` (`edgeCritRule`, `hopRule`, `chaseRule`) gets hit out of the air 74–98% of the time!
- **How the user gets 41% crits against `spacer` (and how the AI can too):**
  A human player **never** jumps onto a ready stationary sword from 3.5m. Instead, they get crits in **three specific ways**:
  1. **The "Micro-Bait" (Step-In-Step-Out Feint at 3.15m):**
     Approach to `3.15–3.25m` (where `spacer` starts its walk-in/jump-in, or `whiffer`/`timer` commits to a swing), tap `S` (or `S + A/D`) for **3–4 ticks** so the opponent's swing whiffs or their jump lands short, and **on the exact tick `SC_OPP_CHARGE` drops below `0.3` (or `spacerOut` begins)**, sprint-jump in (`COUNTER` crit) and land at tick 8–9 of their 12.5-tick cooldown!
  2. **The "Crit $\rightarrow$ Sprint-Hit" Double-Tap Combo (User's §2.2 complaint: *"They crit you and sprint hit back"*):**
     When a jump-crit lands on tick $t=0$, the opponent is knocked upward for ~8 ticks with small horizontal knockback (still at ~2.2–2.6m). While the opponent is in the air ($y > 0$), **now the opponent has the high-eye reach disadvantage** ($2.80\text{m}$ vs our grounded $3.00\text{m}$)! So after landing a crit, staying on the ground and holding `W + Sprint` gives a **guaranteed first-hit advantage on the follow-up sprint-hit** 13 ticks later, which knocks them flying and starts a combo!
  3. **Make `RewardModel.rangeScore` Phase-Conditioned on `SC_OPP_CHARGE`:**
     - When `oppCharge >= 0.85`: shift `rangeMin..rangeMax` outward to **`3.15..3.60m`** (the bait zone just outside 3.0m reach!). Zero out `primed` inside 3.0m when `oppCharge >= 0.85`.
     - When `oppCharge < 0.80` (opponent swung or is recharging): shift `rangeMin..rangeMax` inward to **`2.10..2.85m`**!
     - Now the dense range shaping naturally teaches the oscillator: **hover at 3.2–3.5m while their sword is ready $\rightarrow$ surge inside 2.8m the instant they swing!**

---

### Rank 4 (High Likelihood): Convert Hard Rules to "Logit Priors + Advantages" (Residual Policy Learning) & Fix Demo Imitation (`AWAC` / `BC-Floor`)
- **Problems solved:** §7.3 (78% of swings from rules, attack head entropy collapsed), §7.1 (Learning crits from user demos).
- **References:**
  - *Silver et al. (2018)* — Residual Policy Learning (learn a residual on top of a hand-coded controller in logit space).
  - *Nair et al. (2020, AWAC) / Peng et al. (2019, AWR)* — Advantage-Weighted Actor-Critic for combining offline human demonstrations with online RL.
- **Why hard-masking rules (`keys &= ~mask`) stalls PPO:**
  In `Trainer.java`, `AnswerRule`, `JumpIn`, `JumpReset`, and `StraightIn` lock out the action head (`keys &= ~ActionSpace.mask(...)`). When a head is locked out:
  1. `Ppo.java` skips that head completely in `Policy.gradient()`. The network **never learns** the action that the rule took, nor does it learn *when the rule was a mistake*.
  2. Even worse, in `Learner.loadDemos()` (`DemoFile`), when the user records demos, `demoRec.begin(obs, keys)` records the **locked `keys` mask**! So wherever a rule *would* have fired, **`Ppo.selfImitate` masks out the user's actual key press on those heads**! Look at `Trainer.java` line 1923: `demoRec.begin(obs, keys)` is called *after* `AnswerRule`, `JumpReset`, `JumpIn`, `StraightIn`, and `AimRule` have already stripped those heads out of `keys`!
  - **Wait — verify `Trainer.java` line 1923!**
    Yes!! In `Trainer.java`, lines 1764–1917 strip `ATTACK`, `JUMP`, `FORWARD`, `SPRINT`, `YAW`, `PITCH`, and `STRAFE` out of `keys` whenever any rule applies, and **then** line 1923 calls `demoRec.begin(obs, keys)` with that stripped `keys` mask!
    And in `Ppo.runSil()` (line 206), `Policy.gradient(b.headMask[i], b.action[i], ...)` **ignores every head that is not unlocked in `b.headMask[i]`**!
    That means **whenever the user did something different from a rule during `/swordai demo` (e.g. strafed when `StraightIn` was active, or held/timed a swing/jump when `JumpIn`/`AnswerRule`/`JumpReset` was active), the network was prevented from imitating the user's key because `b.headMask[i]` had that head masked out!**
- **The Fix (Two parts, one is a 1-line fix):**
  1. **In `Trainer.java` line 1923 (`demoRec.begin`):** Pass `episodeKeys() & (chargeAtClick >= swingMinCharge() ? ~0 : ~ActionSpace.mask(ActionSpace.ATTACK))` (only mask `SwingGate` weak hits, NEVER mask tactical rules in `demoRec.begin`!) so `selfImitate` actually trains the network on **all** of the user's moves, jumps, strafes, and swings!
  2. **For User Won Fights (`demos`), add an Unconditional BC Floor (`demoBcFloor = 0.25f`):**
     Instead of skipping demo steps where `ret[i] - v <= 0f` in `Ppo.selfImitate`, give every step from a user-won fight a minimum weight `Math.max(demoBcFloor, Math.min(clip, gap))` (AWAC-style). Since all 29 demo fights in `demowins.py` are **won fights against the spacer**, every spacing and crit transition the user made is proven winning behavior and shouldn't be dropped just because the critic's value estimate rose.
  3. **Replace `JumpIn` and `AnswerRule` masks with Logit Priors (`logit += ±3.0f`):**
     You already built `answerNudge` for `AnswerRule`. Doing the same for `JumpIn` and `StraightIn` (adding `+3.0` to the rule's preferred logit in `Policy` instead of clearing the bit in `keys`) lets the rule guide exploration 95% of the time at first while allowing PPO and User Demos to override the rule when a better move exists!

---

### Rank 5 (Medium-High Likelihood): Temporal Sequence Module (`CausalAttention` / `TCN` on 16-Tick Window, or 64-unit `MinGRU`)
- **Problems solved:** §7.4 (Feed-forward policy can't read opponent rhythm; `OpponentReader` stuck at 50–70%), §7.1/7.2 (Timing jump-crits and spacing to the 12.5-tick sword cycle).
- **Why it helps:**
  Once Findings #1–#4 are fixed, the actor (and `critHidden`) still needs to see **velocity derivatives and cooldown phase** over the last ~0.8–1.6 seconds to distinguish a `spacer` backing out after a crit from a `stander` waiting at 3.3m or a `jumper` preparing a running jump.
- **What to build in Java:**
  - **Step 1 (Fastest & zero rollout changes):** Upgrade `OpponentReader` from a flat `Dense(320, 64)` to a **2-layer Causal 1D-CNN (`TCN`) or 1-layer 2-head Causal Self-Attention** over the $(32 \times 10)$ window, and feed its 16-dim `oppZ` into **`critHidden` and `escapeHidden`** as well as `trunk`! Also include `oppWindow` inside `DemoFile` / `SilBuffer` (currently `SilBuffer` leaves `b.opp[i]` null or static!) so user demos also train with the temporal embedding.
  - **Step 2 (Full recurrent option):** Add a 64-unit `MinGRU` (`h_t = (1 - z_t)*h_{t-1} + z_t*h_tilde`) in `ActorNet` right after `trunkDense`, unrolled over 16-tick episode segments in `Ppo.java`.

---

### Rank 6 (Medium-High Likelihood): Stratified Paired Evaluation + Phase-Synchronized Eval Runs
- **Problems solved:** §4 & §7.5 (Evaluation noise ±40 SD, 1.5–2 h per A/B, whole-run "easy mode").
- **References:**
  - *Glasserman (2004)* — Stratified Sampling + Common Random Numbers (CRN) for variance reduction in Monte Carlo evaluation.
- **How to cut evaluation variance by >60% and cut A/B time from 2 hours to 25 minutes:**
  1. **Fix `ArenaServer.reset` `startYaw` in `evalSeed` mode** (Finding #4 above: draw `startYaw` from `s.rng` when `plan.seed != 0`, never from `agent.getYRot()`, so both networks get 100% identical bot placements and RNG streams).
  2. **Stratified Fight Schedule in `evalSeed` mode:**
     Right now, in a 7-minute `spacer`-only or `mix` run, `rng.nextInt(3)` draws the spacer style (`0 = stander`, `1 = pure crit-S`, `2 = mix`) and `botPing = 3 + rng.nextInt(3)` independently per fight, AND a faster/more aggressive network plays *more* fights in 7 minutes than a slower network (so fight #15 for Network A is not compared to fight #15 for Network B if Network B only finished 12 fights!).
     - **Fix:** Evaluate by **fixed fight count $N$ (e.g. exactly $N = 24$ fights of 400 ticks = 8 minutes)** instead of wall-clock minutes, and use **stratified assignment** for fight index $k = 0 \dots 23$:
       - `style = k % 3` (guarantees exactly 8 standers, 8 pure crit-spacers, 8 mix-spacers!)
       - `botPing = 3 + (k / 3) % 3` (guarantees exact balance of 3, 4, 5 tick ping!)
     - Compute the paired difference $\Delta_k = \text{net}_A(k) - \text{net}_B(k)$ on each matched fight $k$ (or matched 6-fight stratum). Because the opponent subtype and ping are matched 1-to-1, between-stratum variance vanishes completely!
  3. **Interleave by Stratum (6 fights A, 6 fights B, 6 fights A, 6 fights B) within a single session** (using `Learner.keepBase()` / `actBase()` which already exists in `SafeLearn`!) instead of running 7 minutes of A in one process launch and 7 minutes of B in another!
     - **Wait — think about that!** You *already* built `baseActs` / `actBase` in `Trainer.java` (lines 1679, 1685, 1932) for `SafeLearn`!
     - If you load the Installed Network into `base` (`learner.keepBase()`) and the Candidate Network into `actor`, and **alternate `baseActs = (evalFights % 2 == 0)` (or swap every 2 fights with the same seed!) inside a SINGLE `TrainingSession` run**, both networks share the **exact same server-client tick phase ("easy mode" affects both equally!)**, the exact same GPU thermal state, and the exact same fight seeds back-to-back! That eliminates the whole-run "easy mode" bias 100% by construction!

---

### Rank 7 (Medium Likelihood): Fix `LostCases` Replay by Filtering Out Unwinnable Seeds & Using Mid-Episode Resets ("Go-Explore" / Vine)
- **Problems solved:** §7.7 (`LostCases` replay only wins 0–7% and hurts spacer performance when heavy).
- **References:**
  - *Ecoffet et al. (Nature 2021, Go-Explore)* / *Schulman et al. (2015, TRPO Vine)* / *Jiang et al. (ICML 2021, PLR)* — Prioritized Level Replay with positive value-discrepancy filtering.
- **Why `LostCases` currently hurts when `replayLostShare` is high:**
  1. When `LostCases` picks by `lostBy` (worst damage deficit first), it selects fights where the random seed drew the most extreme combination of superhuman bot parameters (e.g. `jumper` with 2-hit opener head-start + `+0.3m` extra reach + `0` reaction ticks + low knockback). Against a 0-tick aimbot with +0.3m reach and a 2-hit combo already running, the fight is **mathematically unwinnable** from that seed.
  2. Replaying an unwinnable seed 3 times in a row (`STICK = 3`) produces 3 consecutive episodes of negative returns, which drives PPO to lower its aggression and play passively (causing the drop against `spacer` observed on 20 Oct).
- **The Fix:**
  1. **Turn on `replayByPotential = true` with a Regret Cap:** Score cases by **outcome variance or positive GAE share** (cases where the agent had moments of positive advantage or lost narrowly by `< 80 net/min`), not the worst blowouts (`> 200 net/min`). Drop `STICK` from `3` to `1` or `2`.
  2. **Require `LostCases` opponents to use the humanized `BotBrain` aim tracker (Rank 2):** Once bots have human-like 3-tick reversal tracking instead of 0-tick 60°/tick aimbot, lost fights represent *tactical mistakes* rather than unwinnable frame-traps.

---

## Summary Checklist for Claude (What to Try First)

1. **Bugfix #1 (High-impact, ~10 lines):** In `Trainer.java` line 1923, pass the un-ruled `keys` mask (only masking `SwingGate` weak hits) into `demoRec.begin(obs, demoKeys)` so `Ppo.selfImitate` isn't blocked by rule masks from learning the user's demos! And pass `task` into `critic.forward` in `Learner.loadDemos` and `Ppo.selfImitate`.
2. **Bugfix #2 (High-impact, ~25 lines):** In `ActorNet.java`, add zero-init residual projections from `ws.headIn` into `ws.critH` and `ws.escapeH` (and backprop them) so the jump/crit and combo-escape networks aren't blind to `ResBlock`s, `OpponentReader`, and `TacticNet`.
3. **Bugfix #3 (`ComboStrafe` vs `JumpReset`):** In `Trainer.java` / `ComboStrafe.java`, make combo defense conditional: if `charge >= 0.55` and `d < 2.85`, run pure `JumpReset` (`W+Sprint`, **no strafe**) to land the counter-hit inside 3.0m without the $1/\sqrt{2}$ diagonal speed loss; if `charge < 0.55` or `d >= 2.85` or `hitsTakenRow >= 3`, suppress `JumpReset` (or set `W=0`) and run `ComboStrafe` (`A/D` disengage) so knockback + strafe clears 3.5m.
4. **Eval Fix (Eliminates "Easy Mode" completely):** Use `baseActs` inside `Trainer.java` during paired eval so the Candidate (`actor`) and Installed (`base`) networks alternate **fight-by-fight (fight $k$ with seed $s_k$ played by A, then fight $k$ with the exact same seed $s_k$ played by B)** inside the *same* `TrainingSession` run, with `startYaw` drawn from `plan.seed` instead of `agent.getYRot()`.
5. **Bot-vs-Human Gap (`BotBrain.aim`):** Add a 3-tick velocity-pursuit filter ($\alpha \approx 0.28$) + lateral `followUpWhiff` to `BotBrain.aim()` / `tryAttack()` so sudden strafe reversals cause 3 ticks of human-like aim overshoot and rhythm whiffs.
6. **Temporal Rhythm Memory (`TCN` / `CausalAttention` / `MinGRU`):** Replace `OpponentReader`'s flat `Dense(320, 64)` with a 3-layer Dilated Causal 1D-CNN or 2-head Causal Self-Attention over the window (or add a 64-unit `MinGRU` to the trunk) and feed its embedding into `critHidden` so the policy can lock onto the 12.5-tick sword cycle.
