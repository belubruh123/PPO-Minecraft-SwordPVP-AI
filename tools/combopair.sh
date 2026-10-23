#!/usr/bin/env bash
# combopair.sh NET LABEL [BLOCKS=3]: 23 Oct 09:50. The combo-escape rule (Config.comboStrafeRule, rl.ComboStrafe: from the
# 2nd hit of a combo it takes, A or D for half a second away from the opponent's way across) ON against OFF, the same
# network NET, frozen, first person. finalpair2.sh's design: BLOCKS blocks, each its own fight set (evalSeed 2026102300+k)
# that both sides fight, the order alternating block by block (odd blocks: rule on first); spacers only 6 min and the mix
# 8 min a side (no separate 0.14.0 part: the mix has it half the time). Waits while the user plays (demo.lock).
# Writes fp2-LABEL-bK-{cand,inst}-{sp,mix}.txt (cand = rule on); fp2sum.py and combowin.py to final-pair.log.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
net=$1; label=$2; blocks=${3:-3}
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
LOCK=/d/swordai/run/swordai/demo.lock
nodemo() { while [ -f $LOCK ]; do sleep 10; done; }
waitfor() { local left=$1; while [ $left -gt 0 ]; do sleep 10; [ -f $LOCK ] || left=$(( left - 10 )); done; }
frozen() {
  local out=$1 secs=$2 S E; shift 2
  nodemo
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S); waitfor "$secs"; E=$(date +%H:%M:%S)
  python report.py client.log "$S" "$E" > "$out"
  echo "$out $S $E" >> fp2-$label-windows.txt
}
cp /d/swordai/run/swordai/lost-cases.json /d/swordai/run/lost-cases-before-combo-$label.json
echo "=== $(date +%H:%M:%S) combo-escape rule A/B ($label): $net, $blocks blocks"
for k in $(seq 1 $blocks); do
  seed=$((2026102300 + k))
  order="cand inst"; [ $(( k % 2 )) -eq 0 ] && order="inst cand"
  for who in $order; do
    x="comboStrafeRule=false"; [ $who = cand ] && x="comboStrafeRule=true"
    frozen fp2-$label-b$k-$who-sp.txt 360 $SPACER evalSeed=$seed $x
    frozen fp2-$label-b$k-$who-mix.txt 480 $MIX evalSeed=$seed $x
  done
  echo "=== $(date +%H:%M:%S) combo A/B block $k of $blocks done"
done
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
python setcfg.py evalSeed=0 comboStrafeRule=false > /dev/null
cp /d/swordai/run/lost-cases-before-combo-$label.json /d/swordai/run/swordai/lost-cases.json
echo "--- $(date '+%d %b %H:%M') combo-escape rule: ON (cand) vs OFF (inst), network $net" >> final-pair.log
python fp2sum.py "$label" "$blocks" | sed 's/^/  /' | tee -a final-pair.log
python combowin.py "$label" | tee -a final-pair.log
