#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
runtime_classpath="house-smoke-classes:forge-gui/target/classes:$(cat house-smoke-classpath.txt)"
# HouseUpdateCheckSmoke imports Gson; the GUI-only classpath omits this Android dependency.
gson_jar="$(find "$HOME/.m2/repository/com/google/code/gson/gson" -type f -name 'gson-*.jar' 2>/dev/null | sort -V | tail -n 1)"
if [[ -z "$gson_jar" || ! -f "$gson_jar" ]]; then
  echo "HOUSE card-update smoke cannot locate Maven's Gson JAR" >&2
  exit 1
fi
runtime_classpath="$runtime_classpath:$gson_jar"
javac -encoding UTF-8 -cp "$runtime_classpath" -d house-smoke-classes \
  .github/scripts/HouseEmpowerSmoke.java \
  .github/scripts/HouseAttackWorkerSmoke.java \
  .github/scripts/HouseOutcomePublicationSmoke.java \
  .github/scripts/HouseUpdateCheckSmoke.java
java -Xmx1400m -cp "$runtime_classpath" HouseEmpowerSmoke house-smoke-runtime \
  forge-gui-android/assets/house19/forge_decks/Garth_One_Eye.dck
java -Xmx1400m -Djava.util.concurrent.ForkJoinPool.common.parallelism=2 \
  -cp "$runtime_classpath" HouseAttackWorkerSmoke house-smoke-runtime \
  forge-gui-android/assets/house19/forge_decks/Garth_One_Eye.dck
java -Xmx1400m -cp "$runtime_classpath" HouseOutcomePublicationSmoke house-smoke-runtime
java -cp "$runtime_classpath" HouseUpdateCheckSmoke forge-gui-android/assets/house-card-data.json
