#!/bin/sh
# Builds the jar and produces dist/apimgr-policy-debugger-<version>.tar.gz (no Axway files inside).
set -e
APD_HOME=$(cd "$(dirname "$0")" && pwd)
sh "$APD_HOME/build.sh"
VERSION=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' "$APD_HOME/src/apd/Main.java")
NAME="apimgr-policy-debugger-$VERSION"
STAGE="$APD_HOME/build/$NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE/conf" "$STAGE/ext/lib" "$APD_HOME/dist"
cp -r "$APD_HOME/bin" "$STAGE/"
cp "$APD_HOME/conf/apimgr-policy-debugger.xml" "$STAGE/conf/"
cp "$APD_HOME/ext/lib/apimgr-policy-debugger.jar" "$STAGE/ext/lib/"
cp -r "$APD_HOME/docs" "$APD_HOME/examples" "$APD_HOME/docker" "$STAGE/"
cp "$APD_HOME/README.md" "$STAGE/"
# sources edited on Windows may carry CRLF; the shell scripts must be LF
find "$STAGE" -type f \( -name '*.sh' -o -name 'apimgr-policy-debugger' -o -name Dockerfile \) -exec sed -i 's/\r$//' {} +
chmod +x "$STAGE/bin/apimgr-policy-debugger" "$STAGE/bin/setup.sh" "$STAGE/docker/build.sh"
tar -czf "$APD_HOME/dist/$NAME.tar.gz" -C "$APD_HOME/build" "$NAME"
echo "packaged dist/$NAME.tar.gz ($(du -k "$APD_HOME/dist/$NAME.tar.gz" | cut -f1) KB)"
