#!/usr/bin/env python3
"""Send a payload to the Plasma bridge.

Usage:
    python3 scripts/send_payload.py <host> <port> [token] [payload_json]
    python3 scripts/send_payload.py <host> <port> --watch <payload_file>

The token is read from <token> if given; otherwise it is auto-discovered by
scanning common launcher locations for config/plasma.properties (PrismLauncher,
.minecraft for Legacy Launcher / vanilla, MultiMC) plus a bounded recursive
search of the home / APPDATA folders.

--watch re-sends the payload every time <payload_file> changes, which gives a
hot-reload loop for GUI development: edit the snippet, save, and the screen
updates in-game.
"""
import json
import os
import socket
import sys
import time


def read_token(config_path):
    with open(config_path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line.startswith("token="):
                return line[len("token="):].strip()
    return None


def discover_token():
    home = os.path.expanduser("~")
    appdata = os.environ.get("APPDATA", "")
    candidates = [
        os.path.join(home, "Library/Application Support/PrismLauncher/instances/26.2/minecraft/config/plasma.properties"),
        os.path.join(home, ".minecraft/config/plasma.properties"),
        os.path.join(home, "Library/Application Support/minecraft/config/plasma.properties"),
        os.path.join(home, "Library/Application Support/MultiMC/instances/26.2/minecraft/config/plasma.properties"),
    ]
    if appdata:
        candidates += [
            os.path.join(appdata, "PrismLauncher/instances/26.2/minecraft/config/plasma.properties"),
            os.path.join(appdata, ".minecraft/config/plasma.properties"),
            os.path.join(appdata, "MultiMC/instances/26.2/minecraft/config/plasma.properties"),
        ]

    seen = set()
    for candidate in candidates:
        candidate = os.path.normpath(candidate)
        if candidate not in seen and os.path.isfile(candidate):
            return read_token(candidate)
        seen.add(candidate)

    def walk_roots(roots):
        for root in roots:
            if not root or not os.path.isdir(root):
                continue
            for dirpath, dirnames, filenames in os.walk(root):
                depth = dirpath[len(root):].count(os.sep)
                if depth >= 6:
                    dirnames[:] = []
                    continue
                if "plasma.properties" in filenames and "config" in os.path.basename(dirpath):
                    token = read_token(os.path.join(dirpath, "plasma.properties"))
                    if token:
                        return token
        return None

    return walk_roots([home, appdata])


if len(sys.argv) < 3:
    print(__doc__)
    sys.exit(1)

host = sys.argv[1]
port = int(sys.argv[2])


def discover_or_exit():
    token = os.environ.get("PLASMA_TOKEN") or discover_token()
    if not token:
        print("Token not found. Pass it as an argument or set PLASMA_TOKEN.", file=sys.stderr)
        sys.exit(1)
    return token


def send(token, payload_text):
    request = json.dumps({"token": token, "payload": json.loads(payload_text)})
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.sendall(request.encode("utf-8") + b"\n")
        sock.settimeout(5)
        buffer = b""
        while True:
            try:
                chunk = sock.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            buffer += chunk
        sys.stdout.write(buffer.decode("utf-8", errors="replace"))


if "--watch" in sys.argv:
    watch_index = sys.argv.index("--watch")
    if len(sys.argv) <= watch_index + 1:
        print("--watch requires a payload file path", file=sys.stderr)
        sys.exit(1)
    payload_file = sys.argv[watch_index + 1]
    token = discover_or_exit()

    last_signature = None
    print(f"Watching {payload_file} -> {host}:{port} (Ctrl+C to stop)", file=sys.stderr)
    try:
        while True:
            try:
                with open(payload_file, "r", encoding="utf-8") as handle:
                    content = handle.read()
                signature = (os.stat(payload_file).st_mtime, content)
            except FileNotFoundError:
                signature = None
                content = None
            if signature != last_signature:
                last_signature = signature
                if content is not None and content.strip():
                    try:
                        send(token, content)
                    except (json.JSONDecodeError, OSError) as error:
                        print(f"send failed: {error}", file=sys.stderr)
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    sys.exit(0)

token = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else (os.environ.get("PLASMA_TOKEN") or discover_token())
if not token:
    print("Token not found. Pass it as an argument or set PLASMA_TOKEN.", file=sys.stderr)
    sys.exit(1)
payload = sys.argv[4] if len(sys.argv) > 4 else '{"className":"bm3.plasma.SampleTask","method":"run"}'

send(token, payload)