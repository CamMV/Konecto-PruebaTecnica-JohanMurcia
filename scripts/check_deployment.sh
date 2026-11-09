#!/usr/bin/env bash
# Checks that a running backend is deployed correctly. No LLM calls (no OpenRouter cost).
#
#   scripts/check_deployment.sh                     # http://localhost:8001
#   scripts/check_deployment.sh http://192.168.1.20:8001
#
# Checks: health + DB + catalog (68 actuators), OpenAPI docs, login (test users, wrong password), token required,
# session endpoints, debug endpoints only for admins, error format. Needs curl and python3.
set -euo pipefail

BASE="${1:-http://localhost:8001}"
BASE="${BASE%/}"
PASSWORD="Series76!demo"
EXPECTED_ACTUATORS="${EXPECTED_ACTUATORS:-68}"
FAILURES=0

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# request METHOD PATH [TOKEN] [JSON_BODY] -> sets STATUS and BODY
request() {
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -m 15 -o /tmp/check_body.$$ -w '%{http_code}' -X "$method" "$BASE$path")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}") || STATUS=000  # 000 = no connection
  BODY=$(cat /tmp/check_body.$$ 2>/dev/null || true)
  rm -f /tmp/check_body.$$
}

# json EXPR -> evaluates a Python expression over the parsed BODY (`d`)
json() { python3 -c "import json,sys; d=json.loads(sys.argv[1]); print($1)" "$BODY" 2>/dev/null || echo "<invalid json>"; }

expect() {  # expect DESCRIPTION ACTUAL EXPECTED
  if [[ "$2" == "$3" ]]; then ok "$1"; else fail "$1 (got '$2', expected '$3')"; fi
}

echo "Checking $BASE"

echo "Service"
request GET /api/health
expect "GET /api/health → 200" "$STATUS" 200
expect "status ok" "$(json 'd["status"]')" ok
expect "database reachable" "$(json 'd["db"]')" True
expect "catalog loaded ($EXPECTED_ACTUATORS actuators)" "$(json 'd["actuators"]')" "$EXPECTED_ACTUATORS"
request GET /docs
expect "GET /docs (Swagger UI) → 200" "$STATUS" 200

echo "Auth"
request POST /api/auth/login "" "{\"email\":\"ana@konecto.test\",\"password\":\"$PASSWORD\"}"
expect "login ana@konecto.test → 200" "$STATUS" 200
ANA=$(json 'd["access_token"]')
expect "ana is admin" "$(json 'd["user"]["is_admin"]')" True
request POST /api/auth/login "" "{\"email\":\"luis@konecto.test\",\"password\":\"$PASSWORD\"}"
expect "login luis@konecto.test → 200" "$STATUS" 200
LUIS=$(json 'd["access_token"]')
expect "luis is not admin" "$(json 'd["user"]["is_admin"]')" False
request POST /api/auth/login "" '{"email":"ana@konecto.test","password":"wrong"}'
expect "wrong password → 401 INVALID_CREDENTIALS" "$STATUS $(json 'd["error"]["code"]')" "401 INVALID_CREDENTIALS"
request GET /api/auth/me "$ANA"
expect "GET /api/auth/me → 200" "$STATUS" 200
request GET /api/sessions
expect "no token → 401 UNAUTHORIZED" "$STATUS $(json 'd["error"]["code"]')" "401 UNAUTHORIZED"

echo "Conversations"
request GET "/api/sessions?limit=5" "$ANA"
expect "GET /api/sessions → 200" "$STATUS" 200
expect "paginated shape {items, total}" "$(json '"items" in d and "total" in d')" True
request GET "/api/sessions/00000000-0000-4000-8000-000000000000/messages" "$ANA"
expect "unknown session → 404 SESSION_NOT_FOUND" "$STATUS $(json 'd["error"]["code"]')" "404 SESSION_NOT_FOUND"
request POST /api/conversation "$ANA" '{"query":""}'
expect "empty query → 422 VALIDATION_ERROR" "$STATUS $(json 'd["error"]["code"]')" "422 VALIDATION_ERROR"

echo "Debug (admins only)"
request GET "/api/debug/sessions?limit=5" "$ANA"
if [[ $STATUS == 503 ]]; then
  ok "debug API disabled on this server (DEBUG_API_ENABLED=false) → 503"
else
  expect "admin: GET /api/debug/sessions → 200" "$STATUS" 200
  expect "each chat has its owner" "$(json 'all("user" in i for i in d["items"])')" True
fi
request GET "/api/debug/sessions?limit=5" "$LUIS"
expect "regular user → 403 ADMIN_REQUIRED" "$STATUS $(json 'd["error"]["code"]')" "403 ADMIN_REQUIRED"

echo
if (( FAILURES > 0 )); then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "All checks passed"
