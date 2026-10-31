# SwordAI handoff (31 Oct 2026, 16:15), from Gemini back to Claude

**Read this whole file before you touch anything.** Then read `docs/TRAINING-SOP.md`, the last rows of `EXPERIMENTS.md`, and `run/best-candidates/CANDIDATES.txt`.

---

## 0. Executive Summary for Claude (What happened while you were away)

1. **Run 28b was evaluated and reverted**:
   - Claude left Run 28b running at 21:55 on 30 Oct (`03e5108`, requiring reach <= 2.6 AND `landingReach` in reach for punish crits).
   - Monitored across 223 updates and 5 hourly checks (23:45 `-2.7`, 00:35 `-2.8`, 01:25 `+5.7`, 02:14 `+2.1`, 03:04 `-7.7`; 5-check avg `-1.1`).
   - `critted when told` on plain hits fell below 10% to `9.6%` (54/561) and `9.4%` (128/1366) across all lifts, with rolling punish crits falling 27% -> 20%.
   - At 03:13, Run 28/28b was reverted per §7 criteria (`git revert 03e5108 c9c9b15`, commit `dd2116a`, pushed). Redeployed from `run28-revert-start.bin`.
2. **Bluescreen Recovery at 03:44 -> 06:11**:
   - The user's Windows PC bluescreened at 03:44:31.
   - At 06:09, verified `checkpoint.bin` (`13,421,592` bytes, 03:44:19), `stages.properties`, and `focus.txt` were completely intact.
   - Resumed training at 06:11 from step `25,761,066`. Training has run continuously ever since via persistent daemon (`task-208`).
3. **Restored Run 27 Line Training (06:11 – 16:15, >10 hours, >27.7M steps)**:
   - Funnel immediately recovered: `teacher B1` on plain hits back to 27.5%, `critted when told` on plain hits 13.4%–16.2% (consistently >10%), `told B1 and landed it` (last 300) rose to 29–30%.
   - 12 hourly checks ran on this line: 7 positive, average `+1.42` net/fight (07:00 `+4.3`, 07:49 `+3.6`, 08:38 `+4.3`, 09:27 `+7.2`, 10:16 `-3.7`, 11:06 `+1.6`, 11:55 `+2.0`, 12:44 `+5.7`, 13:33 `-0.3`, 14:23 `-2.0`, 15:12 `-1.1`, 16:01 `-4.5`).
4. **Candidate Screened on Fresh Pairs: `run27b-0923.bin`**:
   - At 09:27, the check scored `+7.2 (3.8)`. Copied `checked-1031-0923.bin` to `run/best-candidates/run27b-0923.bin` and logged in `CANDIDATES.txt`.
   - Screened on fresh scripted pairs `6100–6232` (133 pairs): scored **`+4.3 (se 2.1)`**, KOs `35-98` vs `26-107`, damage dealt/taken `298/390` vs `302/423` (-33/min damage taken).
   - **NOT installed**: solid `+4.3` (>2 SE above B), but below the `~+4.7` scripted threshold required to proceed to mirror screening.
   - Next fresh pairs start from **`6300`**.
5. **Current State Right Now (16:15)**:
   - Minecraft training client is **LIVE and training** in first-person (`checkpoint.bin` updating, `step > 27,700,000`, ~60 steps/sec).
   - Git working tree is completely clean on branch `main` (`dd2116a`).
   - Installed model remains `run23-1418` (installed 29 Oct 14:49).
   - The Claude Desktop app has been opened on the PC for you.

---

## 0b. State at 31 Oct 23:00 (Claude out of usage until 1 Nov 04:00)

- Training is the restored run 27 line, unchanged. No experiment was left running on purpose: nobody can watch it or revert it overnight.
- Hourly checks run on their own and land in `run/school-checks.log`. Nothing installs itself. `run23-1418` stays installed.
- Restored line so far: 18 hourly checks, mean about +2.0, latest 22:34 +3.1 (5.1).
- Nothing restarts training after a crash unless the user ran `tools/install-watchdog.ps1`. If `java.exe` or the loop is gone on return, start training with `tools/school-redeploy.sh`.
- On return: liveness first, read every check since 22:34, then screen the best one above ~+6 from `FROM=7500` (mirror `FROM=7700`).
- Correction 1 Nov: "weighed by the outcome 1.00" is only the AWR switch being off (Chooser.AWR); the decider already learns from outcomes (Chooser.OUTCOME on) and its own picks do worse than the teacher's (-0.07 vs +0.08). Not an untried idea. Punish crit is still not ahead of B, and the 1 Nov goal is not met.

