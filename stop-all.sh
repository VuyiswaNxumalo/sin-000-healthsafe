#!/bin/bash
# stop-all.sh — stops all HealthSafe services started by start-all.sh.
# Leaves the ActiveMQ broker running (it doesn't need restarting each time).
# Run: ./stop-all.sh

ROOT="$(cd "$(dirname "$0")" && pwd)"
PIDFILE="$ROOT/logs/pids.txt"

if [ ! -f "$PIDFILE" ]; then
    echo "No pids.txt found - nothing to stop (or start-all.sh wasn't run from here)."
    exit 0
fi

echo "Stopping services..."
while read -r pid; do
    if kill "$pid" 2>/dev/null; then
        echo "  -> stopped process $pid"
    fi
done < "$PIDFILE"

rm -f "$PIDFILE"
echo "Done. (ActiveMQ broker left running - stop it separately with:"
echo "  cd common && sudo docker compose down)"