#!/bin/sh
# Verifies a fresh clone builds and runs with only what is versioned.
set -e
SRC=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d /tmp/apd-clone.XXXX)
git clone -q "$SRC" "$T/apd"
cd "$T/apd"
echo "--- versioned top level: $(ls -A | tr '\n' ' ')"
sh build.sh
bin/apimgr-policy-debugger --version
CFG=${1:-$SRC/runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58}
bin/apimgr-policy-debugger test examples/quickstart/tests --config "$CFG" | tail -1
bin/apimgr-policy-debugger run --config /opt/axway/apigateway/system/conf/templates/FactoryConfiguration-VordelGateway.fed --url /healthcheck --quiet
rm -rf "$T"
