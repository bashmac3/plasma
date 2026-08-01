#!/usr/bin/env bash
# Send an example payload to the Plasma bridge.
# Usage: ./send.sh <port> <payload.json>
# The token config is auto-discovered by scripts/send_payload.py:
# PLASMA_CONFIG / PLASMA_TOKEN, then common launcher locations.
set -euo pipefail

PORT="${1:?Usage: ./send.sh <port> <payload.json>}"
PAYLOAD="${2:?Usage: ./send.sh <port> <payload.json>}"

exec python3 "$(dirname "$0")/../scripts/send_payload.py" 127.0.0.1 "$PORT" "" "$(cat "$PAYLOAD")"
