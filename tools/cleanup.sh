#!/usr/bin/env bash
# cleanup.sh LABEL TAG: after TaskStop of a check or loop, its watchdog and the client (22 Oct): kills the bash children
# TaskStop leaves behind (by script name only), puts the lost-fight list back from before the check LABEL (if there is
# one), keeps the client log as client-run-TAG.log, drops that check's result files and moves the traces made since the
# last cleanup to run/traces-TAG.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
for p in $(ps -ef | grep -E "watchdog\.sh|finalpair2\.sh|startEdge\.sh|startRules\.sh|startAim\.sh|startT4\.sh|hourlycheck2?\.sh" | grep -v grep | awk '{print $2}'); do
  kill $p 2>/dev/null
done
sleep 2
ps -ef | grep -E "watchdog\.sh|finalpair2\.sh|startEdge\.sh|startRules\.sh|startAim\.sh|startT4\.sh|hourlycheck2?\.sh" | grep -v grep
[ -f /d/swordai/run/lost-cases-before-final2-$1.json ] && cp /d/swordai/run/lost-cases-before-final2-$1.json /d/swordai/run/swordai/lost-cases.json
cp client.log client-run-$2.log
rm -f fp2-$1-*
mkdir -p /d/swordai/run/traces-$2
since=$(cat cleanup-stamp.txt 2>/dev/null || echo 1022-1047)
for f in $(ls /d/swordai/run/swordai/traces | awk -v s="$since" '$0 >= s'); do mv /d/swordai/run/swordai/traces/$f /d/swordai/run/traces-$2/ 2>/dev/null; done
date +%m%d-%H%M%S > cleanup-stamp.txt
echo "traces moved: $(ls /d/swordai/run/traces-$2 | wc -l); java running: $(powershell -Command '(Get-Process java -ErrorAction SilentlyContinue | Measure-Object).Count')"
