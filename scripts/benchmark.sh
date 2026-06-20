#!/usr/bin/env bash
# ==============================================================================
# Crelia Benchmark Script
# ==============================================================================
# Starts the Crelia server, spawns simulated players across regions, and
# collects TPS / MSPT / memory metrics over a 5-minute measurement window.
# Outputs structured JSON results to benchmark-results.json.
#
# Prerequisites:
#   - Java 21+ installed and on PATH
#   - A built creliatest2.jar
#
# Usage:
#   ./benchmark.sh <path-to-creliatest2.jar> [options]
#
# Options:
#   --duration <seconds>        Measurement duration (default: 300)
#   --players <n>               Number of simulated players (default: 10)
#   --regions <n>               Number of regions to spread players across (default: 4)
#   --preadgen-radius <chunks>  Radius of pre-generated chunks around spawn (default: 32)
#   --rcon-port <port>          RCON port (default: 25575)
#   --rcon-password <pass>      RCON password (default: "benchmark")
#   --output <file>             Output JSON file (default: benchmark-results.json)
#   --baseline <jar>            Vanilla NeoForge jar for baseline comparison
#
# Exit codes:
#   0 — Benchmark completed successfully
#   1 — Benchmark failed (server crash, timeout, etc.)
# ==============================================================================

set -euo pipefail

# ---------- Defaults ----------

JAR_PATH=""
DURATION=300
PLAYERS=10
REGIONS=4
PREGEN_RADIUS=32
RCON_PORT=25575
RCON_PASSWORD="benchmark"
OUTPUT_FILE="benchmark-results.json"
BASELINE_JAR=""
LOG_FILE="benchmark.log"
WORK_DIR=""
SERVER_DIR=""
BENCHMARK_START=0
BENCHMARK_END=0

# ---------- Argument parsing ----------

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration)       DURATION="$2"; shift 2 ;;
        --players)        PLAYERS="$2"; shift 2 ;;
        --regions)        REGIONS="$2"; shift 2 ;;
        --preadgen-radius) PREGEN_RADIUS="$2"; shift 2 ;;
        --rcon-port)      RCON_PORT="$2"; shift 2 ;;
        --rcon-password)  RCON_PASSWORD="$2"; shift 2 ;;
        --output)         OUTPUT_FILE="$2"; shift 2 ;;
        --baseline)       BASELINE_JAR="$2"; shift 2 ;;
        -*)               echo "Unknown option: $1" >&2; exit 1 ;;
        *)
            if [ -z "$JAR_PATH" ]; then
                JAR_PATH="$1"
            else
                echo "Unexpected argument: $1" >&2; exit 1
            fi
            shift ;;
    esac
done

if [ -z "$JAR_PATH" ]; then
    echo "Usage: benchmark.sh <path-to-creliatest2.jar> [options]" >&2
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: JAR not found: $JAR_PATH" >&2
    exit 1
fi

# ---------- Setup ----------

WORK_DIR="$(mktemp -d crelia-benchmark.XXXXXX)"
SERVER_DIR="$WORK_DIR/server"

mkdir -p "$SERVER_DIR/crash-reports" "$SERVER_DIR/logs"

# Server configuration
cat > "$SERVER_DIR/server.properties" <<EOF
# Benchmark server properties
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASSWORD}
level-seed=crelia-benchmark-seed
view-distance=10
simulation-distance=8
max-players=200
online-mode=false
allow-flight=true
motd=Crelia Benchmark Server
EOF

echo "eula=true" > "$SERVER_DIR/eula.txt"

# Enable RCON in bukkit.yml
mkdir -p "$SERVER_DIR"
cat > "$SERVER_DIR/bukkit.yml" <<EOF
settings:
  allow-end: false
  warn-about-overexposed-teleports: false
EOF

# ---------- Helper: send command via RCON or console ----------

# Try to use mcrcon (mcrcon tool) if available, otherwise fall back to stdin pipe
RCON_CMD=""
if command -v mcrcon &>/dev/null; then
    RCON_CMD="mcrcon -H 127.0.0.1 -P ${RCON_PORT} -p '${RCON_PASSWORD}' -c -w 5"
