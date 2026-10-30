#!/usr/bin/env bash
# schoolcycle.sh: the technique school's hourly loop. Training runs (started already); every TRAIN_MIN minutes it stops,
# plays the paired check (PAIRS pairs: A = the school's network, B = the installed model as it plays the user's fights),
# appends the table and the technique summary to D:/swordai/run/school-checks.log, keeps a copy of that checkpoint, and
# goes back to training. Stop it by creating D:/swordai/run/school-cycle.stop.
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
L=/d/swordai/run/school-client.log
OUT=/d/swordai/run/school-checks.log
INBOX=/d/swordai/run/swordai/inbox.txt
TRAIN_MIN=${TRAIN_MIN:-45}
WAIT_MIN=${FIRST_MIN:-$TRAIN_MIN}
PAIRS=${PAIRS:-44}
send() { printf '%s\r\n' "$1" > $INBOX; }
stopped() { # waits for the session to stop after a stop command; $1 = count of "Stopped" lines before
  for i in $(seq 1 120); do [ $(grep -ac "Stopped (" $L) -gt $1 ] && return 0; sleep 1; done; return 1; }
while [ ! -f /d/swordai/run/school-cycle.stop ]; do
  for m in $(seq 1 $WAIT_MIN); do
    [ -f /d/swordai/run/school-cycle.stop ] && exit 0
    sleep 60
  done
  n=$(grep -ac "Stopped (" $L); send "swordai stop"; stopped $n || { echo "$(date) stop did not take" >> $OUT; continue; }
  sleep 2
  cp /d/swordai/run/swordai/school/checkpoint.bin "/d/swordai/run/swordai/school/checked-$(date +%m%d-%H%M).bin"
  start=$(wc -l < $L)
  send "swordai school check"
  until [ $(tail -n +$start $L | grep -ac "school pair") -ge $((PAIRS * 2)) ]; do
    sleep 10
    grep -aq "Stopped because" <(tail -n +$start $L) && break
  done
  n=$(grep -ac "Stopped (" $L); send "swordai stop"; stopped $n
  sleep 2
  {
    echo "=== $(date '+%m-%d %H:%M') check at $(grep -a 'Checking the technique school' $L | tail -1 | sed -E 's/.*school at //')"
    tail -n +$start $L > "$S/check-part.log"
    python "$S/schoolpairs.py" "$S/check-part.log"
    grep -a "school check after" "$S/check-part.log" | tail -1 | sed -E 's/.*school check after/after/; s/ \| /\n  /g'
  } >> $OUT
  send "swordai school"
  WAIT_MIN=$TRAIN_MIN
done
