# Plasma

Un mod de Fabric para Minecraft **26.2** que abre un **puente** local y permite ejecutar código Java arbitrario dentro del juego, con aprobación mediante el chat, evaluación de fragmentos en tiempo de ejecución y localización (inglés / ruso / español).

> **Advertencia:** esto es ejecución remota de código. Úsalo solo en tu propia máquina y en tus propios mundos. No lo instales en una cuenta principal ni entres a servidores con él.

## Funcionalidades

- **Puente TCP local** — puerto aleatorio (30000–50000), protegido por un token.
- **Aprobación por chat** — cada solicitud aparece en el chat; la apruebas/deniegas con comandos.
- **Evaluación de fragmentos** — envía código Java arbitrario (`{"code": "..."}`), se compila y ejecuta en tiempo real.
- **Ejecución de clases** — ejecuta cualquier clase con `Runnable` o método `main`.
- **Payloads multi-paquete** — un array de paquetes con `packetid` / `maxpacketid`.
- **Efectos del lado del cliente** — borrar chunks, objetos/entidades fantasma, lluvia de TNT, explosiones, lanzar al jugador.
- **Acciones en el servidor integrado** — en un jugador solo, por ejemplo, dar objetos en el inventario real.
- **Chat localizado** — inglés, ruso y español mediante las traducciones integradas de Minecraft.

## Compilar

Requisitos:

- **JDK 25+** (el mod requiere `java >= 25`).
- Internet en la primera compilación (Gradle descarga dependencias); luego funciona `--offline`.

```bash
./gradlew build            # compilación normal
./gradlew build --offline  # si las dependencias ya están en caché
```

En Windows usa `gradlew.bat` en lugar de `./gradlew`.

El jar final se escribe en `build/libs/bm3-plasma-1.0.0.jar`. Los jars precompilados de las versiones etiquetadas se publican como GitHub Releases.

## Instalación

1. Copia `build/libs/bm3-plasma-1.0.0.jar` (o descarga el último jar desde la página de GitHub Releases) a la carpeta `mods/` de una instancia **Fabric 26.2** (requiere Fabric API). Usuarios de Legacy Launcher / vanilla: ponlo en `.minecraft/mods` y ejecuta un perfil **Fabric**.
2. Inicia el juego. Aparece un aviso en el chat. El puente permanece **cerrado** hasta que escribas `/plasma agree`, que muestra el puerto abierto.
3. Envía payloads desde la misma máquina (solo localhost) con `scripts/send_payload.py` o los scripts de `examples/`.

## Uso — comandos de chat

| Comando | Acción |
|---|---|
| `/plasma agree` | Aceptar el aviso y abrir el puente |
| `/plasma allow` | Aprobar la solicitud pendiente actual |
| `/plasma allow always` | Confiar en la IP: ejecutar todo de ella automáticamente |
| `/plasma deny` | Denegar la solicitud pendiente actual |
| `/plasma deny always` | Bloquear la IP permanentemente |
| `/plasma trust` | Confiar en la IP de la solicitud actual |
| `/plasma betray` | Revocar confianza (y desbloquear) |
| `/plasma confirm` | Confirmar una acción pendiente |
| `/plasma close` | Cerrar el puente (requiere confirmación) |
| `/plasma forcekill` | Cerrar el puente y denegar todas las solicitudes pendientes |
| `/plasma echo false` | Silenciar el chat de Plasma hasta `/plasma echo true` (la salida del payload sigue visible) |
| `/plasma echo true` | Restaurar la salida completa |

## Ejemplos

Hay payloads listos en `examples/`. Envía uno (el puerto sale del mensaje de `/plasma agree`):

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

El config del token se encuentra automáticamente: PrismLauncher, `.minecraft` (Legacy Launcher / vanilla) o MultiMC; como último recurso se busca en `%APPDATA%`. Se puede indicar con `-Config <ruta>` o la variable `PLASMA_CONFIG`.

| Archivo | Qué hace |
|---|---|
| `01_hello.json` | Saluda al jugador por su nombre — comprobación básica |
| `02_erase_chunk.json` | Borra el chunk actual 16x16 (lado del cliente) |
| `03_tnt_rain.json` | Cascada de 4 paquetes: lluvia de TNT, explosión, anillo de TNT, lanzamiento |
| `04_ghost_diamonds.json` | 24 diamantes "fantasma" brillantes que no se recogen |
| `05_give_diamonds.json` | Da 99 diamantes al inventario real (un jugador) |
| `06_system_info.json` | Muestra SO, usuario y versión de Java |
| `07_launch_player.json` | Lanza al jugador por los aires |

Consejo: ejecuta `/plasma allow always` + `/plasma confirm` una vez para que los siguientes payloads se ejecuten automáticamente.

## Formato del payload

`scripts/send_payload.py <host> <puerto> <token> '<payload-json>'` envía:

```json
{"token": "tu-token", "payload": {"className": "bm3.plasma.SampleTask", "method": "run"}}
```

Un paquete puede ser un **fragmento** (`code`) o una **clase** (`className`). Varios paquetes forman un array, cada uno con `packetid` / `maxpacketid` opcionales.

## Configuración

`config/plasma.properties` guarda el token (se genera aleatoriamente la primera vez si no existe). Todo funciona solo con loopback (`127.0.0.1`).

## Licencia

MIT — ver [LICENSE](LICENSE). Copyright (c) 2026 bashmac3.
