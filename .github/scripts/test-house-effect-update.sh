#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
runtime_classpath="house-smoke-classes:forge-gui/target/classes:$(cat house-smoke-classpath.txt)"
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
