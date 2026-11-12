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
   - 09:26 check (A vs B, same seeds): the crosshair is off them about equally on both sides. In their combo: A swung 27%, crosshair off 27%; B swung 36%, off 25%. Outside their combo: A swung 39%, held with them falling 24%, held otherwise 37%, crosshair off 36%; B 50% / 17% / 34% / 38%. So aim is a weakness of both, not A's own cause. Outside their combo, A's lower swing rate is mostly holding at a faller (K2, which the document asks for; K2 broken A 31 vs B 59). In their combo, A swings 9 points less than B with the same aim.
   - 11:45 check (runs 62 + 59b): in their combo A swung 26% to B's 43%; crosshair off them A 33%, B 41%. A's swing is open only with the crosshair on them (whiff guard); B's looser guard swings anyway (its swings 89% on them, A's 97%). So A's swing back is held by tracking in their combo. Fix 3 (run 64, after the ~12:40 check): the camera is taught onto them in their combo when in sight, within 3.5, crosshair off their body. Status: NOT VERIFIED.
2. **Gets comboed and stunned too easily.**
   - 3-hour rule (10 Nov 07:10, agent 1): 59% of real combo hits had no on-the-ground moment to swing first; the answers are C3 (S before the stun), T9a (jump reset on their rhythm) and B1. The "combo escape" scene practice never ran (focus stuck on the crits).
   - Fix (run 57, after the ~07:50 check): the practice focus pinned on "the combo escape" (keeps the punish scenes). Next if flat: combo scenes cut from the 701 real combo hits. Status: NOT VERIFIED.
   - Seen: real duels, combos of 3+ hits taken: run52 0.79 a round, run35 0.30 a round. The user: "get stunned easily".
   - Measure: 3+ combos taken per round in real duels (tools/swingback.py, rounds.csv combosTaken3).
   - Measure the follow-up after its own ground hit (agent 18, 12 Nov 06:50): use tools/a18/gk2.py, which separates trades (their hit 1-5 ticks after its hit) from the untraded hits where it stays down, plus the take-off cost (takeoff2.py). The old gates ('their hit first <=41%', 'take-offs <=20%') counted trades, which B has as often (B 42-44% itself), so they could not be reached.
   - 3-hour rule again (flat since run 57, 07:50): research agent 5 launched 12:25 (the combos it takes in real duels, why C3/T9a are not taught; two different kinds of approach).
   - Agent 5's findings (real duels, after a hit in their combo): jump reset + crit down -> its hit first 84%; jump reset without a swing -> theirs 23%; ready and no swing -> theirs 60%; late S -> 27%. Run 57's focus never ran (a scene queue took the fights; combo scenes fought 0). The bots combo far harder than real players (2->3 34-36% vs 13%). Fix (run 65, deploying with run 64 after the ~12:40 check): T9a (with the crit down) and C4 taught by gated option layers; the rules give way in their combo while told those. Status: NOT VERIFIED.
   - Run 66 (deploy after the ~15:20 check): agent 5's practice-world approach. Combo-escape scenes get 15% of the training fights ahead of the repeat queue, and are won only by a hit of its own before their second in a row (no K2 broken). Status: NOT VERIFIED.
   - Run 65 REVERTED 13:25 (run 65b): in 23 updates the punish crit chances critted slid 53% -> 49%, C4 hits 320 -> 86, jumps off moments 19 -> 36 a minute, and the third hits did not fall (39%). Next: agent 5's other approach, the practice world (combo scenes actually fought, bots on the real 13-tick rhythm).
   - Checks since run 62: ROUNDS WON A vs B 10:45 24-29 (z -1.4), 11:45 18-15 (z +0.7), 12:42 12-17 (z -1.5; 40% vs 40% third hits). Before run 62: +0.8, +0.7. Watch: if the 13:40 check (runs 64+65) is behind again, take run 62's rule yield out of checks and real fights (keep its lesson).
   - Checks (bots): their combos reaching a third hit, A vs B: 35% vs 23% at 23:42; 24% vs 23% at 01:12; 33% vs 26% at 02:02; 30% vs 42% at 03:02; 27% vs 30% at 04:32. New bot world (runs 58-60): 17% vs 10% at 09:26 (real duels: a 2-hit chain reaches 3 in 12%).
3. **Cannot tell a jump-in crit from an uppercut (T10), a lunge (T11a) or hops (T11b), and gets comboed by those.**
   - The guard treats every jump from outside reach as a jump-in crit: it holds the swing and waits to punish crit. Uppercuts, lunges and hops end in a SPRINT hit (knockback, a stun), so waiting means being stunned.
   - The document's answers: uppercut -> T8 (hit them while they rise); lunge -> K1/K2, T8, B1; hops -> back out, C3 before the hit; jump-in crit (falling, critting) -> no KB hit (K2), let it land, punish crit (B1).
   - ~~Measured: 75% of their hits after a jump from outside reach are sprint or plain hits~~. CORRECTED 10 Nov 07:25 (agent 2): the round files' "crit?" tag is a guess. Labelled by the knockback it received, only about 20% of jump-in hits from outside reach are sprint hits; most are crits, so B1 against jump-ins is right most of the time. No visible take-off cue separates the sprint-hit jumpers; the only weak one is about 0.5 block farther at air tick 5. Real hits come at air tick 9 of their jump, the bots' at 7.
   - Fix 1 (run 54, training from 22:00): 25% of training fights against the uppercutter, lunger, hopper, double-critter, jump-in critter and rhythm comboer. Status: NOT VERIFIED.
   - 3-hour rule (20:35, flat since run 54): in run 67's last hour, of 6678 ticks in their combo with the sword ready and them in reach, the jump-in guard held the swing on 18%, and it was in the air itself on 11%. In the 20:12 check A held with the crosshair on them 36% vs B 22%, and their third hit came 40% vs 29%. Research agent 7 launched: split the real jump-ins by context (in its combo-taken state vs neutral), look for a cue, and propose what to do in the guard-held ticks within the user's rule.
4. **After its punish crit, the opponent crits it back** (their double crit T1 / T1c after its punish crit).
   - The user: it should "either crit back or not let its punish crit be punish-critted".
   - The document: T1c (after your punish crit, jump and crit again), and C1/C2 against their double crit (sprint-hit instead of punish-critting; hold S when your double crit is sprint-hit).
   - Seen in checks: T1c done 7%, T1 double crit 12%. Real duels: after its 410 punish crits they hit back within 30 ticks in 63% (a crit in 12%); it tried T1c in 6%.
   - Fix 1 (run 54): T1C_CRIT +0.3 for a crit from its own jump within 30 ticks of its punish crit; CRIT_BACK_COST -0.3 for their crit in that window. After 2 h: T1c still 2% of its punish crits.
   - 02:49 (after run 54c): its own jump after 14% of its punish crits (4-11% before), T1c 2%, critted back 17% (22-34% before).
   - 04:19: its own jump after 19%, T1c 7% (first real rise), critted back 21%.
   - 07:33-08:30 (run 58 world, 163 punish crits): T1c 2%, critted back 37%, its own jump after 7%.
   - 11:54-12:23 (runs 59b + 63, 114 punish crits): T1c 10%, critted back 21%, its own jump after 23% (7-9% before). First real rise since the bots answer like real players.
   - Afternoon: its own jump after its punish crit 23-36%, but T1c (a crit from it) stays 6-11%. 3-hour rule (19:55): research agent 6 launched (why the jump after its punish crit does not become a crit: sword timing, sprint, distance, rules, costs).
   - Agent 6: not the sword (real: 10 of 16 such jumps became crits). The credit made the jump a loss: their crit on the rising jump closed the window and cost AIR_JUMP_HIT, while a later ground sprint hit paid more. Its take-offs came late (c+10), and half were hit rising. Fix (run 68, 20:15): the window stays open through their crit on its jump; no air-hit cost and no jump-combo pay on a faller in the window. Status: NOT VERIFIED.
   - Run 68, first 24 updates (20:24-20:52, stage T-D jump resets): after 124 punish crits, T1c 9 (7%), critted back 19 (15%; 21-23% before), its own jump after 46 (37%; 24% before). The jump rose and the crits back fell, but the T1c share has not moved yet.
   - Run 68 FAILED, reverted 21:55 (run 68b). Over 64 updates T1c stayed 7-8%, critted back rose to 26%, its jump after fell back to 24%, and the punish crit chances critted slid to 45-47% (under 50% for an hour). Next: Fable's priority list.
   - 3-hour rule (about 10 h flat): agent 2 found the bots answer its punish crit in 24-32%, real players in 56-63% (it hits first 50-59% vs real 26%), so training gave little pressure. Fix 2 (run 59, ~08:30): bots swing back at its punish crit with a 30-60% chance, 1-3 ticks later, when their sword is ready and it is in reach. Status: NOT VERIFIED.
   - Run 59 FAILED on the bench (run35w60, 09:32): after its punish crit the bots hit back first within 30 ticks in 19%, against 36% before run 59 and 72% real; it hit first in 70% (real 26%). Why: the bots' swing back is cancelled by their K2 rule (the AI is still falling right after its crit), and run 60's closer-swing rule cuts the rest. Real answers come with them in the air (75%): lifted by its crit, they come down hitting (B1 back). Fix next (run 59b): the answer as their own punish crit back when lifted, otherwise a swing with no closer-swing rule, in a window of up to 12 ticks.
   - Cause found (run 54b counters): after 141 punish crits its own jump followed only 11 times. The run 43 cost (-0.3 for any hit in the air out of its own jump, paid on 31 of 69 jumps an update) taught it not to jump.
   - Fix 2 (run 54c, 00:21 10 Nov): that cost is now only paid for a hit while rising, past the jump reset's moment (document T9, K3). Status: NOT VERIFIED.
