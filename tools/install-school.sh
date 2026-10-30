#!/usr/bin/env bash
# install-school.sh CHECKED_BIN [--dry]: put the school network into the user's profile, with a backup first.
#   The mod jar (build/libs) replaces the profile's SwordAI jar, and CHECKED_BIN becomes swordai/school/checkpoint.bin:
#   /swordai fight then plays with the school network. Taking swordai/school/checkpoint.bin out goes back to the old one.
#   TEMPS=FILE also puts FILE in as swordai/school/play-temperature (per-head sharpening, jars from 26 Oct 05:48 on); without
#   TEMPS any play-temperature there is taken out.
set -e
BIN="$1"
DRY="$2"
P="/c/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI"
JAR=${JAR:-$(ls -t /d/swordai/build/libs/swordai-*+1.21.11.jar | head -1)}
[ -f "$BIN" ] || { echo "no network file $BIN"; exit 1; }
[ -f "$JAR" ] || { echo "no jar"; exit 1; }
# The user's Minecraft must not be running from this profile (its jar would be in use).
if powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -match 'ModrinthApp.profiles.DRL AI' -and \$_.Name -match 'java' } | Select-Object -First 1" | grep -q .; then
  echo "the profile's Minecraft is running: not installing"; exit 1
fi
STAMP=$(date +%Y%m%d-%H%M%S)
B="$P/swordai/backup-before-school-$STAMP"
echo "jar:      $JAR ($(stat -c %s "$JAR") bytes)"
echo "network:  $BIN ($(stat -c %s "$BIN") bytes)"
echo "temps:    ${TEMPS:-none}"
echo "backup:   $B"
echo "profile jar now: $(ls "$P/mods" | grep -i swordai)"
[ "$DRY" = "--dry" ] && { echo "dry run: nothing copied"; exit 0; }
mkdir -p "$B/mods"
cp -p "$P/mods/"swordai-*.jar "$B/mods/"
[ -d "$P/swordai/school" ] && cp -rp "$P/swordai/school" "$B/school"
cp -p "$P/swordai/config.json" "$B/config.json"
# The new jar in place of the old (one SwordAI jar in mods).
rm -f "$P/mods/"swordai-*.jar
cp -p "$JAR" "$P/mods/"
mkdir -p "$P/swordai/school"
cp -p "$BIN" "$P/swordai/school/checkpoint.bin"
rm -f "$P/swordai/school/play-temperature"
[ -n "$TEMPS" ] && cp -p "$TEMPS" "$P/swordai/school/play-temperature" && echo "play-temperature: $(tr '\n' ' ' < "$TEMPS")"
cmp "$JAR" "$P/mods/$(basename "$JAR")" && cmp "$BIN" "$P/swordai/school/checkpoint.bin" && echo "=== installed and verified: $(ls "$P/mods" | grep -i swordai), school/checkpoint.bin"
ls -la "$B" "$B/mods"
