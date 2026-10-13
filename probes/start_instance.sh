#!/bin/sh
V=/mnt/z/Projetos/apimgateway/extracted/opt/Axway/APIM-7.7.0/apigateway
I=/mnt/z/Projetos/apimgateway/devkit/runtime/groups/group-2/instance-1
cd $V/posix/bin
export VDISTDIR=$V
. ../lib/venv ./startinstance
export INSTANCENAME=instance-1 GROUPNAME=group-2
timeout 120 "$V_PLATFORM_BIN/vshell" -h "$I" -DLicenseType=APIGateway -v conf/service.xml > /tmp/gw-start.log 2>&1
echo "exit=$?"
grep -iE "fatal|error|started|listening|ready|license|cassandra" /tmp/gw-start.log | head -40
echo ...; tail -15 /tmp/gw-start.log