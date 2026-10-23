#!/usr/bin/env bash
# safetest.sh MINUTES: the safe-learning smoke test in the dev world. Needs the dev client up with a world open and
# nothing running. A Carpet fake player "Dummy" (respawned while it is dead) is fought with /swordai fight and
# learning on; prints the safe-learning lines, the files and whether checkpoint.bin changed.
S=/d/swordai/run/swordai
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
inbox() { printf '%s\r\n' "$@" > $S/inbox.txt; sleep 3; }
before=$(md5sum $S/checkpoint.bin | cut -c1-32)
from=$(date +%H:%M:%S)
inbox 'gamemode survival' 'give @s minecraft:diamond_sword' 'effect clear @s'
inbox 'swordai learn on'
inbox 'player Dummy spawn in survival'
sleep 3
inbox 'give Dummy minecraft:iron_sword' 'player Dummy attack interval 15'
inbox 'swordai fight Dummy'
end=$(( $(date +%s) + $1 * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  sleep 12
  inbox 'player Dummy spawn in survival'
  sleep 2
  inbox 'give Dummy minecraft:iron_sword' 'player Dummy attack interval 15'
done
inbox 'swordai stop'
sleep 5
inbox 'player Dummy kill' 'swordai learn off'
echo "== from $from"
awk -v f="$from" 'substr($0,2,8) >= f' client.log | grep -a "SwordAI\]\|safe\|Safe\|learning\|Exception\|ERROR\|inbox" | grep -av "Unknown or incomplete\|already logged" | cut -c1-400 | tail -60
echo "== files"
ls -la --time-style=+%H:%M:%S $S/checkpoint.bin $S/learning.bin $S/learning.properties 2>&1
cat $S/learning.properties 2>&1
after=$(md5sum $S/checkpoint.bin | cut -c1-32)
echo "checkpoint before $before after $after"
