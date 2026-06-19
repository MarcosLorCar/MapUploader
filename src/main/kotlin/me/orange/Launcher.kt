package me.orange

import io.ktor.server.netty.EngineMain
import nl.vv32.rcon.Rcon
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Entry point for the MapUploader jar.
 *
 * The jar runs in one of two modes, decided automatically at runtime:
 *
 *  - **Standalone** — no `proxied_*.jar` is present in the working directory. The Ktor
 *    web app boots exactly as it always has, reading its config from environment
 *    variables (see `application.yaml`). This is what `start-mapuploader` scripts use.
 *
 *  - **Proxy** — a `proxied_*.jar` is present, meaning this jar was installed *in place
 *    of* the real Minecraft server jar (the installer renamed the real one with a
 *    `proxied_` prefix). We then:
 *      1. self-configure the web app from the server's own `server.properties`,
 *      2. launch the real server as a child process, forwarding the JVM's memory/GC
 *         flags down to it (a small slice is reserved for this process),
 *      3. run the web app in-process, and
 *      4. couple both lifecycles — when either side stops, the other does too, and the
 *         server is always stopped gracefully over RCON so the world saves cleanly
 *         (this is what keeps shutdown safe on Windows, where killing the child process
 *         would skip the world save).
 *
 * Nothing about the existing standalone code path changes; this only wraps it.
 */

private const val PROXY_PREFIX = "proxied_"
private const val STOP_TIMEOUT_SECONDS = 90L

private val workDir: File get() = File(System.getProperty("user.dir"))

fun main(args: Array<String>) {
    val serverJar = findProxiedServerJar()
    if (serverJar == null) {
        // Standalone: unchanged, environment-variable driven.
        EngineMain.main(args)
        return
    }
    runProxy(serverJar, args)
}

/** The real Minecraft server jar, renamed by the installer with a `proxied_` prefix. */
private fun findProxiedServerJar(): File? =
    workDir.listFiles { f -> f.isFile && f.name.startsWith(PROXY_PREFIX) && f.name.endsWith(".jar") }
        ?.minByOrNull { it.name }

private fun runProxy(serverJar: File, serverArgs: Array<String>) {
    log("Proxy mode active. Real server jar: '${serverJar.name}'.")

    // Configure the web app from the server's own server.properties so the operator
    // never has to export environment variables by hand.
    val props = readServerProperties()
    applyWebAppConfig(props)

    val child = startServer(serverJar, serverArgs)

    // Stop the server gracefully whenever this process is asked to exit
    // (Ctrl+C, `systemctl stop`, the watcher below, etc.).
    Runtime.getRuntime().addShutdownHook(Thread({ stopServerGracefully(child, props) }, "mc-stop-hook"))

    // If the server exits on its own (e.g. an operator typed `stop` in the console),
    // bring the whole process down with it.
    Thread {
        val code = child.waitFor()
        log("Minecraft server exited (code $code); shutting down MapUploader.")
        exitProcess(code)
    }.apply { isDaemon = true; name = "mc-watcher" }.start()

    // Blocks, serving the web UI, until the server (and therefore the watcher) stops.
    EngineMain.main(webAppArgs())
}

private fun startServer(serverJar: File, serverArgs: Array<String>): Process {
    val command = buildList {
        add(javaExecutable())
        addAll(forwardedJvmFlags())
        add("-jar")
        add(serverJar.name)
        addAll(serverArgs)
    }
    log("Launching server: ${command.joinToString(" ")}")
    return ProcessBuilder(command)
        .directory(workDir)
        .inheritIO() // wire the server console straight to this terminal
        .start()
}

/**
 * Maps the relevant `server.properties` values onto the system properties that
 * `application.yaml` already expects (`MC_WORLD_DATA_PATH`, `MC_RCON_*`). Real
 * environment variables, if set, win — so a `start-mapuploader` script or a hosting
 * panel can still override anything.
 */
private fun applyWebAppConfig(props: Map<String, String>) {
    val levelName = props["level-name"]?.ifBlank { null } ?: "world"
    val mapsDir = File(workDir, "$levelName/data/minecraft/maps")
    setIfAbsent("MC_WORLD_DATA_PATH", mapsDir.absolutePath)

    setIfAbsent("MC_RCON_HOST", "127.0.0.1")
    props["rcon.port"]?.takeIf { it.isNotBlank() }?.let { setIfAbsent("MC_RCON_PORT", it) }
    props["rcon.password"]?.takeIf { it.isNotBlank() }?.let { setIfAbsent("MC_RCON_PASSWORD", it) }

    if (props["enable-rcon"] != "true" || props["rcon.password"].isNullOrBlank()) {
        log("WARNING: RCON is not fully enabled in server.properties. In-game map delivery")
        log("         and graceful shutdown will not work until 'enable-rcon=true' and an")
        log("         'rcon.password' are set.")
    }
}

