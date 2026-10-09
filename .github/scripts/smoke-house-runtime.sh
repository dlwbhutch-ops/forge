#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
mvn -q -pl forge-gui -am org.apache.maven.plugins:maven-dependency-plugin:3.1.2:build-classpath -Dmdep.outputFile=target/house-runtime-classpath.txt
cp forge-gui/target/house-runtime-classpath.txt house-smoke-classpath.txt
runtime_classpath="forge-gui/target/classes:$(cat house-smoke-classpath.txt)"
# CardDataUpdates is included in the HOUSE core smoke javac inputs and uses Gson.
# The GUI dependency classpath does not include Android-only Gson transitives.
gson_jar="$(find "$HOME/.m2/repository/com/google/code/gson/gson" -type f -name 'gson-*.jar' 2>/dev/null | sort -V | tail -n 1)"
if [[ -z "$gson_jar" || ! -f "$gson_jar" ]]; then
  echo "HOUSE smoke cannot locate the Gson JAR installed by Android Maven build" >&2
  exit 1
fi
runtime_classpath="$runtime_classpath:$gson_jar"
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
  $(find forge-gui-android/src/com/housecommander/core \
         forge-gui-android/src/com/housecommander/forgebridge \
         forge-gui-android/src/com/housecommander/spectator -name '*.java') \
  forge-gui-android/src/com/housecommander/lab/state/GameCancellation.java \
  .github/scripts/HouseRuntimeSmoke.java \
  .github/scripts/HouseCancellationSmoke.java
timeout 360 java -Xmx512m -cp "house-smoke-classes:$runtime_classpath" \
  HouseRuntimeSmoke house-smoke-runtime forge-gui-android/assets/house19/forge_decks house-literal-smoke.log \
  >house-runtime-smoke.log 2>&1
tail -n 30 house-runtime-smoke.log
: >house-cancellation-smoke.log
for scenario in pilot ai locked; do
  timeout 90 java -Xmx512m -cp "house-smoke-classes:$runtime_classpath" \
    HouseCancellationSmoke house-smoke-runtime house-stop-logs "$scenario" \
    >>house-cancellation-smoke.log 2>&1
done
tail -n 15 house-cancellation-smoke.log
