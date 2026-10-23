#!/usr/bin/env bash
# combochain.sh FROM: 23 Oct 09:50. The combo-escape A/B (combopair.sh, installed network, label cs), then training goes on
# from FROM: T6 (with the rule) if the A/B is not WORSE and combos went on less often with the rule on; else T5 as before.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
bash combopair.sh /d/swordai/run/kept-v21-final-1950 cs 3
verdict=$(python fp2sum.py cs 3 | head -1)
on=$(python combowin.py cs | grep '^  all rule on ' | sed 's/.*combo went on after one \([0-9]*\)%.*/\1/')
off=$(python combowin.py cs | grep '^  all rule off' | sed 's/.*combo went on after one \([0-9]*\)%.*/\1/')
if ! echo "$verdict" | grep -q WORSE && [ -n "$on" ] && [ -n "$off" ] && [ "$on" -lt "$off" ]; then
  echo "=== $(date +%H:%M:%S) combo rule kept (went on $on% vs $off%; $verdict): T6 from $from"
  bash startT6.sh "$from"
else
  echo "=== $(date +%H:%M:%S) combo rule NOT kept (went on $on% vs $off%; $verdict): T5 from $from"
  bash startT5b.sh "$from"
fi
