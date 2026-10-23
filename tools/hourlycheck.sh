#!/usr/bin/env bash
# hourlycheck.sh TRAIN_SCRIPT [TRAIN_MINUTES=40]: the user's rule of 20 Oct 20:45 ("do the same final check every hour so
# anything that goes wrong is seen in real time"). Forever: let training run TRAIN_MINUTES, then stop it, keep the
# network as run/kept-v24-HHMM, and run the evening's final checks on it in first person on screen, frozen:
# spacers only (7 min), the even mix (9 min), the kept lost fights set up exactly (4 min; the list is put back after).
# One line per hour goes to hourly-check.log (hourlysum.py) against the installed network. Then training again with
# TRAIN_SCRIPT (e.g. trainA.sh) from that network. Never headless.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
train=$1
mins=${2:-40}
FROZEN="evalSeed=20261020 demoWeight=0 comboStrafeRule=false critBridge=false rhythmNet=false traceFights=true tacticModel=false keepWithin=0 jumpAtReadyOnlyFleeing=false edgeCritRule=false aimLevel=false actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 silWeight=0 mutateShare=0 hardOpponents=false counterCritRule=false playTemperature=1.0 answerSpareBacking=false Crits.drillShare=0 Crits.critDrillShare=0"
SPACER="replayLostShare=0 oldModelShare=0 rehearsalProbability=0.95 selfCritShare=1.0 Crits.spacerShare=1.0 Crits.timerShare=0 Crits.whifferShare=0 Crits.punisherShare=0 Crits.jumperShare=0 Crits.traderShare=0 Crits.runnerShare=0 Crits.circlerShare=0 Crits.cornerShare=0 Crits.noKnockShare=0 Crits.oddShare=0 Crits.backerShare=0"
MIX="replayLostShare=0 oldModelShare=0.5 rehearsalProbability=0.6 selfCritShare=1.0 Crits.spacerShare=0.12 Crits.timerShare=0.12 Crits.whifferShare=0.08 Crits.punisherShare=0.1 Crits.jumperShare=0.1 Crits.traderShare=0.04 Crits.runnerShare=0.04 Crits.circlerShare=0.08 Crits.cornerShare=0.06 Crits.noKnockShare=0.08 Crits.oddShare=0.04 Crits.backerShare=0.04"
LOST="replayLostShare=1.0 oldModelShare=0 rehearsalProbability=0.95 selfCritShare=1.0"
frozen() {
  out=$1; net=$2; secs=$3; shift 3
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  python report.py client.log "$S" "$E" > "$out"
}
while true; do
  sleep $(( mins * 60 ))
  label=$(date +%H%M)
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  net=/d/swordai/run/kept-v24-$label
  mkdir -p $net && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $net/
  echo "=== $(date +%H:%M:%S) checking $net"
  frozen "hc-$label-sp.txt" $net 420 $SPACER
  frozen "hc-$label-mix.txt" $net 540 $MIX
  frozen "hc-$label-lost.txt" $net 240 $LOST
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  cp /d/swordai/run/swordai/lost-cases.json $net/lost-cases-after-check.json
  cp $net/lost-cases.json /d/swordai/run/swordai/lost-cases.json
  python hourlysum.py $label
  python setcfg.py hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
  from=$net
  if [ "$(cat hourly-state.txt 2>/dev/null || echo 0)" -ge 2 ]; then
    # Worse than the installed network two hours running: training goes on from the best network so far
    # (the best hourly one if it was at least equal to the installed network, else the installed one).
    best=$(sort -k2 -n -r hourly-scores.txt | head -1)
    bl=${best%% *}; bs=${best#* }
    if python -c "import sys; sys.exit(0 if float('$bs') >= -20 else 1)"; then from=/d/swordai/run/kept-v24-$bl; else from=/d/swordai/run/kept-v21-final-1950; fi
    echo "$label | ROLLED BACK: training goes on from $from" | tee -a hourly-check.log
    echo 0 > hourly-state.txt
  fi
  bash $train $from > /dev/null
  echo "=== $(date +%H:%M:%S) training again with $train"
done
