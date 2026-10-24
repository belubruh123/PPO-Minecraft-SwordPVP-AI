# What SwordAI is judged by

## WHY IT LOSES (the user, 19 October morning; the most important part)

- **Spacing.** Opponents space it: they **crit it, then sprint-hit it, and it never crits back**.
  It walks into reach while their sword is ready (ten times as often as while it is coming back),
  and after their crit it does not jump back in for a crit of its own in their cooldown.
- **Damage, not hits.** Every report and comparison shows **damage dealt and taken per minute** for
  each opponent, not hit counts or rounds alone.
- **Target: at least 80–90% of rounds won, with combos.**
- **The biggest goal: the most damage landed and the least taken.** Networks and rules are kept or
  dropped on damage dealt minus taken a minute.
- **Train on the fights it still loses until it wins them all** (19 October): every scripted fight
  it loses is kept exactly as it was set up (1-1 from the start: same seed, same start, same bot
  choices; the user, 20 October) and fought again and again, a few times running, the worst first,
  never the ones it already won while lost ones remain, until it has won it twice in a row (`LostCases`, `replayLostShare`). A fight it still loses is very likely the
  one a person finds. Once every kept fight is beaten, all of them are fought again (one beaten
  can be forgotten) while new losses are hunted; once it really beats them all, the outside bot
  (Carpet PvP's fighter, `outsideShare`) joins the training (the user, 19 October 22:10).
- **Learn from real fights safely** (19 October): in `/swordai fight` with learning on, the proven
  network and a learning copy take turns; the copy replaces it only after doing better on damage over
  enough fights, and is thrown away if it does worse (`SafeLearn`, `safeLearn`).

Everything the user has asked of the fighter, in one place. Every item stays in force when a new one
is added: after each change all of them are checked again, in the per-update log line
(`fights since the last update`) and in frozen comparisons judged on **damage traded**.

## Hitting

- **No weak hits, ever.** A swing waits for the sword (a hard rule, `SwingGate`). A hit with the sword
  about 90% back counts as a full one in the game, and the network may take that tick (`swingMinCharge`).
- **Sweeps as few as possible.**
- **Learn hit timing:** delay a few ticks, or hit a tick early at 90%, so that it cannot be timed.
- **Crits, and switching between crit and combo**; a consistent **punish crit** (also against a
  circling "strafe combo" and from a bad angle). The **jump-crit chase** too, since many players use it,
  and the counter to it.
- **Uppercut**: nice to have.

- **No swings at nothing**: a swing at an opponent out of reach, or not on the screen, starts the
  sword over and hands the opponent a free hit (`whiffGuard`, since 18 October night).
- **Online, the full thirteen ticks** before a swing: at ~105 ms twelve gave weak hits again.

## Being comboed

- **Swing back** when it is being comboed: never take hit after hit without a swing. Out of a combo
  by the opponent's **second hit**.
- **No holding the swing for the perfect punish crit in a combo**: it looked like a trigger bot.
- **Jump reset sparingly**: only inside a combo; "really not fair" if overdone.
- **24 Oct 22:50: the jump-reset limit is removed** ("remove jump reset barrier cause i didn't even see it manage to pull a jump reset"): the teacher jump-resets wherever the file has it (T9 on a sprint hit, T9b, T9c), and no combo jump is pressed late on purpose (FairJump.MISS 0).
- **Backing off with S** only against opponents that do not chase with jump-crits; "be careful most".
  If it runs at all, it turns and sprint-jumps away rather than walking backwards.

## Distance

- **Spacing**: out of 3 blocks while the opponent's sword is ready, back in while it is on cooldown
  (not exact to the block). It must **space other players itself**, not only survive being spaced.
- **Approach straight**; strafe only at combo range, never circle in to close a gap.
- **Combo without leaning on circle strafing**; stay on the opponent and swing.
- **Close long gaps** (5+ blocks) with sprint-jumps; chase a player who runs.

## The opponents it has to beat

- Players on **high ping** (knockback 0.3 to 0.5 s late) and normal ping alike. The user plays at 60–90 ms,
  the friend at 50–100 ms.
- The **"PERFECT 3 block" player**, in both forms and mixed: one who stands three blocks off and
  combos perfectly, and one who crits then presses S out of reach, crits again, out again.
  Target: **nearly 100% win rate** against both.
- **Near 100% win rate against every scripted opponent and the 0.14.0 model**, and a clear win over
  what 0.14.0 did against people (a win over a beginner proves nothing).
- **An opponent not written here**: Carpet PvP's own fighter (beginner to expert), in training and as
  the judge in benchmarks (`outsideShare`).
- The friend's **spacing + punish crit** during its cooldown; the corner camper who crits; the near
  no-knockback "hacker" who crits; random and weird opponents with lag.

## The player at the edge of the reach, and why it beats the fighter

The player the user and the friend still lose rounds to plays the edge of the 3-block reach, in two ways,
switching between them:

1. **Standing at 3 blocks, perfect combo.** Stays put (or nearly) three blocks off, aim on the fighter,
   and swings the instant the fighter is in reach with its sword back. Never early, never late.
2. **Crit, S, crit, S.** Waits a step outside the fighter's reach while its own sword comes back. Once
   it is back, it walks in, jumps from the edge of the reach, makes the critical hit on the way down,
   and holds S straight back out of reach. Then it waits again. Never strafes.

Why the fighter loses to it (from the frozen tests and the counters in the log):

- **Its timing could be read.** Up to 0.14.0 every swing came the tick its sword was all the way back
  (13 ticks online), always. A player who knows that waits outside reach for exactly that swing, or
  for the cooldown right after it, and comes in then: the fighter's sword is on its way back, so the
  crit is free. Against the scripted copy of this (`BotProfile.timer`), the old network let 91% of the
  timed crits land and took 453 punish crits in 12 minutes.
- **It came into reach whenever its sword was back, whatever the other sword was doing.** It had no
  way to see whether the opponent's sword was ready: it walked into a ready sword at 3 blocks and
  was hit first, by a player standing still with nothing else to do but hit that moment.
- **After its own hit it stayed close with its sword empty.** On servers with reduced knockback the
  player it hit is still in reach and gets the punish crit in before the fighter's sword is back.
- **It closed distance by circling.** Going round someone is slower than running in and gives the
  spacer more time at the edge.
- **Backing off with S was not the answer:** a player who chases with jump-crits punishes that.

What changes it (18 October):

- It may swing a tick early (90% charge) and has to learn when to wait a few ticks, so its swings
  are no longer on a clock (`swingMinCharge`).
- It sees the opponent's sword: how far back it is, read off the opponent's last swing, as a player
  reads it (`ObsSpec.SC_OPP_CHARGE`). Spacing is made of that: out of reach while the other sword is
  ready, in while it is coming back.
- It runs straight in when out of reach (`StraightIn`).
- It trains against both forms of this player and the mix of the two (`BotProfile.spacer`), and the
  friend's timing (`BotProfile.timer`), until it wins nearly every round against them (`rounds won`
  in the log: every 20 health dealt or taken is a round).
