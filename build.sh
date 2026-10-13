#!/bin/sh
# Builds ext/lib/apimgr-policy-debugger.jar against the Axway runtime libraries
# (Java 11 bytecode for the product JRE). Needs a JDK >= 11 (javac, jar).
#   AXWAY_HOME  Axway runtime (default: conf/runtime.env, else /opt/axway/apigateway)
set -e
APD_HOME=$(cd "$(dirname "$0")" && pwd)
if [ -z "$AXWAY_HOME" ] && [ -f "$APD_HOME/conf/runtime.env" ]; then
    . "$APD_HOME/conf/runtime.env"
fi
AXWAY_HOME=${AXWAY_HOME:-/opt/axway/apigateway}
LIB="$AXWAY_HOME/system/lib"
[ -d "$LIB" ] || { echo "build: Axway libraries not found in $LIB (run bin/setup.sh or set AXWAY_HOME)" >&2; exit 2; }
command -v javac >/dev/null || { echo "build: javac not found (install a JDK >= 11, e.g. openjdk-17-jdk-headless)" >&2; exit 2; }

OUT="$APD_HOME/build/classes"
rm -rf "$OUT"
mkdir -p "$OUT" "$APD_HOME/ext/lib"
# works with a full runtime or with the compile-only subset (tools/collect-build-libs.sh)
DIRS=""
for d in "$LIB" "$LIB/plugins" "$LIB/modules" "$LIB/jce" "$AXWAY_HOME/Linux.x86_64/java"; do
    [ -d "$d" ] && DIRS="$DIRS $d"
done
# shellcheck disable=SC2086
CP=$(find $DIRS -maxdepth 1 -name '*.jar' | tr '\n' ':')
find "$APD_HOME/src" -name '*.java' > "$APD_HOME/build/sources.txt"
javac --release 11 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" @"$APD_HOME/build/sources.txt"
jar --create --file "$APD_HOME/ext/lib/apimgr-policy-debugger.jar" -C "$OUT" .
echo "built ext/lib/apimgr-policy-debugger.jar"
