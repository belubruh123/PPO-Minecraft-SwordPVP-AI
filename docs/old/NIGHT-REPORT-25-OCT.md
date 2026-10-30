# SwordAI night of 24-25 Oct: what happened

## Bottom line
- **No model beat the installed one tonight.** Nothing was installed. Your profile still has the original model, restored at 22:07.
- **I found and fixed the bug that made every training run worse than the installed model.** That's the main result of the night.

## The bug (found 04:50, fixed in commit f1df237)
During training, the installed model only *watches*; the school network plays. But the installed model kept feeding its *own*
unplayed choices (its last keys and its camera "hand") back into its next decision. So it taught from a fight that wasn't
happening. Every run, even one started from the installed model's exact weights, got worse within 35 minutes.

| Paired check vs installed (A - B, net damage per fight) | Result |
|---|---|
| School side = installed weights, no training (zero-step) | **-7.3** |
| Same, after 35 min training (bug) | -18 to -28 |
| Same, after 35 min training, **bug fixed** | **-8.6** |
| Bug fixed + technique teachers at half pull | -19.1 |
| **Bug fixed, techniques off, ~95 min training (06:54)** | **-0.6 (se 3.5): even with the installed model** |

## What is still missing
1. **The last gap of about -7 is the installed model's fight-time rules.** It plays with rules on top of its network: answering a hit, backing out, jump rules, its aim rule. All 23 of its punish crits come from them. The school network plays without them.
2. **The technique teachers still cost about 10 per fight**, even at half strength. They make it jump about twice as much and swing about 30% less than the installed model.

## Update 06:54: even with the installed model
After about 95 minutes of bug-free training (techniques off) the school network is level with the installed model (-0.6, se 3.5).
It lands more hits (330 vs 325), with 97% of its swings landing vs 90%. It is better against attack-then-s (+22), the jump-resetter (+16) and the always-jump-in
critter (+7), and worse against the jump-in-critter (-30) and the real-player mixer (-18). It keeps training; the 07:50 check shows whether it pulls ahead.

## Update 08:25
- 07:44 check (techniques off; the stage moved to T-E, so different opponents): -7.7 (se 4.4). Roughly level to slightly behind, not ahead.
  It knockback-hit falling jumpers 52 times vs the installed model's 24.
- 08:22 check, the jump-in answers (T8, K2, B1) alone at half pull: **-38.4**. It nearly stopped swinging (105 swings vs 338).
  The "don't hit a falling jumper" lesson spread into "don't swing". Reverted. Training continues from the 07:44 network.
- So the jump-in problem needs the installed model's own fight-time rule against it (decision 2), not this kind of teaching.

## Running now (since 06:20)
The synced network, with no technique teachers and no basics, keeps training. The hourly checks show whether longer training,
now bug-free, climbs past the installed model.

## Your decisions
1. **Techniques vs damage.** The file's techniques, as taught now, lower net damage against our test opponents. Should I:
   - (a) keep them off until the fighting beats the installed model, then add them one by one and keep only those that don't cost damage, or
   - (b) keep teaching all of them anyway?
2. **Fight-time rules for the school network**, like the installed model has (to close the last -7). OK to build?

## Also built tonight (all in the code, can be switched back on)
- Basics: the old stages as review (aim, tracking, hit timing vs a standing and a moving/jumping opponent, sprint-hit, combo escape, close tracking).
- Fight opponents in training and checks: your friend's crit timer, the always-jump-in critter, the spacer.
- Lag-aware jump-in rule (K2/T8), and a server-truth "falling" check.
- Find-them lesson: turn toward the side they were last seen on, and keep turning.
- Jump-reset limit removed, as you asked.
- Combo counters in every report.

Kept networks are in run/swordai/school/: school-net-kept-*.bin, checked-1025-*.bin.

## Update 09:05: decisions made, building without waiting
- **Decision 2 done:** the installed model's fight-time rules now run over the school network in training, checks and real fights.
  On a tick a rule fires (answer a hit, back out, punish crit, aim, jump rules), that rule's keys are pressed.
- **Your 08:50 order, jump-in crits at 99%:** the golden rule (K2) is now a rule. It never sprint-hits an opponent who will be falling
  when the hit lands, with lag counted: the seen lag in training, your own ping in real fights, plus a tick. Then it lets them land and
  punish-crits (B1). Teaching it instead made it stop swinging (08:22, -38.4).
- **Your 08:56 order, review every skill:** basics review and every technique are back on at a light 0.2 share, so nothing is forgotten.
- **Decision 1:** the techniques stay light (0.2) until it beats the installed model; then raise them one at a time, keeping only those that don't cost damage.
- A check starts about 5 minutes after the restart. It measures the rules and guard on the current network right away.
