#!/usr/bin/env bash
# trainB.sh [DIR]: branch B = branch A (trainA.sh) plus the user's method: half the scripted fights are exact 1-1 replays of
# kept lost fights (replayLostShare 0.5), the no-knockback ones set aside first (filterlost.py). One change from A.
# run of 19 Oct) and none of what came after: no lost-fight replay, no mutants, no self-imitation, no jump-in reward,
# no spacing cost, hits taken costing what they did (Crits 2.0, Duel/Self-play 0.8), damage 0.15, entropy 0.01.
# The final eval of 20 Oct found every network trained since lost-fight replay began worse against the spacer than
# the installed one (+88 net spacer-only vs -49/-69). HEADLESS=1 trains without the screen.
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
python setcfg.py actorLr=0.0003 criticLr=0.001 adaptiveLr=true minActorLr=0.00003 klStop=0.04 klTarget=0.015 rehearsalProbability=0.6 \
  swingMinCharge=1.0 ruleSwingCharge=1.0 stepBackRule=false punishInCombo=false lostRule=true hopRule=true straightInRule=true \
  whiffGuard=true hardOpponents=true oldModelShare=0.5 outsideShare=0 answerRule=true answerSpareBacking=false \
  counterCritRule=false anchorWeight=0 evalSeed=0 replayLostShare=0.5 mutateShare=0 silWeight=0 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=0.85 \
  Crits.drillShare=0.35 Crits.critDrillShare=0.3 \
  Crits.spacerShare=0.2 Crits.whifferShare=0.08 Crits.timerShare=0.18 Crits.punisherShare=0.1 Crits.jumperShare=0.1 \
  Crits.traderShare=0.04 Crits.runnerShare=0.05 Crits.circlerShare=0.05 Crits.cornerShare=0.03 Crits.noKnockShare=0.03 \
  Crits.oddShare=0.02 Crits.backerShare=0.04 Crits.peopleShare=0 \
  Crits.reward.comboBack=0 Duel.reward.comboBack=0 Self-play.reward.comboBack=0 \
  Crits.reward.spaceIn=0.3 Duel.reward.spaceIn=0.3 Self-play.reward.spaceIn=0.1 \
  Crits.reward.spaceOut=0 Duel.reward.spaceOut=0 Self-play.reward.spaceOut=0 \
  Crits.reward.jumpInReady=0 Duel.reward.jumpInReady=0 Self-play.reward.jumpInReady=0 "Combo escape.reward.jumpInReady=0" \
  Crits.reward.damage=0.15 "Combo escape.reward.damage=0.15" Duel.reward.damage=0.15 Self-play.reward.damage=0.15 \
  Crits.reward.hitTaken=2.0 "Combo escape.reward.hitTaken=2.0" Duel.reward.hitTaken=0.8 Self-play.reward.hitTaken=0.8 \
  Crits.reward.combo=0.4 "Combo escape.reward.combo=0.4" Duel.reward.combo=0.25 Self-play.reward.combo=0.25 \
  Duel.reward.crit=0.9 Self-play.reward.crit=0.9 Duel.reward.punishCrit=0.5 Self-play.reward.punishCrit=0.5 | tail -1
python filterlost.py
if [ -n "$1" ]; then
  cp "$1/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$1/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
fi
if [ -n "$HEADLESS" ]; then printf 'swordai train headless\r\n' > /d/swordai/run/swordai/inbox.txt; else printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt; fi
sleep 15
grep -a "inbox: /swordai train\|upgrad" client.log | tail -2