---

## 1. Absolute rules (Unchanged from Claude's handoff)

1. **The GitHub repo is PUBLIC.** Never commit or push anything under `run/`, backups, logs, transcripts, profile copies, or anything with a login, token or session. Never print or repeat the user's `/login` line.
2. **First person only. Never train headless.** Training runs on screen in the dev client.
3. **Never install into the user's profile** unless a candidate passes the install rule (§5).
   - Always install with `tools/install-school.sh` (it backs up first).
   - Never install while the user's Minecraft is running from that profile.
4. **Never redeploy:**
   - during a paired check, i.e. when `school pair` appears in the last 40 lines of `run/school-client.log`;
   - within 20 minutes of the last redeploy;
   - without evidence.
   Batch changes and redeploy about an hour apart at most.
5. **Never run Gradle while a redeploy is running.** Never run Gradle client tasks (`runClient`, `build` of the mod) while the dev client is running. Core tests are OK (§6).
6. **Only techniques from `docs/PVP-TECHNIQUES.md`.** No made-up tricks and no hard-coded one-trick rules.
7. **One change per run.**
   - Log it in `EXPERIMENTS.md` before deploying and fill in the result afterwards.
   - Commit every change to git with a clear message.
8. **Keep every network and all data.** Never delete files in `run/` or in `D:/swordai-backups/`.
9. **Do not restart `playguard6`. Do not change system or security settings. Keep data off `C:`.**
10. **Report briefly and honestly.**

---

## 2. Process / Daemon Survival Rules (Learned 30–31 Oct)

- **Do NOT launch redeploy or start scripts from transient shells without holding the Job Object.**
  On Windows, when an assistant shell command terminates, Windows terminates child processes in that job.
- When starting or restarting training, use `run/keep-school-alive.sh` or run the process detached / persistent.
- `run/keep-school-alive.sh` is **not a watchdog**: it starts training once and restarts nothing after a crash or a reboot. Nothing restarts training on its own until the user installs `tools/install-watchdog.ps1`, so the liveness check in every review still matters. On 31 Oct the bluescreen cost 2.5 hours.
- `run/keep-school-alive.sh` starts the game inside the loop's own process tree, so anything that stops the loop stops the game too. On 31 Oct 18:49 a screen paused the loop, the client died with it, and `net-check.sh` reported "stop did not take". Use `tools/school-redeploy.sh` to start training, then screens work. After any screen, check that `java.exe` is running and the log is moving.
- `run/start-school.cmd` is the game's launch file (the user's OK, 31 Oct). `tools/school-restart.sh`, `run/keep-school-alive.sh` and the watchdog all use it there. `run/` is git-ignored: never move it into `tools/` or anywhere else in the repo. The repo is public, and the launch line has not been reviewed for what it carries.
- The restart and redeploy scripts now kill only the dev client (`fabric\.dli\.` in its command line), not every Fabric `java.exe`, so they cannot close the user's own game.
- Training is currently running: `java.exe` is active and `tools/schoolcycle.sh` is looping, detached from any assistant's shell.
- The full procedures (the review steps, tests, install, revert) are in the 30 Oct handoff: `git show claude-handoff-2026-10-30:HANDOFF.md`.
- To check if training is running:
  ```bash
  tail -20 /d/swordai/run/school-client.log
  ```
  Look for `school update N` or fight lines with timestamps in the last 2 minutes.

---

## 3. History of Runs (Updated to Run 28b Revert)

