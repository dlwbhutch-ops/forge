#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
desktop_jar="$(find forge-gui-desktop/target -maxdepth 1 -name '*jar-with-dependencies.jar' -print -quit)"
test -n "$desktop_jar"
mkdir -p forge-gui-desktop/target/broadcast-smoke
if command -v javac >/dev/null 2>&1; then
    compiler=(javac)
else
    compiler=(java com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -cp "$desktop_jar" -d forge-gui-desktop/target/broadcast-smoke .github/scripts/HouseBroadcastSmoke.java
java -Djava.awt.headless=true -cp "forge-gui-desktop/target/broadcast-smoke:$desktop_jar" \
    com.housecommander.desktop.HouseBroadcastSmoke "$task_root"
