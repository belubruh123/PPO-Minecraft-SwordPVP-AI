# SwordAI handoff (30 Oct 2026, 22:00), from Claude to the next AI assistant

**Read this whole file before you touch anything.** Then read `docs/TRAINING-SOP.md`, the last 10 rows of `EXPERIMENTS.md`, and `run/best-candidates/CANDIDATES.txt`. The user may ask Claude to come back later: leave everything so that it can pick up where you stopped.

## 0. What this is

- **SwordAI** is a Minecraft 1.21.11 (Fabric) sword-PvP agent. It is a PPO network that plays in first person (screen pixels plus game numbers).
- The network trains in a **technique school**:
  - It learns techniques from `docs/PVP-TECHNIQUES.md` from scripted teachers and bots.
  - It also fights the **installed model**, the user's current fighter.
- **Goal (deadline 1 Nov):** beat the installed model on fresh-seed checks, with every technique at 85-90% or more.
- **The user's priorities, in order:**
  1. Real-fight skill: aim, whiffs, tracking, lag.
  2. Damage dealt as high as possible and damage taken as low as possible.
  3. The **punish crit** (B1) must improve.
  4. Technique percentages.

**Layout:**

| Path | What it is |
|---|---|
| `core/` | Pure Java: networks, learner, teachers, simulator, tests. The code that matters is in `core/src/main/java/dev/swordai/core/school/` and `.../pvp/`. |
| `src/client/` | The Fabric client mod. Training happens in `SchoolSession.java`. |
| `src/main/.../server/ArenaServer.java` | The arena. |
| `run/` | The dev client's game folder. **Never committed.** |
| `run/school-client.log` | The live training log. |
| `run/school-checks.log` | Every paired check and screen. |
| `run/swordai/school/` | Networks: `checkpoint.bin` is live; `checked-MMDD-HHMM.bin` are the hourly copies; there are also scenes, `focus.txt` and stages. |
| `run/best-candidates/` | Candidate networks plus `CANDIDATES.txt` (the record of every screen and install). |
| `tools/` | Every script you need. `S` defaults to `/d/swordai/tools`. |
| `EXPERIMENTS.md` | One row per run: what changed, why, and the result. |

**The user's game profile** (where the installed model lives) is the Minecraft profile "DRL AI". `install-school.sh` knows its path.

## 1. Absolute rules. Breaking any of these has made the user furious before.

1. **The GitHub repo is PUBLIC.** Never commit or push anything under `run/`, backups, logs, transcripts, profile copies, or anything with a login, token or session. Never print or repeat the user's `/login` line.
2. **First person only. Never train headless.** Training runs on screen in the dev client.
3. **Never install into the user's profile** unless a candidate passes the install rule (§5).
   - Always install with `tools/install-school.sh` (it backs up first).
   - Never install while the user's Minecraft is running from that profile.
4. **Never redeploy:**
   - during a paired check, i.e. when `school pair` appears in the last 40 lines of `run/school-client.log`;
   - within 20 minutes of the last redeploy;
   - without evidence.
   Batch changes and redeploy about an hour apart at most. The user was furious at redeploy churn.
5. **Never run Gradle while a redeploy is running.** Never run Gradle client tasks (`runClient`, `build` of the mod) while the dev client is running. Core tests are OK (§6).
6. **Only techniques from `docs/PVP-TECHNIQUES.md`.** No made-up tricks and no hard-coded one-trick rules.
7. **One change per run.**
   - Log it in `EXPERIMENTS.md` before deploying and fill in the result afterwards.
   - Commit every change to git with a clear message.
   - Run 25 bundled three changes and the result could not be read.
8. **Keep every network and all data.** Never delete files in `run/` or in `D:/swordai-backups/`.
9. **Do not restart `playguard6`. Do not change system or security settings. Keep data off `C:`.**
10. **Report briefly and honestly.**
    - Report bad results as bad, with numbers.
    - Never call a model "best" without fresh-seed evidence.
    - The user asked the assistant to make decisions itself ("make those decisions yourself") and not to wait for approval while they are away. But be conservative: revert anything that hurts.

