#!/usr/bin/env bash
set -euo pipefail

# Run separate instrumentation sessions with an explicit app force-stop
# between them to exercise cold process relaunches with the same DB.
for pass in 1 2 3; do
  echo "Cold-process startup pass ${pass}/3"
  gradle --rerun-tasks :app:connectedDebugAndroidTest
  if [[ "${pass}" -lt 3 ]]; then
    adb shell am force-stop com.relab.app
    sleep 2
  fi
done
