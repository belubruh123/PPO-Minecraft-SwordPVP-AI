#!/usr/bin/env bash
# One frozen comparison run: abrun.sh LABEL SECONDS key=value...  (stops training, sets the keys, restores the network, trains, waits, summarises)
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
label=$1; secs=$2; shift 2
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python setcfg.py "$@" > /dev/null
cp ${CKPT:-/d/swordai/run/kept-v16-final-installed/checkpoint.bin} /d/swordai/run/swordai/checkpoint.bin
printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
S=$(date +%H:%M:%S)
sleep "$secs"
echo "=== $label ($*) from $S"
python ab.py --from "$S" --kinds own,older,trader,RUNNER,CIRCLER,CORNER,NOKB,ODD,JUMPER,PUNISHER,BACKER,TIMER,SPACER,STANDER,WHIFFER,OLD014,OUTSIDE > "ab-$label.txt"
cat "ab-$label.txt"
python reqs2.py client.log "$S" 99:99:99
