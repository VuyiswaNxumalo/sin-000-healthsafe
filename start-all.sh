#!/bin/bash
# start-all.sh — starts the ActiveMQ broker and all 5 HealthSafe services,
# in the correct order, waiting for each to become healthy before starting
# the next. Run this from the repo root: ./start-all.sh
#
# Logs for each service go to logs/<service-name>.log — check there if a
# service doesn't come up.

set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
LOGDIR="$ROOT/logs"
mkdir -p "$LOGDIR"
rm -f "$LOGDIR/pids.txt"

echo "=== Starting ActiveMQ broker ==="
(cd "$ROOT/common" && sudo docker compose up -d)
sleep 5

wait_for_health() {
    name=$1
    port=$2
    for i in $(seq 1 20); do
        if curl -s "http://localhost:$port/health" 2>/dev/null | grep -q "OK"; then
            echo "  -> $name is up (port $port)"
            return 0
        fi
        sleep 1
    done
    echo "  -> WARNING: $name did not respond after 20s. Check $LOGDIR/$name.log"
    return 1
}

start_service() {
    name=$1
    port=$2
    dir=$3
    jar=$4

    echo "=== Starting $name ==="
    cd "$ROOT/$dir"
    nohup java -jar "target/$jar" > "$LOGDIR/$name.log" 2>&1 &
    echo $! >> "$LOGDIR/pids.txt"
    cd "$ROOT"
    wait_for_health "$name" "$port"
}

start_service ingestion-service 7030 ingestion-service ingestion-service.jar
start_service ward-service 7031 ward-service ward-service.jar
start_service alert-level-service 7032 alert-level-service alert-level-service.jar
start_service staffing-service 7033 staffing-service staffing-service.jar
start_service equipment-alert-service 7034 equipment-alert-service equipment-alert-service.jar

echo ""
echo "=== All services started ==="
echo "Logs: $LOGDIR/"
echo "To stop everything, run: ./stop-all.sh"