#!/usr/bin/env bash
# finalchain.sh: 23 Oct 21:50. After ct6 (take-off rule): the long final check (finalRules.sh) on the build ct6 made.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
until grep -q "ct6 done" ct6.out; do sleep 10; done
sleep 5
bash finalRules.sh
