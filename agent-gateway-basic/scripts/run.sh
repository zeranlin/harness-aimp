#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
"$root/scripts/build.sh"
java -cp "$root/build" Main
