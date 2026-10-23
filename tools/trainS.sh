#!/usr/bin/env bash
# trainS.sh [DIR]: SPECIALIST (Distral, Teh et al. 2017; policy distillation, Rusu et al. 2016): branch K (anchor 0.5 to the
# installed network, half-size steps) trained almost only on the two opponents it loses to, the spacer and the jump-crit
# chaser (Crits stage scripted fights 95%, spacer 0.55, jumper 0.35, timer 0.1). The anchor keeps the rest of what it does.
# every update (Config.anchorWeight; Wołczyk et al. 2024, Schmitt et al. 2018), the installed network copied to
# swordai/anchor.bin. Since 20 Oct 23:35 also half-size steps (klStop 0.02, klTarget 0.01, minActorLr 1e-5; policy churn,
# Schaul et al. NeurIPS 2022: update KL was ~0.04 on every update). Every network trained on from the installed one drifted (update KL ~0.04, ~120 updates an hour)
# and measured worse within hours; the anchor is the pull back. One change from A.
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
python setcfg.py actorLr=0.0003 criticLr=0.001 adaptiveLr=true minActorLr=0.00001 klStop=0.02 klTarget=0.01 rehearsalProbability=0.95 \
  swingMinCharge=1.0 ruleSwingCharge=1.0 stepBackRule=false punishInCombo=false lostRule=true hopRule=true straightInRule=true \
  whiffGuard=true hardOpponents=false oldModelShare=0.5 outsideShare=0 answerRule=true answerSpareBacking=false \
  counterCritRule=false anchorWeight=0.5 evalSeed=0 replayLostShare=0 mutateShare=0 silWeight=0 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=1.0 \
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
cp /d/swordai/run/kept-v21-final-1950/checkpoint.bin /d/swordai/run/swordai/anchor.bin
if [ -n "$1" ]; then
  cp "$1/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$1/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
fi
if [ -n "$HEADLESS" ]; then printf 'swordai train headless\r\n' > /d/swordai/run/swordai/inbox.txt; else printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt; fi
sleep 15
grep -a "inbox: /swordai train\|upgrad" client.log | tail -2
