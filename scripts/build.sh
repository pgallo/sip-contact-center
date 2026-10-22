#!/usr/bin/env bash
# Dependency-free build: javac + jar only. Produces build/sip-contact-center.jar.
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD=build
rm -rf "$BUILD/classes" "$BUILD"/*.jar
mkdir -p "$BUILD/classes"

echo "compiling sip-contact-center"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$BUILD/classes" $(find src/main/java -name '*.java')
jar --create --file "$BUILD/sip-contact-center.jar" --main-class io.github.pgallo.sipcc.demo.Main -C "$BUILD/classes" .
echo "built $BUILD/sip-contact-center.jar"
