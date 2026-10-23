#!/usr/bin/env bash
# snaploop.sh TAG HOURS: every hour, keeps a copy of the dev run's network as run/kept-TAG-HHMM (on D:)
tag=$1; hours=${2:-8}
for i in $(seq 1 "$hours"); do
  sleep 3600
  d=/d/swordai/run/kept-$tag-$(date +%H%M)
  mkdir -p "$d"
  cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties "$d"/ 2>/dev/null
  echo "$(date +%H:%M:%S) kept $d"
done
