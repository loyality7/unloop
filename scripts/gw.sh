#!/usr/bin/env bash
# Serialised Gradle runner. Parallel agents sharing one build dir corrupt each other's output,
# so every Gradle call goes through this lock. Usage: scripts/gw.sh :core:test --tests 'x.*'
set -u
LOCK="${TMPDIR:-/tmp}/unloop-gradle.lock"
until mkdir "$LOCK" 2>/dev/null; do
  # steal a stale lock older than 20 minutes
  if [ -d "$LOCK" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +20 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null; fi
  sleep 3
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM
cd "$(dirname "$0")/../app/android" && ./gradlew "$@"