- **And it uses the method itself, where it works:** against players who walk into reach on a
  ready sword, or swing as soon as their sword is back whether or not they can reach (they miss, and
  their cooldown is the opening), it holds the edge, lets them come or miss, and hits in their cooldown.
  Against players who chase with jump-crits, it does not back off with S.

## Aim and sight

- **Steady aim**, also at 20 blocks; no panicking or spinning.
- **Lost sight**: turn toward where the opponent went (even 180°), never stare at the ground and spin.

## Training and the mod

- **Train on screen, first person**: that is how `/swordai fight` plays. **Never headless** (20 October:
  headless spacer fights are not the same fights; the installed network wins 21% of spacer rounds headless,
  56% on screen). Fights it lost are checked **in first person** too.
- **Every hour, the final check** (20 October): the training network is tested frozen in first person
  against spacers, the full mix and the kept lost fights, and set against the installed network, so a worse
  network is seen within the hour, not at the end of the day; two hours worse and training goes back to the
  best network. Nothing replaces the installed network unless it measured better.
- Before training against a new weakness: **build the scripted opponent and prove, with the network not
  learning, that it beats the current model** the way the real player did.
- A **freshly trained model must learn everything** the current one knows.
- **Proof it has learned to choose its timing:** it beats the 0.14.0 model, which swings on a clock and
  can be spaced, by spacing it, finding that timing and comboing it (`oldModelShare`, the fights against
  the 0.14.0 model in the log).
- **Don't flood the C drive**: backups and logs on D.
- The **fight key stops a fight as well as starting one**.
- A **polished menu** (Right Shift), easy **import/export** buttons, **keybinds**; the real skin shown to the
  user while the AI sees its keyed picture.
- `/swordai fight` only where everyone has agreed to it; nothing aimed at avoiding detection.
