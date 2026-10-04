#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
mvn -q -pl forge-gui -am org.apache.maven.plugins:maven-dependency-plugin:3.1.2:build-classpath -Dmdep.outputFile=target/house-runtime-classpath.txt
cp forge-gui/target/house-runtime-classpath.txt house-smoke-classpath.txt
runtime_classpath="forge-gui/target/classes:$(cat house-smoke-classpath.txt)"
python3 - <<'PY'
from pathlib import Path
import zipfile
target = Path("house-smoke-runtime")
target.mkdir(exist_ok=True)
with zipfile.ZipFile("forge-gui-android/assets/house-forge-runtime.zip") as z:
    z.extractall(target)
PY
mkdir -p house-smoke-classes
javac -encoding UTF-8 -cp "$runtime_classpath" -d house-smoke-classes \
  forge-gui-android/src/com/housecommander/forgebridge/HouseHeadlessGui.java \
  forge-gui-android/src/com/housecommander/forgebridge/HouseForgeRuntime.java \
  forge-gui-android/src/com/housecommander/forgebridge/ForgeDeckLoader.java \
  forge-gui-android/src/com/housecommander/forgebridge/LiveGameState.java \
  forge-gui-android/src/com/housecommander/forgebridge/SpectatorPlayback.java \
  forge-gui-android/src/com/housecommander/forgebridge/SpectatorTransition.java \
  forge-gui-android/src/com/housecommander/forgebridge/PilotDecision.java \
  forge-gui-android/src/com/housecommander/forgebridge/PilotDecisionBridge.java \
  forge-gui-android/src/com/housecommander/forgebridge/HousePilotController.java \
  forge-gui-android/src/com/housecommander/forgebridge/HousePilotLobbyPlayer.java \
  forge-gui-android/src/com/housecommander/forgebridge/ForgeBridge.java \
  .github/scripts/HouseRuntimeSmoke.java
timeout 360 java -Xmx512m -cp "house-smoke-classes:$runtime_classpath" \
  HouseRuntimeSmoke house-smoke-runtime forge-gui-android/assets/house19/forge_decks house-literal-smoke.log \
  >house-runtime-smoke.log 2>&1
tail -n 30 house-runtime-smoke.log
