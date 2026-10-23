#!/usr/bin/env bash
# finalpair2.sh NET LABEL [BLOCKS=3]: the long final check before an install (21 Oct). finalpair.sh (30 min a network) could not
# tell the 04:19 snapshot from the installed one: the installed network alone measured -15, -10, -13 and +63 net against the
# spacer on different fight sets, because spacer profiles differ hugely from fight to fight. Here BLOCKS blocks, each with its
# own fight set (evalSeed 2026102100+k), and in each block NET and the INSTALLED network fight exactly that set in turn
# (spacers only 6 min, the even mix 7 min, the 0.14.0 model only 4 min each), frozen, first person. Writes
# fp2-LABEL-bK-{cand,inst}-{sp,mix,old}.txt; fp2sum.py prints per-block differences and the total to final-pair.log.
# Training is NOT restarted here; the lost-fight list is put back as it was.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
cand=$1; label=$2; blocks=${3:-3}
INST=/d/swordai/run/kept-v21-final-1950
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
OLD="replayLostShare=0 oldModelShare=1.0 rehearsalProbability=0 Self-play.drillShare=0 Self-play.critDrillShare=0"
frozen() {
  local out=$1 net=$2 secs=$3 S E; shift 3
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S); sleep "$secs"; E=$(date +%H:%M:%S)
  python report.py client.log "$S" "$E" > "$out"
  echo "$out $S $E" >> fp2-$label-windows.txt
}
cp /d/swordai/run/swordai/lost-cases.json /d/swordai/run/lost-cases-before-final2-$label.json
for k in $(seq 1 $blocks); do
  seed=$((2026102100 + k))
  # 22 Oct: the order alternates block by block (odd blocks: candidate first), so neither network is always measured second.
  order="cand inst"; [ $(( k % 2 )) -eq 0 ] && order="inst cand"
  for who in $order; do
    net=$cand; [ $who = inst ] && net=$INST
    x="$INSTX"; [ $who = cand ] && x="$CANDX"
    frozen fp2-$label-b$k-$who-sp.txt $net 360 $SPACER evalSeed=$seed $x
    frozen fp2-$label-b$k-$who-mix.txt $net 420 $MIX evalSeed=$seed $x
    frozen fp2-$label-b$k-$who-old.txt $net 240 $OLD evalSeed=$seed $x
  done
  echo "$(date +%H:%M:%S) block $k of $blocks done"
done
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
python setcfg.py evalSeed=0 > /dev/null
cp /d/swordai/run/lost-cases-before-final2-$label.json /d/swordai/run/swordai/lost-cases.json
python fp2sum.py "$label" "$blocks" | tee -a final-pair.log
# Fight by fight (eval result lines): every candidate run against the installed run of the same block and part.
for part in sp mix old; do
  a=""; for k in $(seq 1 $blocks); do
    c=$(grep "fp2-$label-b$k-cand-$part.txt" fp2-$label-windows.txt | tail -1); i=$(grep "fp2-$label-b$k-inst-$part.txt" fp2-$label-windows.txt | tail -1)
    [ -n "$c" ] && [ -n "$i" ] && a="$a client.log $(echo $c | cut -d' ' -f2-3) $(echo $i | cut -d' ' -f2-3)"
  done
  echo "  fight by fight, $part (A = candidate, B = installed):" | tee -a final-pair.log
  python fpairs.py $a | sed 's/^/    /' | tee -a final-pair.log
done
