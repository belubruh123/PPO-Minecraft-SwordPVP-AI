# The 14 Nov duel day: how the A/B duels are read

Written 13 Nov 02:55, before the duels, so that the result cannot be argued after the fact. The basis is advisor 24's plan
(PROBLEMS.md, 13 Nov).

## Setup

- **Since 13 Nov 21:23 (the user: "install the best model so far by 22:10")** the profile's A/B pool (`swordai/school/ab/pool.txt`)
  holds `run88c` (run88c-1615, weight 3, option `airyield`) and `installed` (run35-0657, weight 1): about 75% of duels play run 88c.
  Backup: `swordai/backup-before-ab-20261113-212350`. The decision rule below compares run88c against installed.
- Before that (13 Nov 02:43 to 21:23): `installed` (run35-0657) and `run85` (run85-1727), weight 1 each.
  Each duel draws one arm at random. The user is not told which arm plays.
- A third arm is added only if the user can play about 90 rounds or more, and only with a checkpoint whose pooled benchmark
  is at least as good as run85-1727 against the averaged reference.
- What the user does: play. Nothing else: no notes, no switching. At the end, "done" is enough.
- Since 13 Nov, each round file's first line names its arm. The table is one command:
  `C:/Python38/python.exe D:/swordai/tools/duelday.py 20261114`
- The game log's `[A/B arm` lines are the cross-check.

## The decision for a candidate arm against `installed`

Fix the stopping point in advance: the user's time, not a lucky lead. Aim for **60+ won-or-lost rounds per arm**.

At 60 rounds a gap of 10 points is about 1 se, so a single number is not enough. The decision needs all of the following:

| Verdict | When |
|---|---|
| **YES** (install, with the user's word) | 60+ rounds per arm; rounds won at least **+5 points**; none of the measures below worse by more than 1 se. |
| **NO** | Rounds won at least 5 points lower, or a HARD measure worse by more than 2 se, or more late jump-in hits than `installed`. |
| **TIE** | Anything else. Keep the installed model; no reason to switch. |
| **NOT ENOUGH ROUNDS** | Under 60 per arm. Play more. |

The measures, per round (tools/duelday.py):

1. 3+ combos taken (HARD). The installed model's real-duel level was 0.30; run 52 had 0.79 and failed.
2. After their 2nd hit in a row, their 3rd hit next (HARD).
3. Swing-back within 12 ticks of their 2nd hit (higher is better).
4. Crit-back: the opponent's hit within 30 ticks after its punish crit (lower is better).
5. Late jump-in hits: its connected swing at ground tick 2+ on a jumper still in the air (the user's rule; HARD, 0 is the bar).
6. The punish crit rate stays about 50-65% of chances (B1); outside that, it is flagged.

Only counts per arm per round are compared. There are no per-opponent rows, and opponents' names are never printed.

## After the duels (no imitation of the opponents)

1. **Read only.** Tag each lost round with the doc's technique IDs (what the opponent did; what it did), using the new
   recorder columns (opponent A/D sign, facing, pitch, arm swing). Report the 2 IDs that lose the most rounds.
2. **Change the practice mix** toward the bots that already play those IDs. This changes no reward.
3. **Benchmark first.** Replay the lost scenes as a held-out test, not as training.
4. **Train only after that.** One change, tested as a new arm in the pool, never replacing run 85 before real duels show it.

Supervised learning from opponents' actions (tools/oppdemo) stays behind its safeguards: a strict filter, offline
validation, shadow mode, a small capped weight, and rollback.
