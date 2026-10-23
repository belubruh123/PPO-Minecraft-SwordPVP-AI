#!/usr/bin/env bash
# evalnight.sh SECS: frozen test 20 Oct morning: G1 = the night's network (kept-v22-0615), G2 = the installed one
# (kept-v21-final-1950). Same opponent mix as training, nothing learned, no lost-fight replays, crit back off.
# Then training again from G1 with train22.sh (replays 0.7, spacing cost off).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
secs=${1:-660}
run() {
  label=$1; net=$2
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 oldModelShare=0.8 playTemperature=1.0 counterCritRule=false replayLostShare=0 > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  echo "=== $label: $net, $S-$E"
  python report.py client.log "$S" "$E" | tee "final-$label.txt"
}
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
d=/d/swordai/run/kept-v22-0615
mkdir -p $d && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $d/
run G1 $d
run G2 /d/swordai/run/kept-v21-final-1950
python compare.py G2 G1
bash train22.sh $d
echo "=== training again $(date +%H:%M:%S)"
