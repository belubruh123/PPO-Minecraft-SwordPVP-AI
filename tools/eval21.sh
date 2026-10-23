#!/usr/bin/env bash
# The 21:00 check: waits for the snapshot, stops training and keeps the network, compares v19 with the
# installed v18 at the online swing settings (mostly against the 0.14.0 model), then resumes training.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
until ls -d /d/swordai/run/kept-v19-21* >/dev/null 2>&1; do sleep 30; done
sleep 5
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
d=/d/swordai/run/kept-v19-stop-$(date +%H%M)
mkdir -p "$d"
cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties "$d"/
echo "kept $d"
EV="actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 rehearsalProbability=0.6 swingMinCharge=0.96 ruleSwingCharge=1.0 oldModelShare=0.8"
CKPT=$d/checkpoint.bin bash abrun.sh F1-v19-$(basename "$d") 1200 $EV
python progress.py client.log "$(grep -a 'inbox: /swordai train' client.log | tail -1 | cut -c2-9)" | tail -1
CKPT=/d/swordai/run/kept-v18-1648/checkpoint.bin bash abrun.sh F2-v18-1648 1200 $EV
python progress.py client.log "$(grep -a 'inbox: /swordai train' client.log | tail -1 | cut -c2-9)" | tail -1
bash resume19.sh "$d"
