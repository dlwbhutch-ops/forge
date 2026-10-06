#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
task_classes="$(mktemp -d)"
trap 'rm -rf "$task_classes"' EXIT
if command -v javac >/dev/null 2>&1; then
    compiler=(javac)
else
    compiler=(java com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d "$task_classes" \
    forge-gui-android/src/com/housecommander/lab/state/RunState.java \
    forge-gui-android/src/com/housecommander/lab/state/GameLogFiles.java \
    forge-gui-android/src/com/housecommander/lab/state/GameCancellation.java \
    .github/scripts/HouseAndroidStateSmoke.java
java -cp "$task_classes" HouseAndroidStateSmoke
