#!/bin/sh
set -eu
url="${1:-http://localhost:8080/actuator/health/readiness}"
i=0
until curl --fail --silent "$url" | grep -q '"status":"UP"'; do
  i=$((i + 1))
  [ "$i" -lt 90 ] || { echo "timed out waiting for $url" >&2; exit 1; }
  sleep 1
done
echo "ready: $url"
