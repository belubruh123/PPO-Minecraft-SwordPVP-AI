# SwordAI: open problems (the user's checklist, 9 Nov 2026)

The user (9 Nov 22:00): "type out all of the AI's problem and until the problem is solved don't remove it".
This file is printed at every 30-minute review (tools/review.sh) and copied into every progress report.
**Never delete an item.** Mark it SOLVED only with real-duel evidence (the user's rounds), and move it to the bottom.
The bench and the bot checks do not count as proof: on 9 Nov the bench called run52-1218 BETTER, and it lost in real duels.

## OPEN

1. **Does not swing back in the opponent's combo.**
   - Seen: in real duels of 9 Nov, with its sword ready and them in reach during their combo, it swung on 20% of the ticks.
   - Cause found: the jump-in guard held the swing at every jump within 6.5 blocks (38-45% of those ticks).
   - Fix 1 (run 53, jar 21:49, installed): the guard now only watches take-offs from outside its reach (3.3).
   - Status: NOT VERIFIED in real duels.
2. **Gets comboed and stunned too easily.**
   - Seen: real duels, combos of 3+ hits taken: run52 0.79 a round, run35 0.30 a round. The user: "get stunned easily".
   - Measure: 3+ combos taken per round in real duels (tools/swingback.py, rounds.csv combosTaken3).
3. **Cannot tell a jump-in crit from an uppercut (T10), a lunge (T11a) or hops (T11b), and gets comboed by those.**
   - The guard treats every jump from outside reach as a jump-in crit: it holds the swing and waits to punish crit. Uppercuts, lunges and hops end in a SPRINT hit (knockback, a stun), so waiting means being stunned.
   - The document's answers: uppercut -> T8 (hit them while they rise); lunge -> K1/K2, T8, B1; hops -> back out, C3 before the hit; jump-in crit (falling, critting) -> no KB hit (K2), let it land, punish crit (B1).
4. **After its punish crit, the opponent crits it back** (their double crit T1 / T1c after its punish crit).
   - The user: it should "either crit back or not let its punish crit be punish-critted".
   - The document: T1c (after your punish crit, jump and crit again), and C1/C2 against their double crit (sprint-hit instead of punish-critting; hold S when your double crit is sprint-hit).
   - Seen in checks: T1c done 7%, T1 double crit 12%.
5. **KEEP the punish crit (B1)**, the user: "while keeping the current ability for punish crit".
   - Guard: training "punish crit chances critted" around 50-65%, and the punish crit share in checks, must not fall.
6. **KEEP no late hits on jump-ins** (the user's critical rule: no swing at a jump-in from ground tick 2 on, rising or falling).
7. **Lost rounds were not counted in real duels** (deaths 0, losses "open").
   - Fix (jar 21:49): health at 4 or less, then full on the next tick = a lost round. NOT VERIFIED.
8. **The bots do not fight like real players**: they do not combo, jump-reset or double-crit back as people do. So the checks and the bench do not predict real duels.
9. **It swings about 11% less than the 23 Oct model** in every check (383 vs 429 swings a check), so it deals less.
10. **It is in the air too much in the opponent's combo** (run52 37% of those ticks, run35 27%), where it cannot swing back.
11. **Uppercutter and fight-jumpin bots**: behind in most checks of 9 Nov (uppercutter -18, -23, -21, -44 in the last four).

## SOLVED (with the evidence)

(none yet)
