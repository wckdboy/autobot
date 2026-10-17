#!/bin/sh
# Launch timing on a simulator: installs an Autobot.app, launches it RUNS times (terminated
# in between, so each is a process cold start) with -AutobotLaunchTrace, and prints the median of
# each milestone LaunchTrace recorded (ms since process start, main-thread CPU ms so far).
#
#   scripts/launch-bench.sh <simulator-id> <path/to/Autobot.app> [runs] [extra launch args…]
#
# The first launch seeds the demo data (-AutobotDemo) and is not counted. Release builds ignore the
# debug arguments: the app lock must be off (Settings › Privacy) and onboarding done, or no
# milestone after the lock screen is reached.
set -eu
SIM="$1"; APP="$2"; RUNS="${3:-10}"
shift 2; [ $# -gt 0 ] && shift
BUNDLE=ai.wckd.autobot
ARGS="-AutobotScreenshots -AutobotDemo -AutobotSkipWelcome -AutobotLaunchTrace $*"
OUT="$(mktemp -d)"

xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl install "$SIM" "$APP"
DATA="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)"
TRACE="$DATA/Library/Caches/launch-trace.json"

run() {
  xcrun simctl terminate "$SIM" "$BUNDLE" 2>/dev/null || true
  rm -f "$TRACE"
  sleep 1
  # shellcheck disable=SC2086
  xcrun simctl launch "$SIM" "$BUNDLE" $ARGS > /dev/null
  i=0
  while [ ! -f "$TRACE" ] && [ $i -lt 30 ]; do sleep 0.5; i=$((i + 1)); done
  if [ -f "$TRACE" ]; then cp "$TRACE" "$1"; else echo "no trace from this launch (app lock on, or not onboarded?)" >&2; fi
}

run "$OUT/warmup.json"
n=1
while [ $n -le "$RUNS" ]; do run "$OUT/run$n.json"; n=$((n + 1)); done
xcrun simctl terminate "$SIM" "$BUNDLE" 2>/dev/null || true

python3 - "$OUT" <<'EOF'
import glob, json, statistics, sys
runs = [json.load(open(p)) for p in sorted(glob.glob(sys.argv[1] + "/run*.json"))]
keys = sorted({k for r in runs for k in r}, key=lambda k: statistics.median(r[k]["ms"] for r in runs if k in r))
print(f"{len(runs)} cold launches (median; p90 in brackets)")
for k in keys:
    ms = sorted(r[k]["ms"] for r in runs if k in r)
    cpu = statistics.median(r[k]["cpuMs"] for r in runs if k in r)
    p90 = ms[min(len(ms) - 1, int(len(ms) * 0.9))]
    print(f"  {k:24s} {statistics.median(ms):8.1f} ms [{p90:7.1f}]   thread cpu {cpu:7.1f} ms")
EOF
