#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$ROOT/.runtime"
LOCKED=0
if [[ "${1:-}" == --locked ]]; then
  LOCKED=1
  shift
fi
ACTION="${1:-status}"
TARGET="${2:-all}"

case "$ACTION" in start|stop|status) ;; *) echo "Usage: $0 {start|stop|status} [all|backend|frontend]" >&2; exit 1 ;; esac
case "$TARGET" in all|backend|frontend) ;; *) echo "Unknown server: $TARGET" >&2; exit 1 ;; esac
mkdir -p "$RUNTIME"

configure() {
  NAME="$1"
  PID_FILE="$RUNTIME/$NAME.pid"
  LOG_FILE="$RUNTIME/$NAME.log"
  if [[ "$NAME" == backend ]]; then
    PORT=8080
    HEALTH="http://127.0.0.1:$PORT/actuator/health"
  else
    PORT=5173
    HEALTH="http://127.0.0.1:$PORT/"
  fi
}

# Compare process start time as well as PID so a reused PID is never stopped.
running() {
  [[ -f "$PID_FILE" ]] || return 1
  PID="$(head -n 1 "$PID_FILE")"
  [[ "$PID" =~ ^[0-9]+$ ]] || return 1
  local expected actual command_line
  expected="$(sed -n '2p' "$PID_FILE")"
  actual="$(ps -p "$PID" -o lstart= 2>/dev/null)" || return 1
  [[ -n "$expected" && "$actual" == "$expected" ]] || return 1
  command_line="$(ps -p "$PID" -o command=)" || return 1
  [[ "$command_line" == *"$ROOT/"* ]]
}

healthy() {
  local response
  response="$(curl --noproxy '*' -fsS --max-time 2 "$HEALTH" 2>/dev/null)" || return 1
  [[ "$NAME" == frontend || "$response" == *'"status":"UP"'* ]]
}

# A separate session keeps servers alive when the launching terminal closes.
launch() {
  PID="$(python3 - "$LOG_FILE" "$@" <<'PY'
import subprocess
import sys

with open(sys.argv[1], "ab", buffering=0) as log:
    process = subprocess.Popen(
        sys.argv[2:], stdin=subprocess.DEVNULL, stdout=log, stderr=log,
        start_new_session=True,
    )
print(process.pid)
PY
  )" || return 1
}

start_server() {
  if running; then
    echo "$NAME already running (PID $PID): http://127.0.0.1:$PORT"
    return
  fi
  if [[ -n "$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)" ]]; then
    echo "Port $PORT is in use by an unmanaged process. Stop it before starting $NAME." >&2
    return 1
  fi

  if [[ "$NAME" == backend ]]; then
    local graph="$ROOT/backend/data/processed/seoul.rgraph"
    [[ -f "$graph" ]] || { echo "Run scripts/build-seoul.sh first." >&2; return 1; }
    echo "Building backend... (log: $LOG_FILE)"
    if ! "$ROOT/backend/gradlew" -p "$ROOT/backend" :routing-api:bootJar > "$LOG_FILE" 2>&1; then
      tail -n 30 "$LOG_FILE" >&2
      return 1
    fi
    local jars=() jar
    for jar in "$ROOT"/backend/routing-api/build/libs/*.jar; do
      [[ -f "$jar" && "$jar" != *-plain.jar ]] && jars+=("$jar")
    done
    [[ ${#jars[@]} -eq 1 ]] || { echo "Expected one executable backend JAR in build/libs." >&2; return 1; }
    local java_cmd=java
    [[ -z "${JAVA_HOME:-}" ]] || java_cmd="$JAVA_HOME/bin/java"
    launch "$java_cmd" -jar "${jars[0]}" --server.port=8080 \
      "--routing.graph.path=$graph" "--routing.exclusions.path=$graph.exclusions.geojson" || return 1
  else
    [[ -f "$ROOT/frontend/node_modules/vite/bin/vite.js" ]] || {
      echo "Install frontend dependencies first: npm ci --prefix frontend" >&2; return 1;
    }
    : > "$LOG_FILE"
    launch node "$ROOT/frontend/node_modules/vite/bin/vite.js" "$ROOT/frontend" \
      --host 127.0.0.1 --port 5173 --strictPort || return 1
  fi
  local started
  started="$(ps -p "$PID" -o lstart=)" || { tail -n 30 "$LOG_FILE" >&2; return 1; }
  printf '%s\n%s\n' "$PID" "$started" > "$PID_FILE"

  local attempt
  for ((attempt=0; attempt<120; attempt++)); do
    if ! running; then
      echo "$NAME exited during startup. Log: $LOG_FILE" >&2
      tail -n 30 "$LOG_FILE" >&2
      return 1
    fi
    if healthy; then
      echo "$NAME started (PID $PID): http://127.0.0.1:$PORT"
      return
    fi
    sleep 1
  done
  echo "$NAME did not become ready. Log: $LOG_FILE" >&2
  stop_server
  return 1
}

stop_server() {
  if ! running; then
    echo "$NAME is not running under these scripts."
    return
  fi
  echo "Stopping $NAME (PID $PID)..."
  kill -TERM "$PID" || return 1
  local attempt
  for ((attempt=0; attempt<30; attempt++)); do
    if ! running; then
      rm -f "$PID_FILE"
      echo "$NAME stopped."
      return
    fi
    sleep 1
  done
  echo "$NAME has not stopped after 30 seconds; leaving its PID file for inspection." >&2
  return 1
}

status_server() {
  if running; then
    if healthy; then
      echo "$NAME running (PID $PID): http://127.0.0.1:$PORT"
    else
      echo "$NAME process exists (PID $PID), but HTTP health check failed. Log: $LOG_FILE"
    fi
  elif [[ -n "$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)" ]]; then
    echo "$NAME port $PORT is occupied by an unmanaged process."
  else
    echo "$NAME stopped."
  fi
}

result=0
for server in backend frontend; do
  if [[ "$TARGET" == all || "$TARGET" == "$server" ]]; then
    if [[ "$LOCKED" == 1 ]]; then
      configure "$server"
      ( "${ACTION}_server" ) || result=1
    else
      # Serialize each server's state checks, launch/stop and PID-file writes.
      python3 "$ROOT/scripts/with-server-lock.py" "$RUNTIME/$server.lock" \
        bash "$ROOT/scripts/servers.sh" --locked "$ACTION" "$server" || result=1
    fi
  fi
done
exit "$result"
