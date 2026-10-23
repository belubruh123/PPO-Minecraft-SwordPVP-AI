#!/usr/bin/env bash
# hourlycheck2.sh TRAIN_SCRIPT [TRAIN_MINUTES=40] [HOURS_BEFORE_SWITCH] [NEXT_SCRIPT]: the user's hourly check (20 Oct 20:45)
# with a control: each hour, after the training network's frozen first-person runs (spacers only 7 min, the mix 9 min,
# kept lost fights 4 min), the INSTALLED network is measured too, the mix and the spacer-only run in turn, and the
# verdict (hourlysum2.py) goes against the mean of all its measurements, +-50. (20 Oct 21:58: the installed network
# measured +88 at 18:06 and -9 at 21:51 spacer-only; one run against one baseline was noise.) Two WORSE hours running:
# training goes on from the best snapshot at least EQUAL, else the installed network. After HOURS_BEFORE_SWITCH lines,
# training switches to NEXT_SCRIPT, from the installed network (branch B after branch A). Never headless.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
train=$1
mins=${2:-40}
switch=${3:-0}
next=$4
INST=/d/swordai/run/kept-v21-final-1950
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
LOST=$(grep '^LOST=' hourlycheck.sh | sed 's/^LOST="\(.*\)"$/\1/')
frozen() {
  # local: the control run passes the installed network, and the loop's own $net must survive it (until 21 Oct 02:10
  # it did not, and training went on from the installed network instead of the one just checked).
  local out=$1 net=$2 secs=$3 S E; shift 3
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  cp "$net/lost-cases.json" /d/swordai/run/swordai/lost-cases.json 2>/dev/null || cp "$LOSTLIST" /d/swordai/run/swordai/lost-cases.json
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  python report.py client.log "$S" "$E" > "$out"
}
hours=0
while true; do
  sleep $(( mins * 60 ))
  label=$(date +%H%M)
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  net=/d/swordai/run/kept-v24-$label
  mkdir -p $net && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $net/
  LOSTLIST=$net/lost-cases.json
  echo "=== $(date +%H:%M:%S) checking $net with $train"
  # 21 Oct 14:40: the network measured second does ~13 damage a fight better (orderab.sh), and the control always ran
  # last. Now it runs first in two hours of four and last in the other two, mix and spacer-only in turn.
  n=$(cat hc-count.txt 2>/dev/null || echo 0); echo $(( n + 1 )) > hc-count.txt
  control() { if [ $(( n % 2 )) -eq 0 ]; then frozen "hc-$label-inst-mix.txt" $INST 540 $MIX; else frozen "hc-$label-inst-sp.txt" $INST 420 $SPACER; fi; }
  if [ $(( n % 4 )) -lt 2 ]; then control; echo "first" > hc-$label-order.txt; else echo "last" > hc-$label-order.txt; fi
  frozen "hc-$label-sp.txt" $net 420 $SPACER $CANDX
  frozen "hc-$label-mix.txt" $net 540 $MIX $CANDX
  frozen "hc-$label-lost.txt" $net 240 $LOST $CANDX
  if [ $(( n % 4 )) -ge 2 ]; then control; fi
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  cp $net/lost-cases.json /d/swordai/run/swordai/lost-cases.json
  echo "$train" > hc-$label-branch.txt
  python hourlysum2.py $label
  python setcfg.py hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
  from=$net
  hours=$(( hours + 1 ))
  if [ "$switch" -gt 0 ] && [ "$hours" -eq "$switch" ] && [ -n "$next" ]; then
    train=$next; from=$INST
    echo "$label | SWITCH: training now $train from the installed network" | tee -a hourly-check.log
    echo 0 > hourly-state.txt
  elif [ "$(cat hourly-state.txt 2>/dev/null || echo 0)" -ge 2 ]; then
    best=$(sort -k2 -n -r hourly-scores.txt | head -1)
    bl=${best%% *}; bs=${best#* }
    if python -c "import sys; sys.exit(0 if float('$bs') >= -50 else 1)" && [ -d /d/swordai/run/kept-v24-$bl ]; then from=/d/swordai/run/kept-v24-$bl; else from=$INST; fi
    echo "$label | ROLLED BACK: training goes on from $from" | tee -a hourly-check.log
    echo 0 > hourly-state.txt
  fi
  bash $train $from > /dev/null
  echo "=== $(date +%H:%M:%S) training again with $train from $from"
done
