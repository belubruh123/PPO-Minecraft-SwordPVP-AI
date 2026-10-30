# SwordAI, round 2: stuck on a plateau (23 Oct 2026, 14:45)

**What I'm asking you:** ideas only, please; **don't write or change code.** Claude builds and tests whatever is picked, one change at a time.

The question is different from round 1 (`HANDOFF.md`, where your first answer is from line 219). Small fixes aren't the problem any more. **Nothing I change makes the fighter clearly better.** Every change since 19 Oct lands inside the measurement noise. I want ideas for a **step change**: something that makes it obviously smarter, not 5% better. §7 says what "much better" means in numbers.

Please answer:
1. What you think is really holding it back. §5 has my guesses; tell me where they're wrong.
2. Your top 3–5 approaches for a step change under the constraints in §4. Rank them, with the expected size of the gain and roughly how much work each is.
3. Anything in how it's measured (§3) that would let smaller real gains be seen sooner.

---

## 0. Recap (skip if you have `HANDOFF.md` in mind)

- A Fabric mod for **Minecraft 1.21.11** (1.9+ combat: an attack cooldown, crits from falling hits, sprint knockback, reach 3).
- An RL agent plays sword PvP **in first person from its own screen**: a peripheral picture plus a fovea crop. It also gets a vector of ~30 numbers: both swords' charge, distance, relative velocity, on ground, health, fading clocks since hits, opponent-style averages.
- **Actor:** ~324k weights, feed-forward. It has ResBlocks, a tactics model (4 options), an opponent reader over a 3.2 s window, and since today a small causal TCN over that window.
- **Critic:** ~104k weights. Everything is a hand-written Java NN library, CPU only.
- **Action heads:** forward/back, strafe, sprint, jump, attack, yaw bin, pitch bin.
- **PPO:**
  - adaptive LR and KL stop;
  - a KL anchor (weight 0.5) to the installed network;
  - imitation of the user's own won fights (29 recorded spacer fights);
  - lost-fight replay.
- **Hand-written rules** wrap the network and mask the heads they control:
  - no weak hits, no swings at air;
  - swing back when hit (about 78% of all swings come from rules, measured 20 Oct);
  - jump reset in combos;
  - jump-in/crit-back jumps;
  - aim after a hit;
  - two "coach counters" from 22 Oct.
- **Opponents:**
  - scripted bots: spacer, stander (perfect 3-block player), timer, whiffer, jumper, punisher, circler, no-knockback, trader and others. Several react in 0–1 ticks with ~1° aim.
  - the old 0.14.0 network;
  - copies of itself.

## 1. The problem in one paragraph

The network installed on **19 Oct** (`kept-v21-final-1950`) has not been beaten in any long check since then. That's four days, about 15 training branches and about 10 rule A/B tests: rule changes, reward changes, bigger network, tactics model, opponent reader, the user's demonstrations, crit bridge, and now temporal memory. The trend is real but small. Branches trained before 22 Oct were **worse** than installed. Since 22 Oct they're **about equal, maybe slightly better**. In a long check, nothing has ever come out clearly better. Meanwhile it still loses the way the user described on 19 Oct: **opponents space it, crit it, sprint-hit it, and it rarely crits back.**

## 2. The evidence

**Hourly checks against the installed network.** Score = spacer net A−B + mean hard-kind net A−B + ½ × 0.14.0-model net A−B, where net = damage dealt − taken per minute. One hourly check has noise of about ±35 to ±50.

