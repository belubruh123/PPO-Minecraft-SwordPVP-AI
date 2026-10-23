#!/usr/bin/env bash
# After eval21.sh: the 0.14.0 network itself, with 0.14.0's swing timing and rules, in the same mix as F1/F2.
# Its fights against the 0.14.0 model should come out about even (it is the same fighter): a check of the test.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
until [ -f ab-F2-v18-1648.txt ]; do sleep 30; done
sleep 60
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
d=/d/swordai/run/kept-v19-stop-$(date +%H%M)
mkdir -p "$d"
cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties "$d"/
echo "kept $d"
EV="actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 rehearsalProbability=0.6 oldModelShare=0.8 swingMinCharge=1.0 ruleSwingCharge=1.0 punishInCombo=true lostRule=false straightInRule=false hopRule=false"
CKPT=/d/swordai/run/kept-v16-final-installed/checkpoint.bin bash abrun.sh F3-v16-as-0.14.0 1200 $EV
python progress.py client.log "$(grep -a 'inbox: /swordai train' client.log | tail -1 | cut -c2-9)" | tail -1
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
python setcfg.py punishInCombo=false lostRule=true straightInRule=true hopRule=true | tail -1
echo "stopped; resume with: bash resume19.sh $d"
