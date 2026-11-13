#!/usr/bin/env bash
# tournament.sh "TAG NET [OPT]" ...: benchmarks back to back in one session (the session effect, EXPERIMENTS.md 13 Nov 06:36),
# one at a time through the watchdog (run/bench-request), each after the one before has finished. OPT: hitout or airyield.
# Writes progress to run/tournament.log. Example:
#   bash tools/tournament.sh "run35wT1 /d/swordai/run/ab-keep/run35-0657.bin" "run88bT /d/swordai/run/best-candidates/run88b-1015.bin airyield"
R=/d/swordai/run
SUITE=${SUITE:-suite-v2}
log() { echo "$(date '+%m-%d %H:%M') $*" >> $R/tournament.log; }
running() { ps -ef | grep -a "bench-run.sh" | grep -v grep >/dev/null; }
for spec in "$@"; do
  set -- $spec
  TAG=$1; NET=$2; OPT=$3
  [ -f "$NET" ] || { log "skip $TAG: no $NET"; continue; }
  while running || [ -f $R/bench-request ]; do sleep 20; done
  echo "$TAG $NET $SUITE $OPT" > $R/bench-request
  log "asked $TAG $NET $SUITE $OPT"
  for i in $(seq 1 90); do running && break; sleep 10; done
  while running; do sleep 20; done
  log "done $TAG: $(head -3 /d/swordai/run/swordai/bench/$TAG/DONE 2>/dev/null | tr '\n' ' ')"
done
log "tournament finished"
