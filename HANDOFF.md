# SwordAI handoff (1 Nov 2026, 17:30), to the next Claude conversation

This file replaces every earlier handoff. It stands on its own.

**Read this whole file before you touch anything.** Then read `docs/TRAINING-SOP.md`, the last rows of `EXPERIMENTS.md`, and `run/best-candidates/CANDIDATES.txt`. The memory index loads by itself: read `swordai-user-reminders` and `swordai-current-best` from it.

---

## 1. Do this first

1. Check nobody else is operating. **Only one assistant at a time.** The last conversation stopped its review job when it wrote this.
2. Run `bash /d/swordai/tools/review.sh`. The log must be under 5 minutes old, with loops 1 and java 1. If training is dead, see §7. **First look at `tasklist | grep -i javaw`: the user runs their own Minecraft (Modrinth profile "DRL AI") for live duels against real players, and while it is open every number of the training game is about 30% worse (free RAM 0.3 GB of 9.7; swings on target 97% to 86%, damage dealt -25-40%). On 1 Nov at 20:27 I paused the training and the check loop for it (see §8, "1 Nov 20:25"); resume when it closes. Never read a training window or run a paired check while it is open.**
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
7. **Only the knowledge of `docs/PVP-TECHNIQUES.md`. Never your own PVP knowledge** (the user, 1 Nov 2026; `docs/TRAINING-SOP.md` section 0). No made-up tricks, no hard-coded one-trick rules, no judging a behaviour good or bad that the file does not judge: match it to a technique ID, or call it "not in the document" and neutral. Where the file calls two things fine (a hit select T4 and making them miss T6), both are fine. Run 31's hit and out broke this and was taken out.
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

## 8. Where things stand (1 Nov 08:30)

| Item | Value |
|---|---|
| Training | **Run 30h, the take-over**, live since 16:05 (30: 08:05, 30b 08:26, 30c 09:22, 30d 10:25, 30e 11:38, 30f 12:58, 30g 14:32, 30h 16:05), loops 1, java 1; revert points `run/swordai/school/run30-revert-start.bin` (08:02), `run30d-`, `run30e-`, `run30f-`, `run30g-`, `run30h-revert-start.bin` (16:03) |
| Revert point | `run/swordai/school/run30-revert-start.bin` (the run 27 line at 08:02 on 1 Nov) |
| Installed | `run23-1418`, unchanged |
| Git | `main`, many commits ahead of origin, not pushed (the user has not asked) |

**The 1 Nov change of method (read this before anything else).** The user said two days of teaching techniques and tuning had not improved the model and asked for a PPO-native method, not supervised teaching or hyperparameter changes. The finding behind run 30, from `run/swordai/school/hits.csv` and the code:

- In every check and training fight the installed model's hand-written `Fighter` rules (answer, crit trade, meet jump, jump reset, back nudge, step back, edge hold, jump-in, aim rule ...) press keys over the network, on about 58% of ticks. On 42% of the ticks where A takes a hit they press W/S, sprint or jump (on 25% yaw, pitch, W/S, sprint and jump together).
- A key a rule presses is **locked out of the PPO mask**, and `SchoolPpo.teach` skipped locked keys. So the network never got a policy gradient or a lesson on the keys that decide the fights, and everything taught since 26 Oct (T9 jump, C3 S, B1 W/sprint) was fought by the rules over the same keys. Hourly checks stayed at +2.4 to +4.2 for that reason.
- Against attack-then-s and stun-escaper (A about -19 and -20 a fight, B the same) 94% of the hits taken are in combos with A still sprinting forward on the ground. A and B behave the same there because the rules decide.
- Also found: `ANCHOR_TO_BEST` makes the copying targets of every untaught key the frozen run 6 network's own chances (not the installed model's keys), so the school network has been a near-copy of run 6 plus a little reinforcement learning.

**Run 30 = three parts** (constants in `SchoolSession`, EXPERIMENTS.md row 30):
1. `RULED_PULL` 0.3: the rule's key is a sure target on every head the rules press, locked or not (`SchoolRollout.ruledHeads`, `SchoolPpo.teach`, the installed replay, `SchoolLearner.anchorTargets` leaves it alone).
2. **Fights without the rules**: `RULE_OFF_MIN` 0.10 to `RULE_OFF_MAX` 0.40 of the full fights (scenes, reviews and drills excluded). The network presses every key, is taught the rules' keys and is trained by the damage. The share rises as the smoothed cross-entropy to the rules' keys falls from 0.60 to 0.20. Their damage is kept apart (not in the bars, the lost-case harvest or the damage curve).
3. `RULESOFF=1 bash tools/screen.sh ...` (file `school/rules-off-check`) plays side A without the rules in a check.