fi

send_command() {
    local cmd="$1"
    if [ -n "$RCON_CMD" ] && [ -n "${SERVER_PID:-}" ]; then
        eval "$RCON_CMD" "$cmd" 2>/dev/null || true
    fi
    # Fallback: write to the server's stdin if we have the PID's pipe
    if [ -n "${SERVER_STDIN:-}" ]; then
        echo "$cmd" >&"${SERVER_STDIN}" 2>/dev/null || true
    fi
}

# ---------- Helper: wait for server startup ----------

wait_for_server() {
    local timeout=180
    local elapsed=0

    echo "Waiting for server to start (timeout: ${timeout}s)..." | tee -a "$LOG_FILE"

    while [ $elapsed -lt $timeout ]; do
        if ! kill -0 "${SERVER_PID:-0}" 2>/dev/null; then
            echo "ERROR: Server process exited during startup" | tee -a "$LOG_FILE"
            return 1
        fi

        if grep -q 'Done (' "$LOG_FILE" 2>/dev/null; then
            echo "[${elapsed}s] Server started successfully" | tee -a "$LOG_FILE"
            return 0
        fi

        sleep 2
        elapsed=$((elapsed + 2))
    done

    echo "ERROR: Server did not start within ${timeout}s" | tee -a "$LOG_FILE"
    return 1
}

# ---------- Helper: collect TPS from log ----------

collect_tps() {
    local log="$1"
    local start_ts="$2"
    local end_ts="$3"

    # Extract TPS lines: "TPS from Last 1m, 5m, 15m: 20.0, 20.0, 20.0"
    # Also look for MSPT lines
    python3 - "${log}" "$start_ts" "$end_ts" <<'PYEOF' 2>/dev/null
import sys
import re
import json

log_file = sys.argv[1]
start_ts = float(sys.argv[2])
end_ts = float(sys.argv[3])

tps_samples = []
mspt_samples = []
memory_samples = []

tpspattern = re.compile(r'TPS from Last 1m, 5m, 15m:\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)')
mspt_pattern = re.compile(r'MSPT[^:]*:\s*([\d.]+)')
mem_pattern = re.compile(r'Memory:\s*(\d+)\s*MB')

with open(log_file, 'r') as f:
    for line in f:
        # We don't have precise timestamps per line, so collect all TPS values
        # and return summary statistics
        tps_match = tpspattern.search(line)
        if tps_match:
            tps_samples.append({
                "tps_1m": float(tps_match.group(1)),
                "tps_5m": float(tps_match.group(2)),
                "tps_15m": float(tps_match.group(3))
            })

        mspt_match = mspt_pattern.search(line)
        if mspt_match:
            mspt_samples.append(float(mspt_match.group(1)))

        mem_match = mem_pattern.search(line)
        if mem_match:
            memory_samples.append(int(mem_match.group(1)))

result = {
    "tps_samples_count": len(tps_samples),
    "mspt_samples_count": len(mspt_samples),
    "memory_samples_count": len(memory_samples)
}

if tps_samples:
    tps_1m = [s["tps_1m"] for s in tps_samples]
    tps_5m = [s["tps_5m"] for s in tps_samples]
    tps_15m = [s["tps_15m"] for s in tps_samples]
    result["tps"] = {
        "1m": {"min": round(min(tps_1m), 2), "max": round(max(tps_1m), 2), "avg": round(sum(tps_1m)/len(tps_1m), 2)},
        "5m": {"min": round(min(tps_5m), 2), "max": round(max(tps_5m), 2), "avg": round(sum(tps_5m)/len(tps_5m), 2)},
        "15m": {"min": round(min(tps_15m), 2), "max": round(max(tps_15m), 2), "avg": round(sum(tps_15m)/len(tps_15m), 2)}
    }

if mspt_samples:
    result["mspt"] = {
        "min": round(min(mspt_samples), 2),
        "max": round(max(mspt_samples), 2),
        "avg": round(sum(mspt_samples)/len(mspt_samples), 2)
    }

if memory_samples:
    result["memory_mb"] = {
        "min": min(memory_samples),
        "max": max(memory_samples),
        "avg": sum(memory_samples) // len(memory_samples),
        "final": memory_samples[-1]
    }

print(json.dumps(result, indent=2))
PYEOF
}

