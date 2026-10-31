# SwordAI handoff (1 Nov 2026, 07:30), to the next Claude conversation

This file replaces every earlier handoff. It stands on its own.

**Read this whole file before you touch anything.** Then read `docs/TRAINING-SOP.md`, the last rows of `EXPERIMENTS.md`, and `run/best-candidates/CANDIDATES.txt`. The memory index loads by itself: read `swordai-user-reminders` and `swordai-current-best` from it.

---

## 1. Do this first

1. Check nobody else is operating. **Only one assistant at a time.** The last conversation stopped its review job when it wrote this.
2. Run `bash /d/swordai/tools/review.sh`. The log must be under 5 minutes old, with loops 1 and java 1. If training is dead, see §7.
3. Read every check in `run/school-checks.log` since 1 Nov 07:08.
4. Start the 30-minute review with this text. It is session-only, so it has to be started in every new conversation:

   > /loop 30m SwordAI thirty-minute review (user's standing order, 24 Oct; every 30 minutes since 26 Oct 21:20; real-fight skills first after the 22:05 disaster). Run `bash /d/swordai/tools/review.sh`: swings landing, turned to find them, damage a minute dealt/taken, K2 broken, the rolling punish crit, techniques, the last check. Then `C:/Python38/python.exe D:/swordai/tools/tech-review.py 8` for the full list (under 60% first, then under 80%; classify RULE/STUCK/FEW/RISING and fix RULE/STUCK). Aim, whiffs, tracking a player who leaves the view, reacting to combos and jump-in crits under lag come before technique percentages. Report the punish crit (punish crit chances critted) every time. Never redeploy during a paired check ('school pair' in the last 40 lines of the client log) and not within 20 minutes of the last redeploy; redeploy only with evidence, batched, about an hour apart; never mention the cycle script's file name in the same Bash command as school-redeploy.sh. Do NOT install anything into the user's profile: report check results (A vs B damage, swings landing A/B) and ask. Report briefly and honestly.

5. Then follow §9, What to do next.

---

## 2. What this is

- **SwordAI** is a Minecraft 1.21.11 (Fabric) sword-PvP agent: a PPO network that plays in first person, from screen pixels plus game numbers.
- It trains in a **technique school**. It learns techniques from `docs/PVP-TECHNIQUES.md` from scripted teachers and bots, and it spars with the installed model.
- **Goal (deadline was 1 Nov, not met):** beat the installed model on fresh pairs, with every technique at 85-90% or more.
- **The user's priorities, in order:**
  1. Real-fight skill: aim, whiffs, tracking, lag.
  2. Damage dealt as high as possible, damage taken as low as possible.
  3. The **punish crit** (B1) must improve.
  4. Technique percentages.

| Path | What it is |
|---|---|
| `core/` | Pure Java: networks, learner, teachers, simulator, tests. What matters is in `core/src/main/java/dev/swordai/core/school/` and `.../pvp/`. |
| `src/client/` | The Fabric client mod. Training happens in `SchoolSession.java`. |
| `run/` | The dev client's game folder. **Never committed.** |
| `run/school-client.log` | The live training log. |
| `run/school-checks.log` | Every paired check and screen. |
| `run/swordai/school/` | `checkpoint.bin` is live, `checked-MMDD-HHMM.bin` are the hourly copies, `runNN-revert-start.bin` are revert points. |
| `run/best-candidates/` | Candidate networks plus `CANDIDATES.txt`, the record of every screen and install. |
| `tools/` | Every script. `tools/review.sh` prints the review, `tools/pool-screens.py` pools per-bot rows. |
| `EXPERIMENTS.md` | One row per run: what changed, why, the result. |

The user's game profile, where the installed model lives, is the Minecraft profile "DRL AI". `install-school.sh` knows its path.

---

## 3. Absolute rules. Breaking these has made the user furious.

1. **The GitHub repo is PUBLIC.** Never commit or push anything under `run/`, backups, logs, transcripts, profile copies, or anything with a login, token or session. Never print or repeat the user's `/login` line. Never print `run/start-school.cmd` or put it in the repo.
2. **First person only. Never train headless.** Training runs on screen in the dev client.
3. **Never install into the user's profile** unless a candidate passes §6, and report and ask first. Always use `tools/install-school.sh`, which backs up first. Never install while the user's Minecraft runs from that profile.
4. **Never redeploy** during a paired check, within 20 minutes of the last redeploy, or without evidence. Batch changes, about an hour apart at most. Redeploys need no asking (the user, 29 Oct: "if you need any change and deploy just do yourself no need to ask"; 30 Oct: "make those decision your self"). Dead training may be restarted at once.
5. **Never mention the cycle script's file name in the same Bash command as `school-redeploy.sh`.**
6. **Never run Gradle during a redeploy.** Never run Gradle client tasks while the dev client runs. Core tests are OK.
7. **Only techniques from `docs/PVP-TECHNIQUES.md`.** No made-up tricks, no hard-coded one-trick rules.
8. **One change per run.** Log it in `EXPERIMENTS.md` before deploying, fill in the result after, commit every change. Push only when the user asks.
9. **Keep every network and all data.** Never delete files in `run/` or `D:/swordai-backups/`.
10. **Do not restart `playguard6`. Do not change system or security settings. Keep data off `C:`.**
11. **The watchdog is the user's to install.** Registering it was refused by the permission system. Do not work around that with `schtasks` or Startup.
12. **Report briefly and honestly.** Bad results as bad, with numbers. Never call a model "best" without fresh-pair evidence. Never wait on decisions while the user sleeps: pick, build, check, report, and revert what hurts.

---

## 4. The 30-minute review

`tools/review.sh` prints all of it. Report in this order:

1. **Alive:** log under 5 minutes old, loops 1, java 1.
2. **Real-fight skills:** swings landed %, turned to find them, their combos' third hit, K2 broken, damage a minute.
3. **The punish crit, every time.** The number is the rolling `punish crit chances critted, last P% of 300`, with `told the punish crit and landed it`. It is **not** `punish crit (B1) x/y` from "by the game", which reads 2-9% and means something else.
4. **Techniques:** under 60%, then under 80%, classified RULE, STUCK, FEW or RISING. A STUCK technique that would only copy the teacher better does not automatically need fixing: only B1 is taught now, and copying the teacher's C3/T9 lost 10.2 in run 25.
5. **The last check:** `ALL` row A−B and se, damage A and B, swings landing A and B, punish crit A and B.

A is the school network. B is the installed rules fighter, the fixed baseline of every check.

---

## 5. The method

- **One focus technique at a time**, trained from its failure scenes, with reviews in between. Learned means 90% "told it, landed it". A learned one under 90% gets retrained. Full text in `docs/TRAINING-SOP.md`.
- **The decider (`Chooser`) picks WHEN, a forced drill teaches HOW.** The user's method, 27 Oct.
- **Each run targets the biggest A-vs-B gap.** A plateau means change the learning setup, not tweak it.
- **Teach a technique's deciding key, not its whole body.** Movement lessons spread through the whole fight in 15-25 minutes unless gated by the option layer (`SchoolStages.gatedHeads`, B1's W and sprint only).
- **Keep practising old stages.** Aim and hit timing reviews must not shrink.

