# SwordAI experiments log (from 25 Oct 2026)

**Goal, deadline 1 Nov 2026:** a school network that beats the installed model (the teacher) in paired checks, with every
technique of docs/PVP-TECHNIQUES.md done on at least 85% of its moments.

**What counts as done:**
- **Beats the teacher:** A − B above 0 by more than two standard errors, in two checks in a row, then confirmed by the user in real fights.
- **85%:** the technique's done-rate in tech-review.py (training) and in the check, for every technique with enough moments.

**Method:**
- One change per run.
- About 40 minutes of training, then a paired check against the installed model.
- Kept only if it beats the best so far by more than the noise (a check's se is about 3.5 per fight). Otherwise rolled back.
- Every run gets one row below.

| # | Start | From network | The one change | Check A − B (se) | Kept? | Notes |
|---|---|---|---|---|---|---|
| 0 | 25 Oct 05:00 | installed weights | Fix: the watching installed model fed back its own unplayed keys (Fighter.pressed) | −8.6 (4.3), then −0.6 (3.5) at 06:54 | yes | the best base so far: checked-1025-0650 / 0740 |
| 1 | 25 Oct 10:02 | checked-1025-0740 | The installed model's fight-time rules over the network (with the jump fix), no training | **+17.3 (6.0)**, 23 pairs | see 1b | without rules this network was −7.7 (07:44). Dealt/taken a minute 356/354 vs 301/403, swings landing 95% vs 84%, their third combo hit 40% vs 62% |
| 1b | 25 Oct 10:05 | checked-1025-0740 | Confirmation of run 1, 44 pairs | +4.5 (3.8) | **yes, the best so far** (ahead, but not yet by 2 se) | dealt/taken a minute 320/409 vs 310/426; swings landing 98% vs 90%; behind on punish crits (3% vs 8% by the game) and crits (6 vs 21) |
| 2 | 25 Oct 10:11 | checked-1025-0740 | Training with the rules on (the 06:54 recipe otherwise: copy the installed model + damage reward, no technique teachers) | after 40 min | | |

## Before this log (24-25 Oct night), for the record
Several changes were stacked per check (rules, a K2 guard, full technique teachers, reviews, reward weights, outcome
weighting, self-imitation), so their effects can't be separated. Results:

| Check | A − B |
|---|---|
| 08:22 | −38.4 |
| 09:11 | −15.0 |
| 09:29 | −19.0 |
| 09:49 | −11.6 |

What was learned for sure:
- **Hitting the falling jumper wins damage in this game.** Every check where the school network held its swing on jumpers lost to the jump-in critters by 26–49.
- **Technique teachers at a flat full pull spread into the whole fight.** "Don't swing" spread from K2 (08:22), and holding S spread from C2/C3 (09:49).