**Watch** (`tools/review.sh` prints them; `C:/Python38/python.exe tools/takeover.py` gives the history since the last redeploy):
- update line `rules' keys: ce X agree Y%` and the `take-over:` segment: the smoothed ce must fall (first value after the anchor fix: see below), agree must rise;
- fights without the rules against the overlay fights: net a minute, knockouts (first four bot fights: -47 against -33 a minute, noise);
- swings landing, damage a minute of the overlay fights (about 300/330 healthy), jump/swing off-on moments: a spread of the lessons into the whole fight would show there first (runs 22 and 24 died of it).

**Revert (git revert the run 30 commits, `START_NET=` the revert point)** if the overlay fights' damage a minute falls under about 250 for an hour, swings landing falls clearly under the installed model's, or the ruled ce has not fallen in 3 hours.

**The question that matters (A without the rules against B, same seeds as the hourly checks, pairs 0-65, `RULESOFF=1`):**
- six screens on the hourly seeds: -7.9 (before run 30), -2.5, -2.6, -2.3, -0.7, +5.3 (each se 3-4; B itself ranges -11 to -21 on those seeds, so one screen's noise is about 4).
- **fresh pairs 7640-7773 (the honest number), the 13:43 network alone: -6.1 (2.3); at play temperature 0.5 (`tools/temps/all-0.5`): -3.1 (2.7).** So the network alone is about 4-6 under the installed rules fighter (my estimate for the start of run 30 on fresh pairs: about -10).
- the hourly checks with the rules over the network: +6.0, +1.7, +6.4, +0.4, +4.2 (mean +3.7 against +2.4 the day before); the 11:18 network on fresh pairs 7500-7633: +1.4 (2.7). The with-rules play is level with the installed model (+4.7), not better.
- **What still differs (hit log by block of `school/hits.csv`; B's own key rates vary from block to block, compare A and B inside one block):** at the hits of their combos the network alone jumps 79-87% of the ticks against B's 94-96%, presses S 13-18% against 3-4%, is on the ground 72-82% against 95%, and is hit in the air 244-376 times against 67. Where no rule presses a key its copying target was the frozen run 6 network (`ANCHOR_TO_BEST`), so it kept run 6's jumpier game. Run 30g (14:32) makes every open body key of a fight without the rules a lesson with the installed model's own choice as the target.
Changes of 30c (09:22); 30d (10:25) added the rule-first replay of the rules' keys (`TeacherReplay.ruledOnly`); 30e (11:38) the 4x hit-tick weight, a budget on the pull (`RULED_CE_TARGET` 0.15) and the share of fights without the rules up to 0.70. 30c (09:22): the camera is out of the rules' lesson, fights without the rules are left out of the technique bars, crit and jump-in lines (they have their own swing counts), the update line has the ruled ce by head and by rule, `tools/compile-check.sh` (javac against the built classes; run it before a redeploy). Not yet deployed: `FightSession` plays without the rules when `school/no-rules` exists (commit `1f254f8`).

**Where run 30 stands at 17:30 (honest):**
- With the rules over the network (the deployed configuration): seven hourly checks +6.0, +1.7, +6.4, +0.4, +4.2, -1.8, +0.2 (mean +2.4, exactly the day before); the 11:18 network on fresh pairs +1.4 (2.7). **Not better than the installed model (+4.7 on its two screens).**
- The network alone (no rules at all, `RULESOFF=1`): about -10 on fresh pairs at the start of the run (estimate; -7.9 on the hourly seeds), now **-2.0 (2.7)** on fresh pairs 7640-7772 (30f -6.1, 30g -5.1, 30h -2.0; -3.1 at play temperature 0.5). **Not yet a candidate** (bar +4.7 scripted and +9.9 mirror, twice, on fresh pairs).
- What it still lacks against the installed rules fighter (hit log): their combos' third hit (46% vs 35%), the punish crit by the game (3% vs 6-8%), the straight-in hit (T7 34% vs 49%); it jumps more and is hit in the air more; it crits more than B and aims better (swings landing 94-95% vs 91%).
- Tools: `RULESOFF=1 FROM=7640 bash tools/screen.sh 133 NET:1` (add `:all-0.5` for sharper play) is the measure of the network alone; compare screens on the SAME pairs (B ranges -11 to -21 on the hourly seeds from screen to screen). B's key rates in `school/hits.csv` also vary between blocks: compare A and B inside one block only. `tools/takeover.py`, `tools/compile-check.sh` (always before a redeploy), `tools/hits-report.py` on one block of hits.csv.
- The structural steps, in order: the rules' keys as a target (30), the rare-rule replay (30d), 4x on the hit tick (30e), scenes without the rules (30f), every open body key copies the installed model's own choice (30g), **the network's own last 10 ticks of keys as input (30h, new tensors `new.hist` and `hist.heads`, older checkpoints load; the jar and the checkpoints are no longer interchangeable with jars before 30h)**.
- **1 Nov 20:25: the user's own Minecraft slowed the training (not the network).** `javaw.exe` (Modrinth profile `C:\Users\jerem\AppData\Roaming\ModrinthApp\profiles\DRL AI`, title "Multiplayer (3rd-party Server)") started at 19:58 and plays the installed model in duels against real players. From about 20:00 all training fight groups fell together (against every bot and the sparring partner; swinger +107..+229 to -127; swings on target 97% to 86%; steps a second 58 to 54; free RAM 0.3 GB), while the PPO health numbers stayed normal. I stopped the check loop (`run/school-cycle.stop`) and the school session at 20:27 (`run/swordai/school/run30h-paused-2027.bin` is the paused state; `checked-1101-1855.bin` is the clean 18:55 state). Resume when `javaw.exe` is gone: `rm run/school-cycle.stop; printf 'swordai school\r\n' > run/swordai/inbox.txt; cd run && nohup bash ../tools/schoolcycle.sh > school-cycle.out 2>&1 &`; then confirm swings on target are back at ~97% and the damage a minute at about 0 net; if not, revert to `checked-1101-1855.bin` (`START_NET`). **Free evidence:** the user's game log `profiles/DRL AI/logs/latest.log` has the installed model's real duels (`[CHAT] [SwordAI] WON vs ...`, `Stopped (by command). vs ..., hits A dealt / B taken, kills, deaths, swings ... on target ...`): 20:03-20:26 39 won, 0 lost, hits 864 dealt / 666 taken (ratio 1.30), swings on target 84% on average. Read only those lines and keep the opponents' names out of every note.
- **1 Nov 21:00: the live A/B pool (built and committed, NOT installed).** The user's duels against real players are the only real measure and the scripted-bot screens do not predict them: `python tools/duels.py` (reads the user's game logs, never prints names) gives 1.25 hits dealt per hit taken for the champion's 174 duels of 23 Oct, 1.23 for the school network on 29 Oct (33 duels) and 1.24-1.27 on 1 Nov (117+ duels), after eight days of lessons; the ratio moves +-0.15 from one evening to the next, so two evenings cannot be compared. `AbPool` (core) + `FightSession` draw, for each duel, one arm of `swordai/school/ab/pool.txt` (network, rules on/off, temperatures, weight) at random and print `[A/B arm tag]` in the start line; `duels.py` then compares the arms inside the same hours (share of hits dealt, se, difference to `installed`). About 100 duels an arm detect a 0.025 difference in the share (ratio 1.27 -> 1.37). To use it: rebuild the jar (a redeploy builds it, or `./gradlew remapJar` when RAM allows), `bash tools/install-ab.sh --dry tag=NETWORK[,off][,default][,weight] ...` (refuses while the user's Minecraft is open; backs up first; keeps the installed network as arm `installed`), ask the user, install, read `duels.py` after a couple of hours of their duels. `--off` takes the pool out. Candidates worth a first session: `run30h=run/best-candidates/run30h-1855.bin` with the rules, the same with `,off` (no rules), and perhaps `,default` temperatures. Real-duel breakdown (`swordai/fights/rounds.csv` and the per-duel timelines in the same folder): of the hits taken, 78% come while its own sword is empty, 45% from opponents jumping in, 0.3 combos of 3+ a duel; on 1 Nov 12.1 of 15.6 hits taken a duel in its cooldown, 7.0 jumped in.
- **2 Nov 02:45, T4 in the user's real duels, measured with the file's own failure wording** (SOP section 0: "too late, or too close, and they hit you through your deflection"; "ping changes the right timing and spacing"). 717 hits taken with its sword ready over three days; it sprint-hit back within 8 ticks in 227 (32%); a stun = they do not hit again within 12 ticks. By delay after their hit: 1 tick 92% (n 36), 2 ticks 80% (144), 3 ticks 57% (23). By distance at its sprint hit: under 1.6 blocks 50% (56), 1.6-2.0 74% (19), 2.0-2.4 80% (66), 2.4 and more 97% (86). By the duel's ping: under 50 ms 83% (151), 50 ms and more 68% (76). So the file's two failures are both there: a quarter of its deflections are made from under 1.6 blocks and stun half the time; and the teacher already says "from the edge of reach, not too close" (PvpPlayer T4/T5: step back when nearer than spacing - 0.8). Open question, not changed: whether the network keeps that spacing at the user's ping (about 70 ms) and whether the T4 measure (done = the taught keys) should require the stun. Scripts: tools/duel-hits.py and the inline analyses of 1-2 Nov.
- To install a rule-free network, if it ever passes: `NORULES=1 TEMPS=/d/swordai/tools/temps/all-0.5 bash tools/install-school.sh /d/swordai/run/best-candidates/runNN.bin` (the jar plays without the rules when `school/no-rules` exists; the user's Minecraft must not run from that profile; report and ask first).

**Runs so far:**

| Run | Change | Result |
|---|---|---|
| 26 | B1's W and sprint gated by the option layer | Kept |
| 27 | Crits end the sprint with an S-tap | Kept |
| 28, 28b | Punish crit picked only if the landing is predicted in reach | Reverted |
| 29 | Timer basic 2% to 6% of training fights | Reverted 1 Nov 08:05: five checks +0.7 (fight-timer pooled about -2.5 against -8.2 before, within noise) |
| 30 | The take-over (above) | Running |

**Weak points, with numbers (unchanged):**
- **Punish crit:** rolling 20-30% for days; the loss is mostly the sword not being back (632 falling in reach, 154 charged over five screens).
- **Worst bots for both A and B:** `attack-then-s` about -39, `stun-escaper` about -30 in the checks (the combo chain above).
- **Mirror:** swings +0.1 to +13.3 with se about 4.

---

## 9. What to do next

1. **Run the 30-minute review.** It now prints the take-over lines. Judge run 30 at 3 hours (ruled ce, agree, fights without the rules, overlay damage) and with a `RULESOFF=1` screen of the new network at about the same time: `cd /d/swordai && RULESOFF=1 FROM=0 bash tools/screen.sh 66 /d/swordai/run/swordai/school/checked-MMDD-HHMM.bin:1 > run/screen-rulesoff.log 2>&1` (66 pairs take about 7 minutes and pause training; pairs 0-65 are the hourly seeds, fine for this diagnostic, not for picking a network).
2. **If the ruled ce falls (under 0.3) and the fights without the rules are within about 10 a minute of the overlay's:** let the take-over run, raise `RULE_OFF_MAX` towards 0.7-1.0, then let the pull on the rules' keys follow a budget (the adaptive controller of `SchoolStages.noteBodyCe`, hold the ruled ce at about 0.10-0.15) so reinforcement learning can move the keys past the rules. This is where the new gain would come from: the network finally acts on the keys that decide hits.
3. **If the ce does not fall:** the network cannot read the rules' conditions from its inputs (they use `obs.vec` plus their own timers). Look at which heads stay high (`SchoolPpo` could log ce per head) before changing the pull.
4. **To deploy a rule-free network** the user's game needs a switch: `FightSession` calls `school.rulesFrom(fighter)` whenever `SchoolSession.RULE_OVERLAY` is true (a constant). Add a flag file for it only after a rule-free screen beats the overlay one on two fresh screens (the install rule of section 6, with `RULESOFF=1`).
5. **Other levers noted, not done:** an asynchronous PPO update (the game waits 15 of every 69 seconds for the update and its two replays: +30% data); label smoothing or an entropy floor on the rules' keys if exploration is missing once the pull is a budget; re-try the timer share (run 29) alone later.
6. **Do not re-try:** the sprint let-go for punish crits, copying the teacher's C3/T9, whole-network movement lessons, or "let the decider learn from outcomes" (already on).

## 10. Lessons that cost days

- Per-bot rows are noise at 3 pairs but usable when pooled. `fight-timer` sat in plain sight for five days because of that.
- Movement-key lessons taught to the whole network spread into the whole fight. Only gated ones stay put.
- Copying the scripted teacher loses damage. It is not a better fighter than the installed model.
- The sprint let-go does not make punish crits.
- Bundling changes makes the result unreadable (run 25).
- One bad check is not a verdict, and neither is one good one. Set the rule before the result.
- A "smart" predictor that decides behaviour needs calibration first (run 28).

- **A key locked out of the PPO mask is a decision reinforcement learning cannot improve.** The rules overlay locked the keys that decide most hits for 8 days while the work went into teaching techniques over them (1 Nov). Before teaching anything, ask who presses the key at the moment (the `ruled` column of `hits.csv`).
- **Check what a copying target really is.** `ANCHOR_TO_BEST` replaced the installed model's keys by the frozen run 6 network's chances in every untaught head, so 'installed ce' measured the distance to run 6 (1 Nov).

Before stopping, write what you did into `EXPERIMENTS.md` and `CANDIDATES.txt`, update §6 and §8 here, and commit.
