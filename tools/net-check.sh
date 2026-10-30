#!/usr/bin/env bash
# net-check.sh NET LABEL: one paired check with the school side = NET (a kept network), no training, against the installed model;
# against the installed model; then training resumes on the network it had.
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
NET="$1"; LABEL="$2"; [ -f "$NET" ] || { echo "no network $NET"; exit 1; }
L=/d/swordai/run/school-client.log
OUT=/d/swordai/run/school-checks.log
INBOX=/d/swordai/run/swordai/inbox.txt
SCH=/d/swordai/run/swordai/school
PAIRS=${PAIRS:-23}
send() { printf '%s\r\n' "$1" > $INBOX; }
stopped() { for i in $(seq 1 120); do [ $(grep -ac "Stopped (" $L) -gt $1 ] && return 0; sleep 1; done; return 1; }
n=$(grep -ac "Stopped (" $L); send "swordai stop"; stopped $n || { echo "stop did not take"; exit 1; }
sleep 2
KEEP="$SCH/training-kept-$(date +%H%M).bin"
cp -p $SCH/checkpoint.bin "$KEEP" && cp -p "$NET" $SCH/checkpoint.bin
start=$(wc -l < $L)
send "swordai school check"
until [ $(tail -n +$start $L | grep -ac "school pair") -ge $((PAIRS * 2)) ]; do
  sleep 10
  grep -aq "Stopped because" <(tail -n +$start $L) && break
done
n=$(grep -ac "Stopped (" $L); send "swordai stop"; stopped $n
sleep 2
rm -f $SCH/checkpoint.bin
cp -p "$KEEP" $SCH/checkpoint.bin
cmp "$KEEP" $SCH/checkpoint.bin && echo "training network restored"
{
  echo "=== $(date '+%m-%d %H:%M') NET check: school side = $(basename "$NET"), no training: $LABEL"
  tail -n +$start $L > "$S/check-part.log"
  python "$S/schoolpairs.py" "$S/check-part.log"
  grep -a "school check after" "$S/check-part.log" | tail -1 | sed -E 's/.*school check after/after/; s/ \| /\n  /g'
} >> $OUT
send "swordai school"
grep '^===' $OUT | tail -1
