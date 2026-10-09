#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac -encoding UTF-8 -d "$classes" \
  forge-gui-android/src/com/housecommander/core/DeckSpec.java \
  forge-gui-android/src/com/housecommander/core/Names.java \
  forge-gui-android/src/com/housecommander/core/WinnerIdentity.java \
  forge-gui-android/src/com/housecommander/core/WinnerIdentitySelfTest.java
java -cp "$classes" com.housecommander.core.WinnerIdentitySelfTest
