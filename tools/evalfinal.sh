#!/usr/bin/env bash
# evalfinal.sh FINALDIR SECS: the end-of-day frozen test (19 Oct), the client up on build v70 or later. The same opponent
# mix as training, nothing learned; per run the damage table (report.py) and the requirement lines (reqs2.py).
#   F1 final, temperature 1.0   F2 final, 0.7   F3 final, crit back off   F4 v19, 1.0   F5 v19, crit back off (as installed)   F6 the 09:06 snapshot
# Leaves the dev config frozen; train21.sh puts the training settings back.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
final=$1
secs=${2:-660}
V19=/d/swordai/run/kept-v19-stop-2100
run() {
  label=$1; net=$2; temp=$3; cb=${4:-true}
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 oldModelShare=0.8 playTemperature=$temp counterCritRule=$cb > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  echo "=== $label: $net at temperature $temp, crit back $cb, $S-$E"
  python report.py client.log "$S" "$E" | tee "final-$label.txt"
  python reqs2.py client.log "$S" "$E" | tee -a "final-$label.txt"
}
run F1 "$final" 1.0
run F2 "$final" 0.7
run F3 "$final" 1.0 false
run F4 "$V19" 1.0
run F5 "$V19" 1.0 false
run F6 /d/swordai/run/kept-v21-0906 1.0
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 7
python setcfg.py playTemperature=1.0 counterCritRule=true > /dev/null
echo "=== done $(date +%H:%M:%S)"
