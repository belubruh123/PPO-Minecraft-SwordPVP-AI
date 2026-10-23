#!/usr/bin/env bash
# fps60.sh: 22 Oct 22:15, the dev client now capped at 60 FPS (run/options.txt maxFps 260 -> 60; the user plays at 60; the
# GPU was at ~74% non-stop and hung twice, BSOD 0x119 at 17:11). Waits for the world, measures the INSTALLED network at
# 60 FPS (pairedbase.sh: new paired-since.txt, spacer-only 7 + mix 9 min) so hourly verdicts compare like with like,
# then T4 resumes from kept-v24-T4-2214 (its state when stopped at 22:13) with its lost-fight list.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
sleep 5
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
sleep 5
bash pairedbase.sh
cp /d/swordai/run/kept-v24-T4-2214/lost-cases.json /d/swordai/run/swordai/lost-cases.json
bash trainGT4.sh /d/swordai/run/kept-v24-T4-2214
echo "=== $(date +%H:%M:%S) T4 training again (60 FPS) from kept-v24-T4-2214"
