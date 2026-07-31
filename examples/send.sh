#!/usr/bin/env bash
# Отправка примера payload в мост Plasma.
# Использование: ./send.sh <порт> <файл-payload.json>
# Конфиг ищется автоматически: PLASMA_CONFIG, затем PrismLauncher / .minecraft / MultiMC,
# затем рекурсивный поиск в домашней папке.
set -euo pipefail

CONFIG="${PLASMA_CONFIG:-}"

find_config() {
    local candidates=(
        "$HOME/Library/Application Support/PrismLauncher/instances/26.2/minecraft/config/plasma.properties"
        "$HOME/.minecraft/config/plasma.properties"
        "$HOME/Library/Application Support/minecraft/config/plasma.properties"
        "$HOME/Library/Application Support/MultiMC/instances/26.2/minecraft/config/plasma.properties"
    )
    local c
    for c in "${candidates[@]}"; do
        if [ -f "$c" ]; then
            printf '%s' "$c"
            return 0
        fi
    done
    local found
    found="$(find "$HOME" -maxdepth 6 -name plasma.properties -path '*/config/*' 2>/dev/null | head -n 1)"
    printf '%s' "$found"
}

if [ -z "$CONFIG" ]; then
    CONFIG="$(find_config)"
fi
if [ -z "$CONFIG" ] || [ ! -f "$CONFIG" ]; then
    echo "Не найден config/plasma.properties. Укажите PLASMA_CONFIG или путь." >&2
    exit 1
fi

TOKEN="$(grep '^token=' "$CONFIG" | head -1 | cut -d= -f2)"

PORT="${1:?Укажите порт}"
PAYLOAD="${2:?Укажите файл payload}"

python3 "$(dirname "$0")/../scripts/send_payload.py" 127.0.0.1 "$PORT" "$TOKEN" "$(cat "$PAYLOAD")"
