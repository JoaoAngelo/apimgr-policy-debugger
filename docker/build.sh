#!/bin/sh
# Builds the apimgr-policy-debugger image from YOUR Axway installation (dir or .tar.gz).
#   docker/build.sh <source> [image-tag]
set -e
APD_HOME=$(cd "$(dirname "$0")/.." && pwd)
SRC=${1:?usage: docker/build.sh <Axway apigateway dir | .tar.gz> [tag]}
VERSION=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' "$APD_HOME/src/apd/Main.java" 2>/dev/null || true)
TAG=${2:-apimgr-policy-debugger:${VERSION:-latest}}
CTX=$(mktemp -d)
trap 'rm -rf "$CTX"' EXIT

# runtime parts only, through the same installer used on hosts
cp "$APD_HOME/bin/setup.sh" "$CTX/setup.sh"
mkdir -p "$CTX/apimgr-policy-debugger/conf" "$CTX/apimgr-policy-debugger/bin" "$CTX/apimgr-policy-debugger/ext/lib"
cp "$APD_HOME/bin/apimgr-policy-debugger" "$CTX/apimgr-policy-debugger/bin/"
cp "$APD_HOME/conf/apimgr-policy-debugger.xml" "$CTX/apimgr-policy-debugger/conf/"
cp "$APD_HOME/ext/lib/apimgr-policy-debugger.jar" "$CTX/apimgr-policy-debugger/ext/lib/"
AXWAY_ONLY=1 sh "$APD_HOME/bin/setup.sh" "$SRC" "$CTX/axway"
cp "$APD_HOME/docker/Dockerfile" "$CTX/"
docker build -t "$TAG" "$CTX"
echo "built $TAG"
