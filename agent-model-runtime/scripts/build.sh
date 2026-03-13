#!/usr/bin/env sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
mkdir -p "$root/build"

javac -d "$root/build" "$root/src/Main.java"
