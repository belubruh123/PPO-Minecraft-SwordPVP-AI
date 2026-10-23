#!/usr/bin/env bash
# trainGR.sh: G + the answer rule as a push of 8 on the swing logit (answerNudge, Residual Policy Learning) instead of pressing.
# trainG.sh [DIR]: 21 Oct 07:40, branch G = B+K (trainBK.sh: lost-fight replays at half, anchor 0.5 to the installed net, half-size
# steps) on the GROWN network of the 21 Oct build: actor +64 wide vector units and 2 residual blocks of 256 (SimBa, zero-init
# outputs so it starts exactly as the net it is loaded from), critic told the fight kind + 1 block. Growth comes from the
# Config defaults (actorVecWide 64, actorBlocks 2, actorBlockWidth 256, criticTask, criticBlocks 1).
# 21 Oct 07:50: actorLr starts at 5e-5, not 3e-4. Adaptive LR is not saved, so every restart (each hour after the checks) began at 3e-4
# and the first updates jolted the policy (BK: anchor KL 0.05-0.09 at each restart; G at 07:40: 0.31 with fresh Adam state on the new
# layers). The adaptive rate settled at ~4e-5 every time anyway, and it can still rise x1.5 per update when the KL is small.
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
  counterCritRule=false anchorWeight=0.5 evalSeed=0 replayLostShare=0.5 replayByPotential=false answerNudge=8 taskAdvantageNorm=false mutateShare=0 silWeight=0 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=0.85 \
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
cp /d/swordai/run/kept-v21-final-1950/checkpoint.bin /d/swordai/run/swordai/anchor.bin
if [ -n "$1" ]; then
  cp "$1/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$1/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
fi
if [ -n "$HEADLESS" ]; then printf 'swordai train headless\r\n' > /d/swordai/run/swordai/inbox.txt; else printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt; fi
sleep 15
grep -a "inbox: /swordai train\|upgrad" client.log | tail -2
