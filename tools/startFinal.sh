#!/usr/bin/env bash
# startFinal.sh NET LABEL BLOCKS: 22 Oct 06:25. Waits for the dev client's world, then the long final check (finalpair2.sh,
# order alternating per block) of NET against the installed network, the candidate playing with T3's tactics and reader.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; grep -aq "^exit code" client.log && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; tail -20 client.log; exit 1; }
echo "=== $(date +%H:%M:%S) final check of $1 ($2), $3 blocks"
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true" bash finalpair2.sh "$1" "$2" "$3"
echo "=== $(date +%H:%M:%S) final check done"
