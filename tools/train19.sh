#!/usr/bin/env bash
# Starts the v19 training run in the dev client: from the v18 training checkpoint, with the opponents at
# the edge of the reach, the friend's timing and the whiffer weighted up. Run with the client in the world.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python setcfg.py actorLr=0.0003 criticLr=0.001 adaptiveLr=true minActorLr=0.00003 rehearsalProbability=0.6 \
  swingMinCharge=0.88 ruleSwingCharge=0.95 stepBackRule=false punishInCombo=false lostRule=true hopRule=true straightInRule=true \
  Crits.spacerShare=0.2 Crits.whifferShare=0.08 Crits.timerShare=0.18 Crits.punisherShare=0.1 Crits.jumperShare=0.1 \
  Crits.traderShare=0.04 Crits.runnerShare=0.05 Crits.circlerShare=0.05 Crits.cornerShare=0.03 Crits.noKnockShare=0.03 \
  Crits.oddShare=0.02 Crits.backerShare=0.04 Crits.peopleShare=0 \
  Crits.reward.comboBack=0 Duel.reward.comboBack=0 Self-play.reward.comboBack=0 | tail -1
cp /d/swordai/run/kept-v18-stop-1649/checkpoint.bin /d/swordai/run/swordai/checkpoint.bin
cp /d/swordai/run/kept-v18-stop-1649/curriculum.properties /d/swordai/run/swordai/curriculum.properties
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 15
grep -a "inbox: /swordai train\|Resumed\|resumed\|upgrad" client.log | tail -3
