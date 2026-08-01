# Plasma

A Fabric mod for Minecraft **26.2** that opens a **localhost** bridge and lets you execute arbitrary Java code inside the game — with a chat-driven approval gateway, runtime snippet evaluation, and localization (English / Russian / Spanish).

> **Warning:** this mod is remote code execution. Use it only on your own machine, in your own worlds. Do not install it on a main account or join servers with it.

## Features

- **Localhost TCP bridge** — listens on a random port (30000–50000), protected by a token.
- **Chat-driven approval** — every request is shown in chat; you allow/deny it with commands.
- **Snippet evaluation** — send arbitrary Java source (`{"code": "..."}`), compiled at runtime and executed.
- **Class execution** — run any class with a `Runnable` or `main` method.
- **Multi-packet payloads** — send a JSON array of packets with `packetid` / `maxpacketid`.
- **Clientside effects** — erase chunks, spawn ghost items/entities, TNT rain, explosions, launch the player.
- **Integrated-server actions** — in singleplayer, e.g. grant items into the real inventory.
- **Localized chat** — English, Russian, Spanish via Minecraft's built-in translations.

## Building

Prerequisites:

- **JDK 25+** (the mod requires `java >= 25`).
- Internet on the first build (Gradle downloads dependencies); afterwards `--offline` works.

```bash
./gradlew build            # normal build
./gradlew build --offline  # if dependencies are already cached
```

On Windows use `gradlew.bat` instead of `./gradlew`.

The finished jar is written to `build/libs/bm3-plasma-1.1.1.jar`. Prebuilt jars for tagged versions are published as GitHub Releases.

## Installation

1. Copy `build/libs/bm3-plasma-1.1.1.jar` (or download the latest jar from the GitHub Releases page) into the `mods/` folder of a **Fabric 26.2** instance (Fabric API required). Legacy Launcher / vanilla users: put it in `.minecraft/mods` and run a **Fabric** profile.
2. Launch the game. A warning banner appears in chat. The bridge stays **closed** until you type `/plasma agree` — this prints the port it opened.
3. Send payloads from the same machine (localhost only) with `scripts/send_payload.py` or the helpers in `examples/`.

## Usage — chat commands

| Command | Action |
|---|---|
| `/plasma agree` | Accept the disclaimer, rotate the token, and open the bridge |
| `/plasma allow` | Approve the current pending request |
| `/plasma allow always` | Trust the IP — execute everything from it automatically |
| `/plasma deny` | Deny the current pending request |
| `/plasma deny always` | Block the IP permanently |
| `/plasma trust` | Trust the current request's IP |
| `/plasma betray` | Revoke trust (and unblock) |
| `/plasma bless` | Bless the current payload — identical payloads auto-execute from now on |
| `/plasma unbless <hash>` | Remove a blessed payload hash |
| `/plasma block <ip>` | Block an IP immediately |
| `/plasma unblock <ip>` | Unblock an IP |
| `/plasma save <name>` | Save the current pending payload as a profile |
| `/plasma load <name>` | Execute a saved profile immediately |
| `/plasma del <name>` | Delete a saved profile |
| `/plasma list` | Show trusted/blocked IPs, blessed hashes, and profiles |
| `/plasma status` | Show bridge state, token, echo, and counters |
| `/plasma confirm` | Confirm a staged action |
| `/plasma close` | Close the bridge (requires confirm) |
| `/plasma forcekill` | Immediately close the bridge and deny all pending requests |
| `/plasma echo false` | Silence all Plasma chat until `/plasma echo true` (payload output still shown) |
| `/plasma echo true` | Restore full output |

The token shown by `/plasma agree` and `/plasma status` is also written to
`config/plasma.properties` after each rotation, so the send scripts always pick up the current one.

## Examples

Ready-made payloads are in `examples/`. Send one with (port comes from the `/plasma agree` message):

**macOS / Linux:**
```bash
cd examples
chmod +x send.sh
./send.sh 46946 01_hello.json
```

**Windows (PowerShell):**
```powershell
cd examples
.\send.ps1 -Port 46946 -Payload .\01_hello.json
```

The token config is found automatically: PrismLauncher, `.minecraft` (Legacy Launcher / vanilla), or MultiMC; a recursive search of `%APPDATA%` is used as a fallback. Override with `-Config <path>` or the `PLASMA_CONFIG` env var.

| File | What it does |
|---|---|
| `01_hello.json` | Greets the player by name — sanity check |
| `02_erase_chunk.json` | Erases the current 16x16 chunk to air (clientside) |
| `03_tnt_rain.json` | 4-packet cascade: TNT rain, explosion, TNT ring, launch |
| `04_ghost_diamonds.json` | 24 glowing, non-pickup "ghost" diamonds around the player |
| `05_give_diamonds.json` | Grants 99 diamonds into the real inventory (singleplayer) |
| `06_system_info.json` | Prints OS, user, Java version from the machine |
| `07_launch_player.json` | Launches the player into the air |

Tip: run `/plasma allow always` + `/plasma confirm` once so subsequent payloads auto-execute.

## Payload format

`scripts/send_payload.py <host> <port> <token> '<payload-json>'` sends:

```json
{"token": "your-token", "payload": {"className": "bm3.plasma.SampleTask", "method": "run"}}
```

A single packet can be a **snippet** (`code`) or a **class** (`className`). Multiple packets form an array, each with optional `packetid` / `maxpacketid`.

## Configuration

`config/plasma.properties` stores the token (randomly generated on first run if absent). Everything is loopback-only (`127.0.0.1`).

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 bashmac3.
