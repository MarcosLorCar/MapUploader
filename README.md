# MapUploader

![Minecraft Version](https://img.shields.io/badge/minecraft-26.1%E2%80%9326.1.2-green)

Turn images into Minecraft maps and hand them to players in-game. MapUploader is a
Ktor backend + web UI that converts any image into Minecraft map (`.dat`) files and
delivers them instantly to a player's inventory over RCON.

## Features
* **Web UI & Gallery** — upload images and browse a session-private gallery of your past maps.
* **One-Click In-Game Delivery** — click a map in the web UI to receive it in-game immediately.
* **Async Processing** — non-blocking uploads and parallel pixel work via Kotlin Coroutines.
* **High-Fidelity Colors** — L\*a\*b\* color-space matching for accurate block colors.
* **Grid Support** — slices images into 128×128 chunks for 1×2, 2×2 and larger maps.
* **Native Integration** — safely manages Minecraft's `last_id.dat` to avoid map-ID conflicts.

---

## How it works

MapUploader has two cooperating pieces:

* **The datapack** (`MapUploader.zip`) — adds the in-game `/trigger UploadMap` command,
  which prints a clickable link (`http://<host>:<port>/<your-id>`) that opens the web UI.
  The host/port default to `127.0.0.1:8080` and can be changed in-game with
  `/function mapuploader:change_host` and `change_port`. It also exposes the
  `give_map` function the web app calls to put a finished map in your hands.
* **The web app** (this project) — serves the UI, converts uploaded images into map
  `.dat` files written straight into your world's maps folder, assigns map IDs, and
  triggers in-game delivery over **RCON**.

You can run the web app in either of two ways:

| Mode | What it does | Best for |
|---|---|---|
| **Proxy** (recommended) | MapUploader is installed *in place of* your server jar. It launches the real server as a child process and runs the web app alongside it — one start command, one shared lifecycle, graceful RCON shutdown. Config is auto-read from `server.properties`. | Most servers; the quick installer. |
| **Standalone** | The web app runs as its own process next to the server, configured via environment variables. | Running the UI on a different machine, or keeping it fully separate. |

> Proxy mode still runs as a separate JVM from the server (vanilla cannot load plugins);
> it only *couples the lifecycles*. It talks to the server over RCON and the world files
> exactly like standalone mode does.

---

## Quick install (recommended)

Run from your **Minecraft server directory** (the folder with `server.properties`). The
installer downloads the latest release, auto-detects your RCON/world settings, and
installs the datapack for you.

**Linux / macOS**
```bash
curl -fsSL https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.sh | bash
```

**Windows (PowerShell)**
```powershell
irm https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.ps1 | iex
```

You'll be asked to choose **proxy** or **normal (standalone)** mode. For proxy mode:

* **Stop the server first** — the installer renames your server jar (a running jar is locked, especially on Windows).
* If several jars are present, it lets you pick which one is your server. The chosen jar is renamed with a `proxied_` prefix and MapUploader takes its place.
* If RCON isn't enabled, it shows you exactly what to set and offers to apply it for you (backing up `server.properties` and generating a password).

After that, **start your server the way you always have** — MapUploader comes up with it.
To revert a proxy install, run the generated `uninstall-mapuploader` script.

<details>
<summary>Installer options (non-interactive / scripted)</summary>

Both scripts accept flags (download first, then run):

```bash
./install.sh --mode proxy --jar server.jar --port 8080 --yes
./install.sh --mode normal --no-datapack
```
```powershell
.\install.ps1 -Mode proxy -Jar server.jar -Port 8080 -Yes
```
`--version <tag>` installs a specific release instead of the latest.
</details>

---

## Manual installation

### Prerequisites
* Java 17 (JDK)
* Gradle (or use the bundled `./gradlew`)
* A Minecraft server with **RCON enabled**
* The [`MapUploader.zip`](MapUploader.zip) datapack (included in this repo)

### 1. Set up the Minecraft server

1. **Install the datapack** — copy `MapUploader.zip` into your world's `datapacks/`
   folder and run `/reload` (or restart).
2. **Enable RCON** in `server.properties`:
   ```properties
   enable-rcon=true
   rcon.password=your_secret_password
   rcon.port=25575
   ```
3. **(Optional) Mute RCON broadcasts** so map deliveries don't spam admin chat:
   ```mcfunction
   /gamerule sendCommandFeedback false
   /gamerule logAdminCommands false
   ```

### 2. Build the jar
```bash
./gradlew shadowJar
```
This produces `build/libs/MapUploader-1.2.0.jar`.

### 3. Run it
Set the configuration (see below) and start the jar. Copy the jar wherever you like and
run it by name:

**Linux / macOS**
```bash
export MC_WORLD_DATA_PATH="/path/to/your/world/data/minecraft/maps"
export MC_RCON_PASSWORD="your_secret_password"
java -jar MapUploader-1.2.0.jar
```

**Windows (PowerShell)**
```powershell
$env:MC_WORLD_DATA_PATH = "C:\path\to\your\world\data\minecraft\maps"
$env:MC_RCON_PASSWORD   = "your_secret_password"
java -jar MapUploader-1.2.0.jar
```

> In proxy mode none of these variables are needed — the launcher reads
> `server.properties` automatically.

### Configuration

| Variable | Required | Description |
|---|---|---|
| `MC_WORLD_DATA_PATH` | **Yes** (standalone) | Absolute path to your world's `data/minecraft/maps` folder (where `last_id.dat` and the map `#.dat` files live). |
| `MC_RCON_PASSWORD` | **Yes** | The RCON password from `server.properties`. |
| `MC_RCON_HOST` | No | RCON host (default `127.0.0.1`). |
| `MC_RCON_PORT` | No | RCON port (default `25575`). |
| `MAPUPLOADER_WEB_PORT` | No | Web UI port (default `8080`). If you change it, run `/function mapuploader:change_port` in-game to match. |
| `MAPUPLOADER_HEAP_RESERVE_MB` | No | Proxy mode only — RAM (MB) reserved off the child server's `-Xmx` for the web app (default `512`). |

---

## Usage

1. In-game, run `/trigger UploadMap`.
2. Click the link the datapack prints in chat to open the web UI (with your player ID).
3. Upload a PNG/JPEG, set your grid dimensions, and click any map piece in the gallery
   to send it straight to your in-game inventory.

> **Warning:** Modifying `last_id.dat` while the server is running risks the server
> overwriting it from its in-memory cache on the next save. Proxy mode does not change
> this — it runs as a separate process from the server.

---

## License
[MIT](LICENSE)
