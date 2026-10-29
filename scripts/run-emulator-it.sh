#!/usr/bin/env bash
# Harnais d'intégration ELM327-emulator (tâche 8 M0+M1) : démarre
# l'émulateur réel (Ircama) en TCP sur 127.0.0.1:35000, attend le port,
# lance EmulatorIntegrationTest, puis tue l'émulateur (trap).
# Code de sortie = celui de Gradle.
#
# Prérequis : .toolchain/emu-venv (voir scripts/elm-emulator/README.md).
# Licence de l'émulateur : CC BY-NC-SA 4.0 — usage local seulement,
# jamais commité ni redistribué (il vit dans .toolchain/, gitignorée).

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd -P)"
cd "$ROOT"
# shellcheck disable=SC1091
source scripts/dev-env.sh

EMU_PY="$ROOT/.toolchain/emu-venv/bin/python"
if [ ! -x "$EMU_PY" ]; then
  echo "run-emulator-it.sh : $EMU_PY introuvable — voir scripts/elm-emulator/README.md" >&2
  exit 2
fi

PORT="${ELM_EMU_PORT:-35000}"
HOST="127.0.0.1"
LOG="$(mktemp)"
FIFO="$(mktemp -u)"
mkfifo "$FIFO"
# La boucle interactive de l'émulateur se termine sur EOF de stdin :
# on lui donne un FIFO dont ce shell garde le bout d'écriture ouvert
# (fd 9) sans jamais rien écrire.
exec 9<>"$FIFO"
"$EMU_PY" -m elm -n "$PORT" <"$FIFO" >"$LOG" 2>&1 &
EMU_PID=$!

cleanup() {
  kill "$EMU_PID" 2>/dev/null
  wait "$EMU_PID" 2>/dev/null
  exec 9>&-
  rm -f "$FIFO"
}
trap cleanup EXIT

pret=""
for _ in $(seq 1 30); do
  if (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null; then
    exec 3>&- 3<&-
    pret=1
    break
  fi
  if ! kill -0 "$EMU_PID" 2>/dev/null; then
    echo "run-emulator-it.sh : l'émulateur s'est arrêté au démarrage" >&2
    cat "$LOG" >&2
    exit 2
  fi
  sleep 0.5
done
if [ -z "$pret" ]; then
  echo "run-emulator-it.sh : port $PORT jamais ouvert" >&2
  cat "$LOG" >&2
  exit 2
fi

# La JVM de test hérite de l'environnement, pas des -D de Gradle :
# on passe les deux formes, le test lit l'une ou l'autre.
ELM_EMU_HOST="$HOST" ELM_EMU_PORT="$PORT" \
  ./gradlew :protocol:test --tests "*EmulatorIntegrationTest*" \
  -Delm.emu.host="$HOST" -Delm.emu.port="$PORT"
code=$?
if [ "$code" -ne 0 ]; then
  echo "run-emulator-it.sh : échec des tests, journal de l'émulateur :" >&2
  cat "$LOG" >&2
fi
rm -f "$LOG"
exit "$code"
