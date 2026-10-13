#!/bin/sh
# apimgr-policy-debugger setup (Linux x86_64): installs the parts of YOUR Axway API Gateway 7.7
# installation that the debugger runs on. Nothing from Axway is shipped with the debugger.
#
#   setup.sh <source> [target]
#     source  Axway "apigateway" directory, or a .tar.gz containing .../apigateway/
#     target  install location (default /opt/axway/apigateway)
#
# Copies only Linux.x86_64 (JRE + native libs), system, posix and conf (~650 MB).
set -e
APD_HOME=$(cd "$(dirname "$0")/.." && pwd)
SRC=$1
TARGET=${2:-/opt/axway/apigateway}
PARTS="Linux.x86_64 platform system posix conf"

if [ -z "$SRC" ]; then
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi
[ -e "$SRC" ] || { echo "setup: source not found: $SRC" >&2; exit 2; }
if [ "$(uname -m)" != "x86_64" ]; then
    echo "setup: the Axway 7.7 runtime is Linux x86_64 only (this is $(uname -m))" >&2
    exit 2
fi

mkdir -p "$TARGET"
if [ -d "$SRC" ]; then
    [ -d "$SRC/posix" ] || { echo "setup: $SRC is not an Axway apigateway directory" >&2; exit 2; }
    for p in $PARTS; do
        echo "copying $p"
        cp -a "$SRC/$p" "$TARGET/"
    done
else
    echo "locating apigateway in $SRC ..."
    VENV=$(tar -tzf "$SRC" | grep -m1 'apigateway/posix/lib/venv$')
    [ -n "$VENV" ] || { echo "setup: no apigateway/posix/lib/venv inside $SRC" >&2; exit 2; }
    PREFIX=${VENV%/posix/lib/venv}
    STRIP=$(printf '%s' "$PREFIX" | tr -cd '/' | wc -c)
    STRIP=$((STRIP + 1))
    MEMBERS=""
    for p in $PARTS; do MEMBERS="$MEMBERS $PREFIX/$p"; done
    echo "extracting $PREFIX -> $TARGET"
    # shellcheck disable=SC2086
    tar -xzf "$SRC" -C "$TARGET" --strip-components=$STRIP $MEMBERS
fi

[ -x "$TARGET/platform/bin/vshell" ] || { echo "setup: vshell missing after install" >&2; exit 1; }
# AXWAY_ONLY=1: just lay down the runtime (used by docker/build.sh)
[ -n "$AXWAY_ONLY" ] && exit 0
echo "AXWAY_HOME=$TARGET" > "$APD_HOME/conf/runtime.env"
echo "checking the runtime ..."
sh "$APD_HOME/bin/apimgr-policy-debugger" --version
du -sh "$TARGET" | sed 's/^/installed: /'