private fun setIfAbsent(name: String, value: String) {
    if (System.getProperty(name) == null && System.getenv(name) == null) {
        System.setProperty(name, value)
    }
}

private fun readServerProperties(): Map<String, String> {
    val file = File(workDir, "server.properties")
    if (!file.exists()) {
        log("WARNING: server.properties not found in ${workDir.absolutePath}; using defaults.")
        return emptyMap()
    }
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
}

private fun webAppArgs(): Array<String> {
    val port = System.getenv("MAPUPLOADER_WEB_PORT") ?: System.getProperty("MAPUPLOADER_WEB_PORT")
    return if (!port.isNullOrBlank()) arrayOf("-port=$port") else emptyArray()
}

private fun stopServerGracefully(child: Process, props: Map<String, String>) {
    if (!child.isAlive) return // already gone (e.g. operator typed `stop`)
    log("Stopping Minecraft server...")

    val password = sysOrEnv("MC_RCON_PASSWORD") ?: props["rcon.password"]
    val host = sysOrEnv("MC_RCON_HOST") ?: "127.0.0.1"
    val port = (sysOrEnv("MC_RCON_PORT") ?: props["rcon.port"])?.toIntOrNull() ?: 25575

    if (!password.isNullOrBlank()) {
        try {
            Rcon.open(host, port).use { rcon ->
                rcon.authenticate(password)
                rcon.sendCommand("stop")
            }
            log("Sent 'stop' over RCON; waiting for the world to save and the server to exit...")
        } catch (e: Exception) {
            log("WARNING: RCON stop failed (${e.message}); the server may not save cleanly.")
        }
    } else {
        log("WARNING: no RCON password available; cannot stop the server gracefully.")
    }

    if (!child.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log("Server did not stop within ${STOP_TIMEOUT_SECONDS}s; forcing termination.")
        child.destroyForcibly()
    }
}

private fun sysOrEnv(name: String): String? = System.getProperty(name) ?: System.getenv(name)

/**
 * The JVM swallows `-Xmx`/`-Xms`/etc. before `main`, so program args never see them.
 * We recover them with [ManagementFactory] and forward the memory/GC subset to the
 * child server — minus a small reservation off `-Xmx` for this process, so a tightly
 * sized host does not get oversubscribed. Agents, JMX and `-D` system properties are
 * intentionally not forwarded.
 */
private fun forwardedJvmFlags(): List<String> {
    val reserveMb = System.getenv("MAPUPLOADER_HEAP_RESERVE_MB")?.toLongOrNull() ?: 512L
    val input = ManagementFactory.getRuntimeMXBean().inputArguments

    var xmxRaw: String? = null
    var xmsRaw: String? = null
    val others = mutableListOf<String>()
    for (arg in input) {
        when {
            arg.startsWith("-Xmx") -> xmxRaw = arg
            arg.startsWith("-Xms") -> xmsRaw = arg
            arg.startsWith("-Xmn") || arg.startsWith("-Xss") || arg.startsWith("-XX:") -> others.add(arg)
            // anything else is intentionally dropped
        }
    }

    val result = mutableListOf<String>()
    val xmxMb = xmxRaw?.removePrefix("-Xmx")?.let(::parseSizeMb)
    val trimmedXmxMb = xmxMb?.let { maxOf(it - reserveMb, 512L) }

    when {
        trimmedXmxMb != null -> result.add("-Xmx${trimmedXmxMb}M")
        xmxRaw != null -> result.add(xmxRaw) // unparseable -> forward verbatim
    }

    val xmsMb = xmsRaw?.removePrefix("-Xms")?.let(::parseSizeMb)
    when {
        xmsMb != null && trimmedXmxMb != null -> result.add("-Xms${minOf(xmsMb, trimmedXmxMb)}M")
        xmsMb != null -> result.add("-Xms${xmsMb}M")
        xmsRaw != null -> result.add(xmsRaw)
    }

    result.addAll(others)
    return result
}

/** Parses a JVM size like `4G`, `4096m`, `4194304k` or raw bytes into mebibytes. */
private fun parseSizeMb(value: String): Long? {
    val match = Regex("^(\\d+)([kKmMgG]?)$").find(value.trim()) ?: return null
    val n = match.groupValues[1].toLongOrNull() ?: return null
    return when (match.groupValues[2].lowercase()) {
        "g" -> n * 1024
        "m" -> n
        "k" -> n / 1024
        "" -> n / (1024 * 1024)
        else -> null
    }
}

private fun javaExecutable(): String {
    val home = System.getProperty("java.home")
    if (!home.isNullOrBlank()) {
        val exe = if (isWindows()) "java.exe" else "java"
        val candidate = File(File(home, "bin"), exe)
        if (candidate.exists()) return candidate.absolutePath
    }
    return "java"
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("win")

private fun log(message: String) = println("[MapUploader] $message")
