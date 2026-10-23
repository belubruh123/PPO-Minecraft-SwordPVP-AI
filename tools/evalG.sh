#!/usr/bin/env bash
# The morning benchmark, 19 Oct: at 04:55 stops training and keeps the network, then the same fixed test
# for three versions, 1200 s each, online swing timing, learning off: G1 the v20 network, G2 the v19 one
# installed in the profile (both under 0.15.1's rules), G3 0.14.0 as it played. The expert outside bot
# (Carpet PvP) is in the mix as a judge not written here. Then training resumes.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
while true; do now=$((10#$(date +%H%M))); (( now >= 455 && now < 1200 )) && break; sleep 30; done
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
d=/d/swordai/run/kept-v20-stop-$(date +%H%M)
mkdir -p "$d"
cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties "$d"/
echo "kept $d"
BASE="actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 rehearsalProbability=0.6 oldModelShare=0.8 swingMinCharge=1.0 ruleSwingCharge=1.0 hardOpponents=false outsideShare=0.25 outsideLevels=expert"
NEW="$BASE whiffGuard=true punishInCombo=false lostRule=true straightInRule=true hopRule=true"
OLD="$BASE whiffGuard=false punishInCombo=true lostRule=false straightInRule=false hopRule=false"
last() { python progress.py client.log "$(grep -a 'inbox: /swordai train' client.log | tail -1 | cut -c2-9)" | tail -1; }
CKPT=$d/checkpoint.bin bash abrun.sh G1-v20-$(basename "$d") 1200 $NEW; last
CKPT=/d/swordai/run/kept-v19-stop-2100/checkpoint.bin bash abrun.sh G2-v19-installed 1200 $NEW; last
CKPT=/d/swordai/run/kept-v16-final-installed/checkpoint.bin bash abrun.sh G3-0.14.0 1200 $OLD; last
bash train20.sh "$d"
echo "resumed training from $d"
