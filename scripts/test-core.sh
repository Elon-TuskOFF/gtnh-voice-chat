#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
mkdir -p build/core-tests
find src/main/java/dev/elontusk/gtnhvoice/core -name '*.java' -print > build/core-tests/sources.txt
printf '%s\n' src/main/java/dev/elontusk/gtnhvoice/client/VoiceAudio.java >> build/core-tests/sources.txt
printf '%s\n' src/test/java/dev/elontusk/gtnhvoice/core/CoreTests.java >> build/core-tests/sources.txt
"$javac_bin" --release 8 -d build/core-tests @build/core-tests/sources.txt
"$java_bin" -cp build/core-tests dev.elontusk.gtnhvoice.core.CoreTests