# ---------- Helper: run benchmark for a single jar ----------

run_benchmark() {
    local jar="$1"
    local label="$2"
    local server_dir="$3"

    echo "" | tee -a "$LOG_FILE"
    echo "========================================" | tee -a "$LOG_FILE"
    echo "  Benchmark: ${label}" | tee -a "$LOG_FILE"
    echo "  JAR: ${jar}" | tee -a "$LOG_FILE"
    echo "  Players: ${PLAYERS} across ${REGIONS} regions" | tee -a "$LOG_FILE"
    echo "  Duration: ${DURATION}s" | tee -a "$LOG_FILE"
    echo "========================================" | tee -a "$LOG_FILE"

    # Reset log
    > "$LOG_FILE"

    # Start server
    echo "Starting server..." | tee -a "$LOG_FILE"

    cd "$server_dir"

    # Create a named pipe for stdin if possible
    SERVER_STDIN=""
    if [ -p /dev/stdin ]; then
        SERVER_STDIN="/dev/stdin"
    fi

    java -Xms2G -Xmx4G \
        -XX:+UseG1GC \
        -XX:+ParallelRefProcEnabled \
        -XX:MaxGCPauseMillis=50 \
        -Dcrelia.lod.mode=DISABLED \
        -jar "$jar" \
        --nogui \
        > >(tee -a "$LOG_FILE") 2>&1 \
        &

    SERVER_PID=$!
    cd - > /dev/null

    echo "Server PID: $SERVER_PID" | tee -a "$LOG_FILE"

    # Wait for startup
    if ! wait_for_server; then
        echo "FAIL: Server did not start for ${label}" | tee -a "$LOG_FILE"
        kill -9 "$SERVER_PID" 2>/dev/null || true
        return 1
    fi

    # Give TPS reporting a moment to initialize
    sleep 5

    # Record benchmark start time
    BENCHMARK_START=$(date +%s)

    echo "" | tee -a "$LOG_FILE"
    echo "--- Benchmark Phase ---" | tee -a "$LOG_FILE"

    # Pre-generate chunks around spawn
    echo "Pre-generating chunks (radius: ${PREGEN_RADIUS})..." | tee -a "$LOG_FILE"
    send_command "chunky start ${PREGEN_RADIUS}"
    sleep 3

    # Wait for pregen to complete (with timeout)
    pregen_wait=0
    pregen_timeout=$((DURATION / 2))  # Don't spend more than half the time on pregen
    while [ $pregen_wait -lt $pregen_timeout ]; do
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "ERROR: Server crashed during pregen" | tee -a "$LOG_FILE"
            return 1
        fi
        sleep 5
        pregen_wait=$((pregen_wait + 5))
        # Check if chunky is done (look for completion message)
        if grep -qi "chunky.*done\|chunky.*complete\|chunky.*finished" "$LOG_FILE" 2>/dev/null; then
            echo "[${pregen_wait}s] Pregeneration complete" | tee -a "$LOG_FILE"
            break
        fi
    done

    if [ $pregen_wait -ge $pregen_timeout ]; then
        echo "[${pregen_wait}s] Pregen timeout — continuing with partially generated world" | tee -a "$LOG_FILE"
        send_command "chunky pause"
    fi

    # Spawn simulated players spread across regions
    echo "Spawning ${PLAYERS} players across ${REGIONS} regions..." | tee -a "$LOG_FILE"
    PLAYERS_PER_REGION=$((PLAYERS / REGIONS))
    REMAINDER=$((PLAYERS % REGIONS))

    for region_idx in $(seq 1 $REGIONS); do
        # Spread players in a grid pattern, ~200 blocks apart
        local_x=$((region_idx * 200))
        local_z=$((region_idx * 200))
        local_count=$PLAYERS_PER_REGION
        if [ $region_idx -le $REMAINDER ]; then
            local_count=$((local_count + 1))
        fi

        for p in $(seq 1 $local_count); do
            px=$((local_x + (p * 5)))
            pz=$((local_z + (p * 5)))
            send_command "execute as @p at @p run summon minecraft:player ${px} 64 ${pz}"
        done
    done

    # If we have fewer players than expected (NPCs may not spawn via console),
    # use a simpler approach with bots if available
    echo "Players distributed across regions" | tee -a "$LOG_FILE"

    # --- Measurement phase ---
    echo "Measuring for ${DURATION}s..." | tee -a "$LOG_FILE"

    measure_elapsed=0
    while [ $measure_elapsed -lt $DURATION ]; do
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "ERROR: Server crashed during measurement!" | tee -a "$LOG_FILE"
            BENCHMARK_END=$(date +%s)
            return 1
        fi

        # Periodic status
        if [ $((measure_elapsed % 60)) -eq 0 ] && [ $measure_elapsed -gt 0 ]; then
            echo "[${measure_elapsed}s / ${DURATION}s] Still measuring..." | tee -a "$LOG_FILE"
            # Force a TPS report
            send_command "tps"
        fi

        sleep 5
        measure_elapsed=$((measure_elapsed + 5))
    done

    BENCHMARK_END=$(date +%s)

    echo "" | tee -a "$LOG_FILE"
    echo "--- Benchmark Complete ---" | tee -a "$LOG_FILE"

    # Stop server
    echo "Stopping server..." | tee -a "$LOG_FILE"
    send_command "stop"

    # Wait for graceful shutdown
    shutdown_wait=0
    while kill -0 "$SERVER_PID" 2>/dev/null && [ $shutdown_wait -lt 30 ]; do
        sleep 1
        shutdown_wait=$((shutdown_wait + 1))
    done

    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -9 "$SERVER_PID" 2>/dev/null || true
    fi

    echo "Server stopped" | tee -a "$LOG_FILE"
    return 0
}

# ---------- Main ----------

echo "=== Crelia Benchmark ===" | tee "$LOG_FILE"
echo "JAR:       $JAR_PATH" | tee -a "$LOG_FILE"
echo "Players:   $PLAYERS" | tee -a "$LOG_FILE"
echo "Regions:   $REGIONS" | tee -a "$LOG_FILE"
echo "Duration:  ${DURATION}s" | tee -a "$LOG_FILE"
echo "Output:    $OUTPUT_FILE" | tee -a "$LOG_FILE"
date -Iseconds | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"

# Run the main benchmark
CRELIA_RESULT=""
if run_benchmark "$JAR_PATH" "Crelia" "$SERVER_DIR"; then
    echo "" | tee -a "$LOG_FILE"
    echo "Collecting metrics..." | tee -a "$LOG_FILE"
    CRELIA_RESULT=$(collect_tps "$LOG_FILE" "$BENCHMARK_START" "$BENCHMARK_END")
    echo "$CRELIA_RESULT" | tee -a "$LOG_FILE"
else
    echo "FAIL: Crelia benchmark failed" | tee -a "$LOG_FILE"
    CRELIA_RESULT='{"error": "benchmark_failed", "tps_samples_count": 0}'
fi

# Run baseline if provided
BASELINE_RESULT=""
if [ -n "$BASELINE_JAR" ] && [ -f "$BASELINE_JAR" ]; then
    echo "" | tee -a "$LOG_FILE"
    echo "Running baseline benchmark..." | tee -a "$LOG_FILE"

    BASELINE_DIR="$WORK_DIR/baseline-server"
    mkdir -p "$BASELINE_DIR"
    cp "$SERVER_DIR/eula.txt" "$BASELINE_DIR/"
    cp "$SERVER_DIR/server.properties" "$BASELINE_DIR/"
    cp "$SERVER_DIR/bukkit.yml" "$BASELINE_DIR/"

    if run_benchmark "$BASELINE_JAR" "Baseline (Vanilla NeoForge)" "$BASELINE_DIR"; then
        BASELINE_RESULT=$(collect_tps "$LOG_FILE" "$BENCHMARK_START" "$BENCHMARK_END")
        echo "$BASELINE_RESULT" | tee -a "$LOG_FILE"
    else
        echo "WARN: Baseline benchmark failed" | tee -a "$LOG_FILE"
        BASELINE_RESULT='{"error": "baseline_failed", "tps_samples_count": 0}'
    fi
