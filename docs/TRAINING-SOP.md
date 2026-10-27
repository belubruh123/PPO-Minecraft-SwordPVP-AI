# SwordAI training SOP (the user's standing procedure, 26 Oct 2026)

## The standard
- Every technique at **90% or more**, counted by the game in real first-person fights, and kept there.
- The trained network beats the installed model on **fresh-seed** checks (seeds never used to pick it).
- Techniques and their counters come only from `docs/PVP-TECHNIQUES.md`.

## 1. Learn one technique at a time (the focus)
1. **Pick the focus.** It is the weakest technique by the game's counts and the hit log (`tools/hits-report.py`). Order so far:
   1. the crits: crit scenes, and the jump-in counter (T8, K2, then B1);
   2. the combo escape (C3, T9).
   After that, the next weakest technique from the hit report.
2. **Give it a measure** that the game counts, so that 90% means something. Examples:
   - jump-in countered: in the 40 ticks after their take-off, their hit on it is answered by its own crit (the punish crit, B1) and no sprint hit on them falling (K2). A jump-in where they never hit it and it made no K2 is not counted (nothing to punish). Hitting them while they rise (T8) does not count by itself: with lag and knockback that does not take them out of reach, their crit still lands (the user, 26 Oct 14:30; the hit log: 25% of the time within 20 ticks). The bots' and the installed AI's jump-ins are counted apart and both need 90%;
   - crit scene won: dealt more than taken;
   - combo got out of: no third hit in a row.
3. **Train its scenes.** Every failure of the focus in a full fight is kept as a scene: both fighters' exact state (position, motion, look, sword clock, health, hurt time) from just before the moment, 60-100 ticks long. Training starts episodes from these scenes (teleport, same opponent). Each scene is fought again until it is won **3 times in a row**; it then goes to the mastered pile.
4. **One run may train one focus for a whole day.** Do not cut a method off after a short run.
5. **The decider learns the focus too.** The decision model (which technique to do now) learns from how the fight went after its own choices, on top of copying the teacher's. It chooses in half of the scene fights, so it learns *when* in the same order: the crits first, then the combo escape, then the next technique. Its line in the update log says how often its own choices left the teacher's and whether the outcome after them was better; the scenes it chose in are counted apart from those told the teacher's.
6. **When and how are learned apart (the user's method, 27 Oct).** Being good at one technique can mean being bad at another (the punish crit holds the swing for the fall; aggressive play swings at once). That is what the decision model is for: it picks *when* (the punish crit or the aggressive answer), learning from outcomes in full fights. The *how* is drilled with the decider's output forced to the technique: in that technique's scenes the network is always told it and the teacher always does it, so it learns to land it every time it is told. The technique's bar is "told it, landed it" (90%), never "of every moment it could have been used". Then the decider's choices of it get real payoffs to learn from.

## 2. Review in between (so nothing is forgotten)
Every training run keeps all of these going at the same time:
- **Basics**: 15% of fights (aim, hit timing, older stages).
- **Earlier focuses**: 20% of the scene fights go to their scenes.
- **Mastered scenes**: 15% of the scene fights test one again. A mastered scene that is lost goes back to be learned.
- **Full fights**: every technique's bots plus the installed model (40% of the full fights).

## 3. Learned, then guarded
1. **A focus is learned** when its measures reach 90% over the last 300 events in full fights and the last 200 scene fights. It then moves to review, and the next focus starts. This happens automatically (`SchoolSession` `FOCUS_*`, saved in `school/focus.txt`).
2. **If a learned technique falls below 90% in review:**
   - Training makes it the focus again at once (the log says `RETRAIN`).
   - **Investigate why**, the same hour: run `tools/hits-report.py` on the latest check, look at its scenes, and find which actions lose it.
   - Fix the cause only with methods from the file. Retrain until it is at 90% again.
   - Log the cause and the fix in `EXPERIMENTS.md`.

## 4. Measure honestly
- **Every check** writes the hit log (`school/hits.csv`). Run `tools/hits-report.py` and name the biggest damage gap against the installed model.
- **Decide on fresh seeds only.** Use `screen.sh` with `FROM=` (a pair range never used before), 133 pairs on scripted bots, plus a mirror check with `MIRROR=1` against the server's installed copy.
  - A screen on pairs 0-66 is not evidence: on 26 Oct the screens' +5 to +11 became -3 on fresh seeds.
- **Watch the damage trend over steps** (6-update averages, full fights only), not just 10-minute slices.
  - Scene fights lose by design. Keep them out of the damage curve.
- **Never call a model "best"** without fresh-seed evidence.

## 5. Install
- Install only a network proven better than the installed best on fresh seeds: scripted check and mirror check.
- Back up the profile first (`install-school.sh` does this).
- Record it in `run/best-*/BEST.txt` and in `EXPERIMENTS.md`.
- Keep every trained network and its scenes. Never throw learned data away.

## 6. Every ten minutes (the standing review)
Report:
- swings landing, lost view, damage dealt and taken (full fights, by kind of opponent), K2 broken;
- their combos' third hit;
- the focus and its bars (e.g. jump-ins countered X%), scenes won and mastered, any RETRAIN;
- techniques by `tech-review.py`.

## 7. Redeploys: only at checkpoints
This is rule 1.4 (one run trains one focus for a whole day; do not cut a method off) spelled out, because it was broken on 26 Oct. Do not judge a run, or change it, on the numbers of an hour.
- A redeploy stops training and restarts it, and every change it brings changes what the running numbers mean. So **changes are batched and deployed once, at a checkpoint** (every ~2 hours, just before the fresh-seed checks), never in between.
- Between checkpoints, a change waits committed in the repo. A quick fix of something just changed is not a reason to redeploy.
- The only exception is a broken run: training stopped, a crash, or a measure that cannot be read.
- The ten-minute reviews report and investigate; they do not redeploy.
- Never redeploy during a paired check. (26 Oct: six redeploys between 10:03 and 13:10, and another planned for 13:31, which the user stopped.)

Train first person only, never headless.
