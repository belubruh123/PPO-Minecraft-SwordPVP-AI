# SwordAI plan and checklist (from 6 Nov 2026)

**Read this after every compaction or new session, right after HANDOFF.md section 1.** Tick a box when it is done (with
the time), write each block's verdict under it, and commit. Never delete a block: mark it done, dropped or moved.

**Every report to the user starts with the plan step I am on** (the user, 6 Nov 22:55), e.g. "Plan: block 1, waiting
for check 2 (done: check 1; next: check 3, verdict)". Keep this line current:

**CURRENT STEP (6 Nov 23:20): block 1 (run 40), waiting for check 3 (~00:05). Done: analysis, change, check 1 (68%),
check 2 (47%; both 57%). Next: verdict after check 3, then block 2's first box (what myRow counts).**

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
- [ ] check 3
- [ ] verdict after check 3: kept if the share is 55%+ and A-B not clearly under +0.6; else revert to run 39 (JUMP_IN_GUARD logic) and say why
- [ ] if kept: ask the user to install the run-40 jar before they play on 7 Nov (tools/install-ab.sh, same five arms:
  `noback=@installed,on,,1,noback old1418=.../run23-1418.bin run26=.../run26-0413.bin old0739=.../run30h-0739.bin`, --dry first)

## Block 2: combo starters and holders. ~7 Nov 00:30 to 03:30. Method: exact-moment drills + reward on the result
The user: all ways the document starts a combo, and all ways it holds one, learned by reward (copying only a light hint).
- Starters (doc Part 2, "ways to start a stun"): T4 deflection, T5 reverse deflection, T6/T6a make them miss then stun,
  T7 three-block hit, T8 hit the rising jumper, T9 jump reset (also starts stuns).
- Holders (doc Part 4): T10 uppercut (+S), T11a lunge, T11b hops ("holds combos"), T11c hops and lunges, T2 standing crit
  (best right after a stun or at a combo's end).
- [ ] read what myRow/theirRow in hits.csv count (is 1 the first hit?) and fix the baseline row above
- [ ] read how drills and scenes start (SchoolSession: sceneNow, KIND_PUNISH drill, scenes.txt, focus.txt, the stage
  "T-E jumping" with its pulls) and how a fight could start AT the moment (right after their swing for T4, they stunned
  for T10, they hold S for T11a)
- [ ] reward terms: a stun started (its hit, no hit back within ~10 ticks) and each extra hit of its own combo with no
  hit taken; small enough not to swamp the damage reward (look at PUNISH_CRIT / OTHER_CRIT sizes)
- [ ] lower the copy pull for these techniques (the stage's pulls 2.0-3.0 now), keep the reviews
- [ ] core tests, compile-check, EXPERIMENTS row 41, redeploy (not during a check, 20 min after the last)
- [ ] measure at the block's end (~03:30): starters/holders %, follow-up combo hits A vs B, A-B; verdict; if flat -> block 3's method differs

## Block 3: its own jumps inside their reach. ~7 Nov 03:30 to 06:30. Method: decided by what is found (must differ in kind if block 2 failed)
- [ ] who presses jump in those moments: the rules (hits.csv "ruled" bits) or the network; which situation labels
- [ ] the document's view of those jumps (T1a bait close, T9 jump reset, C3, T11b are allowed: is it their timing that is wrong?)
- [ ] change, EXPERIMENTS row, redeploy, measure at the block's end (~06:30, target: under 12% of damage taken)

## Block 4: the learning itself. After block 3. Method: reward drives the keys it already copies
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
- Block 1:
- Block 2:
- Block 3:
