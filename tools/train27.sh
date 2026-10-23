#!/usr/bin/env bash
# train27.sh [DIR]: train26 with the reward paid on damage (0.4 a health point) and a hit taken costing what a hit dealt earns (20 Oct 07:55; it was 2x, which taught it to avoid trades). train26.sh [DIR]: train25 + self-imitation (silWeight 0.5) + mutated lost fights (mutateShare 0.5), 20 Oct 07:05. train25.sh [DIR]: lost fights replayed exactly from their seed, 80% of scripted fights (20 Oct 07:05, the user: 1-1 from start, no fighting won cases). train24.sh [DIR]: as train22 with replays back to 0.5 and the 200-fight list with never-won fights parked (20 Oct 06:55). train22.sh [DIR]: as train21 with 70% of the scripted fights replays of lost ones (20 Oct 02:35). The v21 run from 19 Oct 07:00 (build v68). As train20 (normal Carpet, no outside bot), plus: crit back in
# the opponent's cooldown (counterCritRule), a jump-crit coming in met while still thrown back (meetWhenComing), 85% of the
# scripted rehearsal on the hard kinds (selfCritShare), damage traded paid in the crit and combo-escape stages, combos and
# crits paid more. Without DIR it goes on from the network the client saved at its last stop.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python setcfg.py actorLr=0.0003 criticLr=0.001 adaptiveLr=true minActorLr=0.00003 rehearsalProbability=0.6 \
  swingMinCharge=1.0 ruleSwingCharge=1.0 stepBackRule=false punishInCombo=false lostRule=true hopRule=true straightInRule=true \
  whiffGuard=true hardOpponents=true oldModelShare=0.5 outsideShare=0 \
  counterCritRule=false replayLostShare=0.8 silWeight=0.5 silBatches=8 silKeepZ=1.5 mutateShare=0.5 meetWhenComing=true dodgeJumpRule=false edgeHoldRule=false selfCritShare=0.85 \
  Crits.spacerShare=0.2 Crits.whifferShare=0.08 Crits.timerShare=0.18 Crits.punisherShare=0.1 Crits.jumperShare=0.1 \
  Crits.traderShare=0.04 Crits.runnerShare=0.05 Crits.circlerShare=0.05 Crits.cornerShare=0.03 Crits.noKnockShare=0.03 \
  Crits.oddShare=0.02 Crits.backerShare=0.04 Crits.peopleShare=0 \
  Crits.reward.comboBack=0 Duel.reward.comboBack=0 Self-play.reward.comboBack=0 \
  Crits.reward.spaceIn=0.3 Duel.reward.spaceIn=0.3 Self-play.reward.spaceIn=0.1 Crits.reward.spaceOut=0 Duel.reward.spaceOut=0 Self-play.reward.spaceOut=0 \
  Crits.reward.damage=0.4 "Combo escape.reward.damage=0.4" Duel.reward.damage=0.4 Self-play.reward.damage=0.4 Crits.reward.hitTaken=1.0 "Combo escape.reward.hitTaken=1.0" Duel.reward.hitTaken=0.3 Self-play.reward.hitTaken=0.3 \
  Crits.reward.combo=0.4 "Combo escape.reward.combo=0.4" Duel.reward.combo=0.25 Self-play.reward.combo=0.25 \
  Duel.reward.crit=0.9 Self-play.reward.crit=0.9 Duel.reward.punishCrit=0.5 Self-play.reward.punishCrit=0.5 | tail -1
if [ -n "$1" ]; then
  cp "$1/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$1/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
fi
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 15
grep -a "inbox: /swordai train\|Resumed\|resumed\|upgrad" client.log | tail -3
