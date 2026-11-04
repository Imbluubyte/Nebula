#!/bin/sh
# Builds the starter project to a .so and drives it from a fake C engine (needs snc + a C compiler).
# Run tests/run-core.sh first (it creates /tmp/nebula-test/game).
set -e
cd "$(dirname "$0")"
cc -o /tmp/nebula-test/starter_host host/starter_host.c -ldl
cd /tmp/nebula-test/game/build && /tmp/nebula-test/starter_host ./libscripts.so > /tmp/nebula-test/starter.out
diff /tmp/nebula-test/starter.out "$OLDPWD/host/starter_expected.out" && echo "native run OK"
