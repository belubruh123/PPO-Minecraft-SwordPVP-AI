#!/usr/bin/env bash
# trainGT9.sh [DIR]: 23 Oct 17:20, T8 plus THE CRIT TRADE (the user's biggest loss: a jumper's crit): Config.holdOnFallingJumper
# (no knock-away swing at a jumper whose crit comes down anyway; the meet rule's walk in kept) and instantPunish (after a
# jumper's crit: the swing kept on the ground until the late knockback lifts it, sprint let go of, W, the punish crit on the
# first tick of the way down), and the JUMP-IN DRILL (Config.jumpInShare 0.9 of the episodes, ~10 ticks each: about a third
# of the steps; the user: 25-50% of training). Drill measured 23 Oct 17:03 on the installed net: +3.16 a fight (se 0.34).
# Frozen candidate runs need CANDX with holdOnFallingJumper=true instantPunish=true (FROZEN pins them false).
# trainGT8.sh [DIR]: 23 Oct, T7 plus the MEMORY (Config.rhythmNet, rl.RhythmNet: a causal TCN, 4 layers of 16, dilations 1/2/4/8,
# over the opponent reader's 3.2 s window, into the trunk and the crit/escape models, zero-init; the user's recorded fights get
# their window made again). Frozen candidate runs need CANDX with critBridge=true rhythmNet=true (FROZEN pins both false).
# trainGT7.sh [DIR]: 23 Oct 11:20, T5 (all the won recorded fights) plus GEMINI'S AUDIT FIXES: Config.critBridge (the crit and escape
# models read the trunk after the blocks/tactic/reader and the reader's numbers, zero-init, read-only), and in the code for
# every run now: the critic told the fight kind for recorded and self-imitation steps, test fights' start heading from the seed.
# Frozen candidate runs need CANDX with critBridge=true (a bridged checkpoint does not load without it; FROZEN pins it false).
# 23 Oct 09:10, the user: "feed ALL of my data" - every recorded step again (demoWindowTicks 0, demoWindowMin 0), the
# critic's comparison alone deciding, as in T5.
# trainGT5b.sh [DIR]: 23 Oct 08:20, T5 with only the recorded steps after which the next 2 s went the user's way
# (Config.demoWindowTicks 40; from 08:40 demoWindowMin 3.5: only the crit combos, a crit or two clean hits in 2 s; the user: "it might learn the part that I lose").
# trainGT5.sh [DIR]: 22 Oct 23:16, T4 plus LEARNING FROM THE USER'S OWN FIGHTS (/swordai demo, run/swordai/demos: spacer fights
# where it attacks them; Config.demoWeight 0.5, demoBatches 4: imitated where they did better than the critic expects).
# trainGT4.sh [DIR]: 22 Oct, T3 plus the two COACH COUNTERS from the fight traces: keepWithin 2.3 (the swing owed for a hit
# is made at once when the opponent is 2.3+ off instead of kept for a crit on the way down that the knockback carries out of
# reach) and jumpAtReadyOnlyFleeing (no chase/hop jump at an opponent standing with its sword ready). Candidate frozen runs
# need CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true".
# trainGT3.sh [DIR]: 21 Oct 22:20, T2 (tactics model + even trades + gated anchor + event choices) plus the OPPONENT READER
# (Config.opponentReader, rl.OpponentReader): the last 3.2 s of the fight (10 numbers per 2-tick sample: distance, in sight,
# moving away/up, both swords, hits either way kept whole, knockback 4 ticks after its last hit held, time in fight) -> 64 ->
# 16 numbers (tanh) -> guess of the opponent kind, taught by cross-entropy on the kind the training knows (own Adam,
# readerLr 1e-3, 2 passes a rollout; replays, drills, people not taught). The 16 numbers go, detached, into the tactics
# model (tactic.opp, zero-init) and the actor trunk (opp.in) and as a FiLM scale before the heads (opp.film), all zero-init.
# Plus tacticPriorWeight 0.3: the tactics model held near its starting plan (KL), after T dropped crit back 45% -> 7% (spacer +58 -> -70).
# Frozen candidate runs need CANDX="tacticModel=true tacticOnEvents=true opponentReader=true".
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python - <<'EOF'
import json
P = 'D:/swordai/run/swordai/config.json'
c = json.load(open(P, encoding='utf-8'))
c['entropyCoef'] = [0.01] * 7
json.dump(c, open(P, 'w', encoding='utf-8', newline=''), indent=2)
EOF
python setcfg.py actorLr=0.00005 criticLr=0.001 adaptiveLr=true minActorLr=0.00001 klStop=0.02 klTarget=0.01 rehearsalProbability=0.6 \
  swingMinCharge=1.0 ruleSwingCharge=1.0 stepBackRule=false punishInCombo=false lostRule=true hopRule=true straightInRule=true \
  whiffGuard=true hardOpponents=true oldModelShare=0.5 outsideShare=0 answerRule=true answerSpareBacking=false \
  counterCritRule=false tacticModel=true tacticOnEvents=true anchorGainWeight=0.05 opponentReader=true readerLr=0.001 readerBatches=16 readerMemory=20000 tacticPriorWeight=0.3 keepWithin=2.3 jumpAtReadyOnlyFleeing=true edgeCritRule=false aimLevel=false tacticLr=0.0003 tacticEntropy=0.01 anchorWeight=0.5 evalSeed=0 replayLostShare=0.5 replayByPotential=false answerNudge=0 taskAdvantageNorm=false mutateShare=0 silWeight=0 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=0.85 \
  Crits.drillShare=0.35 Crits.critDrillShare=0.3 \
  Crits.spacerShare=0.2 Crits.whifferShare=0.08 Crits.timerShare=0.18 Crits.punisherShare=0.1 Crits.jumperShare=0.1 \
  Crits.traderShare=0.04 Crits.runnerShare=0.05 Crits.circlerShare=0.05 Crits.cornerShare=0.03 Crits.noKnockShare=0.03 \
  Crits.oddShare=0.02 Crits.backerShare=0.04 Crits.peopleShare=0 \
  Crits.reward.comboBack=0 Duel.reward.comboBack=0 Self-play.reward.comboBack=0 \
  Crits.reward.spaceIn=0.3 Duel.reward.spaceIn=0.3 Self-play.reward.spaceIn=0.1 \
  Crits.reward.spaceOut=0 Duel.reward.spaceOut=0 Self-play.reward.spaceOut=0 \
  Crits.reward.jumpInReady=0 Duel.reward.jumpInReady=0 Self-play.reward.jumpInReady=0 "Combo escape.reward.jumpInReady=0" \
  Crits.reward.damage=0.15 "Combo escape.reward.damage=0.15" Duel.reward.damage=0.15 Self-play.reward.damage=0.15 \
  Crits.reward.hitTaken=1.5 "Combo escape.reward.hitTaken=1.5" "Sprint-hit + W-tap.reward.hitTaken=1.5" Duel.reward.hitTaken=0.5 Self-play.reward.hitTaken=0.5 \
  Crits.reward.combo=0.4 "Combo escape.reward.combo=0.4" Duel.reward.combo=0.25 Self-play.reward.combo=0.25 \
  Duel.reward.crit=0.9 Self-play.reward.crit=0.9 Duel.reward.punishCrit=0.5 Self-play.reward.punishCrit=0.5 | tail -1
python setcfg.py demoWeight=0.5 demoBatches=4 demoValueWeight=0 demoWindowTicks=0 demoWindowMin=0 comboStrafeRule=false critBridge=true rhythmNet=true holdOnFallingJumper=true instantPunish=true clashJumpRule=false comboJumpRule=false jumpInShare=0.9 jumpInTicks=80 > /dev/null
python filterlost.py
cp /d/swordai/run/kept-v21-final-1950/checkpoint.bin /d/swordai/run/swordai/anchor.bin
if [ -n "$1" ]; then
  cp "$1/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$1/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
fi
if [ -n "$HEADLESS" ]; then printf 'swordai train headless\r\n' > /d/swordai/run/swordai/inbox.txt; else printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt; fi
sleep 15
grep -a "inbox: /swordai train\|upgrad" client.log | tail -2
