# The technique training plan

**What this is:** the plan for the biggest change to SwordAI so far (user, 23 October night). Stop training against
exaggerated one-trick scripted opponents, and stop bolting hard rules onto the network. Instead:
- every technique of real sword PvP gets an opponent designed to train it;
- the AI learns **what to do and when**: which technique, which counter, timed right, picked from what this opponent has
  been doing.

**Where the PvP knowledge comes from: only [PVP-TECHNIQUES.md](PVP-TECHNIQUES.md),** our complete write-up of the
tutorial transcript (`tactiq-free-transcript-NinYq0LcqS8.txt`). Every bot, teacher and check below cites its IDs (B1,
T1-T11c, C1-C7, K1-K3). Nothing here adds a technique of our own. The tick-level facts used are the ones in part 8 of that
file, which were checked in this code base.

**Where the machine-learning choices come from:** the published work in [Sources](#sources), what each paper found, and
the measured limits of this project (part 1).

Progress is tracked with the checkboxes. The rule that never changes: **train on the screen, first person** (never
headless), and install a model in the user's instance as soon as short paired tests show it better in this part and not
worse elsewhere. No hour-long waits once the evidence is in.

---

## Part 1. Where we start (facts, 23 October)

- **Network:** Java, written by hand, with no autodiff library.
  - Trunk: a CNN over the opponent masks plus an MLP over the vector input, into 7 action heads (yaw 15, pitch 11, W/S 3,
    A/D 3, sprint 2, jump 2, attack 2), and a separate critic.
  - Sub-models: a crit model (jump key, and sprint/W in crit moments), an escape model (W/S in combos), OpponentReader (3 s
    window: names the opponent kind), RhythmNet (a TCN over 3.2 s, off in the dev run now) and TacticNet (a manager choosing
    rule sets).
- **Rules:** a dozen hard rules press keys over the network: answer, meet, jump-in, crit trade, punish, step-back, and more.
  This is exactly what the user wants gone.
- **Opponents:** BotBrain (server side) with one-trick kinds: plain, trader, runner, circler, corner, no-knockback, odd,
  jumper, punisher, backer, timer, spacer, whiffer, jump-in, plus self-play and older copies.
  - Some techniques already exist as bot options: W-tap, `jumpResetChance`, `critChance`, `punish`, `jumpIn`, `spacer`,
    `timer`, `dodgeChance`, `fleeChance`.
  - Lag is simulated: bot ping, agent lag, late knockback (`KnockbackLag`), reaction ticks.
- **Speed:** on the screen at 4x, the dev run makes about **24 steps a second, about 86,000 an hour, about 2 million a
  day**.
  - That is the binding limit of this whole plan.
  - For scale: the Blade & Soul fighting agents (pro level, 62% against pros) took 5 million steps each, 6 days.
- **Curriculum:**
  - Stages 1 to 10: aim, tracking, vertical aim, footwork, hit timing, sprint-hit + W-tap, combo escape, crits, duel,
    self-play.
  - Checkpoints after stages 6, 7, 8 and 9 exist in `run/swordai`.
- **Installed:** 0.16.0 = the v21 network plus the crit-trade rules.
- **What the user saw on 23 Oct:** in real fights it still sprint-hits into a player who jumps (on its hit, or into it) and
  eats the crit. It has no first-layer jump-in crit.

---

## Part 2. What the research says, and what we take from it

| Finding | Source | What we do with it |
|---|---|---|
| Agents trained against rule-based fixed opponents do not generalise to unseen opponents | Blade & Soul (Oh et al. 2019) | Specialist bots teach techniques; they are never the whole diet (part 5) |
| Self-play with **several styles of agents** made by reward shaping, trained against each other, beat pros 62% | Blade & Soul | Style agents in the league (aggressive / defensive / balanced shaping) |
| League training (main agents + exploiters, PFSP) gets the best Elo in a fighting game: League 1682 vs FSP 1079, PSRO 1262 | FightLadder (ICML 2024), AlphaStar | Phase 4: a small league with PFSP and exploiters |
| Simple strategies still exploited every learned fighting agent | FightLadder | Keep exploiters and specialist bots in the mix forever; test against held-out styles |
| PFSP over past checkpoints, weighted to the ones it loses to, gave a positive head-to-head against every other method | FootsiesGym (2026) | Opponent sampling weighted by (1 - win rate) |
| Fighting agents use memory: LSTM (B&S); two-timescale LSTM (Quake CTF, FTW); GTrXL beats LSTM on memory tasks | Oh 2019, Jaderberg 2019, Parisotto 2020 | Memory is required (the user: "it doesn't know the future") |
| Frame stacks / windows are usually about as good as an LSTM, and cheaper and steadier | Cobbe et al. 2019 (Procgen), SB3 RecurrentPPO report | Our nets are hand-written: windows + explicit habit counters first, a recurrent net only if they plateau |
| Learning the other agent's policy as an **auxiliary task** steadies learning and beats plain A3C | Hernandez-Leal et al. AIIDE 2019; DRON (He et al. 2016) | Opponent-prediction head: what will it do next (part 4). Caution: Tavares found explicit modelling hurt in one study, so keep it detached and A/B it |
| **Kickstarting:** student + RL loss + decaying cross-entropy to teachers; about 10x fewer frames, ends 42% above the teachers | Schmitt et al. 2018 | Technique teachers instead of hard rules (part 6) |
| BC init + decaying demo term (DAPG) cuts samples a lot and ends above the demonstrator | Rajeswaran et al. 2018 | Same idea for the first hours of each technique |
| Net2Net: function-preserving widening/deepening; growing a PPO net during training gave gains | Chen et al. 2016; GrowNN 2025 | Widen the trunk without losing what it knows (part 7) |
| Full resets cause sharp performance drops; shrink-and-perturb keeps plasticity | Nikishin 2022; plasticity survey 2024 | Restart from a stage via a kickstarted student, not from zero; shrink-and-perturb if learning stalls |
| Autoregressive action heads perform best for dependent sub-actions | Revisiting action factorization (2026) | Hops (W off then jump) and jump resets need key combinations: A/B the jump and attack heads conditioned on W/sprint |
| Discarding passive no-op steps raised data efficiency | Blade & Soul (data skipping) | Skip ticks where nothing can happen (out of reach, both swords back) from the policy loss |
| A ~230 ms decision delay matched pro reaction time and kept it fair | Blade & Soul | Bots get human reaction times and ping; the agent's lag stays simulated |
| PPO defaults: separate wide value net, obs normalisation, Adam 3e-4, GAE λ 0.9, tune γ | Andrychowicz et al. 2021 | Check ours against these when the net is rebuilt |

---

## Part 3. A bot for every technique (the specialist bots)

Each specialist plays an ordinary fight, but **when its technique's trigger comes up, it uses that technique** with
probability `p` (drawn per fight, 0.5 to 0.95). Its timing has human noise and a reaction time (drawn, ~150 to 300 ms =
3 to 6 ticks), and it has a ping (drawn). Every technique is one small, testable state machine in the bot code, and it
follows the steps of PVP-TECHNIQUES.md word for word.

Two kinds of drill come out of every technique:
- **USE drills:** the bot sets up the situation where the AI's use of the technique pays.
- **COUNTER drills:** the bot does the technique, and the AI has to answer it.

| # | Bot | It does (doc IDs) | The AI has to learn (doc IDs) | Drill kind |
|---|---|---|---|---|
| 1 | Punish-critter | B1 after every hit it takes, when it can | T1 double crit, T1a bait, T1b go around it, T1c, T2 standing crit | USE T1/T2 |
| 2 | Double-critter | T1 (+T1a bait, T1b goes around) when the AI punish-crits | C1 sprint-hit instead of punish crit; C2 when its own double crit is sprint-hit | COUNTER T1 |
| 3 | Sprint-hitter vs crits | C1: sprint-hits the AI's second crit | C2: hold S at once, fly out up on damage | USE C2 |
| 4 | Standing-critter | T2 after its sprint hits | Don't punish-crit or jump-reset into it (C6 logic); C1 | COUNTER T2 |
| 5 | Deflector | T4: sprint hit right after the AI's swing, spacing tuned to its ping | T5 reverse deflection; T9b jump reset right after its own attack | COUNTER T4 |
| 6 | Reverse-deflector | T5 after the AI's deflections | Deflect with stun spacing (T4 timing/spacing/ping), or skip the deflection | COUNTER T5 |
| 7 | Miss-baiter | T6/T6a: stops, S or strafes as the AI swings, then sprint-hits after the miss | Don't swing at what will be out of reach; do T6 itself | COUNTER + USE T6 |
| 8 | Swinger (misses) | Swings early and out of reach | T6: after its miss, the sprint hit = stun (not too close) | USE T6 |
| 9 | Three-block hitter | T7: full sprint straight in, hit from the edge | T9c: hit as it enters reach + jump reset, then crit; C7 | COUNTER T7 |
| 10 | Straight sprinter (no jumps) | Sprints straight at the AI (the cue of T9c) | T7 itself (full-sprint hit first), or T9c | USE T7/T9c |
| 11 | Jump-in critter (rising/falling) | Jumps at the AI from 1.8 to 5.7 blocks, crits on the way down | T8: hit it while it rises (stun); K2: never KB-hit it falling; let it land, then B1 | COUNTER jump-ins |
| 12 | Jump-resetter | T9 on the AI's sprint hits (skill = how often the timing is right) | C6 standing crit (only after a stun), C7 delayed sprint hit, spacing + S-tap | COUNTER T9 |
| 13 | Rhythm comboer | Combos to a fixed rhythm, hitting as the AI lands | T9a jump reset on landing; C3 S before the stun | COUNTER combos |
| 14 | Stun-escaper | C3: jump + hold S right before or after a stun | T11a lunge, C4 sprint-jump chase, T11c hops into lunges | USE T11a/T11c |
| 15 | Attack-then-S | C4: attacks right before holding S (kills the chase) | Don't sprint-jump into its hit; wait, then re-engage | COUNTER C4 |
| 16 | Uppercutter | T10: jump, sprint hit at the peak, often S after | T8: hit it while it rises; don't stand at its edge | COUNTER T10 |
| 17 | Lunger | T11a: sprint-jump in, sprint hit falling | K1/K2: it is falling, so don't KB-hit it; T8 if rising; B1 after its hit | COUNTER T11a |
| 18 | Hopper | T11b: hops outside reach, sprint hits disguised as crits | Back out (hops hold only while you stay in); C3 S before its hit | COUNTER T11b |
| 19 | Fall-spacer | K3: spaces so that the AI must hit it while it falls | K2: wait for ground/rising; K3 itself | COUNTER + USE K3 |
| 20 | Stunner | T3 after T4/T6/T7 openings, then combos | C3 (S before), C5 jump reset, T9a | COUNTER T3 |

Notes:
- Bots 1, 9, 11, 12 and 19 grow out of existing options (`punish`, `jumpIn`, `jumpResetChance`, `spacer`); the rest are
  new.
- "Trigger" means the situation the doc names. For example, bot 12 jumps on the tick it takes the AI's sprint hit; bot 5
  sprint-hits within its reaction time after the AI's swing.
- Each bot logs which technique it used on which tick. That is the **truth label** for the auxiliary head (part 4) and the
  report (part 8).

### The style mixer (the "real player" bot)
- **One bot that uses all of them:**
  - For each fight it draws a style: a probability for each technique, a timing skill, a ping and a reaction time. The
    styles are drawn from wide ranges, and some are held out for testing only.
- **It adapts like a player:**
  - Inside a fight it counts what the AI does: jump resets, punish crits, holding S after a hit, deflections.
  - It moves its probabilities toward the counters in part 7 of the doc:
    - the AI jump-resets a lot → more C7 / C6;
    - the AI punish-crits → more T1 / C1;
    - the AI holds S out → more T11a / T11c;
    - the AI hits straight in → more T9c.
  - So no single trick keeps working.
- **This is the user's point made into code:** a real player reads habits, so the AI has to read them too, and change.

---

## Part 4. Memory: so the AI can predict ("it doesn't know the future")

Humans predict from what the opponent did the last times. The doc ends on this: every technique wins only if you
predicted. The AI gets three kinds of memory, all made of plain inputs, so no backpropagation through time is needed:

1. **Habit counters for this fight:** running rates, read from what the AI sees and decayed over about 20 seconds. These
   are exactly the cues listed in part 8 of the doc:
   - jumped on the tick of the AI's hits (jump reset, T9);
   - punish-crit after the AI's hits (B1);
   - held S / moved straight away after taking a hit (C3);
   - sprint-hit right after the AI's swings (T4);
   - double crit after a crit (T1);
   - came in straight at a sprint, not jumping (T7);
   - jumped in from range (rising/falling, T8/T11a);
   - hops in reach (T11b);
   - its combo rhythm (the ticks between its hits; T9a);
   - missed swings (T6);
   - how far its hits sent the AI (stun or not, T3).
2. **The last exchanges:** for each of the last 6 exchanges, who hit first, with what (crit / sprint hit / plain), what
   the other did on the next ticks (jumped, held S, swung), and the damage. That is a small table, flattened.
3. **RhythmNet** (exists): a TCN over the last 3.2 s, for timing within an exchange (a 12.5-tick sword cycle).

**Opponent prediction head (an auxiliary task, detached from the policy as OpponentReader is now):**
- **What it predicts:** the bot's next technique in this exchange, and whether it will jump / hold S / swing in the next
  4 ticks if the AI hits now.
- **What it is trained on:** the truth labels the bots log (part 3).
- **What it feeds:** its output goes into the policy through zero-initialised weights.
- **How it is checked:** an A/B against no head, because explicit modelling has hurt in one fighting-game study.

If windows and counters plateau (the opponent's rhythm over more than 3 s matters), the next step is a GRU core with
truncated BPTT (Blade & Soul and Quake CTF both used recurrent cores), written by hand like the rest.

- [ ] habit counters + exchange table in the observation (new scalars appended; `ObsSpec.SCALARS_BEFORE` upgrade path)
- [ ] technique detector (rule-based from the doc's cues) that drives the counters, and labels real-fight traces too
- [ ] opponent-prediction head + its supervised loss on bot labels
- [ ] RhythmNet back on, fed the new counters

---

## Part 5. The curriculum: retrain from a stage

**Where to restart:**
- The basics are kept: stages 1 to 6 (aim, tracking, vertical aim, footwork, hit timing, sprint hit + W-tap). The
  technique school replaces stages 7 to 10 (combo escape, crits, duel, self-play).
- **Start point: `checkpoint-after-stage-6.bin`, widened (part 7).** The installed v21 network is a **teacher** (its
  sprint-hit and combo play is good), not the starting point. The reason is that its habits are deep: it always
  sprint-hits, and a network that has spent millions of steps never letting go of the sprint does not unlearn that
  cheaply (the plasticity and primacy-bias results).
- **Fallback, run in parallel for a day:** continue from v21 widened. Keep whichever is ahead on the part 8 checks after 1
  day.

**Hard rules go.** In training no rule presses keys any more. Each old rule becomes a **teacher** in part 6, or is
dropped. Only these stay:
- the aim assist that turns the camera onto the opponent, if still needed;
- the rule that keeps the sword from swinging before it is back, which is not a fight decision but a hard game limit.

**Stages of the technique school.** They follow the doc's own order, because each part builds on the one before:

| Stage | Family (doc part) | Specialist bots (part 3) | Passes when |
|---|---|---|---|
| T-A | Knockback and spacing: K1, K2, K3, T6, T7, T8 | 7, 8, 10, 11, 19 | Beats each bot by +X damage a minute, and uses T8 / waits per K2 in >70% of the cases (trace detector) |
| T-B | Crit trades: B1, T1 (a/b/c), C1, C2, T2 | 1, 2, 3, 4 | Same, plus double crits made in >50% of punish-crit chances |
| T-C | Stuns and deflections: T3, T4, T5, C3, C4 | 5, 6, 14, 15, 20 | Same |
| T-D | Jump resets: T9 (a/b/c), C6, C7 | 9, 12, 13 | Same, plus against bot 12 the share of sprint hits answered by its crit falls below 20% |
| T-E | Jumping: T10, T11a, T11b, T11c | 14, 16, 17, 18 | Same |
| T-F | All mixed | Style mixer (part 3) + all specialists at a share | Beats the held-out styles, and nothing in T-A..T-E regresses |
| T-G | League | Part 6 | Elo rises; exploiters' win rate against the main falls |

Rehearsal: every stage keeps ~30% of its fights on the stages before it, so nothing is forgotten (as the current rehearsal
and lost-case replay do).

Each stage's "+X" is set from the first measurement against the installed model on the same bot. The bar is: better than
the installed model on that bot, at 2 standard errors.

---

## Part 6. Teaching without hard rules: technique teachers (kickstarting)

**One controller per technique.** For each technique the AI must USE, we write a teacher: the doc's steps as a small
controller.
- For example: after its own crit lands, jump again and crit on the way down (T1).
- Or: on the tick of taking a sprint hit from a straight sprinter, jump (T9c).
- Teachers reuse the bots' state machines (part 3), seen from the AI's side.

**How a teacher teaches (kickstarting):**
- When a teacher's trigger is on, the loss gets the cross-entropy between the teacher's keys and the network's
  distribution, weighted by λ.
- **λ decays to 0 over that stage**, so the network ends up choosing by reward alone. That is how it learns *when not*
  to use a technique: a teacher only says how. Kickstarting reached its teachers' scores in about a tenth of the frames,
  and ended 42% above them.
- It never presses keys. The network acts, and the teacher only pulls on its probabilities.

**Teachers for the old basics:**
- The installed v21 network is a teacher for sprint hits, W-tap and aim in all situations, with a small λ that decays over
  T-A..T-C. This way the restarted student does not have to relearn what v21 does well.

**Rewards:**
- Damage dealt minus damage taken stays the reward.
- The per-technique stats are for checking only, never rewarded. Rewarding techniques would teach the AI to use them for
  their own sake, the "hold hit for no reason" failure the user warned about.

- [ ] teacher interface (trigger, keys) + kickstart loss with per-stage λ schedule
- [ ] teachers T1, T1a, T2, T4, T5, T6, T7, T8, T9 (a/b/c), C1, C2, C3, C4, C6, C7, T10, T11a, T11b, K2, K3
- [ ] v21 as the basics teacher

---

## Part 7. Making the network fit the job

- **Widen the trunk, keeping what it does (Net2Net):**
  - Trunk 128 → 256. Branch inputs 64 → 96 for the larger vector (habit counters, exchange table, prediction head).
  - New weights start at copies (function-preserving) or at zero for new inputs. The day-1 network plays exactly as before.
- **Dependent keys (an A/B experiment):**
  - The jump and attack heads are given the sampled W/S and sprint choice of the same tick as an input (an autoregressive
    order: W/S, sprint → jump → attack).
  - Hops (W off, then jump), jump resets (jump on the hit tick) and the hop sprint hit "before W is released" are key
    combinations. Independent heads can only learn them by luck.
- **The crit and escape sub-models:** keep them, now trained with teacher pulls too.
- **TacticNet:** its options were rule sets. With the rules gone it is retired, or re-purposed to choose a *technique
  family* as an input to the policy, if the A/B says it helps.
- **Data skipping:**
  - Ticks where nothing can be decided are left out of the policy loss (but kept for the critic): far out of reach, out
    of sight, both swords back.
  - This means more of every update is spent on the moments that decide trades.
- **PPO settings to recheck at the rebuild:** separate wide critic (we have one), observation normalisation, Adam 3e-4,
  GAE λ 0.9 to 0.95, γ 0.98 (tuned), clip 0.2 to 0.25.
- **Plasticity:**
  - If a stage stops improving for a day, apply shrink-and-perturb (w ← 0.8w + small noise) to the trunk, and re-check.

- [ ] Net2Net widening + checkpoint upgrade
- [ ] autoregressive jump/attack heads (A/B)
- [ ] data skipping (A/B)

---

## Part 8. Checking it, and installing fast

**Technique detector on traces** (part 4) gives, per technique: the chances, how often the AI used it, and how often it
paid off. It works on training traces and on the user's real fights (`swordai/fights/*.txt.gz`), which also need the
rule/technique columns the recorder lacks today (it writes only the first 10 rule flags).

**The suite: short paired runs** of the same network vs the installed one, on the same seeds, about 20 minutes in all:
- every specialist bot of the stage just trained, 2 minutes each;
- the style mixer (seen styles and held-out styles);
- the old mix (spacer, stander, timer, and the rest, so the old strengths don't slip).

**Install rule (the user's):** better on the targeted part, and others better or even (A − B > −2 se), then install at
once, with a backup and the README note.

**Real fights:** the user's fights are the final judge. The technique detector reports, from their traces, what the AI did
in the moments the user cares about: sprint hit → opponent jumped → crit taken (C6/C7 should replace it); a jump-in
opening (T1a/T8).

**League (stage T-G):**
- The main agent plays: itself (35%), past copies chosen by PFSP weight (1 − win rate) (35%), the style mixer and
  specialists (20%), and exploiters (10%).
- **An exploiter** is a copy trained for ~2 hours only against the current main. It joins the pool when frozen. Its win
  rate against the main is the "how exploitable" number.

- [ ] trace detector + report lines per technique
- [ ] recorder writes all rule flags and the detected techniques
- [ ] 20-minute suite script + verdict line
- [ ] league sampling + exploiter runs

---

## Part 9. Order of work and time (honest)

At about 2 million steps a day on the screen:

| Step | What | Time |
|---|---|---|
| 1 | Technique primitives in the bot code (20 bots) + tick tests of each against PVP-TECHNIQUES.md | 1 to 2 days of coding |
| 2 | Technique detector, habit counters, exchange table, recorder columns | 1 day |
| 3 | Teacher interface + kickstart loss; teachers for T-A, T-B | 1 day |
| 4 | Net2Net widening, checkpoint upgrade, prediction head | 1 day |
| 5 | Train T-A, T-B (checks + install after each if better) | 2 to 3 days |
| 6 | Train T-C, T-D, T-E | 3 to 5 days |
| 7 | T-F mixer, T-G league | 1 to 2 weeks |

- Coding steps 1 to 4 run while the current installed model stays in the user's game.
- A model goes in the moment one passes the suite, not only at the end.
- First installable result: after T-A/T-B, about 4 to 6 days in.
- The user gets a short report each day: per technique, the usage, the success rate, and the damage a minute against each
  bot.

---

## Part 10. Risks, and what is done about each

| Risk | What is done |
|---|---|
| Too few steps (on-screen only) | Kickstarting (~10x fewer frames), data skipping, start from stage 6, not zero |
| Overfitting to the bots | Probability + timing + ping drawn per fight; style mixer adapts; league + exploiters; held-out styles for tests |
| Forgetting older skills | 30% rehearsal; v21 as a decaying teacher; the old mix in every check |
| A bot does a technique wrong | Each primitive has a tick test written from the doc's steps; the user can watch a drill and say what is off |
| Using a technique for no reason ("holding hit for no reason") | Techniques are never rewarded; teachers decay to 0; the reward is damage traded only |
| Explicit opponent modelling hurting (seen in one study) | The prediction head is detached and A/B'd; it can be dropped |
| Restarted student falls behind v21 | The fallback (continue v21 widened) runs for a day; keep the better one |

---

## Sources

- Oh et al., *Creating Pro-Level AI for a Real-Time Fighting Game Using Deep RL* (Blade & Soul, 2019): https://arxiv.org/abs/1904.03821
- Li et al., *FightLadder: A Benchmark for Competitive Multi-Agent RL* (ICML 2024): https://arxiv.org/abs/2406.02081
- McDonald et al., *FootsiesGym* (2026): https://arxiv.org/abs/2607.06514
- Vinyals et al., AlphaStar league training (summaries): https://www.kdnuggets.com/2019/11/reinforcement-learning-methods-alphastar-outcompete-human-players-starcraft.html , https://neurips.cc/virtual/2023/poster/70220
- Jaderberg et al., *Human-level performance in first-person multiplayer games* (Quake III CTF, FTW): https://ar5iv.arxiv.org/html/1807.01281
- Parisotto et al., *Stabilizing Transformers for RL* (GTrXL): https://arxiv.org/pdf/1910.06764
- Cobbe et al., *Procgen* (frame stack vs LSTM): https://arxiv.org/pdf/1912.01588 ; SB3 PPO vs RecurrentPPO report: https://wandb.ai/sb3/no-vel-envs/reports/PPO-vs-RecurrentPPO-aka-PPO-LSTM-on-environments-with-masked-velocity--VmlldzoxOTI4NjE4
- Hernandez-Leal et al., *Agent Modeling as Auxiliary Task for Deep RL* (AIIDE 2019): https://ojs.aaai.org/index.php/AIIDE/article/download/5221/5077/8319
- He et al., *Opponent Modeling in Deep RL* (DRON): https://arxiv.org/pdf/1609.05559
- Grover et al., *Learning Policy Representations in Multiagent Systems*: https://arxiv.org/abs/1806.06464
- Schmitt et al., *Kickstarting Deep RL*: https://arxiv.org/abs/1803.03835
- Rajeswaran et al., DAPG: https://arxiv.org/pdf/1709.10087
- Chen et al., *Net2Net*: https://arxiv.org/pdf/1511.05641
- Plasticity loss survey: https://arxiv.org/pdf/2411.04832 ; Plasticity injection: https://arxiv.org/pdf/2305.15555
- *Revisiting Action Factorization for Complex Action Spaces* (2026): https://arxiv.org/abs/2606.26574
- Andrychowicz et al., *What Matters in On-Policy RL*: https://arxiv.org/abs/2006.05990
- Ye et al., *Towards Playing Full MOBA Games with Deep RL* (curriculum self-play, distillation): https://arxiv.org/abs/2011.12692
- Liang et al. 2025, opponent-pool fighting game RL: https://jcta.ijournals.cn/cta_cn/article/html/CCTA230688