| Days | Branches | Hourly checks | Average score |
|---|---|---|---|
| 20–21 Oct | A, K, BK, G, GC, T, T2 | 17 | **−29** |
| 22 Oct | T3 (opponent reader), T4 (+ coach counters) | 10 | **+29** |
| 23 Oct | T5 (user's fights), T7 (crit bridge), T8 (TCN memory) | 10 | **+19** |

- **Single "BETTER" hours** (+93, +68, +93, +138, +63, +62) never held up. The next hour fell back to equal, and every long final check came out equal:

  | Snapshot | Long final check |
  |---|---|
  | 0419 | −50 |
  | G1038 | −4 (spacer alone significantly worse fight by fight) |
  | T3_0333 | −27 (SE 21) |

- **The only measured real gain:** the two coach counters on the installed network gave **mix fights +11 damage per fight (p = 0.018)**, with spacer unchanged.
- **T8** (TCN memory) is at **+6 (SE 34)** after its first paired check.

**What the installed network does now** (damage a minute dealt/taken; won = dealt > taken in that 30 s fight):

| Opponent | Installed |
|---|---|
| spacer + stander (paired check, 37 fights) | 444 / 428 (54% won); its hourly average net is +10 to +20, ~43% rounds |
| 0.14.0 network | 455 / 418 (66% won) |
| hard scripted kinds, average | net about −30 |
| jumper | net −90 to −190 |
| no-knockback (90% crits, 2-tick reactions, ≤15% knockback) | ~700 / 980, 20–24% rounds |
| replays of fights it lost (200 kept) | won 0–7% |

**The core skill gap:**
- **Crits:** only about **5–15% of its hits** are crits (spacer runs: installed 15.7%, trained nets 13.5%; all of T8's training fights today: 4.7%).
  - **CORRECTION (23 Oct 15:30): the user's recorded fights are NOT ~41% crits.** Decoded directly, only **1–3%** of their hits are crits (by the game's rule: falling and not sprinting at the swing, and by reward size). About 46% of their hits land while falling from a sprint-jump, *still sprinting*: sprint-jump hits, not crits. The 41% counted those as crits. See the next point for what the user really does.
  - **What the user does to win against the spacer**, per 30 s fight (29 won fights) vs the fighter (536 fights):

    | | User | Fighter |
    |---|---|---|
    | Hits landed | 38.2 | 29.6 |
    | Hits taken | 23.6 | 24.6 |
    | Opponent within reach | 78% | 51% |
    | W / S held | 92% / 0% | 78% / 9% |
    | Sprint key | 100% | 66% |
    | Jumps started per fight | 16.5 | 2.3 |

    Their most common move: **3 ticks after landing a hit, with the sword only ~0.3 back, a sprint-jump after the knocked-back opponent (median 2.5 blocks), hitting it again on the way down; 72% of those jumps land a hit.** They win on hit volume and staying on the opponent, not on crits or spacing.
- **Spacing:** it still walks into reach while the opponent's sword is **ready about twice as often** as while it is cooling down. A reward penalty for that changed nothing (20 Oct).
- **After the spacer's crit:** of 21 falls after being hit, 3 were crits. Otherwise the opponent was out of reach (9) or its own sword wasn't back (6).

## 3. How it is measured (and why small gains are invisible)

- Since today, every check is **paired**: the checked network (A) and the installed one (B) fight the **same fights from the same seed** in the same run, alternating who goes first.
  - A self-check (installed against itself) gave A−B −8 ± 20 over 28 pairs. The pairing works.
- **Noise:**
  - One 30 s spacer fight pair has an SD of about **107 net/min**. The noise is in-fight (action sampling, timing), not in the setup.
  - An hourly check (~40 spacer pairs, ~45 mix pairs, ~10 against the 0.14.0 model) has SE ≈ 34 on the score. Only differences of **~70+ show up in one hour**; +20 takes ~10 hours.
  - Older lesson: whole spacer-only runs sometimes go into an "easy mode" for both networks (−15 to +92 on identical fights).
- **A measuring bug found today** (fixed): Windows changed its audio device, the client froze, and the server kept fighting. One fight read 18,000 damage a minute taken and would have produced a fake BETTER. Fights where the client stalls are now cut and re-fought.
- Install rule: two BETTER hours, then a long paired final check (~2 h), then install with a backup.

## 4. Constraints (hard)

- **Training only on screen, first person, never headless** (the user's rule #1; headless training once tuned the wrong spacer timing).
  - **68 steps/s** at game speed ×4, so **~245k steps per hour**, an update every ~29 s (~2000 steps).
  - 22.4M steps in total since the start.
- **One handheld PC:** ASUS ROG Ally, Ryzen Z1 Extreme, integrated GPU, ~10 GB RAM. The user's own play and training can't run at the same time. Two GPU-driver crashes so far.
- **Java, CPU-only, own NN library** (no PyTorch). Anything new has to be small enough to write and run there.
- **The user's fight rules** stay in force:
  - no weak hits (sword ≥ ~0.9), no swings at air, few sweeps;
  - swing back when comboed, but no "trigger-bot" waiting;
  - jump reset only sparingly;
  - approach straight;
  - turn toward a lost opponent;
  - handle 60–100 ms ping.
- The bot is for fights everyone agreed to. **Nothing aimed at avoiding anti-cheat detection.**

## 5. My guesses at why it plateaus (please attack these)

1. **Too few samples for model-free PPO.** About 245k on-screen steps an hour. The skill we need is precise timing (a crit is a jump that lands its fall in reach exactly while its own sword is back and the opponent's isn't). With 107-net/min noise per fight, the policy gradient for that is buried. 22M steps found the coarse game, not the timing.
2. **The network controls little of what matters.** Rules make most swings and many jumps, and mask those heads. PPO mostly tunes walking and waiting, so even a good learning signal can't change much.
3. **The core skill is geometry and timing with known, deterministic physics** (jump arcs, sprint speed, reach 3.0, knockback vectors, the opponent's cooldown read from its swing). That smells like **planning/search** over a forward model, not something to rediscover by trial and error.
4. **The anchor holds it in place.** A KL of weight 0.5 to the installed network (drift KL 0.26–0.32 now) was added after training wandered off. It may also block any big change.
5. **The scripted opponents may be near-unbeatable** for any fair fighter (0–1 tick reactions, ~1° aim, perfect spacing). Some of the score is decided by kinds where 80–90% isn't realistic. Maybe the curriculum is teaching the wrong lessons.
6. **Too little human data:** 29 won fights from the user, all against the spacer. Imitation helped nothing measurable (T5: about equal).

## 6. What came of your round-1 ideas

| Idea | Result |
|---|---|
| Crit/escape models blind to the opponent reader (critBridge) | Fixed, trained (T7): −11, equal |
| Critic task −1 for demo/SIL steps | Fixed in code |
| Eval start yaw leaked from the last fight | Fixed (yaw from the seed) |
| Paired fight-by-fight evaluation | Built, verified, used for every check now |
| Temporal memory: Option A, causal TCN over the reader's 32-sample window, zero-init into trunk + crit/escape | Built and training (T8): first check +6 ± 34 |
| ComboStrafe (strafe out of the combo line) | Rejected: −11, combos went on more (bots aim perfectly) |
| Humanized bot aim, rules as logit priors, phase-conditioned range reward, LostCases filtering, MinGRU | Not tried yet |

## 7. What "much better" means: the targets

| Measure (per minute unless said) | Installed now | Clearly smarter (visible in one paired hour) | The user's final goal |
|---|---|---|---|
| Spacer net damage | +10 to +20 (≈ 465/450) | **+100** (≈ 500/400) | far ahead, 80–90% rounds |
| Spacer rounds won | ~43% | **65%** | 80–90% |
| Crits as a share of its hits | 5–15% | **30%+** | (the user's own fights are 1–3%: see the correction in §2) |
| Hits landed per 30 s vs spacer | 29.6 | **35+** | 38 like the user |
| Walk-ins on a ready sword vs on a cooling one | ~2 : 1 | **1 : 2** | spaces others itself |
| 0.14.0 network, net / fights won | +40 / ~60% | **+120 / 80%** | clearly beats what 0.14.0 did against people |
| Hard scripted kinds, average net | ≈ −30 | **+30** | near 100% (user's words) |
| Replays of lost fights won | 0–7% | **30%** | all |
| Hourly paired score vs installed | ±30 (noise) | **+100 sustained** (~3 SE in one hour) | — |

The middle column is what I'd call a real step. In one hourly check nobody could argue it's noise. The right column is the user's stated goal (80–90%+ rounds with real combos and crits, beat a good human player).

## 8. Directions I'm considering (rank them, or replace them)

- **A crit/spacing planner:**
  - A small forward simulation of Minecraft movement (known equations) picks between macro-actions every few ticks: wait at ~3.2, step in, sprint-jump crit now, sprint-hit, back out.
  - It predicts where both players and both swords will be over the next ~15 ticks.
  - The network (or tactics model) chooses or vetoes; the planner does the precise timing.
- **A learned world model trained on every logged fight**, with policy improvement in imagination (Dreamer/MuZero-like, tiny), to get 10–100× more learning per real step.
- **Weaken or drop the anchor**, accepting a temporary dip, so training can move further from the installed network.
- **Much more human data:** ask the user to record many more fights, against more kinds, then BC pretraining plus PPO fine-tuning.
- **League/population training** (several branches at once is impossible on one handheld, but a small league of frozen snapshots as opponents is possible).

## 9. Files, if you want details (please don't edit)

- `core/src/main/java/dev/swordai/core/rl/`:
  - `ActorNet.java`, `CriticNet.java`, `Ppo.java`, `Learner.java`
  - `RhythmNet.java` (TCN), `OpponentReader.java`, `TacticNet.java`, `Tactic.java`, `SilBuffer.java`
  - `JumpIn.java`, `AnswerRule.java` (rules)
- `src/client/java/dev/swordai/client/`:
  - `Trainer.java` (the step loop, rewards, fights)
  - `TrainingSession.java` (on-screen body)
  - `PairedEval.java`
- `src/main/java/dev/swordai/server/`: `BotBrain.java` (the scripted opponents), `ArenaServer.java`
- `tools/`: `hourlycheck4.sh` and `hourlysum3.py` (the paired hourly check), `REQUIREMENTS.md` (everything the user asked for)


---

# Response to Round 2 (from Gemini, 23 Oct 2026)

I audited the current code (`ActorNet.java`, `RhythmNet.java`, `JumpIn.java`, `JumpNudge.java`, `AnswerRule.java`, `Trainer.java`, `BotBrain.java`, `Fighter.java`, `Learner.java`, `PairedEval.java`, `hourlycheck4.sh`, `hourlysum3.py`, and live `run/swordai/config.json` + `curriculum.properties`). **No code was modified.**

 Below are the answers to your three questions:
1. **§A:** Attack on your 6 guesses in §5 — what is *actually* locking the fighter onto the 19 Oct plateau (including three concrete mechanical traps in the current code that explain why crits are stuck at 4.7–15%, why 9/21 falls after `spacer`'s crit miss, and why T5/T7/T8 couldn't move the needle).
2. **§B:** Top 4 step-change approaches ranked by expected gain (+100 to +180 net/min) and effort.
3. **§C:** How to cut hourly paired evaluation SE from **34 down to ~12–14** in the same wall time so a +25–30 gain is visible in 1 hour instead of 10.

---

## §A. What Is Really Holding It Back (Attacking Your Guesses in §5)

Your guesses **#2 (rules/masks dominate)**, **#3 (deterministic kinematics/timing belongs in a forward planner)**, and **#4 (the 0.5 anchor)** are spot-on. Your guesses **#1 (too few samples)** and **#5 (scripted bots are near-unbeatable)** are **wrong in a very revealing way**:

### 1. Why Guess #5 ("scripted bots may be near-unbeatable") is false — and proves Guess #3
- The user beat the exact same `spacer` 29 times with **~41% crits**.
- Even more importantly: look at *how* `BotBrain.java` (`spacer`, `timer`, `jumper`) beats the 324k-parameter neural network. `BotBrain` does not use superhuman 2D micro against `spacer`/`timer`; against an opponent that runs straight in (`StraightIn`), aim noise doesn't even matter. `BotBrain` wins because **lines 481–544 and 641–677 of `BotBrain.java` are an explicit 1D kinematic forward planner**:
  - `spacer` holds `SPACER_RANGE = 3.3m` (0.3m outside reach) while recharging, starts walking in at `SPACER_LEAD_TICKS = 6` (`charge ≈ 0.52`), jumps at `HOLD_JUMP_REACH = 3.4m`, drifts forward in the air with `sprint = false` so it crosses `3.0m` on tick 7–9 of its jump (low in the fall, when its sword hits `1.0`), lands a **1.5× critical hit (10.5 dmg)**, and immediately holds `S` for `SPACER_OUT_TICKS = 12` ticks.
  - `timer` (`BotBrain.java` lines 646–657) literally computes the 1-line forward equation:
    $$\text{ends} = d - \Delta d_{\text{jump}} + 8 \cdot v_{\text{away}}, \qquad \text{toReach} = \frac{d - 3.0}{v_{\text{closing}}}$$
    and jumps so its falling phase arrives 1–2 ticks before the agent's sword is back.

### 2. Why Guess #1 ("245k steps/hr is too few for PPO") is NOT the bottleneck — Four Mechanical Walls in the Code Prevent the Policy from Executing or Learning the Crit/Spacing Cycle

Even with 100M steps, the current setup cannot learn or execute 40% crits or 1:2 spacing because of **four concrete walls** in `ActorNet.java`, `Trainer.java`, and `config.json`:

#### Wall 1: Why 9 of 21 falls after `spacer`'s crit missed ("opponent out of reach") — `LET_GO` (`FORWARD = 0`) vs `WALK` (`FORWARD = 1, SPRINT = 0`)
Look at your trace stat in §2:
> *"After the spacer's crit: of 21 falls after being hit, 3 were crits. Otherwise the opponent was out of reach (9) or its own sword wasn't back (6)."*

Look at what happens in `Trainer.java` (lines 1784, 2011–2014) and `ActorNet.java` (lines 247–249, 505–512) when `spacer` hits the agent and knocks it into the air:
- On the way down (`velUp < 0`), `AnswerRule.inTheAir()` returns `LET_GO`, which sets **`action[FORWARD] = 0` and `action[SPRINT] = 0`** (`Trainer.java` line 2012).
- On every falling tick where `AnswerRule` hasn't fired yet, `JumpNudge.critMoment(vec)` is true (`inAir && velUp < 0`), so `ActorNet.critAir` overwrites `FORWARD` and `SPRINT` with `restAir()` biases: **`critAir.bias.w[0] = 3.0f` (`FORWARD = 0`, release `W`!) and `critAir.bias.w[3] = 4.0f` (`SPRINT = 0`)**.
- **Meanwhile, `spacer` sets `spacerOut = 12` and walks backward (`S`) at 0.22 blocks/tick!**
- Because both `AnswerRule.LET_GO` and `ActorNet.critAir` **let go of `W` (`FORWARD = 0`)**, the agent coasts backward from the hit's knockback while `spacer` walks backward, opening the gap past `3.0m` before the agent starts falling (**9 of 21 falls out of reach**)!
- **Now look at what `BotBrain.java` (lines 485–490 & 709–711) does in the exact same situation:**
  > *"Its next swing is for its own way down from this hit, and a critical one. As with a jump of its own: **it walks in on the agent meanwhile (`FORWARD = 1, SPRINT = 0`)**."*
  In Minecraft physics, **holding `W` (`FORWARD = 1`) with `SPRINT = 0` (`WALK`) ends sprinting on the server immediately** (so the falling swing is 100% a critical hit!) **while applying forward air acceleration (`+0.02/tick`) toward the opponent!**
- Releasing `W` (`FORWARD = 0`) was done because of the 7-tick double-tap-W sprint trigger (`ActorNet.java` line 240), **but double-tap sprint ONLY triggers when `W` transitions from `0 -> 1`!** If `W` stays held (`1 -> 1`) while `SPRINT` is set to `0` (or `setSprinting(false)`), Minecraft **never** restarts sprint and the player drifts forward ~0.35 blocks closer during the fall!

#### Wall 2: Why network-initiated jumps (`critOut` $\to$ `critAir`) collapsed to 4.7% crits in T8
Look at `ActorNet.java` lines 505–512 and `JumpNudge.java` lines 45–67:
- Whenever the network itself chooses `JUMP = 1` on the ground (`critOut`), on **Tick 1 of the jump (while rising, `velUp > 0`)**, `JumpNudge.inOwnJump(vec)` is `true`, which makes `JumpNudge.critMoment(vec)` **`true` on the way UP**!
- Immediately on Tick 1 of the jump, `critAir` overwrites the trunk's `FORWARD` and `SPRINT` logits with `restAir()` (`FORWARD = 0`, `SPRINT = 0`)!
- **That means whenever the network jumps on its own, `critAir` releases `W` and `Sprint` on Tick 1 of the jump (for all 6 rising ticks and all 5 falling ticks)!**
- A jump where `W` and `Sprint` are released on Tick 1 loses its forward air carry, stalls out ~0.6m short at `3.1m`, and gets stuffed in the air by `spacer`/`stander`! So PPO quickly teaches `critOut` **never to jump**!
- By contrast, `BotBrain` (`BotBrain.java` line 489) holds **`W + Sprint` on the way UP** (`velUp > 0`) to carry full horizontal speed across the gap, and switches to **`W` with `Sprint = 0` on the way DOWN** (`velUp <= 0`)!

#### Wall 3: Why `escapeHidden`, `escapeTrunk` (T7), and `rhythmEscape` (T8) are 100% dead code in live training
Look at your live `run/swordai/config.json` (lines 77–78) and `curriculum.properties` (line 3):
- `backRule = true`, `jumpResetChance = 1.0`, `stage.backNudgeKeep = 1.0`.
- Now look at `Trainer.java` lines 1818–1830:
  - When `BackNudge.applies(obs.vec)` is true (the 12 ticks after the 2nd+ hit of a combo), either `jumpReset` fires (`reset != 0`) and line 1829 strips `FORWARD, SPRINT, JUMP` from `keys`, **OR** `reset == 0` and `backOut` is `true` (because `backRule = true`), and line 1827 strips `FORWARD` from `keys` (`keys &= ~ActionSpace.mask(ActionSpace.FORWARD)`) and line 2016 overwrites `action[FORWARD] = BackNudge.BACK`!
- Because `ActorNet` only runs `escapeHidden` (and `escapeTrunk` / `rhythmEscape`) when `BackNudge.applies(vec)` is true (`ActorNet.java` line 504, 522), **on 100% of ticks where `escapeHidden` runs, `ActionSpace.FORWARD` is masked out of `keys` by `jumpReset` or `backOut`!**
- Its output is overwritten on every tick in `Trainer.java`, and its gradient in `Ppo.java` is **identically zero**!

#### Wall 4: Why `anchorWeight = 0.5` + `actorLr = 5e-5` + `klStop = 0.02` + `rehearsalProbability = 0.6` freezes the policy at `kept-v21-final-1950`
Look at `run/swordai/config.json`:
- The installed network (`kept-v21-final-1950`) holds `W` with >95% probability at `2.8–3.6m`.
- To space `spacer` (hold `3.25m` when `oppCharge >= 0.9` instead of holding `W`), the policy must flip `FORWARD` from `W` (prob 0.95) to `NONE`/`S` (prob 0.80) across ~30% of fight ticks.
- Flipping a binary/ternary head from `0.95` to `0.10` on 30% of states creates a policy KL divergence of:
  $$D_{\text{KL}}(\pi_{\text{anchor}} \,\|\, \pi) \approx 0.30 \times 0.95 \ln(0.95 / 0.10) \approx \mathbf{0.64\text{ nats}}!$$
- With `anchorWeight = 0.5` (pulling back on every step with $A_t \le 0$ at weight `0.5` and $A_t > 0$ at `0.05`) and `klStop = 0.02`, **the anchor mathematically forbids the policy from moving 0.64 nats away from `kept-v21-final-1950`'s "always hold W" habit!**
- And in `T5` (user demos), as noted earlier, `demoRec.begin(obs, keys)` (`Trainer.java` line 1948) was recorded *after* `StraightIn`, `JumpIn`, `AnswerRule`, `JumpReset`, and `backOut` stripped their bits from `keys`, plus 4 demo minibatches (256 steps) per update were overwhelmed 56:1 by 14,400 PPO steps anchored at `0.5` to `kept-v21-final-1950`.

---

## §B. Top 4 Approaches for a Step Change (Ranked by Expected Gain)

### Rank 1 (Biggest Step Change — Expected: **+100 to +160 net/min vs spacer, 30–40% crits, <1:2 ready:cooldown walk-in** | Work: ~250 lines of Java, 1–2 hours):
### Build a 15-Tick Forward Kinematic Duel Planner (`DuelPlanner.java`) + Fix the 3 Airborne Execution Bugs (`WALK` in Punish Fall, `RUN -> WALK` in Jump-Crit, and `CRIT_PRESS` Follow-up)

Your §8 Idea #1 (*"A crit/spacing planner: a small forward simulation of Minecraft movement picks between macro-actions... the network chooses or vetoes; the planner does the precise timing"*) is the **single highest-leverage change possible in this project**.

Why: In fighting-game AI and tactical game AI, separating **continuous micro-kinematics (exact takeoff tick, air-sprint release tick, edge stopping distance)** from **high-level tactical choice** is what separates trial-and-error policies from tournament-level agents (and it is literally how `BotBrain.java` beats your agent right now!).

Here is the exact design of `DuelPlanner` so it obeys all §4 constraints (pure observation `obs.vec` only, handles 60–100 ms lag, no weak hits, no air swings):

#### 1. Fix the 3 Airborne Execution Mechanics First (Can be tested in 15 minutes on the frozen network!)
Before even training, fix these three mechanics in `AnswerRule` / `JumpIn` / `Trainer`:
1. **Punish-Fall Drift (`AnswerRule.inTheAir` + `Trainer.java` line 2011):**
   When knocked into the air by an opponent's hit with `keep` or `letGo` active (waiting for the falling punish crit), if `JumpIn.distance(vec) > 1.8f`, **do NOT release `W` (`FORWARD = 0`)!** Instead, set **`FORWARD = 1, SPRINT = 0` (`JumpIn.WALK`)** (and explicitly clear client sprint `mc.player.setSprinting(false)` on the `letGo` tick so holding `W` never keeps sprint alive!).
   - *Why:* This keeps the agent drifting forward by +0.30–0.40 blocks during the 8-tick knockback arc while ending sprint on the server, directly fixing the **9/21 falls where `spacer` backed out of reach**!
2. **Fix `ActorNet.critAir` / `JumpNudge.critMoment` on the Way Up (`JumpNudge.java` line 66):**
   Change `JumpNudge.critMoment(vec)` from `inAir(vec) && (inOwnJump(vec) || velUp < 0f)` to **`inAir(vec) && velUp < 0f`** (only on the way **down**, NEVER on the way up!), and in `restAir()` (`ActorNet.java` line 247) bias `critAir` on the way down toward **`FORWARD = 1 (W), SPRINT = 0`** instead of `FORWARD = 0` when `distance > 1.8m`!
   - *Why:* On the way up (`velUp >= 0`), the fighter now keeps the trunk's `W + Sprint` momentum so network-initiated jumps actually cover 2.2–2.8 blocks instead of stalling in mid-air!
3. **Add `CRIT_PRESS` (12 ticks of grounded `W + Sprint` follow-up after landing a crit):**
   Whenever `facts.critDealt` is true on an opponent that isn't a `stander` with a ready sword, for the next 12 ticks (while our sword recharges for the follow-up hit and the opponent is airborne from our crit with a $-0.20\text{m}$ high-eye reach disadvantage), stay grounded and press in with `W + Sprint` to `2.4m` to land the follow-up **sprint-hit** (the exact *"crit then sprint-hit"* 2-piece from §2.2!).

#### 2. The 5 Macro-Actions of `DuelPlanner` (15-Tick Forward Kinematic Simulation from `obs.vec`)
Using only `obs.vec` (`d = JumpIn.distance(vec)`, `closing`, `away`, `charge`, `oppCharge`, `seenLag` estimate), `DuelPlanner` simulates 12 ticks ahead (taking <0.005 ms) and outputs one of 5 macro-states:
1. **`HOLD_BAIT` (Edge-Spacing at `3.15–3.30m` when Opponent's Sword is Ready):**
   - **Trigger:** `oppCharge >= 0.75` (their sword will be ready before we can reach and swing), opponent is grounded and not fleeing (`away < 0.15`), and $d \in [2.85, 4.20]\text{m}$.
   - **Action:** Brake to target distance $d^* = 3.22\text{m}$ using stopping-distance prediction $\hat{d}_{\text{stop}} = d - 1.3 \max(0, v_{\text{closing}})$:
     - If $\hat{d}_{\text{stop}} < 3.15\text{m}$: press **`S` (`FORWARD = 2, SPRINT = 0`)** for 2–3 ticks so we stop at `3.20m` instead of coasting across `3.00m` into their ready sword!
     - Why old `EdgeHold` failed on 19 Oct (§5): `EdgeHold` only set `FORWARD = 0` at $d \in [3.0, 3.5]\text{m}$ (which still coasted inside `3.0m` due to ground inertia!) and waited passively for 20 ticks without launching a counter-crit when `oppCharge` dropped or `spacer` backed out!
2. **`LAUNCH_CRIT` (Kinematic Intercept Jump-Crit):**
   - **Trigger:** Simulate a running jump taken on this tick over falling ticks $k \in \{8, 9, 10, 11\}$:
     $$\hat{d}(k) = d - \Delta d_{\text{jump}}(k, v_{\text{self}}) + k \cdot v_{\text{away}}$$
     Fire `JUMP | RUN` on the exact tick where:
     - $\hat{d}(k) \in [1.75, 2.85]\text{m}$ for some $k \in \{8..11\}$,
     - Our sword will be ready at tick $k$: $c + k / 12.5 \ge 0.92$, AND
     - **Air-Safety Check:** Either (a) `oppCharge <= 0.45` (they just swung, so their sword is on cooldown while we fly in), OR (b) `away >= 0.10` (`spacer` backing out during `spacerOut = 12` or `backer`/`runner` retreating, so $\hat{d}(t) > 2.95\text{m}$ during our rising ticks $t = 1..6$ and we only enter `< 2.90m` on the way down at tick $k \ge 8$!).
   - **Why (b) destroys `spacer`:** After every hit `spacer` lands (or whiffs), `spacer` walks backward at $v_{\text{away}} \approx +0.20\text{ m/tick}$ for 12 ticks (`BotBrain.java` line 506). During those 12 ticks, `oppCharge` climbs from `0.0` to `0.96`. The existing `JumpIn.COUNTER` rule (`JumpIn.java` line 649) **stopped firing as soon as `oppCharge > 0.40`** (5 ticks after `spacer`'s hit, while the agent was still recovering from knockback!). By checking `oppCharge <= 0.45 || (away >= 0.10 && d_rising > 2.95)`, `LAUNCH_CRIT` catches `spacer` on ticks 5–9 of its retreat and lands a falling crit right on its head as `spacer` stops at `3.3m`!
3. **`MEET_AIR`:** Opponent jumped (`oppUp > 0.12`): if our sword will be ready before they start falling, sprint in and hit them on their way up (`MeetJump`); if our sword is spent (`charge < 0.65`) and they are within `3.8m`, step back (`S`) for 4 ticks so their falling crit lands short at `3.1m`, then immediately `LAUNCH_CRIT` into their recovery!
4. **`CRIT_PRESS`:** For 12 ticks after we land a crit, stay grounded (`JUMP = 0`) and hold `W + Sprint` to `2.4m` for the guaranteed follow-up sprint hit.
5. **`COMBO_ESCAPE`:** On hit 2+ taken in a row, if `charge >= 0.65 && d <= 2.85f` do a straight `JumpReset` (`STRAFE = 0`); if `charge < 0.65` (our sword is not ready to trade!), suppress `JumpReset` and hold `S + A/D` for 6 ticks to ride the knockback out to `3.4m` (`HOLD_BAIT`).

- **How the network and planner work together:** Expose the 5 planner modes as the tactics choices in `TacticNet` (or let `DuelPlanner` act as the default tactical controller while the network controls camera, strafe, and residual logit overrides). Even **without waiting for training**, you can A/B test `DuelPlanner` + the 3 airborne fixes on the frozen network in a single 25-minute paired check!

---

### Rank 2 (Expected: **+50 to +90 net/min across mix + spacer** | Work: ~40 lines of code + config, 15 minutes to set up):
### Unshackle PPO from the 19 Oct Anchor + Fix the Demo Masking Bug + Offline BC Pre-Pass on the User's Demos

If you want neural network training itself to produce a step change instead of hovering within ±15 of `kept-v21-final-1950`, you must remove the three brakes that are holding `ActorNet` at `kept-v21-final-1950`:
1. **Fix `demoRec.begin(obs, keys)` in `Trainer.java` line 1948 AND reconstruct `headMask` in `Learner.loadDemos`:**
   - In `Learner.loadDemos()` (`Learner.java` line 647), the 29 already-recorded `.demo` files have `f.headMask[i]` saved with `StraightIn`, `JumpIn`, `AnswerRule`, `JumpReset`, and `backOut` bits **stripped out**!
   - When loading a `.demo` file in `Learner.loadDemos()`, override `f.headMask[i]` to unlock all 7 heads (except `ATTACK` when `vec[SC_ATTACK_CHARGE] < 0.9f`):
     ```java
     int mask = (1 << ActionSpace.HEADS) - 1;
     if (f.vec[i][ObsSpec.VEC_SCALARS + ObsSpec.SC_ATTACK_CHARGE] < cfg.swingMinCharge) {
         mask &= ~ActionSpace.mask(ActionSpace.ATTACK);
     }
     ```
   - **Why this matters immediately:** Without re-recording a single fight, unlocking `headMask` in `loadDemos()` suddenly reveals all of the user's **jumps, edge-holds, back-steps, strafes, and crit timings** in the 29 won spacer fights (17,400 steps) that were previously masked out by `StraightIn` / `JumpIn` / `backOut` / `AnswerRule`!
2. **Add an Unconditional Behaviour-Cloning Floor (`demoBcFloor = 0.3f`) + Pre-Train 200 Minibatches Before Starting PPO:**
   - In `Ppo.selfImitate` (`Ppo.java` line 195), for `demos` (the user's won fights), don't throw away steps where `ret[i] - v <= 0f`! The user's spacing steps (holding `3.2m` before a crit) have similar critic value $V(s)$ to walking in, so `ret[i] - V(s)` filters out the setup ticks that make the user's 41% crits possible! Use `float w = Math.max(demoBcFloor, Math.min(clip, b.ret[i] - v))` so **every step of a won user fight gets imitated** (and upweighted when `ret - V > 0`).
3. **Replace the Static Anchor (`anchorWeight = 0.5` to `kept-v21-final-1950`) with EMA Self-Anchor or Drop `anchorWeight` to `0.05` (`anchorGainWeight = 0.0`):**
   - Set `anchorWeight = 0.05`, `anchorGainWeight = 0.0` (never pull back on positive-advantage steps!), `actorLr = 1.5e-4`, `klTarget = 0.02`, `klStop = 0.04`, `rehearsalProbability = 0.20` (instead of wasting 60% of steps on early rehearsal stages!), and `backRule = false` (so `escapeHidden` + `rhythmEscape` actually control `FORWARD` and receive gradients!).

---

### Rank 3 (Expected: **+60 to +100 net/min on `no-knockback`, `jumper`, `trader`, `punisher`** | Work: ~80 lines of Java, 45 minutes):
### Dedicated "Close-Quarters Crit-Cycle" Mode against High-Pressure / Low-Knockback Opponents

Look at the breakdown of `1351 PAIRED` (`hourly-check.log` lines 45–54):
- `no-knockback`: `688 / 966` (**−278 net/min**, 0% won)
- `trader`: `665 / 816` (**−151 net/min**, 0% won)
- `jumper`: `436 / 541` (**−105 net/min**, 16% won)
- `punisher`: `630 / 728` (**−98 net/min**, 50% won)

Those four opponents alone drag `h_m` (the hard-kind mix average) down by **−158 net/min**!
Why does the AI lose so badly to `no-knockback`, `trader`, `jumper`, and `punisher`?
- Look at `JumpIn.FACE` (`JumpIn.java` lines 139, 619):
  `JumpIn.FACE` was written specifically to jump-crit an opponent that stays in your face (`no-knockback` / `trader`).
  **Look at line 619 of `JumpIn.java`:**
  ```java
  if (face && faceOff <= 0 && unhitTicks >= FACE_UNHIT_TICKS && stayed() && !comboed && d <= FACE_WITHIN ...)
  ```
  **`unhitTicks >= FACE_UNHIT_TICKS` (`40 ticks = 2.0 seconds without being hit`)!!**
  Against `no-knockback`, `trader`, `jumper`, and `punisher`, they hit you **every 12–14 ticks (0.6 seconds)**!
  So `unhitTicks` is **NEVER $\ge 40$**, and **`JumpIn.FACE` NEVER FIRES EVEN ONCE against `no-knockback` or `trader`**!
- And why does `AnswerRule` also fail to crit `no-knockback`?
  Because `no-knockback` hits you with crits while you are already in a combo (`hitsTakenInRow >= 2`), and in `AnswerRule.when()` (`AnswerRule.java` line 127):
  ```java
  if (BackNudge.inCombo(vec) && !proven) {
      return NOW;
  }
  ```
  Because `cfg.punishInCombo = false` (`Config.java` line 504), `proven` is always `false`! So on Hit #2, #3, #4... against `no-knockback`, `trader`, and `jumper`, `AnswerRule` forces an **immediate grounded/rising plain 7-damage hit (`NOW`)** while `no-knockback` hits you with **10.5-damage crits every 12.5 ticks** (trading 7 damage for 10.5 damage until you die: `688 / 966` = `0.71` damage ratio $\approx 7 / 10.5$)!
- **Look at that math:**
  $$\frac{688\text{ dealt}}{966\text{ taken}} = \mathbf{0.712} \approx \frac{1.0 \text{ (plain hit)}}{1.5 \text{ (critical hit)}}!$$
  The agent is landing **almost the exact same number of hits** as `no-knockback`, but losing `688` to `966` (0% rounds won) solely because `punishInCombo = false` and `FACE_UNHIT_TICKS = 40` force the agent to trade **1.0× plain hits against 1.5× critical hits**!
- **The Fix:**
  When `jumpIn.stayed()` is true (the opponent took no knockback and stayed within `2.4m` after our hit, or `OpponentReader` identifies `no-knockback`/`trader`/`punisher` with `d < 2.2m`):
  1. Allow `punishInCombo` (hold the swing for the falling punish crit when knocked into the air with $d < 2.0\text{m}$), **AND**
  2. When on the ground at $d \le 2.1\text{m}$ with `charge` in `[0.35, 0.55]`, jump in place (`JUMP | WALK`) so our swing 7 ticks later is a **1.5× critical hit** instead of a 1.0× plain hit!
  - Turning 1.0× plain hits into 1.5× crits against `no-knockback` and `trader` immediately raises `688 / 966` by $\times 1.4\text{–}1.5$ to **`~950 / 900` (+250 net/min on `no-knockback` alone, which adds +35 to the overall hourly `score`!)**.

---

### Rank 4 (Expected: **+40 to +80 net/min via Sample Efficiency** | Work: ~350 lines of Java, 2–3 hours):
### Hybrid World-Model / Dyna-PPO Rollouts on Logged Fights (§8 Idea #2, Simplified for Java CPU)

Your §8 Idea #2 (*"A learned world model trained on every logged fight, with policy improvement in imagination"*) is a great fit, **with one crucial simplification**:
- You do **not** need a Dreamer-style pixel-reconstruction world model (which would be slow on CPU because of `peri` and `fovea` convs).
- In Minecraft 1.9 PvP, **the physics of distance, velocity, sword charge, jump arc, and knockback are 95% deterministic functions of `(vec_t, action_t, opponent_action_t)`**!
- And you already have a **`SyntheticEye`** (`TrainingSession.java` line 68) that renders `MaskFrame` (`peri` and `fovea`) from `(viewX, viewY, viewZ, viewYaw, viewPitch, botPose)` in pure Java on CPU!
- Even simpler: for **Offline Advantage-Weighted Policy Improvement (IQL / AWAC /离线 PPO)** over the last **100,000 real on-screen steps** stored in a ring buffer on disk/RAM (using only `vec`, `oppWindow`, `tactic`, `opp` by running `ActorNet` with `usePeriphery/useFovea` cached or distilled into `vecWide` + `RhythmNet`), you can run **10× more gradient updates per hour** on real on-screen transitions without any headless physics mismatch!

---

## §C. How to Cut Hourly Evaluation Noise (`SE = 34` $\to$ `SE ≈ 12`) So Real Gains Show Up 3× Faster (§3)

Look at your `1351 PAIRED` breakdown in `hourly-check.log` (lines 40–55):
- `spacer-only` (37 pairs, 10 min): `s_se = 16`
- `mix hard kinds` (7 kinds, 14 min): `h_se = 20`
- `0.14.0 model` (15 pairs, 14 min): `o_se = 43` ($0.5 \times o_{\text{se}} = 21.5$)
- Total `se` = $\sqrt{16^2 + 20^2 + 21.5^2} = \mathbf{34}$.

You can cut that `SE = 34` down to **`~12–14`** in the **exact same 24 minutes** with three small fixes to `PairedEval` and `hourlycheck4.sh`:

### 1. Stop Wasting 51% of the 14-Minute `mix` Run on Unscored Kinds
In `1351 PAIRED`, out of **63 pairs** fought in the 14-minute `mix` run:
- **32 pairs (51% of the run = 7 minutes!) were fought against `plain` (20 pairs), `older copy` (6), `backer` (2), `odd` (2), `corner` (1), and `trader` (1, dropped because `< 2` pairs)** — none of which were used in `h_m`!
- Meanwhile, `punisher` only got **2 pairs** and `no-knockback` and `whiffer` only got **3 pairs**, and because `hourlysum3.py` computes an **unweighted mean of the 7 kind means** (`h_m = sum(m) / len(hard)`), a 2-pair `punisher` with $SE \approx 110$ gets the same weight as a 15-pair kind!
- **Fix:**
  1. In `hourlycheck4.sh`, set the `MIX` stage shares so **only the 8 `HARD` kinds + `0.14.0`** are spawned during the frozen `mix` check (zero out `plain`, `older copy`, `odd`, `backer`, `corner`, `runner`), and cycle through the 8 `HARD` kinds in **stratified round-robin order** (`pair % 9`) so every single hard kind gets **5–6 pairs** instead of 1–2!
  2. Alone, that doubles the sample size on `HARD` and `0.14.0` in the same 14 minutes and eliminates the 2-pair `df=1` variance spike, dropping `h_se` from `20` to **`~10`** and `0.5 * o_se` from `21.5` to **`~11`**.

### 2. Sync the Action-Sampling RNG (`learner.rng` / `baseRng`) and Rival `Fighter` RNG Between Pair Halves A and B
Look at why a single spacer pair has an SD of **107 net/min** even when the installed network fights *itself*:
- In `Trainer.beginEpisode()` (`Trainer.java` line 2363), `evalSeed` reseeds `Trainer.rng` (which sets up the arena and `BotBrain.reseed`), **but it does NOT reseed `learner.rng` (`Learner.java` line 241), `learner.baseRng` (`Learner.java` line 337), or the `0.14.0` rival's `Fighter.rng` (`Fighter.java` line 215)!**
- Furthermore, when fighting `the 0.14.0 model`, `BotPilot.reset()` calls `fighter.reset()`, which calls `jumpIn.newRound()` (`Fighter.java` line 234) — **carrying over `chaseBurns` / `chaseOff` from Fight A into Fight B of the same pair!**
- Because `Policy.sample` draws from two different, un-synced RNG streams (`learner.rng` for A vs `learner.baseRng` for B), even if Network A and Network B have 99% identical action probabilities, on tick 3 Network A rolls `u = 0.04` and Network B rolls `u = 0.87`, causing them to take different actions on tick 3 so the rest of the 600-tick fight diverges completely!
- **Fix (True Common Random Numbers in `Policy.sample` and `Fighter.reset`):**
  At the start of every `paired` fight in `Trainer.beginEpisode()`, seed `learner`'s active action-sampling RNG (`rng` when `!baseActs`, `baseRng` when `baseActs`) AND the rival `Fighter`'s `rng` (with `jumpIn.reset()` instead of `newRound()`) using the **pair's seed `z`**!
  - Now, on every tick where Network A and Network B have similar probabilities, `Policy.sample` draws the **exact same uniform float $u \in [0, 1)$** for each head!
  - In a self-check (A = B), A and B execute the **exact same fight tick-for-tick** (`A - B = 0 ± 0`), and when comparing a modified network against installed, variance comes **only from ticks where the two networks actually disagree**, cutting per-pair SD in half!

### 3. Use Sequential Probability Ratio / Early Accept-Reject in `hourlycheck4.sh`
- Once `SE` is down to `~12–14` per hour, a step change of `+60 to +100` reaches **$4\text{ to }7\text{ SE}$ ($p < 0.0001$) in a single hourly check**, so you no longer need a 2-hour `finalpair2.sh` when the 2-hour cumulative paired score is already $> 3.5\text{ SE}$.

---

## Summary Checklist: What to Try First (In Order)

1. **Measurement Quick Win (15 min):** Sync `learner.rng`/`baseRng` and `Fighter.rng` (+ `jumpIn.reset()`) to the pair seed `z` in `PairedEval`, and remove unscored kinds (`plain`, `odd`, `backer`, `corner`, `older copy`) from the `MIX` eval run with round-robin kind selection. Verify that hourly `SE` drops from ~34 to ~13.
2. **Immediate Mechanics A/B on Frozen Network (30 min):**
   - Fix `AnswerRule.LET_GO` / `critAir` on falling punish crits to hold **`WALK` (`FORWARD = 1, SPRINT = 0` + clear sprint)** when $d > 1.8\text{m}$ instead of `FORWARD = 0` (fixes the 9/21 missed punish crits vs `spacer`).
   - Fix `JumpNudge.critMoment` so `critAir` only overrides `FORWARD`/`SPRINT` when **falling (`velUp < 0`)**, never while rising (`velUp >= 0`).
   - Fix `no-knockback` / `trader` 1.0× vs 1.5× trade deficit by enabling `punishInCombo` and `JumpIn.FACE` (`FACE_UNHIT_TICKS` removed when `stayed()` is true) against opponents that stay within `2.2m` after hits.
3. **The Step-Change Module (`DuelPlanner.java`, 1–2 hours):**
   - Build the 5-state 15-tick kinematic planner (`HOLD_BAIT` at `3.22m` with stopping-distance brake `S` when `oppCharge >= 0.75`; `LAUNCH_CRIT` when $\hat{d}(k) \in [1.75, 2.85]\text{m}$ on cooldown or during `spacer`'s `away >= 0.10` retreat; `CRIT_PRESS` 12-tick grounded sprint follow-up after landing a crit; `MEET_AIR`; `COMBO_ESCAPE`).
4. **Unshackle Training (when resuming training):**
   - Reconstruct unlocked `headMask` in `Learner.loadDemos()` + add `demoBcFloor = 0.3f`, set `backRule = false` (so `escapeHidden`/`rhythmEscape` isn't masked out), lower `rehearsalProbability` from `0.60` to `0.20`, and drop `anchorWeight` from `0.50` to `0.05` (`anchorGainWeight = 0.0`).