The hourly loop runs by itself, started by `school-redeploy.sh`: it trains about 45 minutes, plays 45 pairs (pairs 0-44), appends to `run/school-checks.log` and saves `checked-MMDD-HHMM.bin`. To stop it, create `D:/swordai/run/school-cycle.stop`. Do not kill it mid-check.

---

## 6. Candidates, screens, installs

**A high hourly check is mostly luck.** A 45-pair check swings by about ±11, and every high one so far has shrunk on fresh pairs: +9.3 to +2.3, +6.4 to +3.5, +11.4 to +6.8, +7.2 to +4.3, +9.4 to +3.3, +8.6 to +3.1, +6.7 to +3.5, +8.6 to +3.0.

When an hourly check is above about +6:

```bash
cp /d/swordai/run/swordai/school/checked-MMDD-HHMM.bin /d/swordai/run/best-candidates/runNN-HHMM.bin
# Add a line to run/best-candidates/CANDIDATES.txt, then, in the background:
cd /d/swordai && FROM=7500 bash tools/screen.sh 133 /d/swordai/run/best-candidates/runNN-HHMM.bin:1 > /d/swordai/run/screen-scripted.log 2>&1
# Only if scripted beats about +4.7:
cd /d/swordai && FROM=7700 MIRROR=1 bash tools/screen.sh 132 /d/swordai/run/best-candidates/runNN-HHMM.bin:1 > /d/swordai/run/screen-mirror.log 2>&1
```

