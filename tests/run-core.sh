#!/bin/sh
# Compiles the Android-free core with kotlinc and runs core/test/CoreTests.kt.
# Needs: kotlinc on PATH (or KOTLINC=...), snc on PATH (or SNC=...) and a C compiler.
set -e
cd "$(dirname "$0")/.."
KOTLINC=${KOTLINC:-kotlinc}
SNC=${SNC:-snc}
mkdir -p /tmp/nebula-core-out
$KOTLINC -nowarn core/src core/test -include-runtime -d /tmp/nebula-core-out/core-tests.jar 2>&1 | grep -v "^warning" || true
java -jar /tmp/nebula-core-out/core-tests.jar "$SNC" api /tmp/nebula-test
