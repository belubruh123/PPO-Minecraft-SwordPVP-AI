#!/usr/bin/env bash
# hourlycheck4.sh TRAIN_SCRIPT [TRAIN_MINUTES=40]: 23 Oct. hourlycheck3.sh with PAIRED checks (Config.pairedBase): each
# hour the checked network (A, its settings CANDX) and the installed one (B, the installed settings BX) fight the same fights
# from the same seeds in the SAME run, fight for fight, the order turning each pair: spacers only 10 min, the mix 14 min;
# then the kept lost fights 4 min (A alone). Verdict by hourlysum3.py from the paired differences (two standard errors),
# not against an average of the installed network's earlier runs. Two WORSE hours running: training goes on from the
# best snapshot at least EQUAL, else the installed network. Waits while the user plays (demo.lock). Never headless.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
train=$1
mins=${2:-40}
INST=/d/swordai/run/kept-v21-final-1950
BX="keepWithin=0 jumpAtReadyOnlyFleeing=false"
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
LOST=$(grep '^LOST=' hourlycheck.sh | sed 's/^LOST="\(.*\)"$/\1/')
LOCK=/d/swordai/run/swordai/demo.lock
nodemo() { while [ -f $LOCK ]; do sleep 10; done; }
waitfor() { local left=$1; while [ $left -gt 0 ]; do sleep 10; [ -f $LOCK ] || left=$(( left - 10 )); done; }
run() {
  # run OUT NET SECS PAIRED settings...: one frozen run; PAIRED=1 has the installed network fight every fight too.
  local out=$1 net=$2 secs=$3 pair=$4 S E; shift 4
  nodemo
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  if [ "$pair" = 1 ]; then
    python setcfg.py $FROZEN "$@" "pairedBase=$INST" "pairedBaseSettings=$BX" > /dev/null
  else
    python setcfg.py $FROZEN "$@" pairedBase= pairedBaseSettings= > /dev/null
  fi
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  cp "$net/lost-cases.json" /d/swordai/run/swordai/lost-cases.json 2>/dev/null
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S); waitfor "$secs"; E=$(date +%H:%M:%S)
  if [ "$pair" = 1 ]; then
    python pairsum.py client.log "$S" "$E" > "$out"
    echo "$out $S $E" >> pair-windows.txt
  else
    python report.py client.log "$S" "$E" > "$out"
  fi
}
while true; do
  waitfor $(( mins * 60 ))
  nodemo
  label=$(date +%H%M)
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  net=/d/swordai/run/kept-v24-$label
  mkdir -p $net && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $net/
  echo "=== $(date +%H:%M:%S) paired check of $net with $train"
  run "pair-$label-sp.txt" $net 600 1 $SPACER $CANDX
  run "pair-$label-mix.txt" $net 840 1 $MIX $CANDX
  run "hc-$label-lost.txt" $net 240 0 $LOST $CANDX
  nodemo
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  python setcfg.py pairedBase= pairedBaseSettings= > /dev/null
  cp $net/lost-cases.json /d/swordai/run/swordai/lost-cases.json
  echo "$train" > hc-$label-branch.txt
  python hourlysum3.py $label
  python setcfg.py hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
  from=$net
  if [ "$(cat hourly-state.txt 2>/dev/null || echo 0)" -ge 2 ]; then
    best=$(sort -k2 -n -r hourly-scores.txt | head -1)
    bl=${best%% *}; bs=${best#* }
    if python -c "import sys; sys.exit(0 if float('$bs') >= -50 else 1)" && [ -d /d/swordai/run/kept-v24-$bl ]; then from=/d/swordai/run/kept-v24-$bl; else from=$INST; fi
    echo "$label | ROLLED BACK: training goes on from $from" | tee -a hourly-check.log
    echo 0 > hourly-state.txt
  fi
  nodemo
  bash $train $from > /dev/null
  echo "=== $(date +%H:%M:%S) training again with $train from $from"
done
