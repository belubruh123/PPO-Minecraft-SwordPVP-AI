# SwordAI plan and checklist (from 6 Nov 2026)

**Read this after every compaction or new session, right after HANDOFF.md section 1.** Tick a box when it is done (with
the time), write each block's verdict under it, and commit. Never delete a block: mark it done, dropped or moved.

**Every report to the user starts with the plan step I am on** (the user, 6 Nov 22:55), e.g. "Plan: block 1, waiting
for check 2 (done: check 1; next: check 3, verdict)". Keep this line current:

For the panel (short, plain words; keep both current):
**NOW: Block 2 · the benchmark · first comparison (run 41 vs your game's model)**
**INSTALLED: run35-0657 · +4.5 vs B · since 5 Nov 21:19**

**CURRENT STEP (7 Nov 03:45): block 2 ending, block 3 ready. Waiting: run 41's third check (03:29, ends ~04:05) = run 41's verdict; then the benchmark run41a (queued, suite v1) vs ref1. Run 42 (combo reward, committed 8de2836) deploys after that via run/redeploy-request (on run 41's line, or with COPY_OLD_BODY back on if run 41 is reverted). Then the v2 references.**

## Why this plan exists (the user, 6 Nov 06:30-07:10)
- They want a visible improvement **every ~3 hours**, each block with **one number and a target**, reported at its end.
- **It is PPO, not supervised.** Every update now teaches four copy targets (technique teacher, installed network, rules'
  keys, replays) and one reward target, so it can only tie what it copies. The A-vs-B check shares the rules and stays at
  +0..+3 (noise +-4): it cannot show progress by itself.
- **Any method is allowed** (reward learning, supervised where it fits, drills, a network per skill, self-imitation). The
  user's fear: I am stubborn and never try another way. **Rule: if a block's number has not moved in 3 hours, the next
  block uses a different KIND of method, not a retune of the same one.** Name the method in every block report.
- **The old-stage review (aim, tracking, footwork, hit timing, sprint-hit + W-tap) stays in every block. Never remove it.**
- PVP knowledge only from docs/PVP-TECHNIQUES.md and the user's own words (memory: swordai-feedback-pvp-doc-only).

## The scoreboard (measure at every block's end; baselines are run 39's nine checks, 23:36-06:08)
Measure from the checks' hit log: `C:/Python38/python.exe -I tools/lastchecks.py run/swordai/school/hits.csv <scratch>/hits.csv K`
(K = checks since the block started), then the script named below on `<scratch>/hits.csv`.

| Number | How | Run 39 baseline | Target |
|---|---|---|---|
| After their crit, its next hit is a crit | tools/punish.py | A 46%, B 48% | block 1: 55%+ |
| Damage taken in the air from its own jumps | tools/air2.py ("air, own jump") | A 23.5%, B 5.4% of damage taken | block 3: under 12% |
| Follow-up hits in its own combos a fight | see block 2, first box | A 2.10, B 2.20 (verify meaning) | block 2: A clearly up, B not |
| Combo starters / holders, technique % | tech-review.py 240 | T4 51, T5 46, T7 37, T9 39, T10 15, T11b 24, T11c ~1, T2 45 | block 2: T4 60+, T10 20+ |
| Sprint hits on them falling (K2 broken) | check line "K2 broken" | 31-61 a check | falls |
| A - B a fight | hourly check ALL row | run 39 mean +0.6 over nine | not clearly worse |

## Block 1: punish crit on jump-ins (run 40). 6 Nov 06:22 to check 3 (~7 Nov 00:30; outage 07:13-22:38). Method: a rule (hold until they land, only a crit swing)
- [x] 06:10 analysis (EXPERIMENTS row 40; tools/punish.py, punish2.py, k2hole.py, air.py)
- [x] 06:16 change: SchoolSession.critSwingNow, hold until they land, SchoolFighter.holdJumpInSwing (fights)
- [x] 06:22 redeployed, training run 40
- [x] check 1 (07:11, network of 45 min): after-crit crit share **A 68% (38 of 56)** vs B 46%; jump-ins punished with
  its crit 40% (19 of 48); A-B +0.5 (4.1); swings landing A 96% vs B 92%
- **OUTAGE 07:13 to ~22:45 (6 Nov):** the client and check loop were started from inside the Claude session and died when
  it ended; the watchdog's 89 restarts all failed (Gradle: "Could not determine if stdin is a console"), fixed in
  tools/school-restart.sh and school-redeploy.sh (stdin from NUL). Let the watchdog start the client (it outlives sessions).
- [x] check 2 (23:11, about an hour of training in all): after-crit **A 47% (30 of 64)** vs B 43%; jump-ins punished 37%;
  A-B +1.0 (3.6); swings landing A 94% vs B 90%. Both checks: A 57% (68 of 120) vs B 46%, A-B +0.8
- [x] 23:20 the progress panel redesigned (tools/dashboard, http://localhost:8765): reads PLAN.md, EXPERIMENTS.md, the
  checks and hits.csv live, so it changes with every commit; the watchdog keeps it running
- [x] check 3 (00:00): after-crit A 43% (26 of 61) vs B 60%; A-B -2.6 (3.4)
- [x] 00:10 VERDICT: target missed (51% over three checks, 95 of 186; B 49%; run 39 46%), A-B -0.4: run 40 REVERTED
  (JUMP_IN_HOLD_THROUGH_HIT = false, run 39's hold = the user's rule). Old wording: kept if the share is 55%+ and A-B not clearly under +0.6; else revert to run 39 (JUMP_IN_GUARD logic) and say why
- [ ] DROPPED for the benchmark (it compares with the game's model directly): screen the game's model on the SAME pairs as the
  hourly check, `FROM=0 bash tools/screen.sh 45 /d/swordai/run/best-run35/run35-0657.bin:1` (B stays the ruler: it is also the
  training's copy target and sparring partner, so swapping it would change training). Then the panel shows
  training vs your game = each check's A-B minus that screen's A-B (same fights); add the line INSTALLED_SAME_PAIRS to this file
- [ ] DROPPED (run 40 reverted): ask the user to install the run-40 jar before they play on 7 Nov (tools/install-ab.sh, same five arms:
  `noback=@installed,on,,1,noback old1418=.../run23-1418.bin run26=.../run26-0413.bin old0739=.../run30h-0739.bin`, --dry first)

## Run 41, between blocks 1 and 2 (user, 6 Nov 23:45: "it learns from the 23 Oct model?"). Method: stop copying a worse model
The 23 Oct model is B, AND the training's copy target (installed pull 0.05, its replayed moves), the source of the rules'
keys (pull 0.29), and the only sparring partner (school/league is empty). Two better models were installed since.
- [x] 6 Nov 23:58 switches found: body copy = SchoolPpo installedHere (body pull floor 0.05) + TeacherReplay installedBody; camera = OLD_PULL
- [x] 6 Nov 23:58 SchoolStages.COPY_OLD_BODY = false (body copy and its replay off); camera copy KEPT (aim broke when faded, 24 Oct); rules stay; core tests pass
- [x] 6 Nov 23:58 school/league/game-run35-0657.bin (half of the sparring from the next start)
- [x] EXPERIMENTS row 41
- [x] 7 Nov 00:06 redeploy asked of the watchdog (run 41 + the bench mode + run 40's revert, one restart)

## Block 2: the benchmark. After run 41, a few hours of building. Method: build docs/BENCHMARK.md (the user, 6 Nov 23:50: "redesign the entire benchmark system")
Skill tests per technique family + head-to-head against the game's model + the bot fights, all in HP per real fight,
weighted by how often each situation happens in the user's real duels; verdict BETTER/TIE/WORSE with noise; A/A test first.
- [x] 6 Nov 23:55 design written: docs/BENCHMARK.md
- [x] 7 Nov 00:20 BENCH mode (a background agent; reviewed, compile-checked, committed 9d325ce): school/bench-suite.txt in a check plays
  tools/bench/suite-v1.txt with one network on side A, each episode recorded tick by tick to swordai/bench/<tag>/<iiii>-<bot>.txt.gz,
  DONE at the end; tools/bench-run.sh TAG NET. START IT ONLY via the watchdog: write "TAG NET" to run/bench-request
  (the runner swaps NET into training's slot; a session that dies mid-run must not leave it there)
- [x] head-to-head: suite line h2h 20 plays against school/league/game-run35-0657.bin (untested at runtime)
- [x] 7 Nov 00:07 real-fight frequencies per situation from the user's duels (tools/bench.py weights -> run/swordai/bench/weights.json;
  per real fight: punish chances 12.0, jump reset 12.3, make miss 5.8, holding combo 5.0, reverse defl 4.7, crits 4.4, deflection 3.8)
- [x] 7 Nov 00:10 scorer tools/bench.py (df3148c): score / compare / selftest; selftest on the real duels' two halves: TIE (-0.19 +- 0.51)
- [x] 7 Nov 00:27 the panel's benchmark box (3561294): verdict, total, parts, each test; progress bar while one plays
- [x] 7 Nov 00:30 launcher fixes first (8e3a0a8, a28fc30): the client and everything the watchdog starts get consoles of their own
  (the client in the task's console kept the task "running": every watchdog run 00:15-00:35 was skipped); bench keeps the stop file
- [x] 7 Nov 01:05 ref1 (00:37-00:46) and ref2 (00:55-01:04), 200 episodes in 9 minutes each; A/A `compare ref2 ref1`: TIE
  +0.77 +- 1.16 (skills +2.24 +- 1.89, h2h -1.40 +- 2.65, bots +0.01 +- 0.51) -> suite-v2 (25 a bot, 60 h2h, 360 episodes, ~16 min,
  about +- 0.8): request "TAG NET suite-v2"; R must be measured again on v2 (refv2a, refv2b)
- [ ] first real comparison: run41a = run 41's network of the 03:29 check (best-candidates/run41-0329.bin) on v1 vs ref1 (queued 03:32)

## Block 3: combo starters and holders. After block 2 (judged by the new benchmark). Method: exact-moment drills + reward on the result
The user: all ways the document starts a combo, and all ways it holds one, learned by reward (copying only a light hint).
- Starters (doc Part 2, "ways to start a stun"): T4 deflection, T5 reverse deflection, T6/T6a make them miss then stun,
  T7 three-block hit, T8 hit the rising jumper, T9 jump reset (also starts stuns).
- Holders (doc Part 4): T10 uppercut (+S), T11a lunge, T11b hops ("holds combos"), T11c hops and lunges, T2 standing crit
  (best right after a stun or at a combo's end).
- [x] 7 Nov 03:35 myRow = hits dealt since the last hit taken (1 = first). Follow-up hits (myRow>=2) a fight, last 3 checks:
  A 2.14, B 2.31; within 20 ticks of the last hit A 1.74, B 1.75 (groundwork agent; scratchpad block3-groundwork.md)
- [x] 7 Nov 03:35 scenes restore bodies but not the bot's plan: a moment can't be forced, only kept a few ticks before (T4
  needs their swing after the start). Exact-moment scenes: a later step if the reward alone stays flat
- [x] 7 Nov 03:45 reward: run 42 COMBO_HIT 0.2 a follow-up hit (within 20 ticks, nothing taken since), ~+0.35 a fight vs
  damage +-6; no separate stun-start term (it would pay hit-and-out). Committed 8de2836, compile-check OK
- [x] 7 Nov 03:35 nothing to lower: every starter/holder pull is already 0 (SchoolStages.PULL_CAP = 0; "pull 2.00" is a
  share capped to 0). Reviews kept
- [ ] core tests, compile-check, EXPERIMENTS row, redeploy (not during a check, 20 min after the last)
- [ ] measure at the block's end with the benchmark (docs/BENCHMARK.md): starters/holders %, follow-up combo hits A vs B, A-B; verdict; if flat -> block 4's method differs

## Block 4: its own jumps inside their reach. After block 3. Method: decided by what is found (must differ in kind if block 3 failed)
- [ ] who presses jump in those moments: the rules (hits.csv "ruled" bits) or the network; which situation labels
- [ ] the document's view of those jumps (T1a bait close, T9 jump reset, C3, T11b are allowed: is it their timing that is wrong?)
- [ ] change, EXPERIMENTS row, redeploy, measure at the block's end (target: under 12% of damage taken)

## Block 5: the learning itself. After block 4. Method: reward drives the keys it already copies
- [ ] copy pulls off (or near) on keys where it agrees with the rules 90%+ (update line "rules' keys ... by head"), A/B
  run judged on the stuck techniques and on fresh-pair screens, not on the +-4 hourly check (25 Oct a fading copy lost,
  with a far weaker network: say so in the report)

## Backlog (other kinds of method, use when a block fails)
- [ ] supervised from the user's real duels: the AI's keys in exchanges it won (fights/*.txt.gz, tools/duels.py)
- [ ] a small network per skill and a chooser above (the user's method, 27 Oct: decider picks WHEN, skill nets HOW)
- [ ] self-imitation of its own best moments (the hook exists: "its own best moments again: none yet")
- [ ] fresh-pair screen of the latest network against the installed's bars (next pairs 12100 scripted, 12300 mirror)

## When the user plays (7 Nov)
- [ ] real jump-ins: tools/kbswing.py and tools/knockaway.py on the new duels (crits taken a jump-in; before: swing
  tick 3+ = 0.24-0.43 crits a jump)
- [ ] the round stats count spectator deaths/kills (FightSession, jar of 5 Nov 22:47): lost rounds no longer show
  dealt > taken without a death
- [ ] the live A/B pool (review.sh, tools/duels.py)

## Block reports (append one per block)
- Block 1: MISSED. Method: a rule (hold until they land). After-crit crit 46% -> 51% (target 55%), level with B (49%); reverted. Next blocks use other kinds of method.
- Block 2:
- Block 3:
- Block 4:
