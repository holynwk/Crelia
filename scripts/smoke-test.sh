#!/usr/bin/env bash
# ==============================================================================
# Crelia Smoke Test
# ==============================================================================
# Starts the Crelia server, monitors for successful startup, TPS reporting,
# and crash reports. Designed for use in CI pipelines.
#
# Usage: ./smoke-test.sh <path-to-creliatest2.jar> [timeout-seconds]
#
# Exit codes:
#   0 — Server started successfully, TPS reporting detected, no crashes
#   1 — Server crashed, failed to start, or TPS not detected within timeout
# ==============================================================================

set -euo pipefail

# ---------- Configuration ----------

JAR_PATH="${1:?Usage: smoke-test.sh <path-to-creliatest2.jar> [timeout-seconds]}"
TIMEOUT="${2:-120}"
LOG_FILE="smoke-test.log"
WORK_DIR="$(mktemp -d crelia-smoke-test.XXXXXX)"
EULA_FILE="$WORK_DIR/eula.txt"
SERVER_DIR="$WORK_DIR/server"

# Patterns to detect in server output
PATTERN_DONE='Done \('
PATTERN_TPS='TPS from Last 1m, 5m, 15m'

# ---------- Pre-flight ----------

if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: JAR not found: $JAR_PATH" >&2
    exit 1
fi

echo "=== Crelia Smoke Test ===" | tee "$LOG_FILE"
echo "JAR:      $JAR_PATH" | tee -a "$LOG_FILE"
echo "Timeout:  ${TIMEOUT}s" | tee -a "$LOG_FILE"
echo "Work dir: $WORK_DIR" | tee -a "$LOG_FILE"
echo "Log file: $LOG_FILE" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"

# ---------- Setup ----------

mkdir -p "$SERVER_DIR"

# Accept EULA
echo "eula=true" > "$EULA_FILE"

# ---------- Start server ----------

echo "Starting server..." | tee -a "$LOG_FILE"

# Launch server in background, capturing all output
java -Xms1G -Xmx2G \
    -XX:+UseG1GC \
    -XX:+ParallelRefProcEnabled \
    -Dcrelia.lod.mode=DISABLED \
    -jar "$JAR_PATH" \
    --nogui \
    > >(tee -a "$LOG_FILE") 2>&1 \
    &

SERVER_PID=$!

# ---------- Monitor ----------

echo "Server PID: $SERVER_PID" | tee -a "$LOG_FILE"
echo "Monitoring output (timeout: ${TIMEOUT}s)..." | tee -a "$LOG_FILE"

elapsed=0
interval=2
done_found=false
tps_found=false
crash_found=false
crash_files=""

while [ $elapsed -lt $TIMEOUT ]; do
    # Check if process is still alive
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "[${elapsed}s] Server process exited" | tee -a "$LOG_FILE"
        break
    fi

    # Check log for success patterns
    if [ "$done_found" = false ] && grep -q "$PATTERN_DONE" "$LOG_FILE" 2>/dev/null; then
        done_found=true
        echo "[${elapsed}s] ✓ Server startup detected: '$PATTERN_DONE'" | tee -a "$LOG_FILE"
    fi

    if [ "$tps_found" = false ] && grep -q "$PATTERN_TPS" "$LOG_FILE" 2>/dev/null; then
        tps_found=true
        echo "[${elapsed}s] ✓ TPS reporting detected: '$PATTERN_TPS'" | tee -a "$LOG_FILE"
    fi

    # Check for crash reports
    if [ -d "$SERVER_DIR/crash-reports" ]; then
        new_crashes=$(find "$SERVER_DIR/crash-reports" -name "crash-*.txt" -type f 2>/dev/null || true)
        if [ -n "$new_crashes" ]; then
            crash_found=true
            crash_files="$new_crashes"
            echo "[${elapsed}s] ✗ Crash report(s) detected!" | tee -a "$LOG_FILE"
            echo "$new_crashes" | while read -r crash; do
                echo "  Crash file: $crash" | tee -a "$LOG_FILE"
            done
        fi
    fi

    # If all checks pass, we can stop early
    if [ "$done_found" = true ] && [ "$tps_found" = true ]; then
        # Wait a few more seconds to make sure it stays up
        echo "[${elapsed}s] All checks passed. Waiting 10s for stability..." | tee -a "$LOG_FILE"
        sleep 10
        elapsed=$((elapsed + 10))

        # Final stability check — make sure it's still alive
        if kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "[${elapsed}s] ✓ Server stable after 10s" | tee -a "$LOG_FILE"
            break
        else
            echo "[${elapsed}s] ✗ Server crashed during stability check" | tee -a "$LOG_FILE"
            crash_found=true
            break
        fi
    fi

    sleep "$interval"
    elapsed=$((elapsed + interval))
done

# ---------- Shutdown ----------

echo "" | tee -a "$LOG_FILE"
echo "Stopping server (PID: $SERVER_PID)..." | tee -a "$LOG_FILE"

if kill -0 "$SERVER_PID" 2>/dev/null; then
    # Send /stop command via stdin — but since we're running with --nogui
    # and the process is backgrounded, we need a different approach.
    # Use 'stop' via a brief stdin pipe.
    echo "stop" | timeout 10s java -cp "$JAR_PATH" -jar "$JAR_PATH" --nogui 2>/dev/null || true

    # Wait up to 30 seconds for graceful shutdown
    waited=0
    while kill -0 "$SERVER_PID" 2>/dev/null && [ $waited -lt 30 ]; do
        sleep 1
        waited=$((waited + 1))
    done

    # Force kill if still running
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Force-killing server after 30s shutdown timeout" | tee -a "$LOG_FILE"
        kill -9 "$SERVER_PID" 2>/dev/null || true
    fi
fi

# ---------- Results ----------

echo "" | tee -a "$LOG_FILE"
echo "=== Smoke Test Results ===" | tee -a "$LOG_FILE"
echo "  Startup detected:  $([ "$done_found" = true ] && echo 'YES' || echo 'NO')" | tee -a "$LOG_FILE"
echo "  TPS reporting:      $([ "$tps_found" = true ] && echo 'YES' || echo 'NO')" | tee -a "$LOG_FILE"
echo "  Crash detected:     $([ "$crash_found" = true ] && echo 'YES' || echo 'NO')" | tee -a "$LOG_FILE"
echo "  Elapsed:            ${elapsed}s / ${TIMEOUT}s" | tee -a "$LOG_FILE"
echo "========================" | tee -a "$LOG_FILE"

# ---------- Cleanup ----------

# Copy any crash-reports and logs from server dir to workspace for CI upload
if [ -d "$SERVER_DIR/crash-reports" ]; then
    mkdir -p crash-reports
    cp -r "$SERVER_DIR/crash-reports/"* crash-reports/ 2>/dev/null || true
fi
if [ -d "$SERVER_DIR/logs" ]; then
    mkdir -p logs
    cp -r "$SERVER_DIR/logs/"* logs/ 2>/dev/null || true
fi

# Clean up temp dir
rm -rf "$WORK_DIR"

# ---------- Exit ----------

if [ "$crash_found" = true ]; then
    echo "FAIL: Crash report(s) detected" >&2
    exit 1
fi

if [ "$done_found" = false ]; then
    echo "FAIL: Server did not start (no 'Done (...)' detected within ${TIMEOUT}s)" >&2
    exit 1
fi

if [ "$tps_found" = false ]; then
    echo "FAIL: TPS reporting not detected within ${TIMEOUT}s" >&2
    exit 1
fi

echo "PASS: Smoke test successful"
exit 0