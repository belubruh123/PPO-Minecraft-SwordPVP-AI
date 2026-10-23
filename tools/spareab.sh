#!/usr/bin/env bash
# spareab.sh: frozen A/B of Config.answerSpareBacking (20 Oct 13:15) on the current network, on screen: spacer-only 8 min
# each (P1 off, P2 on), then the even mix 10 min each (M1 off, M2 on). Then headless training again (train29, the
# switch as decided by hand afterwards: left off here).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
net=/d/swordai/run/kept-v23-spareab
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
mkdir -p $net && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $net/
FROZEN="actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 silWeight=0 replayLostShare=0 mutateShare=0 hardOpponents=false counterCritRule=false playTemperature=1.0 Crits.drillShare=0 Crits.critDrillShare=0"
SPACER="oldModelShare=0 rehearsalProbability=0.95 selfCritShare=1.0 Crits.spacerShare=1.0 Crits.timerShare=0 Crits.whifferShare=0 Crits.punisherShare=0 Crits.jumperShare=0 Crits.traderShare=0 Crits.runnerShare=0 Crits.circlerShare=0 Crits.cornerShare=0 Crits.noKnockShare=0 Crits.oddShare=0 Crits.backerShare=0"
MIX="oldModelShare=0.5 rehearsalProbability=0.6 selfCritShare=1.0 Crits.spacerShare=0.12 Crits.timerShare=0.12 Crits.whifferShare=0.08 Crits.punisherShare=0.1 Crits.jumperShare=0.1 Crits.traderShare=0.04 Crits.runnerShare=0.04 Crits.circlerShare=0.08 Crits.cornerShare=0.06 Crits.noKnockShare=0.08 Crits.oddShare=0.04 Crits.backerShare=0.04"
run() {
  label=$1; secs=$2; shift 2
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp $net/checkpoint.bin /d/swordai/run/swordai/checkpoint.bin
  cp $net/curriculum.properties /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  echo "=== $label ($*): $S-$E"
  python report.py client.log "$S" "$E" | tee "final-$label.txt" | grep -a "updates\|0.14.0\|own copy\|spacer\|stander\|timer\|jump-crit\|punisher\|circler\|no-knock\|whiffer"
}
run P1 480 $SPACER answerSpareBacking=false
run P2 480 $SPACER answerSpareBacking=true
run M1 600 $MIX answerSpareBacking=false
run M2 600 $MIX answerSpareBacking=true
python compare.py M1 M2
python setcfg.py answerSpareBacking=false hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
HEADLESS=1 bash train29.sh $net
echo "=== training again $(date +%H:%M:%S)"
