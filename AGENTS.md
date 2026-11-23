# SwordAI: the rules for any AI agent working here (Claude, Gemini, any other)

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
  it. Do not watch it. One check every 6 hours: a one-line reply unless the run is 24 h+ old.
- **One run = one structural change, trained at least 24 hours before it is judged.** No edits mid-run. Stop early only
  on a crash, or a collapse (under 30% of rounds against the installed network after 6 hours).
- **Judge** with the head-to-head (section 1). Better means more rounds won, beyond the noise. Then the user tests it
  on people. Only the user decides on an install.
- **Log** one line in `RUNS.md`: date, the change, the literal problem it fixes, and the result.
- **Status in one command** (don't read the big logs):
  `grep -a "^===\|ROUNDS WON" run/school-checks.log | tail -12`. The flag files in `run/swordai/school/` say what
  mode training is in (`distill`, `rules-off-check`).
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

## The user's goals now (19 Nov)
A big jump in four skills, judged by rounds won and the user's real duels (`tools/bench.py` technique rates only help
with diagnosis):
1. **Starting combos** (T3, T4 in `docs/reference/PVP-TECHNIQUES.md`).
2. **Holding combos** once started.
3. **Countering every kind of crit**: crit spam, jump-in crits, double crits.
4. **After its own punish crit, keep critting back until the opponent misses** (T1c and onward). This reverses the
   user's 15 Nov ask ("no crit back after its punish crit"); the 19 Nov ask is the one in force.

Get them through structure (`docs/DESIGN.md` section 4: distill the rules, fixed reward, league of opponents with
replays of real players, memory), not new rules, rewards or drills.

## Current state (keep this section to a few lines; update it, don't append)
- **Stage 1 PASSED (Run 128, 19 Nov):** fixed full-policy DAgger distillation (`70% vs 69%`, z +0.3).
- **Stage 2 Run 130 PASSED on BOTH honest check AND real humans (22 Nov, zero play-time rules):** 211-pair check **85% vs 70% (z +3.9; 100 ms lag z +4.5)**; **53 real human duels: 38–9 (80.9% WR), 1,099/796 hits (1.38×), max combo 21 (avg 2.42), crit-back 2+ 18% (max 3), whiff 6%, uncharged 7%, double-click 0.6%**. 257 real duels banked in `real-fights/`.
- **Stage 3 Run 131 CHECKED (23 Nov, 22.6h / 12.93M steps, `stage3-run131-23h.bin`, `gru|w| 4.18`, 12 frozen exploits):** 228-pair check **A 177/228 (78%) vs Run 130 B 169/228 (74%), z +1.2** — strong at **0 ms (`90.4% vs 78.9%, z +2.5`, `baseline` 27–7 vs 17–17, `combo-player` 16–0)** with huge mechanics gains (`Holding a combo` **2.21 vs 1.31**, **`whiff` 3.2% vs 5.2%**, **`uncharged` 4.2% vs 7.2%**, `swung first` 56%), slight softness at **100 ms (`64.9% vs 69.3%, z -1.2`)**. Installed on `DRL AI` for user's 21:40–22:00 human test (dev client stopped, `run/school-cycle.stop` set).
- **Next (Stage 4):** after user's real-duel verdict on Run 131, ingest new real duels, fix 100 ms check-pool parity (`(pair/poolSize + pair)%2`) + Exploiter reset-to-PFSP/main (not weak Stage 1 prior), and build Stage 4 (playstyle-clustered lagged 5-tick VPT IDM + Human-KL RL opponents + fading winning human demos, capped share).
- **Installed in the user's game:** Run 131 Stage 3 (`stage3-run131-23h.bin` + `de2f0c0` jar) installed 23 Nov 21:29 on `DRL AI` (17 Nov backup safe in `DRL AI\swordai\backup-before-league-20261121-2224\`; Run 130 safe in `D:\swordai\run\swordai\school\stage2-run130-22h.bin`).

## This machine
- Windows, 16 GB RAM (about 13 GB usable, often under 1.5 GB free): only training and the agent should be open.
- JDK for Gradle: `C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2` (set JAVA_HOME). Don't build while
  the dev client runs. Python: `C:\Python38\python.exe`. Shell scripts run in Git Bash.
- Dev client: run folder `D:\swordai\run`. Drive it by writing `/swordai` commands to `run\swordai\inbox.txt`; its log
  is `run/school-client-live.log`. `/swordai school` trains, `/swordai school check` is the paired check.
- The user's own game: Modrinth profile `C:\Users\jerem\AppData\Roaming\ModrinthApp\profiles\DRL AI`. Its
  `logs/latest.log` holds the real-duel lines (`tools/duels.py`).
