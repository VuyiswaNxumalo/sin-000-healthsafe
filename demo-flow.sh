#!/bin/bash
# demo-flow.sh — walks through all 4 HealthSafe stages, one command at a
# time, pausing after each so you can narrate before moving on. Press
# ENTER to advance to the next step. Run this AFTER start-all.sh.

pause() {
    echo ""
    read -p ">>> Press ENTER to continue... " _
    echo ""
}

echo "================================================================"
echo "STAGE 1 - Ingestion: cleaned ward data"
echo "================================================================"
echo "Command: curl http://localhost:7030/wards"
pause
curl -s http://localhost:7030/wards | python3 -m json.tool
pause

echo "================================================================"
echo "STAGE 2 - REST integration: compute a schedule for a VALID ward"
echo "================================================================"
echo "Command: curl http://localhost:7033/schedule/W-01"
pause
curl -s http://localhost:7033/schedule/W-01 | python3 -m json.tool
pause

echo "================================================================"
echo "STAGE 2 - REST integration: an INVALID ward (expect 404)"
echo "================================================================"
echo "Command: curl -i http://localhost:7033/schedule/W-99"
pause
curl -s -i http://localhost:7033/schedule/W-99
echo ""
pause

echo "================================================================"
echo "STAGE 3 - MQ decoupling: trigger the publish again"
echo "================================================================"
echo "Command: curl http://localhost:7033/schedule/W-01"
pause
curl -s http://localhost:7033/schedule/W-01 | python3 -m json.tool
pause

echo "================================================================"
echo "STAGE 3 - MQ decoupling: prove ward-service received it asynchronously"
echo "================================================================"
echo "Command: curl http://localhost:7031/wards/W-01/staffing"
pause
curl -s http://localhost:7031/wards/W-01/staffing | python3 -m json.tool
pause

echo "================================================================"
echo "STAGE 4 - Guaranteed delivery: report an equipment failure"
echo "================================================================"
echo 'Command: curl -X POST -H "Content-Type: application/json" \'
echo '  -d '"'"'{"equipment": "MRI Machine", "description": "Coolant pressure critical"}'"'"' \'
echo '  http://localhost:7031/wards/W-01/equipment-failure'
pause
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"equipment": "MRI Machine", "description": "Coolant pressure critical"}' \
  http://localhost:7031/wards/W-01/equipment-failure | python3 -m json.tool
pause

echo "================================================================"
echo "STAGE 4 - Guaranteed delivery: confirm it was processed"
echo "================================================================"
echo "Command: curl http://localhost:7034/alerts"
pause
curl -s http://localhost:7034/alerts | python3 -m json.tool

echo ""
echo "================================================================"
echo "Demo complete."
echo "================================================================"