- Use `/d/...` paths: a `D:` path breaks the `NET:TEMP` split.
- Each screen pauses training about 15 minutes. Afterwards check `java.exe`, the loop and the log are back.
- Never reuse pairs. **Used: 0-7432. Next: 7500 scripted, 7700 mirror.** Move these numbers on here and in `CANDIDATES.txt` after every screen.

**Install rule:** beat about **+4.7 scripted and +9.9 mirror** on one fresh screen and again on a second. Then report and ask, and install with:

```bash
bash /d/swordai/tools/install-school.sh /d/swordai/run/best-candidates/runNN-HHMM.bin
```

Record it in `CANDIDATES.txt`, `run/best-*/BEST.txt`, `EXPERIMENTS.md` and the `swordai-current-best` memory. The install also replaces the profile's mod jar with the current build.

| Network | Scripted | Mirror | Notes |
|---|---|---|---|
| `run23-1418` | **+4.7 (1.8)** | **+9.9 (2.7)** | **INSTALLED** since 29 Oct 14:49. The bar. |
| `run26-0413` | +3.5 | +13.3 | Not installed |
| `run27-0744` | +2.3 | +7.2 | Not installed |
| `run27-2031` | +6.8 | +7.4 | Not installed, mirror below the bar |
| `run27b-0923` | +4.3 (2.1) | - | Not installed |
| `run27b-1824` | +3.3 (2.6) | - | Not installed |
| `run27b-2045` | +6.2 (2.4) | +0.1 (4.2) | Not installed |
| `run27b-2319` | +3.1 (2.4) | - | Not installed |
| `run27b-0224` | +3.5 (2.6) | - | Not installed |
| `run29-0510` | +3.0 (2.4) | - | Not installed, 45 minutes into run 29 |

---

## 7. How to do things

**Core tests.** The system Java is too old for Gradle:
```bash
cd /d/swordai && export JAVA_HOME="C:/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2" && ./gradlew :core:test --tests "dev.swordai.core.school.*" --tests "dev.swordai.core.pvp.*" -q
```
`RangeLessonTest` fails in the full suite. It is an old, unrelated test.

**Redeploy.** It stops the loop and client, runs the school tests, rebuilds, and restarts both. About 3 minutes. Run it in the background with `JAVA_HOME` set. First save a revert point:
```bash
cp -p /d/swordai/run/swordai/school/checkpoint.bin /d/swordai/run/swordai/school/runNN-revert-start.bin
cd /d/swordai && bash tools/school-redeploy.sh > /d/swordai/run/redeploy.log 2>&1
```
Afterwards the log must say `cycle restarted HH:MM` and `run/school-client.log` must be moving. In its test lines the second number is skipped tests, not failures.

**Revert a run:**
```bash
cd /d/swordai && git revert --no-commit <commit> && git commit -m "Revert ...: evidence"
START_NET=/d/swordai/run/swordai/school/runNN-revert-start.bin bash tools/school-redeploy.sh > /d/swordai/run/redeploy.log 2>&1
```

**Process rules on Windows:**
- Start training with `tools/school-redeploy.sh`, not `run/keep-school-alive.sh`. The keep-alive ties the game to the loop, so a screen stopping the loop kills the game, and it restarts nothing after a crash.
- `run/start-school.cmd` is the game's launch file. It stays in `run/`.
- Restart scripts kill only the dev client (`fabric\.dli\.` in its command line), never the user's own game.
- **Nothing restarts training after a crash or a reboot** unless the user has run `tools/install-watchdog.ps1`. It was not installed as of 31 Oct 23:17, and a real restart through it was never tested. So the liveness check matters. The 31 Oct bluescreen cost 2.5 hours.

**Python:** use `C:/Python38/python.exe`.

**If training is broken:** `git revert` the bad commits, redeploy with `START_NET=` on a revert point. Old code and networks are in tag `claude-handoff-2026-10-30` and `D:/swordai-backups/handoff-20261030/`.

---

## 8. Where things stand (1 Nov 07:30)

| Item | Value |
|---|---|
| Training | **Run 29** since 04:25 (`f446ae8`), live, loops 1, java 1 |
| Revert point | `run/swordai/school/run29-revert-start.bin` |
| Installed | `run23-1418`, unchanged |
| Git | `main`, about 11 commits ahead of origin, not pushed (the user has not asked) |

**Runs so far:**

