#!/usr/bin/env bash
# resume19.sh DIR: goes back to training v19 from the network kept in DIR, with the training settings of train19.sh, but at the online swing timing (from 18 Oct 22:00).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
src=$1
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python setcfg.py actorLr=0.0003 criticLr=0.001 adaptiveLr=true minActorLr=0.00003 rehearsalProbability=0.6 \
  swingMinCharge=0.96 ruleSwingCharge=1.0 oldModelShare=0.3 | tail -1
cp "$src/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
cp "$src/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 15
grep -a "inbox: /swordai train\|Resumed\|resumed" client.log | tail -2