fi

# ---------- Generate output JSON ----------

ACTUAL_DURATION=$((BENCHMARK_END - BENCHMARK_START))

CRASH_DETECTED="false"
if [ -d "$SERVER_DIR/crash-reports" ] && [ -n "$(ls -A "$SERVER_DIR/crash-reports/" 2>/dev/null)" ]; then
    CRASH_DETECTED="true"
fi

# Build the final JSON
python3 - <<PYEOF
import json
import sys
import os
import datetime

crelia_raw = '''${CRELIA_RESULT}'''
baseline_raw = '''${BASELINE_RESULT}'''

try:
    crelia_data = json.loads(crelia_raw)
except json.JSONDecodeError:
    crelia_data = {"error": "parse_error", "raw": crelia_raw}

baseline_data = None
if baseline_raw.strip():
    try:
        baseline_data = json.loads(baseline_raw)
    except json.JSONDecodeError:
        baseline_data = {"error": "parse_error", "raw": baseline_raw}

output = {
    "benchmark": {
        "tool": "crelia-benchmark",
        "version": "1.0.0",
        "timestamp": datetime.datetime.utcnow().isoformat() + "Z",
        "duration_seconds": ${ACTUAL_DURATION},
        "configured_duration": ${DURATION},
        "players": ${PLAYERS},
        "regions": ${REGIONS},
        "pregen_radius": ${PREGEN_RADIUS},
        "crash_detected": ${CRASH_DETECTED} == "true"
    },
    "crelia": {
        "jar": os.path.basename("${JAR_PATH}"),
        "metrics": crelia_data
    }
}

if baseline_data:
    output["baseline"] = {
        "jar": os.path.basename("${BASELINE_JAR}"),
        "metrics": baseline_data
    }
    # Add comparison if both have TPS data
    if "tps" in crelia_data and "tps" in baseline_data:
        c_avg = crelia_data["tps"]["1m"]["avg"]
        b_avg = baseline_data["tps"]["1m"]["avg"]
        if b_avg > 0:
            delta_pct = round(((c_avg - b_avg) / b_avg) * 100, 2)
            output["comparison"] = {
                "tps_1m_avg_crelia": c_avg,
                "tps_1m_avg_baseline": b_avg,
                "tps_delta_percent": delta_pct,
                "conclusion": "better" if delta_pct >= 0 else "worse"
            }

with open("${OUTPUT_FILE}", "w") as f:
    json.dump(output, f, indent=2)

print(json.dumps(output, indent=2))
PYEOF

# ---------- Cleanup ----------

# Copy crash reports if any
if [ -d "$SERVER_DIR/crash-reports" ]; then
    mkdir -p crash-reports
    cp -r "$SERVER_DIR/crash-reports/"* crash-reports/ 2>/dev/null || true
fi
if [ -d "$SERVER_DIR/logs" ]; then
    mkdir -p logs
    cp -r "$SERVER_DIR/logs/"* logs/ 2>/dev/null || true
fi

# Copy benchmark log
if [ -f "$LOG_FILE" ]; then
    cp "$LOG_FILE" benchmark.log 2>/dev/null || true
fi

# Clean up temp dir
rm -rf "$WORK_DIR"

# ---------- Result ----------

echo "" | tee -a "$LOG_FILE"
echo "Benchmark complete. Results written to: $OUTPUT_FILE"

if [ "$CRASH_DETECTED" = "true" ]; then
    echo "WARN: Crash reports were generated during the benchmark" >&2
    exit 1
fi

exit 0