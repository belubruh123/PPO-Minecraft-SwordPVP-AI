#!/usr/bin/env bash
# pairrun.sh A_NET B_NET LABEL PART SECS [A settings...]: 23 Oct. One paired evaluation in one run (Config.pairedBase):
# frozen as hourlycheck.sh's FROZEN, PART = sp (spacers only) or mix, every fight fought by A (A_NET with the settings
# given) and by B (B_NET, with $BX as Config.pairedBaseSettings) from the same seed (evalSeed $SEED, default 2026102350).
# Waits while the user plays (demo.lock). Writes pair-LABEL-PART.txt (pairsum.py) and the window to pair-windows.txt.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
anet=$1; bnet=$2; label=$3; part=$4; secs=$5; shift 5
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
LOCK=/d/swordai/run/swordai/demo.lock
nodemo() { while [ -f $LOCK ]; do sleep 10; done; }
waitfor() { local left=$1; while [ $left -gt 0 ]; do sleep 10; [ -f $LOCK ] || left=$(( left - 10 )); done; }
sel=$SPACER; [ "$part" = mix ] && sel=$MIX
# The fights lost here are not training's to replay: the list is put back as it was.
cp /d/swordai/run/swordai/lost-cases.json /d/swordai/run/lost-cases-before-pair-$label-$part.json
nodemo
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python setcfg.py $FROZEN $sel evalSeed=${SEED:-2026102350} "pairedBase=$bnet" "pairedBaseSettings=$BX" "$@" > /dev/null
cp "$anet/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
cp "$anet/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
S=$(date +%H:%M:%S); waitfor "$secs"; E=$(date +%H:%M:%S)
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
python setcfg.py pairedBase= pairedBaseSettings= evalSeed=0 > /dev/null
cp /d/swordai/run/lost-cases-before-pair-$label-$part.json /d/swordai/run/swordai/lost-cases.json
python pairsum.py client.log "$S" "$E" > pair-$label-$part.txt
echo "pair-$label-$part.txt $S $E" >> pair-windows.txt
grep -a "paired evaluation:" client.log | tail -1 >> pair-$label-$part.txt
cat pair-$label-$part.txt
