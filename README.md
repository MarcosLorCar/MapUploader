# MapUploader Backend & Web UI

A high-performance Ktor backend and web interface designed to convert images into Minecraft Map (`.dat`) files and deliver them instantly in-game.

## Features
* **Web UI & Gallery**: Upload images and view a persistent, session-private gallery of your past map creations.
* **One-Click In-Game Delivery**: Click any generated map in the web UI to instantly give it to your player in-game via RCON.
* **Async Processing**: Non-blocking image uploads and parallel pixel processing using Kotlin Coroutines.
* **High-Fidelity Colors**: Uses L*a*b* color space math for highly accurate Minecraft block color matching.
* **Grid Support**: Automatically slices images into 128x128 chunks for 1x2, 2x2, or larger maps.
* **Native Integration**: Safely generates and updates Minecraft's `last_id.dat` logic to prevent ID conflicts.

## Prerequisites
* Java 17 (JDK)
* Gradle
* A Minecraft Server with **RCON enabled**
* The `MapUploader.zip` Datapack (included in this repository)

---

## Minecraft Server Setup

Before starting the web application, you must configure your Minecraft server to accept the generated maps and RCON commands.

1. **Install the Datapack**
   Place the provided `MapUploader.zip` file into your world's `datapacks` folder and run `/reload` or restart the server.

2. **Enable RCON**
   Open your `server.properties` file and set the following values:
```properties
   enable-rcon=true
rcon.password=your_secret_password
rcon.port=25575
   ```

3. **Mute RCON Broadcasts (Optional but Recommended)**
   By default, every time the web app gives a player a map, admins will see it in the chat. Run these commands in your server console to hide these notifications:
```mcfunction
   /gamerule sendCommandFeedback false
   /gamerule logAdminCommands false
   ```

---

## Installation & Configuration

### Environment Variables
The application relies on environment variables for configuration.

| Variable | Required | Description |
|---|---|---|
| `MC_WORLD_DATA_PATH` | **Yes** | Absolute path to your world's `data` folder (where `last_id.dat` and `map_#.dat` files live). |
| `MC_RCON_PASSWORD` | **Yes** | The RCON password you set in `server.properties`. |
| `MC_RCON_HOST` | No | RCON IP Address (Defaults to `127.0.0.1`). |
| `MC_RCON_PORT` | No | RCON Port (Defaults to `25575`). |

### Running the Server

1. **Build the project**:
```bash
   ./gradlew shadowJar
   ```

2. **Run the application**:

**Linux / macOS**:
```bash
   export MC_WORLD_DATA_PATH="/path/to/your/world/data"
   export MC_RCON_PASSWORD="your_secret_password"
   java -jar build/libs/mapuploader-all.jar
   ```

**Windows (Command Prompt)**:
```cmd
   set MC_WORLD_DATA_PATH="C:\path\to\your\world\data"
   set MC_RCON_PASSWORD="your_secret_password"
   java -jar build\libs\mapuploader-all.jar
   ```

### Usage
Once the server is running, players can access the web UI directly from the game:

1. In Minecraft, run the command `/trigger UploadMap`.
2. The datapack will generate a clickable link in your chat containing your unique player ID.
3. Click the link to open the web UI in your browser.
4. Upload an image (PNG or JPEG), set your grid dimensions, and click any map piece in the generated gallery to send it directly to your in-game inventory!

> **Warning:** Modifying `last_id.dat` while the Minecraft server is running carries a risk of the server overwriting the file from its RAM cache when it saves.