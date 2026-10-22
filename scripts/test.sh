#!/usr/bin/env bash
# Dependency-free test run. Uses the JUnit console launcher if one is installed under
# /usr/share/java; otherwise compiles the same test sources against the in-repo
# source-compatible shim (tools/minitest) and runs them with its runner.
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/build.sh
BUILD=build
CP_MAIN="$BUILD/sip-contact-center.jar"
mkdir -p "$BUILD/test-classes"
rm -rf "$BUILD/test-classes"/*

LAUNCHER=$(ls /usr/share/java/junit-platform-console-standalone*.jar 2>/dev/null | head -n1 || true)

if [[ -n "$LAUNCHER" ]]; then
  echo "running tests with $LAUNCHER"
  javac --release 21 -encoding UTF-8 -cp "$CP_MAIN:$LAUNCHER" -d "$BUILD/test-classes" $(find src/test/java -name '*.java')
  java -jar "$LAUNCHER" execute --class-path "$CP_MAIN:$BUILD/test-classes" --scan-class-path "$BUILD/test-classes"
else
  echo "no JUnit console launcher found, using tools/minitest"
  mkdir -p "$BUILD/minitest"
  javac --release 21 -encoding UTF-8 -d "$BUILD/minitest" $(find tools/minitest/src -name '*.java')
  javac --release 21 -encoding UTF-8 -cp "$CP_MAIN:$BUILD/minitest" -d "$BUILD/test-classes" $(find src/test/java -name '*.java')
  java -cp "$CP_MAIN:$BUILD/minitest:$BUILD/test-classes" minitest.Runner "$BUILD/test-classes"
fi
