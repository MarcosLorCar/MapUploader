# MapUploader — Installation Guide

MapUploader is a web app that turns images into Minecraft maps and delivers them in-game.
It talks to your server two ways: over **RCON** (to give players maps) and by reading/writing
the world's map files directly. It is **not** a plugin or mod — vanilla can't load one — so it
always runs as its own process. The question is only *how* that process is started and managed.

There are two installation modes:

| Mode | One-line summary |
|---|---|
| [**Proxy**](#proxy-mode-recommended) | One command starts your server **and** MapUploader together, sharing a single lifecycle. Recommended. |
| [**Normal / standalone**](#normal-standalone-mode) | MapUploader runs as a separate process you start yourself, next to the server. |

Both are offered by the quick installer:

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.sh | bash
```
```powershell
# Windows (run from your server folder; if a window flashes shut, run it from an
# already-open PowerShell so you can read any message)
irm https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.ps1 | iex
```

Run it from your **server directory** (the folder containing `server.properties`). It auto-detects
your RCON settings and world path, installs the datapack, and sets everything up.

---

## Proxy mode (recommended)

### The idea

Normally you start your server with something like:

```
java -Xmx4G -jar server.jar nogui
```

Proxy mode slips MapUploader into the middle of that command **without changing the command itself**.
The installer renames your real server jar with a `proxied_` prefix and drops MapUploader in under the
original name:

```
server.jar          ->  proxied_server.jar   (your real Minecraft server)
MapUploader-x.y.z    ->  server.jar           (the launcher, now under the old name)
```

So your existing start command — whatever it is, from a `.bat` file to a hosting panel — now launches
MapUploader instead. MapUploader then:

1. **Finds the real server** (`proxied_*.jar`) and launches it as a child process.
2. **Passes your memory down.** The `-Xmx`/`-Xms`/GC flags you put on the start command are read from
   the running JVM and forwarded to the real server, so it gets the RAM you intended. (A small slice is
   reserved for MapUploader itself — see [memory](#memory--jvm-flags).)
3. **Runs the web app** alongside the server, in the same process tree.
4. **Couples the lifecycles.** Stop the server and MapUploader stops; stop MapUploader and the server is
   shut down **gracefully over RCON** (a clean `stop` that saves the world — important on Windows, where
   simply killing the process would skip the save).

> **It is still a separate JVM from the server.** Proxy mode couples the two processes and shares one
> start command; it does not run *inside* the server. RCON and direct file access work exactly as in
> standalone mode.

### What the installer does

- Confirms the server is **stopped** (a running jar can't be renamed, especially on Windows).
- Lists the `.jar` files in the folder and lets you pick which one is your server (if there's more than one).
- Renames it `proxied_<name>` and installs the MapUploader launcher under the original name.
  It backs up the original first and refuses to run twice (it won't double-prefix).
- Verifies the downloaded jar actually contains the launcher before touching anything.
- Checks **RCON**. Proxy mode needs it for safe shutdown and map delivery. If it's off, the installer
  shows you exactly what to set and offers to do it for you (backing up `server.properties` and
  generating a random password).
- Installs the datapack into `<level-name>/datapacks/`.

### Running it

Just **start your server the way you always have.** MapUploader comes up with it. Its console output is
interleaved with the server's, so typing `stop` in the console still stops the server normally.

To stop everything: stop the server as usual (console `stop`, `Ctrl+C`, or your panel's stop button).
MapUploader catches it and shuts the server down cleanly over RCON.

### Configuration

In proxy mode, MapUploader reads `server.properties` automatically — you normally set nothing. To
override, set these as **environment variables** before the start command (real env vars win over the
auto-detected values):

| Variable | Default | Purpose |
|---|---|---|
| `MAPUPLOADER_WEB_PORT` | `8080` | Web UI port. If you change it, run `/function mapuploader:change_port` in-game to match. |
| `MAPUPLOADER_HEAP_RESERVE_MB` | `512` | RAM (MB) reserved off the server's `-Xmx` for MapUploader itself. |
| `MC_WORLD_DATA_PATH` | from `level-name` | Override the maps folder path. |
| `MC_RCON_HOST` / `MC_RCON_PORT` / `MC_RCON_PASSWORD` | from `server.properties` | Override RCON connection. |

#### Memory / JVM flags

The JVM consumes `-Xmx`/`-Xms` before your program ever runs, so MapUploader recovers them from the
running JVM and forwards the memory + GC flags to the real server. It trims `MAPUPLOADER_HEAP_RESERVE_MB`
(default 512) off the forwarded `-Xmx` so the box isn't oversubscribed — e.g. a start command with
`-Xmx4G` gives the server `-Xmx3584M` and leaves ~512M for the web app. Agents, JMX and `-D…` system
properties are **not** forwarded (they'd collide between the two JVMs).

> **Note on `-Xms` / Aikar's flags.** `-Xms` (initial heap) is committed by the JVM at startup and can't
> be reclaimed, so if your start command sets `-Xms` equal to `-Xmx` (with `+AlwaysPreTouch`), the
> launcher JVM also holds that memory. On a tightly-sized host you may want to lower the start command's
> `-Xmx` slightly to leave headroom. Plain `-Xmx` (a ceiling) has no such cost.

### Reverting

The installer writes an uninstaller next to the jar:

```bash
./uninstall-mapuploader.sh          # Linux / macOS
```
```powershell
.\uninstall-mapuploader.ps1         # Windows
```

It deletes the launcher and restores `proxied_<name>` back to its original name. The datapack and
`server.properties` are left untouched.

### Limitations

- It's a separate JVM, so it does **not** fix the `last_id.dat` race noted in the README (only a true
  plugin running inside the server would).
- The on-disk jar swap assumes your start command launches a server **jar**. Modern **Forge** (1.17+)
  launches via an `@libraries/.../args.txt` file with no server jar, so proxy mode doesn't apply there —
  use normal mode instead.

---

## Normal (standalone) mode

### The idea

MapUploader runs as an ordinary separate process. Nothing about how you start your server changes; you
start MapUploader yourself, before or after the server. Use this when:

- you want to run the web app on a **different machine** than the server,
- your server uses a launch mechanism the proxy can't wrap (e.g. Forge `@args`), or
- you simply prefer to keep the two fully independent.

### What the installer does

- Downloads the jar to `./mapuploader/mapuploader.jar`.
- Writes a ready-to-run start script with your detected config baked in:
  - `start-mapuploader.sh` (Linux / macOS)
  - `start-mapuploader.ps1` (Windows)
- Installs the datapack into `<level-name>/datapacks/`.

### Running it

From your server directory, after the server is up:

```bash
./start-mapuploader.sh              # Linux / macOS
```
```powershell
.\start-mapuploader.ps1             # Windows
```

The script just sets the environment variables below and runs `java -jar mapuploader/mapuploader.jar`.
If you'd rather run it by hand or move it elsewhere, set these yourself:

| Variable | Required | Description |
|---|---|---|
| `MC_WORLD_DATA_PATH` | **Yes** | Absolute path to `…/<world>/data/minecraft/maps`. |
| `MC_RCON_PASSWORD` | **Yes** | RCON password from `server.properties`. |
| `MC_RCON_HOST` | No | RCON host (default `127.0.0.1`). |
| `MC_RCON_PORT` | No | RCON port (default `25575`). |
| `MAPUPLOADER_WEB_PORT` | No | Web UI port (default `8080`). |

Example by hand:

```bash
export MC_WORLD_DATA_PATH="/path/to/world/data/minecraft/maps"
export MC_RCON_PASSWORD="your_secret_password"
java -jar mapuploader/mapuploader.jar
```

> The web app must reach the server's RCON port, and it writes into the world's maps folder — so if it
> runs on another machine, point `MC_RCON_HOST` at the server and make `MC_WORLD_DATA_PATH` a shared/
> synced path.

---

## Datapack & RCON (both modes)

MapUploader needs the **datapack** installed (the installer does this) and **RCON enabled**. In
`server.properties`:

```properties
enable-rcon=true
rcon.password=your_secret_password
rcon.port=25575
```

The datapack adds `/trigger UploadMap`, which prints the clickable link to the web UI. The link's
host/port default to `127.0.0.1:8080` and can be changed in-game with `/function mapuploader:change_host`
and `change_port`. After installing the datapack, run `/reload` or restart.

---

## Building from source

Prefer to build the jar yourself instead of downloading a release? See
[Manual installation](../README.md#manual-installation) in the README — `./gradlew shadowJar` produces
`build/libs/MapUploader-<version>.jar`, which works in either mode (rename it `proxied_…`-style for proxy,
or run it directly for standalone).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| The PowerShell window flashes red and closes instantly | You double-clicked the script or used "Run with PowerShell". Open a PowerShell window yourself and run it there so it stays open. |
| "Java is not installed" but Java works | Java isn't on the PATH of the shell you ran the installer from. Open a shell where `java --version` works, then re-run. |
| "The latest release jar has no proxy launcher" | The installer fetched a release older than v1.2.0. Update to v1.2.0+ (or pass `--version 1.2.0`). |
| In-game map delivery does nothing | RCON isn't enabled or the password is wrong in `server.properties`. |
| Maps don't appear / wrong folder | `MC_WORLD_DATA_PATH` (or the detected `level-name`) doesn't point at the active world's `data/minecraft/maps`. |
| Proxy mode: server world looks corrupted after stop | Make sure RCON is enabled — graceful shutdown depends on it. Without it, MapUploader can only force-kill the server. |
