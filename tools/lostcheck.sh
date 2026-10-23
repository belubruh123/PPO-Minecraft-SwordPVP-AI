#!/usr/bin/env bash
# lostcheck.sh SECS DIR: first person, on screen, frozen: every scripted fight is one of the kept lost fights, set up
# exactly (seed, start, bot choices); counts how many it wins now, by kind. The list is restored afterwards, so the
# check changes nothing. Then training again on screen (trainA.sh, branch A) from the network that was training.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
secs=$1; net=$2
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
cur=/d/swordai/run/kept-v24-A-$(date +%H%M)
mkdir -p $cur && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $cur/
python setcfg.py actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 silWeight=0 mutateShare=0 replayLostShare=1.0 \
  hardOpponents=false oldModelShare=0 rehearsalProbability=0.95 selfCritShare=1.0 Crits.drillShare=0 Crits.critDrillShare=0 > /dev/null
cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
S=$(date +%H:%M:%S)
sleep "$secs"
E=$(date +%H:%M:%S)
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
echo "=== lost fights in first person: $net, $S-$E"
python report.py client.log "$S" "$E" | grep -a "updates\|replays of lost"
awk -v s="$S" -v e="$E" 'substr($0,2,8) >= s && substr($0,2,8) <= e' client.log | grep -a "lost fights: " \
  | grep -o "replayed [0-9]* of which won [0-9]*, beaten for good [0-9]*" | awk '{r+=$2; w+=$6; b+=$10} END {printf "replayed %d, won %d (%.0f%%), beaten for good %d\n", r, w, 100*w/(r>0?r:1), b}'
cp /d/swordai/run/swordai/lost-cases.json $cur/lost-cases-after-check.json
cp $cur/lost-cases.json /d/swordai/run/swordai/lost-cases.json
bash trainA.sh $cur
echo "=== training again $(date +%H:%M:%S)"
