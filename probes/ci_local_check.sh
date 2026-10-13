#!/bin/sh
# Reproduces the release workflow locally: build with ONLY dist/apd-build-libs.tar.gz, package, then run the
# packaged jar on the real runtime.
set -e
SRC=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d /tmp/apd-ci.XXXX)
mkdir -p "$T/apd" && (cd "$SRC" && git ls-files -co --exclude-standard | tar -cf - -T -) | tar -xf - -C "$T/apd"   # working tree, minus ignored files
mkdir -p "$T/libs" && tar -xzf "$SRC/dist/apd-build-libs.tar.gz" -C "$T/libs"
cd "$T/apd"
AXWAY_HOME="$T/libs" sh package.sh
ls -l dist
cat dist/SHA256SUMS
mkdir -p "$T/inst" && tar -xzf dist/*.tar.gz -C "$T/inst"
H=$(ls -d "$T"/inst/apimgr-policy-debugger-*)
AXWAY_HOME=/opt/axway/apigateway "$H/bin/apimgr-policy-debugger" test "$H/examples/quickstart/tests" --config "$SRC/runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58" | tail -1
rm -rf "$T"
