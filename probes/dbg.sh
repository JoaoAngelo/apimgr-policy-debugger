#!/bin/sh
export VDISTDIR=/opt/axway/APIM-7.7.0/apigateway DEVKIT_HOME=/mnt/z/Projetos/apimgateway/devkit
. $VDISTDIR/posix/lib/venv $VDISTDIR/posix/bin/x
ulimit -c 0
C=$VDISTDIR/system/conf
sed -e 's#<include file="jvm.xml"/>#<include file="jvm.xml"/><ClassPath name="/mnt/z/Projetos/apimgateway/devkit/lib/apigw-devkit.jar"/>#' $C/jython.xml > $C/devkit-j.xml
"$V_PLATFORM_BIN/vshell" -Dquiet -e $C/devkit-j.xml -c "
import java.lang.Class as C
try:
    from devkit import Hello
    Hello.main(['x'])
except Exception, e:
    print 'ERR', e
import java.lang.Thread as T
cl = T.currentThread().getContextClassLoader()
print cl
print cl.getResource('devkit/Hello.class')
" 2>&1 | head -20