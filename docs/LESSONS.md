# Hard facts from runs 1-125 (Oct-Nov 2026)

Check this file before trying an old idea again. The full logs are in the git tag `backup-before-cleanup-2026-11-18`.

## About measuring
- **Bot results do not predict human results.** On 15 Nov a network that beat the bots (17-12) lost every round to a
  person, while `run35` (3 Nov) was "near perfect" on people. Too-perfect bots teach tricks that only work on bots.
- **Small checks are noise.** 45 fights see only about ±11 HP. Rows per bot (4 fights) are pure noise. Use 200+
  rounds.
- **Swap the order in A/B checks.** Whole runs can be in an "easy mode", so alternate which network goes first.
- **Technique % is not fighting.** High "technique done" rates came with lost real fights: aim, lag and the opponent
  leaving the view decide more.
- **Real-duel logs need care.** For days the real duels used the 19 Oct network by mistake (learning mode), and deaths
  went uncounted. Check which network played before trusting a real-duel number.
- **The user's own Minecraft slows training by ~30%**, and every number looks worse while it is open.
- **Loading Carpet PvP made every bot weak** (training won 90% instead of 35-39%). Keep it off.

## About the method
- **Rules lock the network out.** The play-time rules press keys on ~58% of ticks, including the ticks where it gets
  hit, so PPO cannot learn those keys (run 30, 1 Nov).
- **Copying a scripted teacher can at best tie the teacher**, and copying the technique teacher lost (runs 7-11).
- **Fixing the biggest funnel drop is not always the fix.** Letting go of sprint for punish crits raised "not
  sprinting" from 49% to 67-85%, but crits stayed at 26% and fights got worse. The loss just moved downstream.
- **Re-weighting rewards went in circles.** Runs 36-125 mostly added or re-weighted a reward, rule or drill per
  technique. Rounds won against `run35` stayed about level.
- **Hops, lunges and uppercuts broke its own combos** against people (user, 15 Nov): "just don't teach hop lunge and
  uppercut".
- **No memory means no timing.** The user saw it act as if "it doesn't know what it is doing when it jumps". Memory
  (frame history or an LSTM) is structural work that is still open.

## What people beat it with (the user's real duels)
- Spacing: they crit, then sprint-hit, and it never crits back in their cooldown.
- Advanced crit spammers: a jump + crit after their punish crit, and crit combos.
- It gets stunned too easily: bad jump resets, and its own late jumps take knockback.
- It jumps right after landing its own hit and breaks its own combo.
- Not hitting at 2 blocks; the aim is too twitchy up close.

These are symptoms. Diagnose each one with `CLAUDE.md` section 2 (see, do, meet, reward, remember, bug), not with a
new reward or rule.
