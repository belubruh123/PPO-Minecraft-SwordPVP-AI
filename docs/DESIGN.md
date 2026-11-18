# Design: how strong game AIs are built, and how SwordAI gets there

## 1. What the labs that succeeded actually did

| System | Reward | Opponents | Human data | What made it work |
|---|---|---|---|---|
| AlphaGo Zero / AlphaZero (DeepMind) | win / loss only | self-play | none | one clean loop, scale, a strong fixed evaluation |
| OpenAI Five (Dota 2) | win plus a few game-score terms, kept fixed for months | self-play: 80% latest, 20% past versions | none | an LSTM for memory, months of one long run, "surgery" to change the network without losing skill |
| AlphaStar (StarCraft II) | win / loss | a league: main agents, past snapshots, exploiter agents | supervised start from human replays, then RL held near that human policy | play that stayed human-like and beat pros, with no rule overlays |
| Capture the Flag "FTW" (DeepMind) | win / loss; agents learned their own inner reward | population self-play | none | population-based training tuned the knobs, so no human tuned them |
| GT Sophy (Sony) | progress plus a few penalties, fixed early | a mix of opponent styles | none | a varied opponent mix and a lot of time |

The same five things show up every time:
1. **The reward is simple and stays fixed.** Behaviour comes from winning, not from a reward term per behaviour.
2. **Opponents come from a league**: the latest self, past selves and a few varied styles. Facing past versions stops
   it forgetting and stops strategies going in circles. Exploiter agents find holes, so no human has to patch them
   with rules.
3. **No hand rules at play time.** When it lacks a skill, the fix is its inputs, memory, opponents or training time.
4. **Human data keeps it human-viable.** AlphaStar started from human replays and stayed near that policy. Our recorded
   real rounds are that data.
5. **One long run with a fixed evaluation.** Few changes, each judged by win rate against a fixed set of opponents.
   When the network changes shape, carry the weights over ("surgery", Net2Net) so it does not start from zero.

## 2. Where SwordAI went wrong
Runs 1-125 did the opposite of those five things:
- **Reward**: `RewardModel` + `RewardWeights` hold 35+ terms, one per technique or mistake, re-weighted run after run.
- **Rules**: 18 rule classes in `core/rl` (JumpIn, CritTrade, AimRule, StepBack...) press keys over the network in real
  duels. Run 30 already found that **the rules lock the network out of the keys that decide hits**.
- **Opponents**: one scripted bot per technique, plus "too-perfect" bots that teach anti-human tricks. Bot wins rose
  while human wins fell (15 Nov).
- **Switches**: `SchoolSession` (8,300 lines) holds 55 on/off switches, and every run added one more.
- **Process**: 30-minute reviews, a 400 KB experiment log, a 210 KB problem list and new rules every day. That work
  went into tuning instead of into finding the literal problem.

The first anchor, `run35`, is still the one the user calls "near perfect on humans". Most of what came after it added
complexity without adding rounds won.

## 3. Target: one network, a simple reward, a league
```
observation (what a player can see) -> one network with memory -> keys + camera
reward = +1 win, -1 loss, + small health-difference term   (fixed)
opponents = 50% latest self, 30% past snapshots, 20% varied bots + replays of real players
evaluation = rounds won against a fixed pool: installed net, run35, 3 past snapshots, the bots
```
- **No rule overlay at play time.** The network presses every key.
- **Human regularizer**: a small KL term toward a policy trained on the recorded real rounds, so it stays good against
  people and does not drift into bot-only tricks.
- **Memory**: a short history of frames or an LSTM, so it can time stuns and the opponent's hit cooldown. That is
  structure, not a reward.
- **Code target**: env (arena, observation, actions), net, PPO, league, eval. Under about 10k lines, against ~60k now.

## 4. The rewrite, step by step (each step keeps current ability)
1. **Clean (done 18 Nov):** 430 unused scripts and ~800 KB of stale docs deleted, one short SOP (`CLAUDE.md`).
2. **Freeze the evaluation:** a fixed opponent pool (above) and a 200-round head-to-head. Baseline = the installed
   network with its rules.
3. **Distill the rules away:** train one network to copy what the installed network *plus its rules* does, on its own
   states (DAgger). When it ties the installed network in the head-to-head, delete every rule class and switch from the
   play path. Same ability, no rules.
4. **Simplify the training:** replace `RewardModel` with the fixed reward and the per-technique bots and stages with
   the league. Start from the distilled network (surgery, so no skill is lost). One long run.
5. **Rewrite the code** around the target in section 3. Delete `school/`, `curriculum/` and the rule classes in `rl/`.
   Keep the Minecraft glue (mixins, arena server, round recorder) and the pure-Java NN and PPO, which work and are
   tested.
6. **After that, only fixes found through CLAUDE.md section 2** (see, do, meet, reward, remember, bug).

Steps 3 and 4 need training. Start them only when the user says to train.
