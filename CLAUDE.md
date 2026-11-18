# SwordAI: the rules for any AI working here

SwordAI is a Fabric mod: one neural network plays Minecraft 1.9+ sword PvP. The goal is to **win rounds against real
people**. Read this file and the last 10 lines of `RUNS.md`. Nothing else is required reading.

## 1. The scoreboard
1. The user's real duels are the truth.
2. The training stand-in is **rounds won in a head-to-head against the installed network, over at least 200 rounds**.
   Fewer rounds than that is noise.
3. Everything else (technique %, bot win rates, reward curves, dashboard numbers) only helps with diagnosis. It never
   decides anything. Bots mislead: a network that beat every bot lost every round to people (15 Nov).

## 2. Before touching code: name the literal problem
Write three plain lines first:
- **What** it does wrong, seen in a real lost round (when it happened, what it did).
- **Why**, the literal cause. Ask these in order and stop at the first yes:
  1. Can it **see** it? Does the observation even hold that information?
  2. Can it **do** it? Do the action space, timing or lag make it impossible?
  3. Does it **meet** it in training? Do its opponents behave like the people it loses to?
  4. Does **winning** reward it? If doing it does not win more rounds, it may not be wrong.
  5. Can it **remember** it? Some timing needs memory or more capacity.
  6. Is it a **bug** in recording, replay, sync or lag?
- **Fix** that thing. The fix changes the structure: the inputs, actions, opponents, memory, or a bug.

If you cannot write the "why" in one sentence, you do not understand the problem yet. Watch more real rounds before
changing anything.

## 3. Banned: these caused 125 runs of going in circles
- **New reward terms, or reshaping the reward.** The reward is fixed: win or loss, plus a small health-difference term.
- **Hand-coded rules, guards or overlays** that press keys over the network.
- **Tuning numbers to chase a metric.** A number changes only when you can write why the old value is literally wrong
  (for example "longer than a round"). Never change a number twice for the same problem. If one change did not fix
  it, the problem is not that number.
- **Bots that script one technique**, and drills built around one technique.
- **New documents**: no handoffs, checklists, plans, per-run reports or problem lists. History is one line in `RUNS.md`.
- **One-off analysis scripts in `tools/`.** Write them in the scratchpad and throw them away.

## 4. The long-running loop
- Training runs unattended: `start-training.cmd` starts it, the watchdog restarts crashes, `stop-training.cmd` stops
  it. Do not watch it. Look at most once a day, or when the user asks.
- **One run = one structural change, trained at least 24 hours before it is judged.** No edits mid-run. Stop early only
  on a crash, or a collapse (under 30% of rounds against the installed network after 6 hours).
- **Judge** with the head-to-head (section 1). Better means more rounds won, beyond the noise. Then the user tests it
  on people. Only the user decides on an install.
- **Log** one line in `RUNS.md`: date, the change, the literal problem it fixes, and the result.
- **After 3 runs without improvement, stop changing things.** Watch real lost rounds again and question the premise:
  is this even what loses rounds? Deleting code is a valid next run.

## 5. Keep it small
- A change should leave the code the same size or smaller. If you add 100 lines, delete 100.
- If you cannot explain the change in one sentence to someone outside the project, do not make it.
- Reports to the user are short: what changed, the result, the next step. No process detail, no usage talk.
- Use subagents only for real work (a rewrite step, a deep diagnosis). Never use them for monitoring or reviews.

## 6. Never
- Install a network the user did not approve. Back up the installed one first.
- Train while the user's own Minecraft is open (both run about 30% slower).
- Delete `run/`. It holds the checkpoints and the recorded real rounds, and it is not in git.
- Commit secrets: the GitHub repo is public.

## Where things are
- `docs/DESIGN.md`: how strong RL systems are built, and this project's rewrite plan. Read it before structural work.
- `docs/LESSONS.md`: hard facts learned from runs 1-125. Check it before repeating an old idea.
- `docs/reference/PVP-TECHNIQUES.md`: PvP vocabulary (T1, C3...) for describing rounds. Reference only: the network
  learns techniques by winning, not from this file.
- `core/` holds the pure-Java network, PPO and fight model, with tests (`gradlew :core:test`). `src/` holds the
  Minecraft side: sessions, arena server, mixins.
- `tools/`: training start/stop and watchdog, the head-to-head (`schoolcycle.sh`, `net-check.sh`, `bench-run.sh`),
  real-duel reading (`duels.py`, `duel-hits.py`), the dashboard (`tools/dashboard`, http://localhost:8765),
  `install-school.sh` (puts a network into the user's profile, with a backup first) and `install-watchdog.ps1` (the
  Windows task that runs `watchdog.sh` every 10 minutes; it expires after 7 days, so re-run it).
- Shell scripts must keep LF line endings: edit them with Edit or sed, never Python text mode.
- The full old history (EXPERIMENTS.md, PROBLEMS.md, the old SOPs) is in the git tag `backup-before-cleanup-2026-11-18`.