| Run | Change | Result | Kept? |
|---|---|---|---|
| 27 | **Crits end the sprint with an S-tap** (S while sprinting, then W held), in B1 teacher (`PvpPlayer`), rules (`Fighter`/`CritTrade` LET_GO; both A and B use it) and let-go overlay. | 18 hourly checks averaged ~+1.1 (level with B). | Kept |
| 28 | Pick punish crit only when sword is charged **and predicted landing in reach** (`PvpPlayer.landingReach`). | Uncalibrated prediction was too kind: B1 picks 29->42%, critted-when-told 10->2%. | Fixed as 28b |
| 28b | Reach at their hit <= 2.6 **and** predicted landing in reach. | 5 hourly checks averaged -1.1; critted when told dropped to 9.6% on plain hits (<10% threshold) and rolling punish crits fell 27%->20%. | Reverted (`dd2116a`) |
| 27 line (restored) | Restored `d.reach <= 2.6` alone in `PvpPlayer.punishable()`. Trained from `run28-revert-start.bin`. | Funnel restored: teacher B1 27.5%, critted-when-told 13.4%–16.2%. 12 hourly checks avg +1.42 (7 of 12 positive, high +7.2). Fresh scripted screen +4.3 (2.1). | **Training now** |

---

## 4. Fresh-Seed Candidate Screens (Updated)

| Network | Scripted | Mirror | Notes |
|---|---|---|---|
| `run23-1418` | **+4.7 (1.8)** | **+9.9 (2.7)** | **INSTALLED MODEL** (29 Oct 14:49). Next model must beat ~+4.7 scripted and ~+9.9 mirror on two fresh screens. |
| `run26-0413` | +3.5 | +13.3 | Not installed. |
| `run27-0744` | +2.3 | +7.2 | Not installed. |
| `run27-2031` | +6.8 | +7.4 | Not installed (mirror below +9.9). |
| `run27b-0923` (31 Oct) | **+4.3 (2.1)** | - | Fresh pairs 6100–6232. Not installed (scripted just below ~+4.7 bar, mirror skipped). |
| `run27b-1824` (31 Oct) | +3.3 | - | Not installed (scripted below the bar, mirror skipped). |
| `run27b-2045` (31 Oct) | +6.2 (2.4) | +0.1 (4.2) | Pairs 6500–6632, 6700–6831. Not installed (mirror far below +9.9). |
| `run27b-2319` (31 Oct) | +3.1 (2.4) | - | Pairs 6900–7032. Not installed (scripted below the bar, mirror skipped). |
| `run27b-0224` (1 Nov) | +3.5 (2.6) | - | Pairs 7100–7232. Not installed (scripted below the bar, mirror skipped). |
| `run29-0510` (1 Nov) | +3.0 (2.4) | - | Pairs 7300–7432. Not installed (scripted below the bar, mirror skipped). Run 29, 45 min in. |

**Pairs used so far:** `0–7432`.
**Next fresh pairs start at:** **`7500`** (e.g. `FROM=7500` scripted 133 pairs, `FROM=7700 MIRROR=1` 132 pairs).

---

## 5. Candidate Screening Quick Reference

When an hourly check is above ~+6:
```bash
cp /d/swordai/run/swordai/school/checked-MMDD-HHMM.bin /d/swordai/run/best-candidates/runNN-HHMM.bin
# Screen scripted on fresh pairs (next is FROM=6300):
cd /d/swordai && FROM=6300 bash tools/screen.sh 133 /d/swordai/run/best-candidates/runNN-HHMM.bin:1 > /d/swordai/run/screen-scripted.log 2>&1
# If scripted beats ~+4.7, screen mirror:
FROM=6500 MIRROR=1 bash tools/screen.sh 132 /d/swordai/run/best-candidates/runNN-HHMM.bin:1 > /d/swordai/run/screen-mirror.log 2>&1
```
Log all screens in `run/best-candidates/CANDIDATES.txt`.

---

## 6. How Claude can pick up immediately

1. Check that `run/school-client.log` is writing.
2. Run `tools/tech-review.py 8`.
3. Check the newest block of `run/school-checks.log`.
4. Claude's own memory files in `C:\Users\jerem\.claude\projects\D--swordai\memory\` are updated with `swordai-handoff-31-oct.md`.
