#!/bin/sh
set -eu
base="${BASE_URL:-http://localhost:8080}"
compose="${COMPOSE_COMMAND:-docker compose}"
sku="restart-$(date +%s)"
key="$(python3 -c 'import uuid; print(uuid.uuid4())')"

item_json="$(curl --fail --silent -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$sku\",\"initialStock\":2}" "$base/items")"
item_id="$(printf '%s' "$item_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

first="$(curl --fail --silent -H 'Content-Type: application/json' \
  -d "{\"reservation_id\":\"$key\",\"quantity\":1}" "$base/items/$item_id/reserve")"
printf '%s' "$first" | python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["status"]=="SUCCEEDED" and not x["replay"]'

$compose stop safe >/dev/null
$compose start safe >/dev/null
"$(dirname "$0")/wait-ready.sh" "$base/actuator/health/readiness" >/dev/null

replay="$(curl --fail --silent -H 'Content-Type: application/json' \
  -d "{\"reservation_id\":\"$key\",\"quantity\":1}" "$base/items/$item_id/reserve")"
printf '%s' "$replay" | python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["status"]=="SUCCEEDED" and x["replay"]'

# Model an ambiguous client-side timeout. The first call may or may not commit;
# retrying its durable key must converge on one successful side effect.
timeout_key="$(python3 -c 'import uuid; print(uuid.uuid4())')"
curl --silent --max-time 0.001 -H 'Content-Type: application/json' \
  -d "{\"reservation_id\":\"$timeout_key\",\"quantity\":1}" \
  "$base/items/$item_id/reserve" >/dev/null || true
timeout_retry="$(curl --fail --silent -H 'Content-Type: application/json' \
  -d "{\"reservation_id\":\"$timeout_key\",\"quantity\":1}" "$base/items/$item_id/reserve")"
printf '%s' "$timeout_retry" | python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["status"]=="SUCCEEDED"'
final_item="$(curl --fail --silent "$base/items/$item_id")"
printf '%s' "$final_item" | python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["stock"]==0 and x["reservedQuantity"]==2'

invalid_code="$(curl --silent -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d "{\"reservation_id\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"quantity\":0}" \
  "$base/items/$item_id/reserve")"
[ "$invalid_code" = 400 ] || { echo "expected 400 for zero quantity, got $invalid_code" >&2; exit 1; }

missing_code="$(curl --silent -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d "{\"reservation_id\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"quantity\":1}" \
  "$base/items/9223372036854775807/reserve")"
[ "$missing_code" = 404 ] || { echo "expected 404 for missing item, got $missing_code" >&2; exit 1; }

echo "restart replay, timeout retry, invalid quantity, and missing-item scenarios passed"
