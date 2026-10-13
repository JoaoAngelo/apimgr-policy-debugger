#!/bin/sh
# Finds the minimal set of Axway jars needed to COMPILE src/apd and packs them, keeping the
# runtime-relative layout, into dist/apd-build-libs.tar.gz (consumed by the release workflow).
#   tools/collect-build-libs.sh [AXWAY_HOME]
# Method: compile against an empty classpath, add the jar that provides each missing
# class/package reported by javac, repeat until it compiles.
set -e
APD_HOME=$(cd "$(dirname "$0")/.." && pwd)
if [ -z "$1" ] && [ -z "$AXWAY_HOME" ] && [ -f "$APD_HOME/conf/runtime.env" ]; then
    . "$APD_HOME/conf/runtime.env"
fi
AXWAY_HOME=${1:-${AXWAY_HOME:-/opt/axway/apigateway}}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# index: class file path -> jar (relative to AXWAY_HOME)
INDEX="$WORK/index"
cd "$AXWAY_HOME"
for j in $(find system/lib system/lib/plugins system/lib/modules system/lib/jce Linux.x86_64/java -maxdepth 1 -name '*.jar'); do
    unzip -Z1 "$j" 2>/dev/null | grep '\.class$' | sed "s#\$# $j#"
done > "$INDEX"
cd "$APD_HOME"
find src -name '*.java' > "$WORK/sources"

JARS=""
for round in $(seq 1 40); do
    CP=$(for j in $JARS; do printf '%s:' "$AXWAY_HOME/$j"; done)
    if javac --release 11 -nowarn -encoding UTF-8 -cp "$CP" -d "$WORK/classes" @"$WORK/sources" >"$WORK/out" 2>&1; then
        break
    fi
    # missing symbols: "package a.b does not exist", "class file for a.b.C not found", "cannot access a.b.C", import lines
    NEED=$( (grep -oE 'package [a-zA-Z0-9_.]+ does not exist' "$WORK/out" | awk '{print $2}' | sed 's#\.#/#g; s#$#/#';
             grep -oE 'class file for [a-zA-Z0-9_.$]+ not found' "$WORK/out" | awk '{print $4}' | sed 's#\.#/#g; s#$#.class#';
             grep -oE 'cannot access [a-zA-Z0-9_.$]+' "$WORK/out" | awk '{print $3}' | sed 's#\.#/#g; s#$#.class#';
             grep -A1 'cannot find symbol' "$WORK/out" | grep -oE '^import [a-zA-Z0-9_.]+' | awk '{print $2}' | sed 's#\.#/#g; s#$#.class#') | sort -u)
    ADDED=0
    for n in $NEED; do
        J=$(grep -m1 "^$n" "$INDEX" | awk '{print $2}')
        case " $JARS " in *" $J "*) ;; *) if [ -n "$J" ]; then JARS="$JARS $J"; ADDED=1; fi ;; esac
    done
    if [ $ADDED = 0 ]; then
        echo "collect-build-libs: cannot resolve remaining errors:" >&2
        head -30 "$WORK/out" >&2
        exit 1
    fi
done
echo "jars needed to compile ($(echo $JARS | wc -w)):"
for j in $JARS; do echo "  $j"; done
mkdir -p "$APD_HOME/dist"
(cd "$AXWAY_HOME" && tar -czf "$APD_HOME/dist/apd-build-libs.tar.gz" $JARS)
echo "packed dist/apd-build-libs.tar.gz ($(du -k "$APD_HOME/dist/apd-build-libs.tar.gz" | cut -f1) KB)"
