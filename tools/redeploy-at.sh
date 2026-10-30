#!/usr/bin/env bash
# redeploy-at.sh HH:MM: waits until then, then redeploys unless a paired check is running (then waits for it to end).
until [ "$(date +%H:%M)" \> "$1" ] || [ "$(date +%H:%M)" = "$1" ]; do sleep 20; done
while tail -n 40 /d/swordai/run/school-client.log | grep -q "school pair"; do sleep 30; done
echo "redeploy $(date +%H:%M)"
bash /d/swordai/tools/school-redeploy.sh < /dev/null > /d/swordai/run/redeploy.out 2>&1
tail -3 /d/swordai/run/redeploy.out
