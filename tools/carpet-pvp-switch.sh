#!/usr/bin/env bash
# carpet-pvp-switch.sh pre|post   (Run 103, 15 Nov: the outside opponent, Carpet PvP's own sword bot; default OFF)
# ON when /d/swordai/run/carpet-pvp.on exists: its first line is the Carpet PvP jar path (empty line or empty file = the default jar in
# run/outside-bot). Remove the file to go back to plain Carpet. The mod ids are the same ("carpet"), so only one of them is loaded.
#   pre : prints the extra Gradle argument for the build ("-PcarpetPvp=<jar>" when on, nothing when off), so that Loom remaps the jar.
#   post: after the build, points Loom's argument file (build/loom-cache/argFiles/runClient, which a build does not regenerate) at the
#         remapped Carpet PvP when on and back at plain Carpet when off. Prints "on" or "off". Never fails the restart.
FLAG=/d/swordai/run/carpet-pvp.on
JAR=""
if [ -f "$FLAG" ]; then
  JAR=$(head -n1 "$FLAG" | tr -d '\r')
  [ -z "$JAR" ] && JAR=D:/swordai/run/outside-bot/carpet-pvp-1.21.11-19.jar
  [ -f "$JAR" ] || JAR=""
fi
if [ "$1" = pre ]; then
  [ -n "$JAR" ] && printf '%s' "-PcarpetPvp=$JAR"
  exit 0
fi
python -I - "$JAR" <<'PYEOF'
import glob, sys
jar = sys.argv[1]
root = "D:/swordai/.gradle/loom-cache/remapped_mods/remapped"
plain = [p for p in glob.glob(root + "/maven/modrinth/carpet-*/*/*.jar")]
pvp = [p for p in glob.glob(root + "/*/carpet-pvp-*/*/*.jar")]
af = "D:/swordai/build/loom-cache/argFiles/runClient"
try:
    if not plain or not pvp:
        print("off" if not jar else "on-but-not-remapped")
        sys.exit(0)
    plain_w = plain[0].replace("/", "\\")
    pvp_w = pvp[0].replace("/", "\\")
    s = open(af, "rb").read().decode()
    if jar:
        s = s.replace(plain_w, pvp_w)
    else:
        s = s.replace(pvp_w, plain_w)
    open(af, "wb").write(s.encode())
    print("on" if jar else "off")
except Exception as e:
    print("switch failed:", e)
PYEOF
exit 0
