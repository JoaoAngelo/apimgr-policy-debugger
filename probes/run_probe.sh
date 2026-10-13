#!/bin/sh
cd /opt/axway/APIM-7.7.0/apigateway
G=/opt/axway/APIM-7.7.0/apigateway/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58
/usr/bin/time -f 'rss=%MKB wall=%es' posix/bin/jython "$@" "federated:file://$G/configs.xml" 2>&1 | grep -vE '^\s*$' | tail -80