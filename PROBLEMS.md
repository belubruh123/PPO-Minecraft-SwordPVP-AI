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
   - Training (run 54d, 29 updates): on the ground and not swinging at a NON-falling opponent on 14% of those moments; nothing paid that hit.
   - Fix 2 (run 55, live 03:14): COUNTER_HIT +0.2 for a hit that breaks into their combo on one not falling (document C4). Run 55b (about 03:41): paid only from the ground, because B1 dipped 85% -> 71% in its first 4 updates. Status: NOT VERIFIED.
2. **Gets comboed and stunned too easily.**
   - Seen: real duels, combos of 3+ hits taken: run52 0.79 a round, run35 0.30 a round. The user: "get stunned easily".
   - Measure: 3+ combos taken per round in real duels (tools/swingback.py, rounds.csv combosTaken3).
   - Checks (bots): their combos reaching a third hit, A vs B: 35% vs 23% at 23:42; 24% vs 23% at 01:12; 33% vs 26% at 02:02; 30% vs 42% at 03:02; 27% vs 30% at 04:32.
3. **Cannot tell a jump-in crit from an uppercut (T10), a lunge (T11a) or hops (T11b), and gets comboed by those.**
   - The guard treats every jump from outside reach as a jump-in crit: it holds the swing and waits to punish crit. Uppercuts, lunges and hops end in a SPRINT hit (knockback, a stun), so waiting means being stunned.
   - The document's answers: uppercut -> T8 (hit them while they rise); lunge -> K1/K2, T8, B1; hops -> back out, C3 before the hit; jump-in crit (falling, critting) -> no KB hit (K2), let it land, punish crit (B1).
   - Measured (200 real rounds): 75% of their hits after a jump from outside reach are sprint or plain hits, 25% crits. Their sprint flag in the air does not tell the two apart (23-25% crits either way), so a rule cannot. The network has to learn it.
   - Fix 1 (run 54, training from 22:00): 25% of training fights against the uppercutter, lunger, hopper, double-critter, jump-in critter and rhythm comboer. Status: NOT VERIFIED.
4. **After its punish crit, the opponent crits it back** (their double crit T1 / T1c after its punish crit).
   - The user: it should "either crit back or not let its punish crit be punish-critted".
   - The document: T1c (after your punish crit, jump and crit again), and C1/C2 against their double crit (sprint-hit instead of punish-critting; hold S when your double crit is sprint-hit).
   - Seen in checks: T1c done 7%, T1 double crit 12%. Real duels: after its 410 punish crits they hit back within 30 ticks in 63% (a crit in 12%); it tried T1c in 6%.
   - Fix 1 (run 54): T1C_CRIT +0.3 for a crit from its own jump within 30 ticks of its punish crit; CRIT_BACK_COST -0.3 for their crit in that window. After 2 h: T1c still 2% of its punish crits.
   - 02:49 (after run 54c): its own jump after 14% of its punish crits (4-11% before), T1c 2%, critted back 17% (22-34% before).
   - 04:19: its own jump after 19%, T1c 7% (first real rise), critted back 21%.
   - Cause found (run 54b counters): after 141 punish crits its own jump followed only 11 times. The run 43 cost (-0.3 for any hit in the air out of its own jump, paid on 31 of 69 jumps an update) taught it not to jump.
   - Fix 2 (run 54c, 00:21 10 Nov): that cost is now only paid for a hit while rising, past the jump reset's moment (document T9, K3). Status: NOT VERIFIED.
5. **KEEP the punish crit (B1)**, the user: "while keeping the current ability for punish crit".
   - Guard: training "punish crit chances critted" around 50-65%, and the punish crit share in checks, must not fall.
   - 03:19: chances critted 56% (OK); the technique review's B1 71% (85% before run 55): run 55b keeps the new swing-back reward off the punish crit's air time.
6. **KEEP no late hits on jump-ins** (the user's critical rule: no swing at a jump-in from ground tick 2 on, rising or falling).
7. **Lost rounds were not counted in real duels** (deaths 0, losses "open").
   - Fix (jar 21:49): health at 4 or less, then full on the next tick = a lost round. NOT VERIFIED.
8. **The bots do not fight like real players**: they do not combo, jump-reset or double-crit back as people do. So the checks and the bench do not predict real duels.
9. **It swings about 11% less than the 23 Oct model** in every check (383 vs 429 swings a check), so it deals less.
   - 23:42 check 375 vs 456; 01:12 check 417 vs 462 (run 54c); 04:32 check 359 vs 432. At the moments a swing is called for: A 51 vs B 62 a minute. STILL OPEN after runs 54-55b.
10. **It is in the air too much in the opponent's combo** (run52 37% of those ticks, run35 27%), where it cannot swing back.
   - Training (run 54b, 3309 ticks in their combo with the sword ready and them in reach): swung 25%, guard 8%, in the air itself 48%, other 19%. Split into lifted by their hit / falling from run 54c on.
11. **Uppercutter and fight-jumpin bots**: behind in most checks of 9 Nov (uppercutter -18, -23, -21, -44 in the last four).
12. **Fight back with its own combo: start a combo and hold it with uppercuts (T10), hops (T11b), lunges (T11a) and strafing (T6a)** (the user, 10 Nov 06:40).
   - The document: deflection into uppercut starts combos (T10); hops hold players in combos (T11b); lunges catch players holding S (T11a); alternate hops and lunges (T11c); strafe so they miss (T6a).
   - Seen (training, 9-10 Nov): uppercut (T10) 7-30% and hops (T11b) 12-58%, both stuck or swinging; T11c 0-15%; lunge (T11a) too few moments.
   - Fix 1 (run 56, deployed about 06:52): on top of the combo reward, a follow-up hit of its own combo pays +0.2 when it is a sprint hit out of its own jump (uppercut rising, lunge or hop falling), and +0.1 when A or D was held. Counted as "its combo held by an uppercut / a lunge or hop / strafing". Status: NOT VERIFIED.

## SOLVED (with the evidence)

(none yet)
