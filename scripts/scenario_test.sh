#!/bin/bash
# WeatherWall Scenario Performance Test Script
# Tests different weather/cloud configurations and captures performance metrics
# Usage: ./scenario_test.sh

PACKAGE="info.olli.weatherlwp"
WAIT_SECONDS=10  # How long to wait for each scenario

# Function to set SharedPreferences value
set_pref() {
    local key="$1"
    local type="$2"  # string, int, boolean
    local value="$3"
    
    case $type in
        string)
            adb shell am broadcast -a android.intent.action.CALL \
                --es "${key}" "${value}" 2>/dev/null || true
            # Use alternative: direct SharedPreferences write via am
            ;;
        int)
            adb shell "run-as $PACKAGE cat /dev/null > /data/data/$PACKAGE/cache/.pref_update" 2>/dev/null || true
            ;;
        boolean)
            adb shell "run-as $PACKAGE cat /dev/null > /data/data/$PACKAGE/cache/.pref_update" 2>/dev/null || true
            ;;
    esac
}

# Function to reset gfxinfo stats
reset_stats() {
    adb shell dumpsys gfxinfo $PACKAGE reset > /dev/null
}

# Function to capture gfxinfo and extract metrics
capture_metrics() {
    local scenario="$1"
    echo "=== Scenario: $scenario ===" >> /tmp/perf_report.txt
    
    local output=$(adb shell dumpsys gfxinfo $PACKAGE framestats 2>&1)
    
    # Extract key metrics
    local frames=$(echo "$output" | grep "Total frames rendered" | grep -oP '\d+' | head -1)
    local janky=$(echo "$output" | grep "Janky frames:" | head -1 | grep -oP '\d+' | head -1)
    local janky_pct=$(echo "$output" | grep "Janky frames:" | head -1 | grep -oP '\d+\.\d+' | head -1)
    local p50=$(echo "$output" | grep "50th percentile" | grep -oP '\d+' | head -1)
    local p90=$(echo "$output" | grep "90th percentile" | grep -oP '\d+' | head -1)
    local p95=$(echo "$output" | grep "95th percentile" | grep -oP '\d+' | head -1)
    local p99=$(echo "$output" | grep "99th percentile" | grep -oP '\d+' | head -1)
    local missed=$(echo "$output" | grep "Number Frame deadline missed:" | head -1 | grep -oP '\d+' | head -1)
    
    echo "  Total frames: $frames" >> /tmp/perf_report.txt
    echo "  Janky frames: $janky ($janky_pct%)" >> /tmp/perf_report.txt
    echo "  50th percentile: ${p50}ms" >> /tmp/perf_report.txt
    echo "  90th percentile: ${p90}ms" >> /tmp/perf_report.txt
    echo "  95th percentile: ${p95}ms" >> /tmp/perf_report.txt
    echo "  99th percentile: ${p99}ms" >> /tmp/perf_report.txt
    echo "  Frame deadlines missed: $missed" >> /tmp/perf_report.txt
    echo "" >> /tmp/perf_report.txt
    
    # Print to console
    echo "[$scenario] Frames: $frames, Janky: $janky ($janky_pct%), P50: ${p50}ms, P95: ${p95}ms, P99: ${p99}ms"
}

# Initialize report
echo "WeatherWall Performance Test Report" > /tmp/perf_report.txt
echo "Generated: $(date)" >> /tmp/perf_report.txt
echo "" >> /tmp/perf_report.txt

echo "Starting performance scenario tests..."
echo "Each scenario runs for $WAIT_SECONDS seconds"
echo ""

# Test scenarios - we'll use manual gfxinfo snapshots between scenarios
# User should manually change settings in app or we broadcast intents

# Scenario 1: Current state (baseline)
echo "Testing Scenario 1: Current State (Baseline)..."
reset_stats
sleep $WAIT_SECONDS
capture_metrics "Baseline (Current Settings)"

# For interactive testing, we'll prompt user to change settings
echo ""
echo "=== INTERACTIVE TESTING MODE ==="
echo "Please change weather settings in the app between each measurement."
echo "After changing settings, press ENTER to measure the next scenario."
echo ""

scenarios=(
    "Clear Sky (no clouds)"
    "Light Clouds (10 clouds)"
    "Medium Clouds (25 clouds)"
    "Heavy Clouds (50 clouds)"
    "Overcast (75+ clouds)"
    "Rain Weather"
    "Snow Weather"
    "Thunderstorm"
    "High Wind (80%+)"
    "Night Time"
    "Dawn/Dusk Transition"
)

for scenario in "${scenarios[@]}"; do
    echo "Prepare scenario: $scenario"
    read -p "Press ENTER when ready to measure..." dummy
    reset_stats
    echo "Measuring for $WAIT_SECONDS seconds..."
    sleep $WAIT_SECONDS
    capture_metrics "$scenario"
done

echo ""
echo "=== Test Complete ==="
echo "Full report saved to: /tmp/perf_report.txt"
cat /tmp/perf_report.txt
