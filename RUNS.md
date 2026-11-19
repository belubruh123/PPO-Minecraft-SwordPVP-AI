# Runs

One line per run: the date, the one structural change, the literal problem it fixes, and the result (rounds won in the
200-round head-to-head against the installed network, and the user's real duels when tested). Runs 1-125 are in the
git tag `backup-before-cleanup-2026-11-18` (EXPERIMENTS.md).

| # | Start | From network | The one change | Result | Kept? | Why (the literal problem) |
|---|---|---|---|---|---|---|
| 35 | 3 Nov | run 30h line | combo scenes in training | the user: "near perfect on humans" (15 Nov) | anchor | - |
| 125 | 17 Nov | checked-1117-1913 | combo-hold drill after its own stun | not judged | - | last run of the old method |
| 127 | 18 Nov 20:40 | checked-1117-1913 (installed) | distill (`school/distill`): no reward gradient; copy the play-time rules' keys on every tick they speak; half the fights without the rules (DAgger). Checks run WITHOUT rules (`school/rules-off-check`) | baseline, same 200 bot pairs: with rules 66% rounds, without 54% (z -4.1). Target: 66% without rules. Stopped 19 Nov 21:05 after ~23 h: flat at ~60% without rules (hourly 44-pair checks 53-69%); saved as school/run127-distill-1119-2105.bin | not yet (no 200-pair check) | the rules add 12 points, so they can't just be deleted; earlier take-over runs (30, 38, 72) copied the rules only where the shaped reward said it paid, and PPO pulled away |
| 126 | 18 Nov | - | cleanup: 430 dead scripts and ~800 KB of docs deleted, one short SOP (CLAUDE.md); the old pre-school pipeline (Trainer, train/headless/spar/demo/learn sessions, ~9k lines) removed | no training change; `/swordai school` and `/swordai fight` unchanged | yes | the project was too complex to reason about |
| 128 | 19 Nov 21:38 | checked-1117-1913 (installed) | full-policy DAgger distillation: include YAW/PITCH camera heads in `ruledHeads`, KL-anchor all non-ruled head-ticks to frozen teacher probs, freeze Predictor/Chooser, full check-bot mix, disable klStop throttle | **Stage 1 PASSED**: 204-pair rules-off check: **A 143/204 (70%) vs B (installed+rules) 141/204 (69%), z +0.3** (27-25 paired; ground ready swing 49% vs 53%, aim on-target 97% vs 90%); saved as `school/distilled-baseline.bin` | yes (Stage 1 gate passed) | Run 127 capped at ~60% because YAW/PITCH were excluded from `ruledHeads` and non-ruled ticks had zero target (causing catastrophic forgetting of ground swings 47%->24%) |
