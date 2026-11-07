#!/usr/bin/env bash
# compile-check.sh [file ...]: compile the client source files given (default SchoolSession.java) with javac against the classes the
# running dev client already built, into a temp folder, so that a redeploy does not stop training to find a compile error. It never
# touches build/ and never runs Gradle (rule 6). One error from a Fabric API jar that is not remapped ("class_6880 not found") is the
# known cost of this shortcut: any other error line is a real one.
cd /d/swordai || exit 1
export JAVA_HOME="C:/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2"
G=/c/Users/jerem/.gradle/caches/modules-2/files-2.1
w() { sed 's#^/c/#C:/#; s#^/d/#D:/#' | tr '\n' ';'; }
EXTRA=$( (find /d/swordai/.gradle /d/swordai/build/loom-cache -iname '*carpet*.jar' -not -name '*sources*' | head -1; find $G -name 'slf4j-api-2.0.17.jar'; find $G -name 'jspecify-1.0.0.jar'; find $G -name 'fabric-block-view-api-v2-1.0.39*.jar' -not -name '*sources*'; \
  find $G -name 'fabric-resource-loader-v1-2.0.13*.jar' -not -name '*sources*'; find $G -name 'fabric-data-attachment-api-v1*.jar' -not -name '*sources*' | head -2; \
  find $G -name 'annotations-2*.jar' -path '*jetbrains*' -not -name '*sources*' | head -1) 2>/dev/null | w)
LIBS=$(find $G \( -name "guava-*.jar" -o -name "gson-*.jar" -o -name "joml-*.jar" -o -name "jsr305*.jar" -o -name "fabric-loader-*.jar" -o -name "sponge-mixin*.jar" \
  -o -name "datafixerupper*.jar" -o -name "brigadier*.jar" -o -name "netty-*.jar" -o -name "lwjgl-3*.jar" -o -name "commons-lang3*.jar" -o -name "fastutil*.jar" \
  -o -name "authlib*.jar" -o -name "log4j-api*.jar" \) -not -name "*sources*" 2>/dev/null | w)
MCDIR="D:/swordai/.gradle/loom-cache/minecraftMaven/net/minecraft"
MC=$(ls $MCDIR/minecraft-clientOnly-*/*/*.jar $MCDIR/minecraft-common-*/*/*.jar 2>/dev/null | sed 's#^#D:/swordai/#; s#D:/swordai/D:#D:#' | tr '\n' ';')
[ -z "$MC" ] && MC="$(find /d/swordai/.gradle/loom-cache/minecraftMaven -name '*.jar' | sed 's#^/d/#D:/#' | tr '\n' ';')"
OUT=D:/swordai/build-check-tmp
rm -rf $OUT && mkdir -p $OUT
FILES="$@"; [ -z "$FILES" ] && FILES=src/client/java/dev/swordai/client/SchoolSession.java
"$JAVA_HOME/bin/javac" -proc:none -nowarn -d $OUT -cp "D:/swordai/build/classes/java/client;D:/swordai/build/classes/java/main;D:/swordai/core/build/classes/java/main;$MC$EXTRA$LIBS" $FILES 2>&1 | grep -v "^printing javac" > /tmp/compile-check.out
rm -rf /d/swordai/build-check-tmp
REAL=$(grep -c "error:" /tmp/compile-check.out)
KNOWN=$(grep -c "class_6880 not found" /tmp/compile-check.out)
if [ "$REAL" -le "$KNOWN" ]; then echo "compile-check OK ($REAL error, the known library one)"; else grep -B1 -A3 "error:" /tmp/compile-check.out | head -40; echo "compile-check FAILED"; fi
