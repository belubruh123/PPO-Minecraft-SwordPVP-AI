# FABLE-PLAN: the architect's priority list (10 Nov 2026, 21:00)

Numbers first. Branch `worktree-agent-ad506828c52ab2a46`, built on main at run 68 (5559842).

## 1. Why A plateaus level with B (the structure, with the counters)

1. **In a check A = the network + B's hand-written rules + A's guards; B = the 23 Oct network + the same rules.**
   The rules press about 1770 of 4184 head-ticks an update (W/S 405, strafe 502, sprint 416, jump 416, swing only 30):
   hitAim 1265, straight 629, reset 538, punish 203, backOut 98. So the rules decide the movement at the decisive moments;
   the network's free keys are the camera, the swing (where the guards allow it) and the movement in between.
   A and B differ only there: A's camera is better (crosshair off them 25% vs 36%), A swings less (321 vs 392 a check;
   A's whiff guard 97% on them vs B's 89%), A's jump-in guard holds where B swings (K2 broken 18 vs 34, T8 hits 74 vs 106,
   crits taken within ten ticks 1 vs 10). Net: 17-18 vs 18-18 of 45 rounds for six hours.
2. **The network alone is far below the rules.** 61-70% of the full training fights are played without the rules; over
   runs 67-68 (60 updates) they ran at net -100 to -480 a minute, knockouts 1-3 for and 3-7 against, every update.
   The rules' keys are imitated at 89-90% agreement (CE 0.25, a controller's target), but 90% on every tick is not the
   rules' timing: the rules are timers (state machines) the network sees only through its own last keys (HIST).
   Since run 30 the plan was that the network would take the rules over; ten days later it cannot. In checks the rules
   carry A, so A can only be as good as the rules plus its free keys.
3. **The decisive chain against people** (counters of run 68 and the user's 200 rounds): it swings first (the swing
   head's entropy is 0.01: it never waits), they hit-select it (T4), it is lifted with the sword spent (training: of 22
   lifts in reach, the sword was back in 6; real duels: hit with the sword empty 72%), so no punish crit (B1 by the game
   4-13%), then it is comboed (their third hit 40% vs B 29%). The document's answers are T6/T6a (make them miss), T9b
   (jump right after your attack), T5, and B1 with the sword kept.
4. **The network cannot see timing.** It gets two frames (100 ms) of the opponent's mask statistics and running averages
   over seconds (Habits); nothing in between. A jump is 12 ticks, a combo's rhythm 13, the deflection's window 1-3 ticks
   after their swing. The predictor guesses their technique right 48%. Agent 2 found no take-off cue in one frame; the one
   weak cue ("0.5 block farther at air tick 5") is a history cue. The document's part 8: "it needs memory of the last few
   seconds to predict".
