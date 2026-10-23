#!/usr/bin/env bash
# finaleval.sh LABEL=DIR ...: the evening's frozen test (20 Oct) on screen. Each network: 8 min against spacers only
# (the friend's way; X-LABEL-sp) and 15 min against the even mix (X-LABEL-mix). Nothing learned, no replays, mutants,
# self-imitation, drills or PFSP. Writes final-X-*.txt; then on-screen training again (train29) from the network that
# was training.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
cur=/d/swordai/run/kept-v23-before-final-$(date +%H%M)
mkdir -p $cur && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $cur/
FROZEN="actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 silWeight=0 replayLostShare=0 mutateShare=0 hardOpponents=false counterCritRule=false playTemperature=1.0 answerSpareBacking=false Crits.drillShare=0 Crits.critDrillShare=0"
SPACER="oldModelShare=0 rehearsalProbability=0.95 selfCritShare=1.0 Crits.spacerShare=1.0 Crits.timerShare=0 Crits.whifferShare=0 Crits.punisherShare=0 Crits.jumperShare=0 Crits.traderShare=0 Crits.runnerShare=0 Crits.circlerShare=0 Crits.cornerShare=0 Crits.noKnockShare=0 Crits.oddShare=0 Crits.backerShare=0"
MIX="oldModelShare=0.5 rehearsalProbability=0.6 selfCritShare=1.0 Crits.spacerShare=0.12 Crits.timerShare=0.12 Crits.whifferShare=0.08 Crits.punisherShare=0.1 Crits.jumperShare=0.1 Crits.traderShare=0.04 Crits.runnerShare=0.04 Crits.circlerShare=0.08 Crits.cornerShare=0.06 Crits.noKnockShare=0.08 Crits.oddShare=0.04 Crits.backerShare=0.04"
run() {
  label=$1; net=$2; secs=$3; shift 3
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
  echo "=== $label: $net, $S-$E"
  python report.py client.log "$S" "$E" | tee "final-$label.txt"
}
for pair in "$@"; do
  label=${pair%%=*}; net=${pair#*=}
  run "X-$label-sp" "$net" 480 $SPACER
  run "X-$label-mix" "$net" 900 $MIX
done
python setcfg.py hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
bash train30.sh $cur
echo "=== training again $(date +%H:%M:%S)"
