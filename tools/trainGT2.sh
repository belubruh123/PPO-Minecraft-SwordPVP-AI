#!/usr/bin/env bash
# trainGT2.sh [DIR]: 21 Oct 21:40, T (tactics model, trainGT.sh) plus three changes from the reviews the user passed on:
# (1) even trades score even: hitTaken 2.0 -> 1.5 in the bot stages (= hit 1.0 + sprintHit 0.5 for a hit landed) and
#     0.8 -> 0.5 in Duel/Self-play (= 0.3 + 0.2); the damage term (0.15 a point, dealt minus taken) was already even.
#     Before, an even sprint-hit exchange scored -0.5 in training while the checks score net damage.
# (2) advantage-gated anchor: anchorGainWeight 0.05 = on steps that did better than the critic expected, the pull
#     to the installed network is 0.05 instead of 0.5 (a floor, not a ceiling).
# (3) tacticOnEvents (Config default true, in the 21:40 build): the tactics model also chooses right after a hit either
#     way, an opponent's swing and an opponent's jump (at least 3 ticks apart), not only every half second.
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
  counterCritRule=false opponentReader=false tacticPriorWeight=0 tacticModel=true tacticOnEvents=true anchorGainWeight=0.05 tacticLr=0.0003 tacticEntropy=0.01 anchorWeight=0.5 evalSeed=0 replayLostShare=0.5 replayByPotential=false answerNudge=0 taskAdvantageNorm=false mutateShare=0 silWeight=0 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=0.85 \
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
python filterlost.py
cp /d/swordai/run/kept-v21-final-1950/checkpoint.bin /d/swordai/run/swordai/anchor.bin
if [ -n "$1" ]; then
  cp "$1/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$1/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
fi
if [ -n "$HEADLESS" ]; then printf 'swordai train headless\r\n' > /d/swordai/run/swordai/inbox.txt; else printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt; fi
sleep 15
grep -a "inbox: /swordai train\|upgrad" client.log | tail -2
