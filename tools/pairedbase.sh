#!/usr/bin/env bash
# pairedbase.sh: starts paired evaluation (Config.evalSeed in hourlycheck.sh's FROZEN): writes paired-since.txt, then
# measures the INSTALLED network frozen, first person, on the paired fights: spacer-only 7 min (pinst-HHMM-sp.txt) and
# the mix 9 min (pinst-HHMM-mix.txt). From then on hourlysum2.py compares only with paired measurements.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
INST=/d/swordai/run/kept-v21-final-1950
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
python -c "import time; print(time.time())" > paired-since.txt
label=$(date +%H%M)
frozen() {
  out=$1; secs=$2; shift 2
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp "$INST/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$INST/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S); sleep "$secs"; E=$(date +%H:%M:%S)
  python report.py client.log "$S" "$E" > "$out"
}
frozen pinst-$label-sp.txt 420 $SPACER
frozen pinst-$label-mix.txt 540 $MIX
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
echo "=== paired baseline $label"; sed -n 3,4p pinst-$label-sp.txt; sed -n 3,12p pinst-$label-mix.txt
