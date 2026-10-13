#!/bin/sh
set -e
SRC=/mnt/z/Projetos/apimgateway/apimgr-policy-debugger
cd "$SRC"
sed -i 's/\r$//' build.sh package.sh bin/apimgr-policy-debugger bin/setup.sh docker/build.sh
sh package.sh
T=$(mktemp -d /tmp/apd-trial.XXXX)
tar -xzf dist/apimgr-policy-debugger-1.0.0.tar.gz -C "$T"
H="$T/apimgr-policy-debugger-1.0.0"
echo "AXWAY_HOME=/opt/axway/apigateway" > "$H/conf/runtime.env"
"$H/bin/apimgr-policy-debugger" --version
"$H/bin/apimgr-policy-debugger" doctor --config /opt/axway/apigateway/system/conf/templates/FactoryConfiguration-VordelGateway.fed
"$H/bin/apimgr-policy-debugger" test "$H/examples/quickstart/tests" --config "$SRC/runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58" | tail -1
rm -rf "$T"