## 2. The 30-minute review (the user's standing order). Do it every 30 minutes.

**Step 1: check that training is alive.**
```bash
ls -la --time-style=+%H:%M:%S /d/swordai/run/school-client.log   # last write must be under 5 minutes old
grep -c "school pair" <(tail -40 /d/swordai/run/school-client.log) # >0 means a paired check is running: do not redeploy
```
If the log is stale, training died. On 28 Oct an app update killed it for 12 hours. Restart it with `tools/school-redeploy.sh`; see §6.

**Step 2: real-fight skills first.** From the last "school update" lines of `run/school-client.log`, report:
- `swings: N swings, ... hits landed (P%)`, the share of swings landing;
- `turned to find them on N`, which should be about 0;
- `damage a minute X dealt, Y taken`, from the lines starting `T-E ...k steps` or similar;
- `K2 broken N`;
- `punish crit chances critted (whatever was chosen), last P% of 300`. **Report this every time.**
- Sparring against the installed model, a stable gauge. Compute it from `school fight installed-model ... dealt D, taken T` lines; recent normal is about +5 to +15 a fight.

**Step 3: techniques.**
```bash
C:/Python38/python.exe D:/swordai/tools/tech-review.py 8
```
- Read the techniques under 60% first, then those under 80%.
- Classify each one: RULE (a rule kept the teacher's key back), STUCK (enough moments, not rising), FEW (too few moments) or RISING.
- STUCK techniques that only copy the teacher better do **not** automatically need fixing. Copying the teacher's C3/T9 movement cost damage (run 25).

**Step 4: the last paired check.** Read the last block of `run/school-checks.log`:
- the `ALL` row: A−B net a fight, its se, and A and B damage a minute (dealt/taken);
- swings landing for A and B;
- the punish crit on their jump-ins for A and B;
- their combos' third hit for A and B.

Here A is the school network and B is the installed rules fighter.

## 3. The SOP (summary; the full text is in `docs/TRAINING-SOP.md`)

- **One focus technique at a time.**
  - It is trained from its failure scenes, with reviews in between: basics, earlier focuses, mastered scenes, full fights.
  - A technique is learned at 90% "told it, landed it".
  - A learned technique that falls under 90% gets RETRAIN.
- **The decision model (the "decider", `Chooser`) picks WHEN; a forced drill teaches HOW.** This is the user's method from 27 Oct.
- **Measure only on fresh seeds.** Pairs 0-44 (the hourly checks) are noisy, about ±4-5 a fight, and repeated, so they are never evidence for an install.
- **Every review starts with the liveness check.**
- **Before working on a technique**, check that its teacher pull is above 0 (`SchoolStages.techPulls()`; only `RUN_TAUGHT` techniques are taught).
- **Teach a technique's deciding key, not its whole body.** Movement-key lessons spread into the whole fight unless they are gated (see §8).
- **Always know the best so far.** Keep `run/best-candidates/CANDIDATES.txt` up to date after every check, screen and install.

## 4. The hourly loop

`tools/schoolcycle.sh` runs by itself, started by `school-redeploy.sh`:
1. It trains for 45 minutes.
2. It plays a paired check: 45 pairs, A against B, pairs 0-44.
3. It appends the result to `run/school-checks.log`.
4. It saves `run/swordai/school/checked-MMDD-HHMM.bin`.
5. It resumes training.

To stop it, create `D:/swordai/run/school-cycle.stop`. Do not kill it in the middle of a check.

## 5. Candidates, screens and installs

- **When an hourly check is high** (above about +6), copy that network (`checked-MMDD-HHMM.bin`) to `run/best-candidates/runNN-HHMM.bin` and add a line to `CANDIDATES.txt`.
- **Screen it on fresh pairs.** This pauses training for about 30 minutes. Use `/d/...` paths: a `D:` path breaks the `NET:TEMP` split.
  ```bash
  cd /d/swordai && FROM=6100 bash tools/screen.sh 133 /d/swordai/run/best-candidates/runNN-HHMM.bin:1 > /d/swordai/run/screen-scripted.log 2>&1
  FROM=6300 MIRROR=1 bash tools/screen.sh 132 /d/swordai/run/best-candidates/runNN-HHMM.bin:1 > /d/swordai/run/screen-mirror.log 2>&1
  ```
  - **Pairs 0-6031 are used.** The next fresh pairs start at 6100. Never reuse pairs. Record the range you used in `CANDIDATES.txt`.
- **Install rule (winner's curse: hourly highs are mostly luck).** A candidate must beat **about +4.7 against scripted bots and about +9.9 in the mirror** on one fresh screen, **and again on a second fresh screen**. Only then:
  ```bash
  bash /d/swordai/tools/install-school.sh /d/swordai/run/best-candidates/runNN-HHMM.bin   # backs up the profile first
  ```
  - Record it in `CANDIDATES.txt`, in `run/best-*/BEST.txt` and in `EXPERIMENTS.md`.
  - The install also replaces the profile's mod jar with the current build. That build has the S-tap rules (§8).
- **Installed now:** `run23-1418` (since 29 Oct 14:49, jar of 29 Oct 07:13).
  - It is truly about +4.7 against scripted bots and about +9.9 in the mirror.
  - The profile backup of the model before it is `DRL AI/swordai/backup-before-school-20261029-144929`.

## 6. How to do things

**Run the core tests.** The system Java is too old for Gradle, so set `JAVA_HOME`:
```bash
cd /d/swordai && export JAVA_HOME="C:/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2" && ./gradlew :core:test --tests "dev.swordai.core.school.*" --tests "dev.swordai.core.pvp.*" -q
```
- Results are in `core/build/test-results/test/*.xml`.
- The full suite takes about 13 minutes. `RangeLessonTest` fails, and that failure is unrelated to the current work; it is an old rl learning test. Everything else passes.

**Redeploy.** This stops the loop and the client, runs the school tests, rebuilds, and restarts training and the loop. It takes about 4-6 minutes.
```bash
cd /d/swordai && bash tools/school-redeploy.sh > /d/swordai/run/redeploy.log 2>&1
# To start from a given network (a revert):
START_NET=/d/swordai/run/swordai/school/run28-revert-start.bin bash tools/school-redeploy.sh > /d/swordai/run/redeploy.log 2>&1
```
- Afterwards, check that the log says `cycle restarted HH:MM` and that `run/school-client.log` is moving again.
- **Before every redeploy that changes behaviour, save a revert point:**
  `cp -p run/swordai/school/checkpoint.bin run/swordai/school/runNN-revert-start.bin`

**Revert a run.**
```bash
git revert --no-commit <commit>
git commit -m "Revert ... : evidence"
# then redeploy with START_NET=<the revert-start .bin>
```

**Python.** Use `C:/Python38/python.exe` with a script file. `python -` heredocs hang on this machine (the Windows Store python).

**If training is dead** (for example after an app update), redeploy. `tools/install-watchdog.ps1` and `tools/watchdog.sh` restart training automatically. The user has to run the `.ps1` themselves; it was never installed.

## 7. Where things stand now (30 Oct, 22:00)

| Item | Value |
|---|---|
| Training now | **run 28b**, since 21:55 (commit `03e5108`), started from `run/swordai/school/run28-revert-start.bin` |
| Its first paired check | about 22:40 |
| Installed model | `run23-1418` |

**Runs today:**

| Run | Change | Result | Kept? |
|---|---|---|---|
| 25 | **Gated lessons**: a per-technique "option layer" in `SchoolNet`, active only while the network is told that technique. B1 W/sprint, T9 jump and C3 S were taught into it. | Learned, no spread, but the hourly check was −10.2: copying the teacher's C3/T9 costs damage. | Reverted |
| 26 | Gating for **B1's W+sprint only** (`SchoolStages.B1_GATED_HEADS`). | Damage held. | Kept |
| 27 | **Crits end the sprint with an S-tap** (S while sprinting, then W held), in the B1 teacher (`PvpPlayer`), the rules (`Fighter`/`CritTrade` LET_GO; both A and B use it) and the let-go overlay (`SchoolSession`). Cause: pressing W again within 7 ticks restarts the sprint, even in the air. | 18 hourly checks average about +1.1 (level with B). The punish crit did not improve. | Kept |
| 28 (user's choice) | Pick the punish crit only when the sword is charged **and the landing is predicted in reach** (`PvpPlayer.landingReach`). | The prediction alone was too kind: B1 picks went 29→42% and critted-when-told 10→2%. | Fixed as 28b |
| 28b | Reach at their hit ≤ 2.6 **and** the predicted landing in reach. | Started 21:55. | Running |

**For run 28b, watch these in the funnel lines** (`after plain hits N: ... teacher B1 X, told B1 Y (by the decider Z), ..., critted when told W`):
- the teacher-B1 share, which was 29% before run 28;
- critted when told, which was 10% before run 28.

If critted when told does not rise above 10% within about 2 hours, revert to the run 27 behaviour:
1. `git revert 03e5108 c9c9b15`, which restores `d.reach <= 2.6` alone.
2. Redeploy from `run28-revert-start.bin`.

**Screens today (none installed):**

| Network | Scripted | Mirror |
|---|---|---|
| `run26-0413` | +3.5 | +13.3 |
| `run27-0744` | +2.3 | +7.2 |
| `run27-2031` | +6.8 | +7.4 |

**Weakest points:**
- **The punish crit** is about 20% in checks, against the rules fighter's 23%. In the full fights, only about 40% of lifts after their hit come down in reach, and the sword is charged on only about half of those.
- **Getting out of combos:** their third hit comes as often as against the rules fighter, or more in the mirror.

## 8. Lessons that cost days. Do not repeat them.

- **A technique's movement-key lesson taught to the whole network spreads into the whole fight within 15-25 minutes.** This happened with B1's W/sprint, T9's jump twice, and C3's S. Only the gated version (the option layer, run 25/26) stays put.
- **Copying the scripted teacher's techniques exactly loses damage.** Runs 7-10 and run 25 show this. The teacher is not a better fighter than the installed model.
- **The sprint let-go (W release) does not make punish crits.** The user said "PUNISH: NOT THE SPRINT".
- **A high hourly check is mostly luck.** Always do two fresh screens.
- **Bundling several changes in one run makes the result unreadable.**
- **A "smart" predictor that decides behaviour needs calibration from logged data first.** Run 28 is the example.

## 9. Memory, backups and coming back

- **Full backup** (training state, all networks, candidates, scripts and Claude's notes):
  - `D:/swordai-backups/handoff-20261030/`, 2.6 GB, made 30 Oct about 22:05.
  - Claude's own notes are in its `claude-memory/` folder.
- **Git tag** `claude-handoff-2026-10-30`: the exact code at this handoff. Claude can come back to it with
  `git checkout claude-handoff-2026-10-30`. To restore the networks, copy from the backup above.
- **If you break training:**
  1. `git checkout claude-handoff-2026-10-30` (or `git revert` your commits).
  2. Copy `D:/swordai-backups/handoff-20261030/school/checkpoint.bin`, or `run28-revert-start.bin`, back to `run/swordai/school/`.
  3. Redeploy with `START_NET=`.
- **Before you stop, write what you did** in `EXPERIMENTS.md` and `CANDIDATES.txt`, and commit and push. Never push `run/`.