5. **KEEP the punish crit (B1)**, the user: "while keeping the current ability for punish crit".
   - Guard: training "punish crit chances critted" around 50-65%, and the punish crit share in checks, must not fall.
   - 13:24: 49% for three updates after run 65, so run 65 was reverted (run 65b).
   - 03:19: chances critted 56% (OK); the technique review's B1 71% (85% before run 55): run 55b keeps the new swing-back reward off the punish crit's air time.
   - 20:53 (run 68): chances critted 51% for 4 updates (53-54% before), technique review B1 74% (81%). At the floor. If it falls under 50% for 3 updates, revert run 68's credit (as run 65 was reverted).
   - 21:51: 45-47% since 20:57. Run 68 reverted (run 68b, deploys after the ~21:55 check).
6. **KEEP no late hits on jump-ins** (the user's critical rule: no swing at a jump-in from ground tick 2 on, rising or falling).
7. **Lost rounds were not counted in real duels** (deaths 0, losses "open").
   - Fix (jar 21:49): health at 4 or less, then full on the next tick = a lost round. NOT VERIFIED.
8. **The bots do not fight like real players**: they do not combo, jump-reset or double-crit back as people do. So the checks and the bench do not predict real duels.
   - Found (10 Nov, agent 2): since run 49c the bots felt its knockback 2-4 ticks later than any real player (swing to lift: median 6-7 ticks against real 3-5). They also hit from farther away (2.7-2.9 against 1.87), rarely fly out (8-12% against 36%), and rarely answer its punish crit (24-32% against 56%).
   - Run 58 gates: the timing test PASSED (bench swing-to-lift median 4-5, real 3-4, old 6-7); the ranking test is level now (run35 vs run52: bot rounds 185 vs 194, z -0.8; head-to-head 16 vs 12; TIE), where the old world had run52 clearly better. More calibration needed (A4 punish-crit answers = run 59; next A2 hit distance, A3 fly-out).
   - Fix 2+3 (runs 59/60, one redeploy ~08:31): bots answer its punish crit (30-60%), swing only 0.3-1.0 inside their reach (real players hit from closer), and fly out of a third of the sprint-hit trades (C3). Gates on the next bench: their-hit distance median toward 1.9, hits taken with its sword empty toward 72%, fly-out 36%, punish-crit answers about 60%. Status: NOT VERIFIED.
   - Bench run35w60 (09:32, after runs 58-60) against the user's 200 real rounds:
     - their whiffs 10% (real 29%, before 51%);
     - a 2-hit combo reaching 3 hits 24% (real 12%);
     - follow-up distance 3.22 (real 2.65, before 3.64);
     - jump hits still at air tick 7 (real 9);
     - its sprint hits answered within 12 ticks 9% (real 33%);
     - after its sprint hit the bots barely move back at all: distance gain in 6 ticks p50 0.10 (real 1.31), and half are still coming toward it (real: 49% knocked back at 0.15-0.3 a tick).
     - Run 60's fly-out shows no effect in the arena, though it works in the offline bot simulation (C3 started after 100% of the hits).
     - A research agent (10:00) is checking how the arena applies knockback to the bots.
     - Agent 4 (10:05): the bots ARE knocked back as hard as real players (peak away 0.37 both); the AI just sees it 3-4 ticks late. SeenLag holds back only the bot's position (EntityMixin -> moveOrInterpolateTo); its ground flag, arm swing, hurt flash and health come at once. So the network sees the arm and flash 1-4 ticks before the body (real: all in the same packets). NEXT (run 63, not written yet): hold every packet about the opponent together in ClientPacketListener (handleMoveEntity, handleEntityPositionSync, handleTeleportEntity, handleRotateMob, handleAnimate, handleHurtAnimation, handleEntityEvent, handleDamageEvent, handleSetEntityData, handleSetEntityMotion; require = 0, client thread only) through SeenLag's LagLink as Runnables. Then re-bench with agent 4's gates (scratchpad agentD timing.py, align.py). The fly-out is off again in run 59b.
     - Run 63 LIVE 11:53: every packet about the opponent is held back together (the first update held 5918 packets). Gates on the next bench: flag off to visible rise in 1 tick for 90%+ of hits; 6-tick gain p50 >= 0.9. Status: NOT VERIFIED.
   - Fix 1 (run 58, ~07:25): the 49c uplink off. Gates: bench swing-to-lift median back to 3-4; a re-bench should rank run35-0657 above run52-1218, as in real duels. Next: bots answer its punish crit (A4), bot hit distance (A2). Status: NOT VERIFIED.
9. **It swings about 11% less than the 23 Oct model** in every check (383 vs 429 swings a check), so it deals less.
   - 23:42 check 375 vs 456; 01:12 check 417 vs 462 (run 54c); 04:32 check 359 vs 432. At the moments a swing is called for: A 51 vs B 62 a minute. STILL OPEN after runs 54-55b.
   - 3-hour rule: the same 08:40 finding. Outside their combo, on the ground with the sword ready and them in reach (3291 ticks), the swing was not open on 50% (crosshair pick not on them), and it swung on 33%. Run 61's check counters compare A's and B's crosshair-off share. If A's tracking is worse, that is the missing swings.
10. **It is in the air too much in the opponent's combo** (run52 37% of those ticks, run35 27%), where it cannot swing back.
   - Training (run 54b, 3309 ticks in their combo with the sword ready and them in reach): swung 25%, guard 8%, in the air itself 48%, other 19%. Split into lifted by their hit / falling from run 54c on.
11. **Uppercutter and fight-jumpin bots**: behind in most checks of 9 Nov (uppercutter -18, -23, -21, -44 in the last four).
   - 09:26 check: uppercutter -12.2, fight-jumpin -14.0 (A minus B, net damage a fight); still behind.
   - 12:42 check (run 63 world): uppercutter 0.0, fight-jumpin +8.2; A behind on reverse-deflector -28.0, fall-spacer -26.5, mixer* -19.8.
12. **Fight back with its own combo: start a combo and hold it with uppercuts (T10), hops (T11b), lunges (T11a) and strafing (T6a)** (the user, 10 Nov 06:40).
   - The document: deflection into uppercut starts combos (T10); hops hold players in combos (T11b); lunges catch players holding S (T11a); alternate hops and lunges (T11c); strafe so they miss (T6a).
   - Seen (training, 9-10 Nov): uppercut (T10) 7-30% and hops (T11b) 12-58%, both stuck or swinging; T11c 0-15%; lunge (T11a) too few moments.
   - Fix 1 (run 56, deployed about 06:52): on top of the combo reward, a follow-up hit of its own combo pays +0.2 when it is a sprint hit out of its own jump (uppercut rising, lunge or hop falling), and +0.1 when A or D was held. Counted as "its combo held by an uppercut / a lunge or hop / strafing". Status: NOT VERIFIED.
   - Run 62b (14:50): since run 62 the checks were behind (A 68/178 rounds vs B 78/178; 14:24 z -0.8). The hand-over of the rules' keys in its own combo now happens in training only, not in checks or real fights.
   - 15:24 check (first with run 62b): ROUNDS WON A 18/45, B 15/45 (z +0.7); damage A 378/427, B 366/411. In their combo A swung 35% vs B 38% (31% vs 51% at 14:24); outside 49% vs 52%. Uppercutter +13.0, fight-jumpin +14.0, hopper +18.4.
   - 16:22 check: A 16/44, B 16/44 (z 0.0); third hits A 37% vs B 44%; K2 broken A 12 vs B 44. 17:11 check: A 21/45, B 19/45 (z +0.5); damage A 397/430, B 376/394; third hits 33% vs 34%; in their combo swung 34% vs 41%; K2 broken 26 vs 56. Since run 62b: A 55/134 rounds, B 50/134.
   - 18:12 check: A 22/45, B 23/45 (z -0.3); crits taken within ten ticks of a K2 break A 2 vs B 17. 19:12 check (run 66c, combo scenes scoring): A 13/44, B 18/44 (z -1.4); A swung outside their combo 33% vs B 52% (held with them falling 28% vs 15%), in their combo 28% vs 41%. Since run 62b: A 90/223, B 91/223. Watch: if 20:08 is behind again, take the combo-scene share (run 66) down.
   - 21:12 check (run 68): A 17/45, B 19/45 (z -0.5); damage A 353/419, B 359/392. Outside their combo A swung 44% vs B 53%; in their combo 32% vs 35%; third hits 40% vs 30%.
   - 20:12 check (run 67): A 17/45, B 18/45 (z -0.3). Outside their combo A swung 49% (33% at 19:12), crosshair off them 25% vs B 36%; in their combo 29% vs 42%; third hits 40% vs 29%. Level, so the combo-scene share stays.
   - 07:33-08:30 (19 updates): its combo held by an uppercut 31, a lunge or hop 2, strafing 378. Stage T-E: T10 10%, T11a 5%, T11b 15-20%, T11c 0%; the cue's jump missed 162 times for T11c and 47 for T11b, so the jump-hit reward is almost never seen. The 3-hour mark is 09:52.
   - 3-hour rule (agent 3, 09:40): no teacher taught these at all (PULL_CAP 0, only B1 in RUN_TAUGHT). The take-over copying pulled the jump toward the 23 Oct model, which never hops or lunges. Its rules took the keys in its combos and made crits of the follow-ups. The jump head barely explores (entropy 0.03-0.08). The teacher's hop timing was off (jumped with the sword at 0.08). Fix 2 (run 62, ~10:00): the user's 27 Oct method. The decider picks WHEN; a gated option layer per technique learns HOW (jump/W/sprint/swing into its own layer only). The rules give way in its own combo while told those. The teacher's take-offs are retimed: hops and lunge 2-3 ticks after the hit, uppercut about 6. Status: NOT VERIFIED.

## FABLE (10 Nov 20:50, max effort): priority list in docs/FABLE-PLAN.md

- Batch 1 (runs 69a seen history + 69b entropy floor), merged 22:05: for problems 1, 2, 3, 4, 9 and 12 (timing it could not see; the swing head never waits). Status: NOT VERIFIED.
- Next on its list: problem 3 (the guard in their combo); then P3 (T9b/T1c as gated options) and P4 (the rules' keys pulled only where the rule's moment went well).
- DEPLOYED TOGETHER 22:17 10 Nov (one restart; the build ran after the merges): run 68b + 69a/69b + 70 (K3 jump along in their combo) + 71 (T1c/T9b gated).
- Night, 194 updates to 02:52 11 Nov:
  - punish crit chances critted 50 -> 61% (OK);
  - T1c 9-17% of its punish crits (was 7-8%), its jump after 42-45% (24%), critted back 17-23%;
  - the guard's share in their combo 6-8% (18-25%); the jump along about 5 an update, crits about 0.5;
  - swing in their combo still 25-30%; third hits 40-43%; seen-history weights growing (1.0 -> 1.7);
  - K3 91%, T9b 100% when told.
- Checks since the restart: A 83/224, B 85/224 (z -1.5, +0.9, +0.5, -0.5, -0.2): still level. 02:23: punish crit on jump-ins A 31% vs B 15%; third hits 42% vs 42%; in their combo A swung 31% vs B 50%.
- Fable resumed 02:52 on P4 (the rules' keys pulled only where the rule's moment went well).
- Fable finished 03:05. Run 72 (RULED_AWR) live 03:46, after its test fix; training was down 03:21-03:46.
- 04:35 check (first with run 72): A 19/45, B 16/45 (z +0.8); damage A 364/408, B 365/399.
  - Third hits A 32% vs B 40% (41% vs 19% at 03:10).
  - In their combo A swung 34% vs B 38%; outside their combo 48% vs 54%.
- Run 72, first hour:
  - fights without the rules net: bots -104, league -268, sparring -234 (before: -112, -243, -217);
  - rules' agree 85-88%;
  - punish crit chances critted dipped to 49-50% at 04:38-04:46, then 54%.
  - Judge at about 06:45.
- 05:24 check: A 17/44, B 17/44 (z 0.0); damage A 347/413, B 366/406.
  - Third hits A 41% vs B 35%.
  - In their combo A swung 28% vs B 41%; outside their combo 49% vs 49%.
- Run 72 at 2 h: fights without the rules net bots -102, league -272, sparring -186 (flat). Punish crit 56-57%.
- 3-hour rule (05:55), problems 1 and 9: the crosshair off them in their combo has held at 33-36% for both A and B since the camera lesson (runs 64/67). Research agent 8 launched: why the crosshair is off, and a different kind of approach.
- Agent 8 (06:20): in their combo the camera belongs to the AIM RULE (93% of ready ticks in real duels, 90% on the bench), not the network. So the camera lessons (runs 64/67) taught locked heads.
  - The rule aims at their middle while it is lifted (67% of those ticks), so the ray meets their box beyond 3.0. That is "on the body, no pick", 22-28 points of the 42-46% swing-not-open.
  - The nearest-point aim gives 0% no-pick.
  - The whiff guard also reads last tick's pick (stale).
  - Handed to Fable (round 2, max effort, also on P6): fresh pick for the guard (bug fix), and the camera lesson corrected plus the camera handed to the network where its aim beats the rule's (the reach aim on the rule only as an A/B stopgap). Status: in progress.
- RULES-OFF paired check (07:03, Fable's direct measure): A WITHOUT the rules 9/44 (20%), B 18/44 (41%), z -2.3; damage A 280/442, B 351/400.
  - Outside their combo, sword ready: A swung 32% vs B 55%, crosshair off them 43% vs 29%.
  - In their combo: A swung 27% vs B 45%. Third hits 44% vs 42%.
  - So the network alone is clearly behind the rules. No key can be handed over yet. Its camera is worse than the aim rule's (crosshair off 43% vs 29%).
- Runs 73/73b (fresh pick for the whiff guard; the camera lesson aims at the nearest point of their box; camera counters by who held it), deploying 07:04. Status: NOT VERIFIED.
  - Run 73's fresh pick: reverted 07:43 (mismatched positions now with the camera before the turn; punish crit 49%).
  - Run 73b's lesson: reverted 08:25 (punish crit 50% -> 44%).
  - Run 74 (the reach aim for A's aim rule) waits for the punish crit to come back over 50%; then it goes alone.
- 08:33 check (run 73b's lesson on): A 19/44, B 12/44 (z +1.5); damage A 336/404, B 369/408.
  - Third hits A 42% vs B 18%.
  - In their combo A swung 32% vs 35%; outside their combo 44% vs 58%.
  - Restart 08:46 with 73c (the known state plus the counters).
- 09:36 check (run 73c, the known state): A 20/44, B 15/44 (z +1.3); damage A 354/397, B 344/386.
  - Third hits A 39% vs B 23%.
  - In their combo A swung 26% vs 37% (crosshair off 44% both).
  - Outside their combo 48% vs 51%.
  - Two checks in a row ahead (08:33 +1.5, 09:36 +1.3), as the counters stayed flat.
- Run 74 (the reach aim, alone) live 09:44.
  - Run 74, 29 updates: the aim rule's camera in their combo on body no pick 16% (20-23% before), off body 20% (13-22%); total not open 34-36% (38-42%).
  - Swing in their combo 26-27% (flat). Punish crit 48-50% (T-D to T-E). Kept.
- 3-hour rule (10:25), items 1, 2, 9: the camera approach has been flat 3 h on the swing-back. Research agent 9 launched: why A holds with the crosshair ON them in their combo (checks: A 30%, B 19%), a different kind of fix.
- Agent 9 (10:45): with the crosshair on them in their combo the swing is the rules' (1285 of 1291).
  - The holds are the B1 keeps (they win the punish crit 73-87% in real duels, so they stay) and the jump-in guard (6-7%).
  - Under the guard MeetJump ran it into the jumper with W and the sprint: their hit first 70-75%.
  - A is in the air at their hits 50% vs B 8% (since run 62), which is about all of its extra third hits (follow-up running).
  - Run 75 (rules give way on W/sprint under the guard in their combo; the network taught to back off) deploying 10:50.
- 10:33 check (run 74 first): A 19/45, B 19/45 (z 0.0); third hits 36% vs 18%; in their combo 30% vs 39%.
- Run 75, 23 updates (to 11:22):
  - Under the guard in their combo the rules gave way 566 times; taught to back off 39.
  - Swing in their combo 30% (25-27%), outside 46% (29-36%).
  - The network's camera off them in their combo 19% (27-32%). Punish crit 53-54% (T-E).
- 11:42 check (run 75 first): A 18/45, B 21/45 (z -0.7); damage A 383/413, B 387/396.
  - Third hits A 34% vs B 27% (A 36-42% before).
  - In their combo A swung 34% vs 39%, crosshair off 24% vs 27%. Outside their combo 44% vs 52%.
- Run 76 (no jump between their hits) live 11:53.
- 12:42 check (runs 75 + 76, first with 76): A 17/45, B 15/45 (z +0.5); damage A 385/426, B 359/427. FIRST CHECK WITH A AHEAD OF B ON ALL THE COMBO NUMBERS:

  | | A | B |
  |---|---|---|
  | Their third hits | 26% | 37% |
  | Swung in their combo | 45% | 41% |
  | Swung outside their combo | 55% | 51% |
  | Crosshair off in their combo | 31% | 32% |

  - Training: its own take-offs in their combo 37 -> 52 an update (not falling yet). Told at take-off: BASE 226, T9 214, K3 188, T7 73, T9a 57: early jump resets, not run 62's options.
  - One check is not proof: watch the next two. Real duels decide.
- 13:31 check (runs 75 + 76): A 24/44, B 19/44 (z +1.4); damage A 389/395, B 363/417 (net -6 vs -54 a fight's sum).
  - Third hits A 19% vs B 36%. In their combo A swung 39% vs 43%; outside their combo 52% vs 56%. Punish crit 62-64%.
  - Since runs 75/76: A 41/89 rounds vs B 34/89; third hits 26%/19% vs B 37%/36%.
  - Before, A's third hits ran 36-42% against B's 18-27% in every check.
- 14:21 check (stage T-C): A 14/45, B 15/45 (z -0.3); damage A 343/418, B 352/400.
  - Third hits A 44% vs B 33%: back to the old pattern. In their combo A swung 33% vs 46%.
  - So since runs 75/76: A 55/134, B 49/134; third hits 26/19/44% vs 37/36/33%.
  - The gain is not stable yet (the two good checks were stage T-B). The real-duel port waits for more checks.
- 15:10 check (T-C): A 15/44, B 15/44 (z 0.0); damage A 329/403, B 339/410.
  - Third hits A 33% vs B 37%. In their combo A swung 30% vs 39%.
  - Outside their combo A swung only 26% vs 51% (held otherwise 49%): watch.
  - Since runs 75/76 (4 checks): A 70/178, B 64/178; third hits 26/19/44/33% vs 37/36/33/37%.
- Run 77 (READY_JUMP_COST, agent 10) deploying 15:21.
- 16:12 check (T-D, run 77 live but its cost never charged): A 17/44, B 17/44 (z 0.0); damage A 355/404, B 338/391.
  - Third hits A 31% vs B 31%. In their combo A swung 32% vs 43%.
  - Outside their combo A swung 43% vs 50% (held otherwise 47% vs 29%), up from 26% at 15:10.
  - Its knockback hits on a falling jumper (K2 broken, the late hits): A 41 vs B 32; crits taken within ten ticks 9 vs 10. Its own T8 hits on a rising jumper close: A 27 vs B 13 (crits taken after them 10 vs 6).
  - Since runs 75/76 (5 checks): A 87/222, B 81/222; third hits 26/19/44/33/31% vs 37/36/33/37/31%.
- Run 77's cost charged 0 in 23 updates. Run 77b (counters for which part of its test fails) deploying 16:13.
- 3-hour rule (16:25), items 1 and 9: since run 75 (10:50), A swung in their combo 30-39% against B 39-46% in six checks; outside their combo 26-55% against 50-56%. Research agent 11 launched: split A's non-swing ticks against B's by cause (swing not open, rule held, in the air, the network's choice), then 2-3 fixes of a different kind.
- Agent 11 (16:50), 6 checks pooled: on the ground A does not hold more than B (never swung since their 1st hit: A 8%, B 11%).
  - The gap is A in the air at their 2nd hit (40% vs 6%), from its own take-offs 4-20 ticks after their hit (278 vs 23). While it rises, the rule's keep for the way down holds its swing.
  - B's swing share includes swings at nothing (11.4% of its swings vs A 2.5%).
  - Third hits A 31.6% vs B 33.5%; rounds A 105 vs B 102 of 267.
  - Run 77b's first update: of 20 take-offs, 18 were out of 3.5 and 15 told K3. Run 77c: the charge to 4.5, and told K3 no longer let off.
  - Run 78: on its own rising jump in their combo, the keep gives the swing to the network.
  - 77c and 78 deploy together after the ~17:08 check.
- 3-hour rule (17:00), item 4: T1c 9% and critted back 18% after its punish crit (43 updates), flat at 6-19% for days. Research agent 12 launched: the ~20 ticks after its punish crit in real duels vs checks vs training, the cause chain, 2 fixes of a different kind. Item 12 is queued next.
- Agent 12 (17:05), item 4: the T1c teacher walked in and waited for the sword, so its jump came about c+9, into their answer.
  - Real players answer at c+5, as it lands (their hit first 69%); the bots at c+10.
  - A's take-offs at c+1..4 net +0.54; at c+5..8, -0.59.
  - Run 79: the teacher's T1c jump on its first ground tick after the punish crit. It deploys with 77c and 78.
- 17:12 check (T-E; runs 75/76, run 77 not charging): A 14/44, B 18/44 (z -1.2); damage A 343/407, B 381/406.
  - Third hits A 40% vs B 26%: the old pattern again. In their combo A swung 34% vs 45%. Outside their combo 36% vs 55% (held otherwise 42% vs 30%).
  - K2 broken A 40 vs B 52.
  - Since runs 75/76 (6 checks): A 101/266, B 99/266; third hits 26/19/44/33/31/40% vs 37/36/33/37/31/26%.
- Runs 77c + 78 + 79 deploying 17:13.
- Runs 77c/78/79 live 17:23. First update (17:27): run 77c charged 6 of 15 take-offs 2-20 ticks after their hit (run 77: 0 in 65 updates). Run 78 gave the swing back on 4 ticks (swung 0). Run 79's T1c take-offs at 0-1/2-4/5+ ticks after landing: 0/3/2.
- Agent 13 (17:40), item 12: its own combo's 3rd hit out of the 2s is A 27% vs B 39% (A 44% before run 62).
  - 42% of A's follow-ups come in the air (B 5%). When their hit ended its combo, A was in the air 55% (B 8%).
  - The teacher tells T10/T11 on their knockback flight, and the pay favours air follow-ups 2-3x.
  - Run 80 (its follow-ups paid only once held 8 ticks; chase crit's going-away on the ground only) is committed and deploys after the ~18:08 check unless that check is clearly worse.
- 17:52, 20 updates of runs 77c/78/79:
  - Run 79: T1c 14/87 (16%; it was 6-11%); critted back 15/87 (17%; 18-23%); its own jump after its punish crit 56% (31-41%). T1c take-offs at 0-1/2-4/5+ ticks after landing: 26/14/9.
  - Run 77c: charged 78 of 288 take-offs 2-20 ticks after their hit; take-offs at 13-20 are 8.7 an update (about 10 before, gate -30%).
  - Run 78: the keep given back on 138 ticks, swung on 6.
  - In their combo: swung 30%, air 15%.
  - Punish crit 60%.
- 18:15 check (T-E, the first with runs 77c/78/79): A 21/44, B 17/44 (z +1.2); damage A 373/412, B 357/390.
  - Their jump-in punished with its crit: A 24 of 54 (44%) vs B 12 of 63 (19%).
  - K2 broken (hits on a falling jumper): A 27 vs B 53.
  - Third hits A 38% vs B 29% (still behind). In their combo A swung 33% vs 40%. Outside their combo 43% vs 53% (held otherwise 29% vs 32%).
- Run 80 deploying 18:15.
- 18:52, 20 updates of run 80 (live 18:24): moved the wrong way on the air.
  - Its own take-offs 13-20 ticks after their hit: 17.2 an update (about 10 before; already 7 -> 13.5 across the 77c/78/79 hour). Hits taken in the air from its own jump: 24 an update (17-19). In their combo, air 18% (13-15%).
  - Better: chase crits 2.6 an update (5). Follow-ups' held pay: paid 299, dropped 128 (30%).
  - Run 79 still up: T1c 15%, critted back 10%, its own jump after its punish crit 81%.
  - Punish crit 58%.
  - If the ~19:10 check is not ahead, revert run 80 first (its air counters went the wrong way), then judge 77c/78.
- 18:49-19:3x: training stood still. Claude crashed, and the PC had 130-300 MB available, so the server ran up to 63 s behind and the watchdog waited for 1.2 GB. At the user's word (19:25) I closed Chrome, Roblox, the Razer/Radeon/WARP windows, and later the VS Installer background download. A redeploy restart was asked for 19:24.
- STRUCTURAL DIAGNOSIS (agent 14, 19:30; 10 checks 09:36-18:15, 444 pairs; Fable-style brief): Fable's "A and B are the same machine" is WRONG.
  - In 15 of 15 checks A and B differ by 2-5 HP a fight at a dozen moments (same signs every check), and the differences cancel. Rounds A 179, B 171.
  - A ahead:
    - its punish crit +4.56 a fight;
    - its crit opener +3.17;
    - their combo +2.75: its sword is ready at 99% of their combo hits vs 95%, which feeds the punish.
  - B ahead:
    - its hits on jump-ins: -3.93 for A, much of it the user's rule (B hits late, the bots don't punish it);
    - its own combo -2.75 (3+ chains A 7.1% vs B 10.3%);
    - their neutral openers -2.93.
  - 86% of the gross A-B difference is at moments where the body keys belong to the NETWORK on both sides, and only 14% at rule-held ones.
  - The network alone is worse than the rules at every defensive moment and equal on offense, so there is no key to hand over yet (Fable's P6 has nothing to take).
  - Bottleneck hypothesis: A's swing REPLACES hits instead of adding them.
    - Crits: A 21.0 vs B 10.7 HP a fight.
    - Plain hits: A fewer on fallers (K2) 9.6 vs 13.8, on risers (the jump-in rule) 5.8 vs 8.5, on the ground 18.6 vs 21.9.
    - Damage dealt: 55.0 vs 54.9 a fight. Across 18 checks crits +65 vs plain hits -69 a minute, correlation -0.52.
  - After its own ground hit, A's next hit comes first 24% vs B 33%; theirs comes first 56% vs 46%.
  - Plainly: against these bots, the hits the user's rules give up cost A nothing it can win back, so the bot checks may never show A clearly ahead. Real duels decide.
  - Test (checks only): CHECK_B_FOLLOWUP, B's network's W/S, sprint and jump for 12 ticks after A's own ground hit. It runs after run 80 is judged at the next check.
  - Proposal for the user: a real-duel A/B arm vs the installed run35-0657. It needs a new jar, so it waits for the user's word.
- Training back 19:34 (redeploy restart). 36 updates to 20:18:
  - Its own take-offs 13-20 ticks after their hit 7.3 an update (about 10 before: run 77c's -30% gate met).
  - Hits taken in the air from its own jump 14.9 an update (17.5-20.5); chase crits 2.3.
  - Punish crit 53% (near the floor: watch). T1c 12%, critted back 17%.
- 20:22 check (T-B; runs 77c/78/79/80): A 20/45, B 17/45 (z +0.7); damage A 372/417, B 353/415 (net -7.1 vs -10.1).
  - Third hits A 26% vs B 33%: A better.
  - Outside their combo A swung 49% vs B 49% (even, up from 36-43%). In their combo 36% vs 40%.
  - New late-hit counter: at ground tick 0-1 A 0, B 0. LATE (tick 2+, before their hit) A rising 9, falling 2; B rising 15, falling 5. This is before the split of its own jump-along crits, which deploys 20:24.
  - K2 broken A 32 vs B 52. Their jump-ins punished with its crit A 23/71 vs B 14/70.
  - Two checks in a row ahead since runs 77c/78/79 (18:15 z +1.2, 20:22 z +0.7): A 41/89 vs B 34/89.
  - Run 80 KEPT.
- Agent 14's CHECK_B_FOLLOWUP test waits one more check (a third check of the current A first).
- 21:22 check (T-C): A 12/46, B 17/46 (z -1.3); damage A 326/438, B 360/402 (net -17.6 vs -6.7).
  - Third hits A 29% vs B 42% (A still better there). Outside their combo A swung 44% vs 54%; in their combo 32% vs 42%.
  - Late hits on jump-ins (split counter):
    - A: knock-away 4 rising, 0 falling; its own jump-along crits 5; at tick 0-1, 1.
    - B: 21 rising, 1 falling.
  - K2 broken A 33 vs B 47. Their jump-ins punished with its crit A 21/68 vs B 11/64.
  - A keeps the user's rules far better and takes fewer third hits, and still lost the rounds on damage taken (438 vs 402). This is agent 14's picture: what A gives up is not paid back against the bots.
  - Three checks since runs 77c-80: A 53/135, B 51/135, level. Since 11:42: A 172, B 171.
- Agent 14's CHECK_B_FOLLOWUP test on 21:25 for two checks (checks only; training unchanged). Then off.
- 21:25 the user rebooted with the iGPU at 3 GB (13.0 GB visible, was 9.7). Training was back 21:35 (deploy with the test on), about 2.8 GB free.
- 22:23 check (T-C), agent 14's TEST ON (A's W/S, sprint and jump were B's for 12 ticks after its own ground hits, 667 ticks): A 24/44, B 17/44 (z +1.7); damage A 371/358, B 336/387 (net +2.2 vs -8.4).
  - After its own ground hit (agent 14's groundnext):

    | | A, test on | A, 3 checks before | B, test check | B, 3 checks before |
    |---|---|---|---|---|
    | Takes off within 10 ticks | 2% | 39% | 1% | 1% |
    | Its next hit | 42% | 24% (ground), 34% (jump) | 39% | 36% |
    | Their hit | 33% | 54% (ground), 36% (jump) | 37% | 45% |

  - Third hits A 33% vs B 30%. Outside their combo A swung 36% vs 46%.
  - Late knock-away hits on jump-ins: A 8 (7 rising, 1 falling) vs B 16. K2 broken A 43 vs B 34: B's sprint key in that window may add A's sprint hits on fallers. Watch.
  - One check supports agent 14's hypothesis: A's own movement after its own ground hit is where it loses the next hit. Even A's ground ticks were worse than B's (their hit 54% vs 45%). The second test check (~23:10) decides. Then the training fix.
- 23:12 check (T-D), the second with the test on (1362 ticks): A 19/44, B 17/44 (z +0.4); damage A 374/407, B 349/390.
  - After its own ground hit: A's next hit 37% vs B 32%; their hit 46% vs 52%.
  - Third hits A 35% vs B 26%. Outside their combo A swung 48% vs 50%.
  - Late knock-aways on jump-ins: A 6 vs B 21. K2 broken A 28 vs B 37.
  - The two test checks: A 43/88 vs B 34/88. In both, A's follow-up beat B's. HYPOTHESIS SUPPORTED: A's own movement after its ground hit cost it the next hit.
- 23:22: test off. Run 81 (OWN_FOLLOWUP_AWR: B's movement keys in that window as an outcome target in training) deploying.
- Run 81 live 23:31.
- Agent 15 (23:40), T9 stuck at 48-62% for 5+ h, always off on the jump. Two causes:
  - (a) A COUNTING ARTEFACT. A moment where a rule (or the sword gate) held the deciding key counts as done with nothing judged. Of 254 T9 moments, about 141 were rule-held. On the rest, the network itself jumped about 2%. T9a's 80%+ is the same (the rule jumped on 22 of A's 24 T9a ticks). So every technique percentage reported so far mixes in rule-held moments.
  - (b) A TEACHER CONFLICT on their FIRST hit:
    - The T9 teacher asks for the jump reset on every ground sprint hit taken, which is the document's ("press jump at the same moment you take damage"; Part 7: sprint hit -> jump reset).
    - T9's pull is 0, so only B's key teaches that tick (RULED_AWR, weighted x4 on the hit tick).
    - B's JumpReset rule fires only from their 2nd hit (JumpReset.java:23-25, 157). That limit is the 23 Oct rule's, not the document's.
    - In rules-off scenes the network jumps 98% on their 2nd+ hit but 6% on their 1st.
  - The timing is reachable (its own hurt is not delayed by SeenLag).
  - After a first-hit jump the next hit was its own 61% (n 18) vs 37% without.
  - Measurement fix (F0): rule-held moments are counted apart in the technique lines, and tech-review shows the network's own share. Committed 7cbace9; it deploys with the next batch. THE TECHNIQUE VERDICTS WILL CHANGE (lower) from then on: that is the honest number, not a regression.
  - Training fix for the first-hit jump reset: after run 81 is judged.
    - Preferred: F2 (B's JumpReset also on a first sprint hit, training only, A's side), taught by outcome through RULED_AWR, the path that reached 98%.
    - Not first: F1 (T9 run-taught and gated). Teaching T9's jump spread jumps everywhere on 29 Oct, and run 25 cost -10.2 in a check.
- 12 Nov 00:23 check (T-D), run 81's first (test off): A 22/45, B 17/45 (z +1.0); damage A 381/391, B 337/379 (net -1.5 vs -7.3).
  - Third hits A 28% vs B 19%. Outside their combo 51% vs 51%. In their combo 33% vs 46%.
  - After its own ground hit (test off now): A takes off 33% (39% before run 81; gate <= 20%). Its next hit 30% on the ground / 35% after a jump, theirs 54% / 37%. B: 32% / 47%. Not yet B's level on the ground: 50 minutes of run 81.
  - Late knock-aways on jump-ins A 9 vs B 19; jump-along crits 4 vs 4. K2 broken A 30 vs B 35.
  - Since 17:23 (runs 77c-81): A ahead in 5 of 6 checks, 118 vs 102 rounds (two were agent 14's test checks). A's own play only: 18:15 +4, 20:22 +3, 21:22 -5, 00:23 +5.
- 00:25: the technique scoring fix (rule-held moments apart) deploying.
- 00:33 the scoring fix was live. Network-own rates: T9 5% (37 of 57 moments rule-held), T9a 0/0 (all 12 rule-held), T10 46%, C6 0/7 (82 rule-held). Its own: B1 80%, K3 83%, T11c 83%, T4 54%, T1c 61%.
- 01:24 check (T-E), run 81's second: A 19/45, B 18/45 (z +0.3); damage A 372/430, B 376/394 (net -8.7 vs -3.0).
  - After its own ground hit: A takes off 38%. Its next hit 23% ground / 16% jump; theirs 63% / 50%. B: 35% / 47%. WORSE than 00:23, so run 81 has not taught it yet (about 1.5 h of training).
  - Outside their combo A swung 36% vs 54%; third hits 31% vs 29%. Late knock-aways A 5 vs B 18; K2 broken A 29 vs B 55.
  - Run 81's revert rule (follow-up back to 24-34% / 54% AND z < 0 in two checks) is not met, since z was +1.0 and +0.3. KEPT, but its mechanism is not working yet. If its follow-up is still under B's in the next two checks, look at its pull: the outcome-gated B-key target at ruledPull may be too weak.
- 01:26: run 82 (RESET_FIRST_HIT_TRAIN) deploying.
- 01:52, 13 updates of run 82: T9's own share 79% (11/14; was 5%), with 41 rule-held moments (the rule now resets first hits too in training). Punish crit 58%.
- 02:24 check (T-B; runs 81 + 82): A 24/44, B 20/44 (z +1.2); damage A 395/381, B 387/399 (net +2.1 vs -1.9).
  - After its own ground hit: A's next hit about 38% (36% ground, 40% after a jump) vs B 33%; theirs about 43% vs B 46%. A ahead of B on its follow-up for the first time without the test.
  - Third hits A 34% vs B 30%. Outside their combo 51% vs 55%; in their combo 36% vs 40%.
  - Late knock-aways on jump-ins A 4 vs B 11. K2 broken A 33 vs B 41.
  - A's own play since 17:23: +4, +3, -5, +5, +1, +4 rounds (6 checks, +12). With the two test checks (+7, +2), A was ahead in 7 of 8.
- 02:54 REAL-DUEL A/B ARM ON (standing order: reversible A/B arms with a backup).
  - The profile jar is replaced by the 01:34 build. The backup is swordai/backup-before-ab-20261112-025404.
  - Pool: installed run35-0657 1 : run82 (checked-1112-0220, the 02:24 check's network) 1, both rules on.
  - The installed arm plays as before: run 62/65 fight hand-overs off, old networks load unchanged.
  - The run82 arm has no runs 74/75/78 in real fights (those are in training and check code only).
  - Judge by the user's real rounds: tools/duels.py '[A/B arm' lines; rounds won, their 3+ combos, late jump-in hits.
  - Undo: tools/install-ab.sh --off, and the jar from the backup.
- 03:13 check (T-B): A 15/44, B 20/44 (z -1.1); damage A 352/424, B 344/411 (net -11.4 vs -10.8).
  - Third hits A 30% vs B 40% (A better). Outside their combo 42% vs 50%.
  - Late knock-aways on jump-ins A 5 vs B 21. K2 broken A 29 vs B 39.
  - After its own ground hit: A takes off 40%. On the ground: its next hit 26%, theirs 60%. After a jump: 46% / 39%. B: 35% / 44%.
  - A's own play since 17:23: +4, +3, -5, +5, +1, +4, -5 (7 checks, +7).
- 3-hour rule (03:25), run 81's target: after A's own ground hit, their hit first on the ground was 54/63/51/60% vs B 44-47% in 4 checks, with take-offs 33-41%. Flat for about 3.5 h. Research agent 16 launched: who presses A's W/S and jump there (rules, teacher, network), why B's identical rules do better on B's state, whether run 81's outcome target is even reached and pulled; 1-2 fixes of a different kind.
- Agent 16 (03:35): A is not worse on the ground. It loses the next hit by TAKING OFF (56% vs B 4% with no hit in ticks 1-4; after its take-off their hit first 35% vs 18% staying down).
  - The jumps are the network's, taught by run 62's hops: the teacher began T11b after every own sprint hit without checking for a stun, against the document's T11b/T3/T10.
  - Run 81 is masked off on those ticks.
  - Run 83 (TEACH_STUN_GATE) deploying 03:38.
- 04:34 check (T-C), RUN 83's FIRST: A 20/45, B 11/45 (z +2.3, only A won 12, only B won 3); damage A 365/411, B 327/429 (net -7.2 vs -15.7).
  - After its own ground hit: A takes off 28% (was 40%). On the ground, its next hit 35% and theirs 51% (was 26/60%). After a jump, 41/34%. B: 32/49%.
  - Run 83's gates:
    - take-offs <=20%: NOT YET (28%);
    - their hit first <=41%: NOT YET (46% overall);
    - its next >=30%: OK (37%);
    - T3 stuns >=25%: OK (A 26%, B 45%);
    - z >=0: OK;
    - punish crit 63% in training, B1 80% done in the check.
  - Revert rule (two checks with z<0 and their hit first >=46%) not met. KEPT; the second check decides.
  - Third hits A 39% vs B 40%. Outside their combo swung 42% vs 53%; in their combo 35% vs 45%.
  - K2 A 126/135 (93%) vs B 34/118 (29%). T10 done A 42% vs B 8%.
  - Late knock-aways on jump-ins: rising A 8 vs B 25, falling 0 vs 2, at ground tick 0-1 0 and 0.
  - Uppercutter A 1-2 vs B 0-3; fight-jumpin 0-3 both.
  - A's own play since 17:23: +4, +3, -5, +5, +1, +4, -5, +9 (8 checks, +16).
- 3-hour rule (04:55), item 1: swing-back in their combo has been flat in checks since 20:17 on 11 Nov (8+ checks).
  - A swung 33-44%, B 40-45%. With the crosshair on them (04:34), A about 53% vs B 70%.
  - Research agent 17 launched with a structural brief:
    - who holds the swing in their combo for A vs B (B1 keep, jump-in guard, crosshair guard, network);
    - how many rounds flow through it (A's B1 80% vs B 54% may be the trade that wins rounds);
    - one bottleneck, with a test that could prove it wrong;
    - 1-2 fixes of a different kind than runs 72-83.
- Agent 17 (05:15). 9 checks from 20:22 to 04:34: 402 pairs, A 175 rounds, B 154. Scripts are in tools/a17.
  - Verdict: the swing-back gap in their combo is the B1 trade plus the jump-in guard plus C3 fly-outs. It is NOT costing rounds.
  - Holds per check:
    - A has more ready ticks than B (494 vs 444) but swings almost as often (170 vs 184).
    - Held with the crosshair on them: A 170, B 115.
    - In training, A's jump-in guard holds 7.1% of those ticks, about 35 of A's 55 extra held ticks. That guard is the user's rule; B breaks it with 25+2 late knock-aways a check vs A's 8.
    - The rest is state: A is in the air at their first hit 48% vs B 7%, so the same rules keep the swing.
  - Rounds flow (20 checks, about 4,300 hits each):
    - After their FIRST hit, their next hit comes first: A 31.8%, B 31.4%.
    - After their SECOND hit or later: A 23.7%, B 42.6% (A better).
    - Third hits taken A 225 vs B 219.
    - A replies with a crit 17.6% vs 12.4%, with a plain hit 24.0% vs 32.5%.
    - Net of the whole bundle: +0.20 +- 1.28 damage a fight.
  - Measure item 1 from now on by "their next hit first after their hit" and "replies per hit taken", not by swung% of ticks. Do not weaken the B1 keep or the guard to raise swung%.
  - Hypothesis: A's own take-offs caught rising outside their combo.
    - A takes off 241 times a check vs B 30.
    - Their hit lands on it rising 1.28 a fight vs B 0.15, mostly at air ticks 1-4, 74% sprint hits.
    - After such a hit, their next hit comes first 51% (after its own hit) vs 26-27% when hit falling or on the ground.
    - The rising hits in their combo are C3 fly-outs and work (their next first 3%).
    - Expected value is small (about +0.7 rounds per 45 pairs).
  - Free test: run 83 already cuts these take-offs. Over its next 3 checks, if rising catches fall from 0.68 to 0.4 or less a fight while "their next first after their first hit" stays at 31.8% and rounds do not rise, drop the hypothesis.
  - Proposed fixes, not built yet; run 83 is still being judged:
    1. Branching replays at its own take-offs (K3/T8/K2): replay from 3 ticks before with the jump held off for 4 ticks, and use the 25-tick net damage as the jump head's target.
    2. A next-swing-timing predictor as a policy input (T9). This changes the observation; speculative.
  - Built now, counter only (deploys with the next redeploy, after run 83's second check): the check line "in their combo ... held with the crosshair on them: by the jump-in guard x%, in the air y%, on the ground z%" for A and B (agent 17's mechanism test).
- 05:23 check (T-C), run 83's second: A 17/44, B 20/44 (z -0.8; only A won 6, only B won 9); damage A 363/415, B 348/384 (net -8.2 vs -6.0).
  - Run 83 over its two checks: A 37, B 31. KEPT; the revert rule (both checks z<0) is not met.
  - After its own ground hit: A takes off 22% (40% before run 83, then 28%). On the ground: its next 38%, theirs 49%. B: 40/38%.
    - Gate "their hit first <=41%": FAILED (about 50% overall).
    - "its next >=30%": OK. T3 stuns 28% (OK).
  - Agent 17's free test, catches of A rising on its own jump a fight, run 83's 2 checks vs the 9 before:
    - neutral 0.44 vs 0.58;
    - its combo 0.47 vs 0.66.
    - Falling, not yet at 0.4 each; the third check decides.
  - Third hits A 30% vs B 37%. In their combo A swung 28% vs 42% (round-neutral per agent 17).
  - Late knock-aways on jump-ins rising A 4 vs B 20. K2 broken A 35 vs B 47.
  - A's ground sprint/plain hits are far fewer than B's in every check: 129 vs 235, 149 vs 208.
  - A's own play since 17:23: 9 checks, +13.
  - Not done: agent 16's fix 2 (COMBO_JUMP_AWR, relabel the decider's T10/T11 to none where the outcome was worse). It would also cut T10/T11b in its own combo, which item 12 asks for: T10 done A 42% vs B 8%, T11b rising 55 -> 78%.
- 05:26 redeploy: agent 17's counter only (who held the swing in their combo). No training change.
- 06:22 check (T-D), run 83's third: A 21/44, B 21/44 (z 0.0; only A won 5, only B won 5); damage A 399/404, B 373/373 (net -0.8 vs -0.1).
  - RUN 83 VERDICT, 3 checks: A 58, B 52 (z +2.3, -0.8, 0.0). KEPT. It cut take-offs after its own ground hit (40% -> 28%, 22%, 28%).
  - The new counter (agent 17's mechanism test) CONFIRMS agent 17. In their combo, held with the crosshair on them:
    - A: by the jump-in guard 8%, in the air 22%, on the ground 2%. Swung 34%.
    - B: guard 0%, in the air 14%, on the ground 7%. Swung 42%.
    - So A's gap is the user's guard plus air time. On the ground A holds LESS than B.
  - Agent 17's free test, catches rising on its own jump a fight:
    - neutral 0.38 (<=0.4 OK);
    - its combo 0.82 (0.47 in the two checks before).
    - Not a steady fall, so the take-off hypothesis is not proven.
  - After its own ground hit, by its W/S key on the hit tick (tools/a17/groundkey.py; last 3 checks, then 9):

    | key on the hit tick | A: its next / their hit | B: its next / their hit |
    |---|---|---|
    | ground + S (A 45%, B 92%), last 3 | 39 / 56% | 39 / 42% |
    | ground + S, last 9 | 37 / 50% | 35 / 45% |
    | ground + W (A 20%, B 1%), last 3 | 29 / 37% | |
    | ground + no W/S (A 9%, B 6%), last 9 | 24 / 65% | 48 / 36% |
    | jump + S (A 20%), last 3 | 50 / 34% | |
    | jump + S, last 9 | 40 / 37% | |

    - W on the hit tick is NOT the cause (their hit 37%).
    - A's loss is AFTER the hit, on the ground: with the same S its opponent hits first 56% vs 42%, and with no W/S 65% vs 36%.
  - Third hits A 22% vs B 31%. Outside their combo A swung 57% vs 53% (A ahead for once).
  - K2 broken A 22 vs B 49. Late knock-aways rising A 7 vs B 13, at ground tick 0-1 0 and 0.
- 3-hour rule (06:30), item 2 / run 81's target: A's ground follow-up after its own hit has been flat since about 22:00. Their hit first 49-60% vs B 38-49% in 7 checks; runs 81 and 83 did not move it. Research agent 18 launched on the ticks AFTER its own ground hit while it stays down: its keys, the distance, who presses them, and the bots' reply.
- Agent 18 (06:50, 12 checks, A 1697 / B 2731 ground hits): the follow-up gap after its own ground hit is mostly how it was MEASURED.
  - Trades (their hit 1-5 ticks after its hit): A 25.4% vs B 24.2% (z 0.9).
  - Untraded and staying down, their hit first: A 24.9% vs B 26.4%; its next hit: A 48.5% vs B 47.7%.
  - Why the old split showed a gap: a hit back within 4 ticks rules out a take-off, so trades always count as 'ground'. A's untraded hits leave that class by taking off (A 36%, B 1.5%).
  - The take-off cost (take-off at tick t vs staying down at the same t): about 1.1 HP each before run 83 (569 take-offs), about 0.1 HP in run 83's 3 checks (190). RUN 83 REMOVED THE WINDOW'S ONLY REAL COST.
  - Keys: the rules press no W/S/sprint/jump on either side's hit tick. The deciding keys are A's network vs B's.
    - Close hits with neither W nor S (13% of A's, 2.22 blocks; T4 "too close, they hit you through") trade 37% vs B 15%. About 0.3 rounds a check; not worth a run.
  - Rounds flow of the whole window with B's rates: +1.1 HP a fight (+1.8 in the last 3 checks), about 0.4-0.6 rounds a check; noise is +-4.
  - The upstream difference: A lands 3.2 ground hits a fight vs B 5.1.
    - Ready on the ground outside their combo: about 220 vs 355 ticks a check; it swings 45% vs 52% there.
    - This is a trade for crits: A deals more damage a minute than B in 5 of the last 6 checks.
  - Its rounds are lost on damage TAKEN: their crit openers cost A 137 vs B 99 (-1.8 HP a fight), plain openers -0.9, partly from the user's jump-in rule.
  - Test that could prove it wrong (3 checks, no training change): A's untraded staying-down 'their hit first' at least B's + 6 points with z >= 2, or the take-off cost back above 0.7 HP.
  - Done: the gates are rewritten (item 2's measure line), scripts are in tools/a18, and no new training run on item 2.
  - Next: their crit openers (A -1.8 HP a fight): split them into jump-ins under the user's rule, others, made to miss by T6, answered by B1. Agent 18's follow-up was STOPPED at the user's word (12 Nov 07:00: at most one subagent per 2 h, Sonnet only). QUEUED for a Sonnet agent at 08:30 or later; I measure what I can myself until then.
- 3-hour rule (14:55), item 10: run 76's no-jump lesson left its own take-offs in their combo flat at 41-46 an update (130 updates). Research agent 10 launched: what keeps the jump (rewards, the decider telling T9/T9a, the lesson's weight or window, the jump reset's timing), and a different kind of fix.
- NEXT for real duels: the profile's jar is from 9 Nov. A 69a+ checkpoint (4 more tensors) does not load in it, and runs 74/75 act only in training and checks, not in real fights (SchoolFighter). A real-duel arm needs the new jar and runs 74/75 ported to the real fight. Needs the user's word.
- 06:50 BENCHMARK (docs/BENCHMARK.md, per-skill tests vs the installed model; the user: "make sure to run benchmark"; none since 10 Nov): R re-measured under the current code as run35w83 (run35-0657, suite-v2), then the candidate run83n0618 (checked-1112-0618 = run 83 after its 3 checks, kept as run/best-candidates/run83-0618.bin). Scored with tools/bench.py compare; then about every 3 h on the newest candidate.
- 07:27 BENCHMARK RESULT, run83n0618 (run 83 after 3 checks) vs run35w83 (the installed run35-0657, re-measured today, suite-v2): TOTAL -0.02 (1.04) -> TIE.
  - Skills part +2.16 (1.89) HP per real fight.

    | Test | HP per fight (se) |
    |---|---|
    | Three-block hit (T7) | +4.89 (1.70) |
    | Crits | +4.18 (2.32) |
    | Make them miss (T6) | +2.82 (2.09) |
    | Punish crit (B1) | +2.13 (3.65); rate 17% vs 12% |
    | Reverse deflection | +2.41 (6.55) |
    | Jump reset | +0.56 |
    | Holding a combo | +0.24 |
    | Jump-in defence (K2/T8) | -2.95 (1.94); crits taken per jump-in 0.04 vs 0.02 |
    | Getting out of combos | -1.59 (0.98) |
    | Deflection (T4) | -1.45 (3.19) |

    No test is worse by more than 2 se.
  - HEAD-TO-HEAD (C against the installed model itself): C-R -4.87 (1.62) HP per fight, 3 se WORSE. Rounds C 22 vs R 31 of 60 (z -1.7). This alone blocks BETTER.
  - Bot fights +0.46 (0.45); rounds 159 vs 149 of 300 (z +1.0). Of these the mixer bot -2.00 (1.34).
  - The hourly checks (A slightly ahead of the 23 Oct model) did not show this: against the INSTALLED model it loses the head-to-head. run52-1218 also lost it, 19-22 on 9 Nov.
  - The h2h episodes are run/swordai/bench/run83n0618/NNNN-h2h.txt.gz (real-fight format, per tick).
  - Next: WHY it loses the head-to-head, which is the biggest and the blocking part. The queued Sonnet agent (08:30 or later) takes this instead of the crit-opener split; jump-in defence -2.95 may be the same thing.
- 08:20 check (T-E, first after the benchmark): A 24/45, B 21/45 (z +0.8); damage A 419/412, B 376/391 (net +0.9 vs -2.4).
  - Third hits 28% vs 28%. Outside their combo A swung 44% vs B 56%.
  - In their combo, held with the crosshair on them: A guard 7%, in the air 26%, on the ground 5%; B 0/16/6%.
  - Late knock-aways rising A 5 vs B 27. K2 broken 35 vs 37.
  - Run 83 so far (4 checks): A 82, B 73.
- Agent 19 (Sonnet, 08:30-08:36): the head-to-head loss against the installed model is LONG CHAINS TAKEN.
  - C takes 3.2 hits a fight in chains of 6+ vs R 0.65, which is 3.4 of the 4.87 HP. Its chains end within 15 ticks after a combo hit 20% vs 39%.
  - In their combo the jump reset rule holds W and the sprint (99-100% of ticks). The back-out rule only fires when the reset does not, and the network never presses S itself.
  - C is in the air 68% vs 47% of ticks; ground ready ticks near them 3.7 vs 14.6 a fight.
  - Jump-in crits are about equal (0.52 vs 0.43): it is not mainly the jump-in test.
  - Run 84 (COMBO_EXIT_YIELD, EXPERIMENTS.md) deploying 08:40: the network takes W/S/sprint/jump in their combo from their 2nd hit in 35% of training fights (C3), paid by the existing combo-escape reward.
  - Item 2 (comboed) and the benchmark's head-to-head are its targets. Judged by the next benchmark (h2h better than -2).
- 09:32 check (T-B), RUN 84's FIRST: A 23/44, B 16/44 (z +1.8; only A won 11, only B won 4); damage A 412/395, B 368/430 (net +2.5 vs -9.4).
  - Third hits A 30% vs B 37%. Outside their combo A swung 46% vs 60%. In their combo 37% vs 45%; held with the crosshair on them: guard 5%, in the air 26%, on the ground 3% (B 0/18/6%).
  - Jump-ins: A's hits at ground tick 0-1: 2 (allowed). Late knock-aways rising A 7 vs B 24. K2 broken 35 vs 43.
  - Run 84 in training (52 updates): the network had the keys on 2325 ticks and pressed S 24%. Their 3rd hit after their 2nd: 40% in the yielded fights vs 40% in the others (level; the revert needs 10+ worse).
- 10:21 check (T-B), run 84's second: A 21/45, B 16/45 (z +1.4; only A won 9, only B won 4); damage A 397/409, B 363/419 (net -1.8 vs -8.9).
  - Run 84 over 2 checks: A 44, B 32 (z +1.8, +1.4).
  - Third hits A 29% vs B 28%. Outside their combo A swung 47% vs 53%. In their combo 37% vs 43%; held with the crosshair on them in the air 30% vs 13%.
  - Late knock-aways rising A 3 vs B 23; ground tick 0-1 0 and 0. K2 broken 37 vs 41.
  - Training (72 updates): the network pressed S on 27% of the yielded ticks. Their 3rd hit after their 2nd: 38% yielded vs 37% others.
  - Punish crit chances critted 67% (just above the 50-65 band, not below).
- 11:10 check (T-C), run 84's third: A 17/44, B 16/44 (z +0.3); damage A 349/418, B 345/395 (net -10.9 vs -7.9).
  - Run 84 over 3 checks: A 61, B 48.
  - Third hits A 34% vs B 38%. Outside their combo A swung 46% vs 53%. In their combo 30% vs 49%.
  - Late knock-aways rising A 6 vs B 25. At ground tick 0-1: A 1 (allowed).
  - Training, their 3rd hit after their 2nd, all 117 updates: 39% yielded vs 37% others (level). The last 24 updates: 49% vs 33% (worse, n=43).
  - The 11:30 benchmark decides. Revert if the h2h does not improve and the yielded share stays 10+ worse.
- 11:47 BENCHMARK, run84n1106 (run 84 after 2h25m, the 11:10 check's network) vs run35w83 (installed): TOTAL +0.19 (1.10) -> TIE (run83n0618 was -0.02).
  - HEAD-TO-HEAD C-R -1.40 (1.67), was -4.87. Rounds 33 vs 31 of 60, was 22 vs 31. RUN 84's GATE (h2h better than -2) MET: RUN 84 KEPT.
    - Agent 19's split (tools/a19/an.py): hits taken while in the air 2.37 a fight (3.40 before) vs R 1.88. Air share 63% (68%) vs R 47%.
  - Skills +1.26 (2.02):
    - Reverse deflection +6.17, Crits +3.33, Make them miss +1.71, Three-block +1.14, Holding a combo +0.95.
    - Punish crit -1.98 (4.54), rate 16% vs 12%. Getting out of combos -1.25 (1.05).
    - JUMP-IN DEFENCE -2.94 (1.29), WORSE by more than 2 se: this blocks BETTER.
  - Bots -0.38 (0.46); rounds 152 vs 149.
  - Jump-in defence per tag (bench.py score):

    | | crits taken per jump-in | KB hits on FALLING jumpers | hits on rising jumpers | HP per jump-in |
    |---|---|---|---|---|
    | installed | 0.02 | 0.11 | 0.30 | +0.77 |
    | run 84 | 0.05 | 0.03 | 0.30 | -0.03 |
    | run 83 | 0.04 | 0.08 | 0.31 | -0.60 |

    The installed model breaks K2 / the user's jump-in rule almost 4x as often, and the test pays it in HP. Part of 'WORSE' is the price of the user's rule.
    - The benchmark's scoring is the user's; not changed. Told the user.
  - Next: the legal part of the jump-in defence gap (agent 20, Sonnet, 11:50).

## SOLVED (with the evidence)

(none yet)
