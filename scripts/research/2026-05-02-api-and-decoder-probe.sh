#!/usr/bin/env bash
# Reproduces the live probe from docs/research/2026-05-02-api-and-decoder-probe.md.
#
# Set KODIK_TOKEN to one of the active tokens in data/kodik_tokens.json.
# Re-run any time you suspect the upstream behaviour has changed.

set -uo pipefail

TOK="${KODIK_TOKEN:-}"
if [[ -z "$TOK" ]]; then
  echo "ERROR: KODIK_TOKEN not set" >&2
  exit 64
fi

UA="${KODIK_USER_AGENT:-Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36}"
BASE_API="${KODIK_API_BASE:-https://kodik-api.com}"
BASE_PLAYER="${KODIK_PLAYER_BASE:-https://kodikplayer.com}"

echo "============================================================"
echo "  PROBE A — REST API: GET vs POST on all 7 endpoints"
echo "============================================================"
printf "%-25s | %4s | %4s | %s\n" "Endpoint" "GET" "POST" "bodies-match (modulo time)"
echo "----------------------------------------------------------------"

for endpoint in search list translations/v2 genres countries years qualities/v2; do
  case "$endpoint" in
    search) qs="title=naruto&limit=1" ;;
    *) qs="limit=1" ;;
  esac

  get_status=$(curl -s -o /tmp/probe_get.txt -w "%{http_code}" "$BASE_API/$endpoint?token=$TOK&$qs" --max-time 15)
  post_status=$(curl -s -o /tmp/probe_post.txt -w "%{http_code}" -X POST "$BASE_API/$endpoint?token=$TOK&$qs" --max-time 15)

  if diff -q <(sed -E 's/"time":"[^"]*",?//g' /tmp/probe_get.txt) \
              <(sed -E 's/"time":"[^"]*",?//g' /tmp/probe_post.txt) >/dev/null 2>&1; then
    match="YES"
  else
    match="NO  (G=$(wc -c < /tmp/probe_get.txt)B P=$(wc -c < /tmp/probe_post.txt)B)"
  fi
  printf "/%-24s | %4s | %4s | %s\n" "$endpoint" "$get_status" "$post_status" "$match"
done

echo ""
echo "============================================================"
echo "  PROBE B — REST API edge cases"
echo "============================================================"

probe() {
  local desc="$1"; shift
  local status
  status=$(curl -s -o /tmp/probe.txt -w "%{http_code}" "$@" --max-time 15)
  printf "  %-50s → %s body: %s\n" "$desc" "$status" "$(head -c 100 /tmp/probe.txt)"
}

probe "no token (GET)"               "$BASE_API/search?title=a"
probe "no filter (POST)"             -X POST "$BASE_API/search?token=$TOK"
probe "unknown enum types=film"      "$BASE_API/search?token=$TOK&types=film&limit=1"
probe "JSON body to /search"         -X POST "$BASE_API/search" -H "Content-Type: application/json" --data "{\"token\":\"$TOK\",\"title\":\"naruto\",\"limit\":1}"

echo ""
echo "============================================================"
echo "  PROBE C — Decoder iframe HTML player-js naming"
echo "============================================================"

inspect_iframe() {
  local label="$1" url="$2"
  echo ""
  echo "--- $label: $url ---"
  local html
  html=$(curl -s -L "$url" -H "User-Agent: $UA" --max-time 20)
  local type hash id player_js
  type=$(echo "$html"   | grep -oE "vInfo\.type = '[^']+'" | sed -E "s/.*'([^']+)'.*/\1/" | head -1)
  hash=$(echo "$html"   | grep -oE "vInfo\.hash = '[^']+'" | sed -E "s/.*'([^']+)'.*/\1/" | head -1)
  id=$(echo "$html"     | grep -oE "vInfo\.id = '[^']+'"   | sed -E "s/.*'([^']+)'.*/\1/" | head -1)
  player_js=$(echo "$html" | grep -oE 'src="/assets/js/app\.[^"]+\.js[^"]*"' | head -1 | sed -E 's/src="\/([^"]+)"/\1/')
  echo "  vInfo.type=$type  vInfo.hash=$hash  vInfo.id=$id"
  echo "  player_js=$player_js"
}

inspect_iframe "MOVIE (foreign-movie #46929)"  "$BASE_PLAYER/video/46929/28fd730628f190861cc55bfdd150c11f/720p"
inspect_iframe "SERIAL (anime-serial Naruto)"  "$BASE_PLAYER/serial/6646/4698b2bea53c04aa757d3d1ff42fea53/720p"
inspect_iframe "RUSSIAN MOVIE (#62336)"         "$BASE_PLAYER/video/62336/bc1cd2d5adea8c009141c601bda35d4f/720p"

echo ""
echo "============================================================"
echo "  PROBE D — Decoder /ftor body-format"
echo "============================================================"

form_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_PLAYER/ftor" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "User-Agent: $UA" \
  --data "type=video&hash=00000000000000000000000000000000&id=1&bad_user=false&cdn_is_working=true&info=%7B%7D" \
  --max-time 15)
echo "  POST application/x-www-form-urlencoded (with no real urlParams) → $form_status (expected 200 with empty links, OR 200 with empty body — see raw)"

json_status=$(curl -s -o /tmp/json_resp.txt -w "%{http_code}" -X POST "$BASE_PLAYER/ftor" \
  -H "Content-Type: application/json" \
  -H "User-Agent: $UA" \
  --data "{\"type\":\"video\",\"hash\":\"x\",\"id\":1,\"bad_user\":false,\"cdn_is_working\":true,\"info\":{}}" \
  --max-time 15)
echo "  POST application/json                                           → $json_status body: $(head -c 100 /tmp/json_resp.txt)"
echo "  (expectation: 404 — Kodik's router rejects non-form Content-Type at the entry point)"

echo ""
echo "============================================================"
echo "  Geo check"
echo "============================================================"
ip_info=$(curl -s "https://ipinfo.io/json" --max-time 10)
echo "$ip_info"
echo ""
echo "If 'country' is not KZ/RU/BY/KG, expect dummy URLs from /ftor — see"
echo "docs/quirks-and-hacks.md 'Kodik is geo-fenced' entry."

echo ""
echo "Done. Compare with docs/research/2026-05-02-api-and-decoder-probe.md to verify nothing changed."
