# The benchmark: is a model better than the one in the user's game? (design, 7 Nov 2026)

The user (6 Nov 23:40): one total of damage against one old model cannot tell whether a model is good. Test each technique
on its own against the installed model, fight the installed model too, weight everything and sum it into one comparison;
a model can be better at the techniques and slightly worse in one kind of fight and still win in real use. It must be exact.

## What is compared
- **C**, the candidate: a training network at a moment, with the rules on, as the user's game plays it.
- **R**, the reference: **the model installed in the user's game** (run35-0657 now), rules on. Not the 23 Oct model.
- Both play **the same list of test episodes** (same seeds: same bot, start distance, lag draws). C's and R's results are
  compared seed by seed (paired), so the luck of the draw cancels. R's results are kept per suite version and measured
  twice (see the A/A test).

## Part 1: skill tests, one per technique family of docs/PVP-TECHNIQUES.md
Each test = 30 short episodes against the arena bot that creates the situation. **Scored on the outcome, never on whether
the keys look like a teacher's.** Every test reports its own rate (what the user reads) and the **health points (HP) won
or lost in the 40 ticks after the situation starts** (what the score adds up).

| Test | Doc IDs | Bot(s) that create it | Rate shown | HP window starts at |
|---|---|---|---|---|
| Punish crit | B1, T1c | punish-critter, jump-in-critter | crit landed on the way down after their hit lifted it | their hit lifts it |
| Jump-in defence | K2, T8 | jump-in-critter | crits taken per jump-in; knockback hits on falling jumpers (lower = better) | their take-off |
| Deflection | T4 | swinger, attack-then-s | stun started right after their attack (its hit, no hit back for 10 ticks) | their swing |
| Reverse deflection | T5 | deflector | stun right after their deflection | their deflection lands |
| Make them miss | T6, T6a | swinger | their miss, then its stun | their swing |
| Three-block hit | T7 | straight-sprinter, three-block | first hit of the trade with no hit back | entering 4 blocks |
| Jump reset | T9, T9a, T9b, T9c | rhythm-comboer, stunner, deflector | knockback taken on a hit (less is better) | their hit |
| Holding a combo | T10, T11a, T11b, T11c, T2 | stunner, stun-escaper, mixer | hits in a row after its first stun | its first stun |
| Getting out of combos | C2, C3, C4, C7 | rhythm-comboer, uppercutter, lunger, hopper | their third hit in a row (lower is better) | their second hit |
| Crits | T1, T1a, T1b, T2, C1, C6 | standing-critter, double-critter, crit-breaker | crit damage dealt minus taken | each crit chance |
| Basics | old stages 1-3 | the review stages | on target %, found again (ticks), swings landing % | each episode |

## Part 2: whole fights
- **Head-to-head:** C against R itself (the league opponent slot), 45 seeds, each played twice with the sides swapped.
  HP net per fight and knockouts.
- **Bot fights:** the 45 mixed fights of the hourly check, C and R on the same seeds. HP net per fight.

## The score: everything in HP per real fight
- A skill test's difference (C minus R, HP per situation) is multiplied by **how often that situation happens in a real
  fight**, counted from the user's own duels (fights/*.txt.gz). Its weight comes from real play, not a guess.
  So the skills part reads "HP per real fight that C's skills win over R's".
- **Total = 50% skills + 25% head-to-head + 25% bot fights**, all in HP per fight. The split is in
  `run/swordai/bench/weights.txt` (the user can change it).
- Every number has its noise (standard error, from the paired seeds); the total's noise is propagated from its parts.

## The verdict
- **BETTER** when the total is above 0 by more than 2 noise **and** no test is worse by more than 2 of its own noise
  **and** head-to-head is not worse by more than 2 noise.
- **WORSE** when the total is under 0 by more than 2 noise. Otherwise **TIE** (not enough to say).
- A model is offered for the user's game only on BETTER, and the user's real duels (the live A/B pool) have the last word.

## Making sure the benchmark itself is exact
- **A/A test:** R against R must come out about 0 (within its noise). If it doesn't, the benchmark is broken; fix it
  before trusting any result.
- **Fixed, versioned suite:** the seed list is a file (`bench/suite-v1.txt`); a new version re-measures R.
- **Order counterbalanced:** half the seeds C plays first, half R first.
- **Real lag:** every episode draws agent lag 1-4 ticks and bot knockback ping as training does, from the seed.
- **First person, on screen** (user rule #1).
- **Enough episodes:** after the A/A test, the episode counts are set so the total's noise is under 1 HP per fight.

## Time
About 2 hours for a candidate (R's results are kept). It runs at every block's end (every ~3 hours), not hourly;
the hourly check stays as a quick monitor only.

## Building it (order)
1. Situation detectors and outcome rows for each test (most exist: the jump-in exchange, punish chances, combos got
   out of, crit moments in SchoolSession); a BENCH mode that plays a suite with a given network and writes
   `bench/<model>-v1.csv`, one row per situation.
2. Head-to-head through the league slot (SchoolBotPilot with R's network and rules).
3. Real-fight frequencies per situation from the user's duels (`tools/bench-weights.py`).
4. The scorer `tools/bench.py`: per test C, R, difference and noise; total; verdict; `bench/latest.json` for the panel.
5. The panel's "Benchmark: candidate vs your game" box.
6. The A/A test (R vs R), then the first real comparison.
