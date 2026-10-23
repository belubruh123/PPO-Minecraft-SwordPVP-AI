#!/usr/bin/env bash
# instcheck.sh LABEL [SECS=420]: control for the hourly check: the INSTALLED network (kept-v21-final-1950), spacer-only,
# frozen, first person, under the same settings as hourlycheck.sh's spacer run, so its baseline is measured again
# in the same conditions (it was measured once, 20 Oct 18:06). Then training goes on (trainA.sh) from the network
# that was training. Output: inst-LABEL-sp.txt.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
label=$1; secs=${2:-420}; train=${3:-trainA.sh}
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
cur=/d/swordai/run/kept-v24-T-$(date +%H%M)
mkdir -p $cur && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $cur/
python setcfg.py $FROZEN $SPACER > /dev/null
cp /d/swordai/run/kept-v21-final-1950/checkpoint.bin /d/swordai/run/swordai/checkpoint.bin
cp /d/swordai/run/kept-v21-final-1950/curriculum.properties /d/swordai/run/swordai/curriculum.properties
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
S=$(date +%H:%M:%S); sleep "$secs"; E=$(date +%H:%M:%S)
python report.py client.log "$S" "$E" > inst-$label-sp.txt
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
cp /d/swordai/run/swordai/lost-cases.json $cur/lost-cases-after-check.json
cp $cur/lost-cases.json /d/swordai/run/swordai/lost-cases.json
python setcfg.py hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
bash $train $cur > /dev/null
echo "=== installed spacer-only $S-$E; training again $(date +%H:%M:%S) from $cur"
head -4 inst-$label-sp.txt
