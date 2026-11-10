# SwordAI: open problems (the user's checklist, 9 Nov 2026)

The user (9 Nov 22:00): "type out all of the AI's problem and until the problem is solved don't remove it".
This file is printed at every 30-minute review (tools/review.sh) and copied into every progress report.
**Never delete an item.** Mark it SOLVED only with real-duel evidence (the user's rounds), and move it to the bottom.
**3-hour rule** (the user, 10 Nov 06:55): an item whose training fix has been flat for 3+ hours gets a different KIND of approach. Research it with a subagent, then apply the best idea as its own run.
The bench and the bot checks do not count as proof: on 9 Nov the bench called run52-1218 BETTER, and it lost in real duels.

## OPEN

1. **Does not swing back in the opponent's combo.**
   - Seen: in real duels of 9 Nov, with its sword ready and them in reach during their combo, it swung on 20% of the ticks.
   - Cause found: the jump-in guard held the swing at every jump within 6.5 blocks (38-45% of those ticks).
   - Fix 1 (run 53, jar 21:49, installed): the guard now only watches take-offs from outside its reach (3.3).
   - Status: NOT VERIFIED in real duels.
   - Training (run 54d, 29 updates): on the ground and not swinging at a NON-falling opponent on 14% of those moments; nothing paid that hit.
   - Fix 2 (run 55, live 03:14): COUNTER_HIT +0.2 for a hit that breaks into their combo on one not falling (document C4). Run 55b (about 03:41): paid only from the ground, because B1 dipped 85% -> 71% in its first 4 updates. Status: NOT VERIFIED.
   - 3-hour rule (flat since 03:41: swung 25% at run 54b, 22-23% now). New kind of cause found 08:40: of 2956 ticks in their combo with the sword ready and them in reach (07:40-08:30), the swing was NOT OPEN on 53%. The whiff guard (since 24 Oct) locks the swing whenever the game's crosshair pick is not on them. The network swung on 22%, about 47% of the ticks it could. Run 61 (counters, live 08:36) splits it: crosshair on its body but no pick (past reach / a frame late) vs crosshair off them (tracking). The paired check now prints A's and B's swing-back and crosshair-off share. Next: fix whichever it is.
   - 08:37-08:52 (run 61 counters, 2425 such ticks in their combo): swing not open 52%. Of those, the crosshair was on its body in 40% (no pick: past reach or a frame late; a swing would mostly whiff) and OFF them in 60%. So in their combo the crosshair was off them on 31% of the ready-in-reach ticks (tracking). Outside their combo: not open 55%, off them 21% of the ticks. The A-vs-B comparison comes in the next check.
2. **Gets comboed and stunned too easily.**
   - 3-hour rule (10 Nov 07:10, agent 1): 59% of real combo hits had no on-the-ground moment to swing first; the answers are C3 (S before the stun), T9a (jump reset on their rhythm) and B1. The "combo escape" scene practice never ran (focus stuck on the crits).
   - Fix (run 57, after the ~07:50 check): the practice focus pinned on "the combo escape" (keeps the punish scenes). Next if flat: combo scenes cut from the 701 real combo hits. Status: NOT VERIFIED.
   - Seen: real duels, combos of 3+ hits taken: run52 0.79 a round, run35 0.30 a round. The user: "get stunned easily".
   - Measure: 3+ combos taken per round in real duels (tools/swingback.py, rounds.csv combosTaken3).
   - Checks (bots): their combos reaching a third hit, A vs B: 35% vs 23% at 23:42; 24% vs 23% at 01:12; 33% vs 26% at 02:02; 30% vs 42% at 03:02; 27% vs 30% at 04:32.
3. **Cannot tell a jump-in crit from an uppercut (T10), a lunge (T11a) or hops (T11b), and gets comboed by those.**
   - The guard treats every jump from outside reach as a jump-in crit: it holds the swing and waits to punish crit. Uppercuts, lunges and hops end in a SPRINT hit (knockback, a stun), so waiting means being stunned.
   - The document's answers: uppercut -> T8 (hit them while they rise); lunge -> K1/K2, T8, B1; hops -> back out, C3 before the hit; jump-in crit (falling, critting) -> no KB hit (K2), let it land, punish crit (B1).
   - ~~Measured: 75% of their hits after a jump from outside reach are sprint or plain hits~~. CORRECTED 10 Nov 07:25 (agent 2): the round files' "crit?" tag is a guess. Labelled by the knockback it received, only about 20% of jump-in hits from outside reach are sprint hits; most are crits, so B1 against jump-ins is right most of the time. No visible take-off cue separates the sprint-hit jumpers; the only weak one is about 0.5 block farther at air tick 5. Real hits come at air tick 9 of their jump, the bots' at 7.
   - Fix 1 (run 54, training from 22:00): 25% of training fights against the uppercutter, lunger, hopper, double-critter, jump-in critter and rhythm comboer. Status: NOT VERIFIED.
4. **After its punish crit, the opponent crits it back** (their double crit T1 / T1c after its punish crit).
   - The user: it should "either crit back or not let its punish crit be punish-critted".
   - The document: T1c (after your punish crit, jump and crit again), and C1/C2 against their double crit (sprint-hit instead of punish-critting; hold S when your double crit is sprint-hit).
   - Seen in checks: T1c done 7%, T1 double crit 12%. Real duels: after its 410 punish crits they hit back within 30 ticks in 63% (a crit in 12%); it tried T1c in 6%.
   - Fix 1 (run 54): T1C_CRIT +0.3 for a crit from its own jump within 30 ticks of its punish crit; CRIT_BACK_COST -0.3 for their crit in that window. After 2 h: T1c still 2% of its punish crits.
   - 02:49 (after run 54c): its own jump after 14% of its punish crits (4-11% before), T1c 2%, critted back 17% (22-34% before).
   - 04:19: its own jump after 19%, T1c 7% (first real rise), critted back 21%.
   - 07:33-08:30 (run 58 world, 163 punish crits): T1c 2%, critted back 37%, its own jump after 7%.
   - 3-hour rule (about 10 h flat): agent 2 found the bots answer its punish crit in 24-32%, real players in 56-63% (it hits first 50-59% vs real 26%), so training gave little pressure. Fix 2 (run 59, ~08:30): bots swing back at its punish crit with a 30-60% chance, 1-3 ticks later, when their sword is ready and it is in reach. Status: NOT VERIFIED.
   - Cause found (run 54b counters): after 141 punish crits its own jump followed only 11 times. The run 43 cost (-0.3 for any hit in the air out of its own jump, paid on 31 of 69 jumps an update) taught it not to jump.
   - Fix 2 (run 54c, 00:21 10 Nov): that cost is now only paid for a hit while rising, past the jump reset's moment (document T9, K3). Status: NOT VERIFIED.
5. **KEEP the punish crit (B1)**, the user: "while keeping the current ability for punish crit".
   - Guard: training "punish crit chances critted" around 50-65%, and the punish crit share in checks, must not fall.
   - 03:19: chances critted 56% (OK); the technique review's B1 71% (85% before run 55): run 55b keeps the new swing-back reward off the punish crit's air time.
6. **KEEP no late hits on jump-ins** (the user's critical rule: no swing at a jump-in from ground tick 2 on, rising or falling).
7. **Lost rounds were not counted in real duels** (deaths 0, losses "open").
   - Fix (jar 21:49): health at 4 or less, then full on the next tick = a lost round. NOT VERIFIED.
8. **The bots do not fight like real players**: they do not combo, jump-reset or double-crit back as people do. So the checks and the bench do not predict real duels.
   - Found (10 Nov, agent 2): since run 49c the bots felt its knockback 2-4 ticks later than any real player (swing to lift: median 6-7 ticks against real 3-5). They also hit from farther away (2.7-2.9 against 1.87), rarely fly out (8-12% against 36%), and rarely answer its punish crit (24-32% against 56%).
   - Run 58 gates: the timing test PASSED (bench swing-to-lift median 4-5, real 3-4, old 6-7); the ranking test is level now (run35 vs run52: bot rounds 185 vs 194, z -0.8; head-to-head 16 vs 12; TIE), where the old world had run52 clearly better. More calibration needed (A4 punish-crit answers = run 59; next A2 hit distance, A3 fly-out).
   - Fix 2+3 (runs 59/60, one redeploy ~08:31): bots answer its punish crit (30-60%), swing only 0.3-1.0 inside their reach (real players hit from closer), and fly out of a third of the sprint-hit trades (C3). Gates on the next bench: their-hit distance median toward 1.9, hits taken with its sword empty toward 72%, fly-out 36%, punish-crit answers about 60%. Status: NOT VERIFIED.
   - Fix 1 (run 58, ~07:25): the 49c uplink off. Gates: bench swing-to-lift median back to 3-4; a re-bench should rank run35-0657 above run52-1218, as in real duels. Next: bots answer its punish crit (A4), bot hit distance (A2). Status: NOT VERIFIED.
9. **It swings about 11% less than the 23 Oct model** in every check (383 vs 429 swings a check), so it deals less.
   - 23:42 check 375 vs 456; 01:12 check 417 vs 462 (run 54c); 04:32 check 359 vs 432. At the moments a swing is called for: A 51 vs B 62 a minute. STILL OPEN after runs 54-55b.
   - 3-hour rule: the same 08:40 finding. Outside their combo, on the ground with the sword ready and them in reach (3291 ticks), the swing was not open on 50% (crosshair pick not on them), and it swung on 33%. Run 61's check counters compare A's and B's crosshair-off share. If A's tracking is worse, that is the missing swings.
10. **It is in the air too much in the opponent's combo** (run52 37% of those ticks, run35 27%), where it cannot swing back.
   - Training (run 54b, 3309 ticks in their combo with the sword ready and them in reach): swung 25%, guard 8%, in the air itself 48%, other 19%. Split into lifted by their hit / falling from run 54c on.
11. **Uppercutter and fight-jumpin bots**: behind in most checks of 9 Nov (uppercutter -18, -23, -21, -44 in the last four).
12. **Fight back with its own combo: start a combo and hold it with uppercuts (T10), hops (T11b), lunges (T11a) and strafing (T6a)** (the user, 10 Nov 06:40).
   - The document: deflection into uppercut starts combos (T10); hops hold players in combos (T11b); lunges catch players holding S (T11a); alternate hops and lunges (T11c); strafe so they miss (T6a).
   - Seen (training, 9-10 Nov): uppercut (T10) 7-30% and hops (T11b) 12-58%, both stuck or swinging; T11c 0-15%; lunge (T11a) too few moments.
   - Fix 1 (run 56, deployed about 06:52): on top of the combo reward, a follow-up hit of its own combo pays +0.2 when it is a sprint hit out of its own jump (uppercut rising, lunge or hop falling), and +0.1 when A or D was held. Counted as "its combo held by an uppercut / a lunge or hop / strafing". Status: NOT VERIFIED.
   - 07:33-08:30 (19 updates): its combo held by an uppercut 31, a lunge or hop 2, strafing 378. Stage T-E: T10 10%, T11a 5%, T11b 15-20%, T11c 0%; the cue's jump missed 162 times for T11c and 47 for T11b, so the jump-hit reward is almost never seen. The 3-hour mark is 09:52.

## SOLVED (with the evidence)

(none yet)
