#!/bin/sh
# Cuts a release: sets VERSION in src/apd/Main.java, commits, tags vX.Y.Z and pushes.
# The "release" GitHub workflow then builds and publishes the .tar.gz/.zip/SHA256SUMS.
#   tools/release.sh 1.1.0
set -e
V=${1:?usage: tools/release.sh <X.Y.Z>}
echo "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "release: version must be X.Y.Z" >&2; exit 2; }
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain)" ] || { echo "release: working tree not clean" >&2; exit 2; }
git rev-parse -q --verify "refs/tags/v$V" >/dev/null && { echo "release: tag v$V already exists" >&2; exit 2; }
CUR=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' src/apd/Main.java)
if [ "$CUR" != "$V" ]; then
    sed -i "s/VERSION = \"$CUR\"/VERSION = \"$V\"/" src/apd/Main.java
    git add src/apd/Main.java
    git commit -q -m "Release $V"
fi
git tag -a "v$V" -m "apimgr-policy-debugger $V"
git push -q origin HEAD "v$V"
echo "pushed v$V — follow the build in GitHub Actions (workflow: release)"
