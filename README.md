# MapUploader Backend

A high-performance Ktor backend designed to convert images into Minecraft Map (.dat) files for use in-game.

## Features
* **Async Processing**: Non-blocking image uploads using Kotlin Coroutines.
* **High-Fidelity**: Uses L*a*b* color space math for accurate Minecraft block color matching.
* **Grid Support**: Automatically slices images into 128x128 chunks for 1x2, 2x2, or larger maps.
* **Native Integration**: Generates compatible `.dat` files using Minecraft's `last_id.dat` logic to prevent ID conflicts.

## Getting Started

### Prerequisites
* Java 17 (JDK)
* Gradle

### Installation & Run
1.  **Build the project**:

```bash
    ./gradlew shadowJar
```
2.  **Run the server**:
    You must provide the path to your Minecraft world's `data/minecraft/maps` folder via an environment variable. The server will fail to start if this is missing.

**Linux/Mac**:
```bash
    export MC_WORLD_DATA_PATH="/path/to/your/world/data/minecraft/maps"
    java -jar build/libs/mapuploader-all.jar
```

**Windows**:
```cmd
    set MC_WORLD_DATA_PATH="C:\path\to\your\world\data\minecraft\maps"
    java -jar build\libs\mapuploader-all.jar
```