#!/bin/sh
# Runs a Jython probe inside the Axway runtime against a federated configuration.
#   run_probe.sh <probe.py> [configs.xml dir]
# AXWAY_HOME defaults to /opt/axway/apigateway; the config defaults to the local QuickStart copy in runtime/.
AXWAY_HOME=${AXWAY_HOME:-/opt/axway/apigateway}
HERE=$(cd "$(dirname "$0")/.." && pwd)
G=${2:-$HERE/runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58}
cd "$AXWAY_HOME"
/usr/bin/time -f 'rss=%MKB wall=%es' posix/bin/jython "$1" "federated:file://$G/configs.xml" 2>&1 | grep -vE '^\s*$' | tail -80
