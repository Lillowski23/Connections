#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

OUT_DIR="out"
GSON_JAR="lib/gson-2.10.1.jar"
CLASSPATH="$OUT_DIR:$GSON_JAR"

needs_rebuild=false
if [[ ! -d "$OUT_DIR" ]]; then
  needs_rebuild=true
elif [[ -z "$(find "$OUT_DIR" -type f -name "*.class" -print -quit 2>/dev/null)" ]]; then
  needs_rebuild=true
elif [[ ! -f "$OUT_DIR/server/ServerMain.class" ]] || [[ ! -f "$OUT_DIR/client/ClientMain.class" ]]; then
  # out non e' vuota ma e' incompleta (tipico: compilato solo server o solo client)
  needs_rebuild=true
elif [[ -n "$(find src -type f -name "*.java" -newer "$OUT_DIR/server/ServerMain.class" -print -quit 2>/dev/null)" ]]; then
  needs_rebuild=true
elif [[ "$GSON_JAR" -nt "$OUT_DIR/server/ServerMain.class" ]]; then
  needs_rebuild=true
fi

if [[ "$needs_rebuild" == true ]]; then
  echo "[run-server] out mancante/vuota/incompleta: compilo tutto src/..."
  mkdir -p "$OUT_DIR"
  find src -type f -name "*.java" > .all_sources.tmp
  javac -cp "$GSON_JAR" -d "$OUT_DIR" @.all_sources.tmp
  rm -f .all_sources.tmp
fi

echo "[run-server] Avvio server.ServerMain"
java -cp "$CLASSPATH" server.ServerMain "$@"
