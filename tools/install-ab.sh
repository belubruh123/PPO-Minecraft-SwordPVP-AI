#!/usr/bin/env bash
# install-ab.sh [--dry|--off] [SPEC ...]: put the live A/B pool (AbPool) into the user's profile, with a backup first.
#   Each SPEC is  tag=NETWORK_FILE[,rules][,temps][,weight][,options]  with NETWORK_FILE a file or @installed (the profile's own network again,
#   for an arm that differs only in its options), rules "on" (default) or "off", temps "default" or a file (default: none), weight a number
#   (default 1), options words joined by + (hitout: hit and out over the network; noback: the back-out rule off; both with the rules on). The installed network (swordai/school/checkpoint.bin, with the profile's no-rules and play-temperature
#   files as they are) is always the arm "installed", weight 1. The mod jar (build/libs) replaces the profile's SwordAI jar, because
#   the pool is in it; the installed network and everything else stay as they are. Each duel the mod then draws one arm at random and
#   says which in its start line ("[A/B arm tag]"); python tools/duels.py compares the arms from the user's game log.
#   --off takes swordai/school/ab/pool.txt out: the installed network plays alone again (the jar can stay).
#   Never while the profile's Minecraft is open (its jar is in use), and only after the user has said yes (the standing rule).
#   Example: bash tools/install-ab.sh --dry run30h=/d/swordai/run/best-candidates/run30h-1855.bin run30h-free=/d/swordai/run/best-candidates/run30h-1855.bin,off
set -e
P="/c/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI"
SCH="$P/swordai/school"
MODE=""
case "$1" in --dry) MODE=dry; shift ;; --off) MODE=off; shift ;; esac
if powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -match 'ModrinthApp.profiles.DRL AI' -and \$_.Name -match 'java' } | Select-Object -First 1" | grep -q .; then
  echo "the profile's Minecraft is running: not installing"; exit 1
fi
if [ "$MODE" = off ]; then
  rm -f "$SCH/ab/pool.txt"
  echo "pool off: $SCH/ab/pool.txt removed, the installed network plays alone"
  exit 0
fi
JAR=${JAR:-$(ls -t /d/swordai/build/libs/swordai-*+1.21.11.jar | head -1)}
[ -f "$JAR" ] || { echo "no jar"; exit 1; }
[ -f "$SCH/checkpoint.bin" ] || { echo "the profile has no installed school network ($SCH/checkpoint.bin)"; exit 1; }
[ $# -ge 1 ] || { echo "no candidate given (tag=NETWORK_FILE[,rules][,temps][,weight])"; exit 1; }
STAMP=$(date +%Y%m%d-%H%M%S)
B="$P/swordai/backup-before-ab-$STAMP"
POOL=$(mktemp)
INSTALLED_RULES=on
[ -f "$SCH/no-rules" ] && INSTALLED_RULES=off
INSTALLED_TEMPS=""
[ -f "$SCH/play-temperature" ] && INSTALLED_TEMPS=default
{
  echo "# tag | network | rules | temperatures | weight   (written by tools/install-ab.sh $STAMP)"
  echo "installed | ../checkpoint.bin | $INSTALLED_RULES | $INSTALLED_TEMPS | 1"
} > "$POOL"
COPIES=()
for SPEC in "$@"; do
  TAG="${SPEC%%=*}"; REST="${SPEC#*=}"
  IFS=',' read -r NET RULES TEMPS WEIGHT OPTS <<< "$REST"
  if [ "$NET" != "@installed" ]; then
    [ -f "$NET" ] || { echo "no network file $NET (for $TAG)"; rm -f "$POOL"; exit 1; }
  fi
  RULES=${RULES:-on}; WEIGHT=${WEIGHT:-1}; OPTS=${OPTS//+/ }
  case "$RULES" in on|off) ;; *) echo "rules must be on or off in $SPEC"; rm -f "$POOL"; exit 1 ;; esac
  T=""
  if [ "$TEMPS" = default ]; then T=default
  elif [ -n "$TEMPS" ]; then [ -f "$TEMPS" ] || { echo "no temperatures file $TEMPS"; rm -f "$POOL"; exit 1; }; T="$TAG.temps"; COPIES+=("$TEMPS|$T"); fi
  if [ "$NET" = "@installed" ]; then
    REF="../checkpoint.bin"
  else
    REF="$TAG.bin"; COPIES+=("$NET|$TAG.bin")
  fi
  echo "$TAG | $REF | $RULES | $T | $WEIGHT | $OPTS" >> "$POOL"
done
echo "jar:     $JAR ($(stat -c %s "$JAR") bytes)"
echo "backup:  $B"
echo "profile jar now: $(ls "$P/mods" | grep -i swordai)"
echo "--- the pool that will be written:"; cat "$POOL"
[ "$MODE" = dry ] && { echo "dry run: nothing copied"; rm -f "$POOL"; exit 0; }
mkdir -p "$B/mods" "$B/school"
cp -p "$P/mods/"swordai-*.jar "$B/mods/"
cp -p "$SCH/checkpoint.bin" "$B/school/"
for f in no-rules play-temperature; do [ -f "$SCH/$f" ] && cp -p "$SCH/$f" "$B/school/"; done
[ -d "$SCH/ab" ] && cp -rp "$SCH/ab" "$B/school/ab"
rm -f "$P/mods/"swordai-*.jar
cp -p "$JAR" "$P/mods/"
mkdir -p "$SCH/ab"
for c in "${COPIES[@]}"; do cp -p "${c%%|*}" "$SCH/ab/${c##*|}"; done
cp "$POOL" "$SCH/ab/pool.txt"; rm -f "$POOL"
cmp "$JAR" "$P/mods/$(basename "$JAR")" && echo "=== installed and verified: $(ls "$P/mods" | grep -i swordai), school/ab/pool.txt with $(grep -vc '^#' "$SCH/ab/pool.txt") arms"
ls -la "$SCH/ab"