5. **The learning signal is diluted and the policy is pinned.** About twenty shaped terms of 0.15-1.0 sit beside 0.1 a
   health point; five pulls (technique teachers, the rules' keys, the installed network, the anchor, three replay buffers)
   act on about two thirds of the steps; the guards take the swing head out of the loss on held ticks; exploration sits at
   the floor (swing 0.01, jump 0.06). PPO cannot reinforce a swing back or a T1c jump it never tries.
6. **Why bot results mislead.** The bench ranks by rounds against bots calibrated piece by piece (runs 58-63). Habits the
   rules reward there (swing first, hit rising jumpers) are what people hit-select and double-crit. Only the user's duels
   decide; the checks are a guard against regressions, not proof.

## 2. Rebuild or not

Not a rebuild from scratch: the rules and the guards are the only thing winning rounds now, and rules 1-2 live in the
guards. Staged instead, each stage behind a flag, the live loop never broken:
- Stage A (this session): give the network what it lacks (the opponent's last half second; exploration where it is
  deterministic), without touching the rules or the guards.
- Stage B: free the network from the rules' mistakes (the rules' keys pulled only where the rule's moment went well),
  so it can grow past them on its free keys.
- Stage C: per-key hand-overs where the network beats the rule in rule-off fights (not before: runs 62/65 handed keys to
  untrained layers and lost).

## 3. Priority list

| # | Problem (PROBLEMS.md) | Change (kind) | Expected on rounds | Risk to rules 1-2 | Gate in 1-2 h (50-60 updates) | Status |
|---|---|---|---|---|---|---|
| P1 | 1, 2, 3, 9, 12: timing it cannot see | **Seen history**: the opponent's last 8 frames (head x/y/area, body area, relative speed, their sword, its own camera turn) as a side input through zero-start layers (SchoolNet.SEEN_HISTORY; run 69a) | +1-3 of 45 once the layers learn (3-4 h); first needed for every timing technique | none: zero-start, plays as before at load; the guards unchanged | "seen history \|w\|" rises from 0 (0.5+ in an hour); by the game T4 19% -> 25%+, T9a 34% -> 40%+; "sword back" at lifts 6/22 -> 10/22; punish crit chances critted >= 50%; next two checks z >= 0 | committed da2f825 |
| P2 | 1, 4, 9: the swing head never waits | **Entropy floor** per head over the OPEN ticks (SchoolPpo.ENTROPY_FLOOR; run 69b): the swing's coefficient rises 1.3x an update (cap 0.08) until its open-tick entropy is >= 0.25 (jump >= 0.08, W/S >= 0.12); not on steps told B1 | +0-2: deflections and kept swords | rule 2 protected (B1 steps keep the base coefficient); rule 1 untouched (held ticks are locked) | the line's "open-tick entropy swing" reaches 0.25 within 10 updates; swings on them stays >= 90%; deflects (T4) up; "jumps a minute off moments" not above 2x; punish crit >= 50% | committed da2f825 |
| P3 | 4, 5: lifted with the sword spent; T1c flat (run 68 reverted 21:30: T1c 7-8%, critted back 15 -> 26%, punish crit 54 -> 45-47%) | Counters first (lifts in reach with the sword back); then T9b (jump right after its attack) and T1c as gated options like run 62's T10/T11 (did 90%+ when told), never a reward term again | +1 | low; B1's own layer untouched | "sword back" at lifts toward 50% of lifts in reach; T1c when told 50%+ | todo |
| P4 | 2, 9: pinned to the rules | **Rules' keys pulled by the outcome** (SchoolPpo.RULED_AWR): the rule's key is a target only where the moment went better than expected (as the technique teachers since run 11) | rule-off play up; checks indirect | none in checks (rules press) | rule-off fights' net a minute from -170 toward 0 over 3 h; ruled agree stays >= 80%; checks not down | todo |
| P5 | 3: the guard holds in their combo (18% of ready ticks) | Agent 7's table (300 real jump-ins taken in their combo, no hit of theirs in air ticks 1-6): holding W, no swing (what the guard leaves it doing) lost, their hit first 51%; a swing at their ground tick 0-1 won 92%, its own jump along with theirs then the crit won 89%, a swing at air ticks 3-6 (the rule-1 break) won 82%. **Run 70** (SchoolStages.JUMP_ALONG_TAUGHT, PvpPlayer#jumpAlongMoment): in their combo the teacher meets the jump-in with the jump along and the crit (K3) instead of the wait; K3 taught as a gated option (run 62's method); no rule gives way; the guard unchanged | +1-2 | rule 1 kept (only the crit passes the guard); rule 2 untouched (B1's own layer) | "jumped along with their jump-in" from ~3 an update to 10+, "crits then" > 0 and rising; K3 done rate 22% -> 60%+ when told; held ticks in their combo (18%) fall; third hits toward B's; punish crit not down | committed 4b4e2bd |
| P7 | 1-3 (the user's call) | The rule-1 break row above (a swing at air ticks 3-6 in their combo: its first 82%, HP lost 1.71 vs 1.99 holding) is reported, not acted on: rule 1 is the user's | - | - | - | reported |
| P6 | 12: its own combo | Per-key hand-over in checks only where the option layer's rule-off outcome beats the rule's | +1 | none | each key's A/B in rule-off fights | todo (stage C) |

Not planned: more reward terms, more bots' calibration (the main agent's lane), any hard rule.

## 4. Deploy notes (every batch)
- Each batch compile-checks alone (`tools/compile-check.sh`), has its own commit and `static final boolean` flag named
  for the run; a failing gate is answered by the flag, not a revert of the code.
- P1 adds two tensors to the acting network (appended, zero): an old checkpoint loads (`missing()` + 2); a checkpoint
  saved by the new jar does NOT load in the old jar. So: to go back, set SEEN_HISTORY = false (the layers stay, unused),
  do not reinstall the old jar on a new checkpoint.
- Core changes come with tests in core/src/test (the deploy runs them).
