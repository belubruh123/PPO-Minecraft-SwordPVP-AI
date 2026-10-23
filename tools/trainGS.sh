#!/usr/bin/env bash
# trainGS.sh [DIR]: 21 Oct 10:30, SPECIALIST on the grown network (branch G): trainS.sh (Crits scripted fights 95%: spacer 0.55,
# jumper 0.35, timer 0.1; anchor 0.5 to the installed net) with the LR-restart fix (actorLr starts at 5e-5) and a third of
# the scripted fights replays of lost fights (the user: replay lost fights 1-1). Why: hourly checks cannot see small general
# gains (one 7-min spacer run varies by ~40 net/min for the same network), so aim at what loses (spacer, jump-crit chaser)
# for an effect big enough to measure; the mix check catches what it costs elsewhere. Then distil or keep as candidate.
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
python setcfg.py actorLr=0.00005 criticLr=0.001 adaptiveLr=true minActorLr=0.00001 klStop=0.02 klTarget=0.01 rehearsalProbability=0.95 \
  swingMinCharge=1.0 ruleSwingCharge=1.0 stepBackRule=false punishInCombo=false lostRule=true hopRule=true straightInRule=true \
  whiffGuard=true hardOpponents=false oldModelShare=0.5 outsideShare=0 answerRule=true answerSpareBacking=false \
  counterCritRule=false anchorWeight=0.5 evalSeed=0 replayLostShare=0.3 replayByPotential=false answerNudge=0 taskAdvantageNorm=false mutateShare=0 silWeight=0 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=1.0 \
  Crits.drillShare=0 Crits.critDrillShare=0 \
  Crits.spacerShare=0.55 Crits.whifferShare=0 Crits.timerShare=0.1 Crits.punisherShare=0 Crits.jumperShare=0.35 \
  Crits.traderShare=0 Crits.runnerShare=0 Crits.circlerShare=0 Crits.cornerShare=0 Crits.noKnockShare=0 \
  Crits.oddShare=0 Crits.backerShare=0 Crits.peopleShare=0 \
  Crits.reward.comboBack=0 Duel.reward.comboBack=0 Self-play.reward.comboBack=0 \
  Crits.reward.spaceIn=0.3 Duel.reward.spaceIn=0.3 Self-play.reward.spaceIn=0.1 \
  Crits.reward.spaceOut=0 Duel.reward.spaceOut=0 Self-play.reward.spaceOut=0 \
  Crits.reward.jumpInReady=0 Duel.reward.jumpInReady=0 Self-play.reward.jumpInReady=0 "Combo escape.reward.jumpInReady=0" \
  Crits.reward.damage=0.15 "Combo escape.reward.damage=0.15" Duel.reward.damage=0.15 Self-play.reward.damage=0.15 \
  Crits.reward.hitTaken=2.0 "Combo escape.reward.hitTaken=2.0" Duel.reward.hitTaken=0.8 Self-play.reward.hitTaken=0.8 \
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