| Run | Change | Result |
|---|---|---|
| 26 | B1's W and sprint gated by the option layer | Kept |
| 27 | Crits end the sprint with an S-tap: W re-pressed within 7 ticks restarts the sprint, S clears that | Kept |
| 28, 28b | Punish crit picked only if the landing is predicted in reach | Reverted (`dd2116a`): uncalibrated, critted-when-told fell under 10% |
| 27 line restored | Trained 31 Oct 06:11 to 1 Nov 04:25 | 23 checks, mean about +2.4. Five scripted screens +4.3, +3.3, +6.2, +3.1, +3.5, so its real lead is about +4, level with the bar |
| 29 | Timer basic about 2% to 6% of training fights (`REVIEW_WEIGHT[9]` 3 to 9, `REVIEW_SHARE` 0.15 to 0.19; other reviews unchanged) | Running. Checks +8.6, −10.8, +5.4, mean +1.1 |

**Why run 29.** Pooled over the five screens (55 pairs a bot), `fight-timer` is the only bot A clearly loses to B: **−8.2 (2.2)**, dealing 276 a minute to B's 326. Hourly rows agree in every period since 27 Oct: −5.7, −6.5, −10.3, −7.7. It is the bot modelled on the friend who beat the AI on 18 Oct: it reads the agent's swing rhythm and times its crit. The first screen, 45 minutes in, was still −8.6 (4.2).

**Weak points, with numbers:**
- **Punish crit:** the rolling number has moved 26, 29, 28, 23, 20, 22, 24 since 04:18. Fresh screens: A 22% vs B 27% on the last one. Pooled crit funnel over five screens for A: 1461 falling ticks, 632 in reach, **154 charged**, 93 not sprinting. **The sword not being back is the largest loss by far,** and B has the same problem.
- **Worst bots for both A and B:** `attack-then-s` about −39, `stun-escaper` about −30. A deals about 190 a minute there, half its normal. Both are already common in training, and PPO alone has not solved them.
- **Mirror:** swings +0.1 to +13.3 with se about 4. The +9.9 bar is the harder one.

---

## 9. What to do next

1. **Judge run 29 at five checks,** around 08:50. Five-check average below +1: revert `f446ae8` and redeploy from `run29-revert-start.bin`.
2. **Judge it again at about 10 checks,** around 13:00. Pool `fight-timer` from the checks and from screens with `tools/pool-screens.py`:
   ```bash
   C:/Python38/python.exe D:/swordai/tools/pool-screens.py D:/swordai/run/school-checks.log run29-0510.bin
   ```
   It takes the network names of scripted screens. Revert if the gap has not moved from about −8. Also watch that the rolling punish crit does not settle near 20%, which was part of why run 28b was reverted. Fill in the run 29 row in `EXPERIMENTS.md` either way.
3. **Until then, change nothing.** Run the reviews and screen checks above about +6.
4. **Next run, one change only.** My order:
   - **Why A swings less into the timer.** Compare A and B fight by fight from the `school pair N A/B fight-timer` lines before changing anything else. If rhythm is the cause, a fixed swing rhythm the timer can read is the thing to fix.
   - **The sword-charge loss in the punish funnel.** 632 in reach to 154 charged. Work out from logged lifts when it swung before the lift. Anything that predicts a behaviour must be calibrated from logged data first (run 28).
   - **`attack-then-s` and `stun-escaper`:** about 19 of A's weakest pairs. A deals half its normal damage, so look at reach and chasing with `hits-report.py`.
5. **Do not re-try:** the sprint let-go for punish crits, copying the teacher's C3/T9, whole-network movement lessons, or "let the decider learn from outcomes". That last one is already on (`Chooser.OUTCOME`), and its own picks do worse than the teacher's (−0.07 vs +0.08). The 1.00 in that log line only means `AWR` is off.

---

## 10. Lessons that cost days

- Per-bot rows are noise at 3 pairs but usable when pooled. `fight-timer` sat in plain sight for five days because of that.
- Movement-key lessons taught to the whole network spread into the whole fight. Only gated ones stay put.
- Copying the scripted teacher loses damage. It is not a better fighter than the installed model.
- The sprint let-go does not make punish crits.
- Bundling changes makes the result unreadable (run 25).
- One bad check is not a verdict, and neither is one good one. Set the rule before the result.
- A "smart" predictor that decides behaviour needs calibration first (run 28).

Before stopping, write what you did into `EXPERIMENTS.md` and `CANDIDATES.txt`, update §6 and §8 here, and commit.
