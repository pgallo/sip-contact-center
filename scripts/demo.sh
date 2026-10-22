#!/usr/bin/env bash
# Runs the demo. Extra arguments are passed through, e.g. --platform manager, --seed 3, --load --calls 5000
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f build/sip-contact-center.jar ]] || ./scripts/build.sh >/dev/null
java -jar build/sip-contact-center.jar "$@"
