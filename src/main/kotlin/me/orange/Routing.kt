package me.orange

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.orange.plugins.GiveMapRequest
import me.orange.plugins.GiveMapResponse
import me.orange.plugins.UploadResponse
import nl.vv32.rcon.Rcon
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun Application.configureRouting() {
    val configPath = environment.config.propertyOrNull("app.minecraft.worldDataPath")?.getString()

    if (configPath.isNullOrBlank()) {
        val errorMessage = """
            
        =========================================================
         FATAL ERROR: MISSING CONFIGURATION
        =========================================================
         This server requires the 'MC_WORLD_DATA_PATH' environment
         variable to be set to the Minecraft maps folder.
        
         Example (Linux): export MC_WORLD_DATA_PATH='/path/to/maps'
         Example (Win):   set MC_WORLD_DATA_PATH="C:\path\to\maps"
        =========================================================
        
        """.trimIndent()

        System.err.println(errorMessage)

        throw IllegalStateException("Startup failed: Missing MC_WORLD_DATA_PATH.")
    }

    val rconHost = environment.config.propertyOrNull("app.minecraft.rcon.host")?.getString() ?: "127.0.0.1"
    val rconPort = environment.config.propertyOrNull("app.minecraft.rcon.port")?.getString()?.toIntOrNull() ?: 25575
    val rconPassword = environment.config.propertyOrNull("app.minecraft.rcon.password")?.getString()

    if (rconPassword.isNullOrBlank()) {
        System.err.println("WARNING: RCON password not set in environment (MC_RCON_PASSWORD). /give-map will fail.")
    }

    val minecraftMapsDir = File(configPath)

    val appDataDir = File("map_uploader_data").apply { mkdirs() }
    val previewsDir = File(appDataDir, "previews").apply { mkdirs() }

    MapIdManager.initialize(minecraftMapsDir)
    HistoryManager.initialize(appDataDir)

    routing {
        get("/history/{sessionId}") {
            val sessionId = call.parameters["sessionId"]
            if (sessionId == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing session ID")
                return@get
            }
            call.respond(HistoryManager.getHistoryForSession(sessionId))
        }

        get("/previews/{filename}") {
            val filename = call.parameters["filename"]
            if (filename == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing filename")
                return@get
            }

            val file = File(previewsDir, filename)
            if (file.exists() && file.isFile) {
                // Manually serve the image file
                call.respondFile(file)
            } else {
                call.respond(HttpStatusCode.NotFound, "Image not found")
            }
        }

        post("/upload-map") {
            try {
                val multipartData = call.receiveMultipart()
                var uploadedImage: BufferedImage? = null
                var gridX = 1
                var gridY = 1
                var sessionId = ""

                multipartData.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            uploadedImage = withContext(Dispatchers.IO) {
                                ImageIO.read(part.provider().toInputStream())
                            }
                        }
                        is PartData.FormItem -> {
                            if (part.name == "grid_x") gridX = part.value.toIntOrNull() ?: 1
                            if (part.name == "grid_y") gridY = part.value.toIntOrNull() ?: 1
                            if (part.name == "session_id") sessionId = part.value
                        }
                        else -> {}
                    }
                    part.release()
                }

                call.application.log.info("Received a new map upload request from id {}", sessionId)

                if (uploadedImage == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        UploadResponse(status = "error", message = "Failed to read image.")
                    )
                    return@post
                }

                val result = ImageProcessor.processMapGrid(uploadedImage, gridX, gridY)
                val generatedIds = mutableListOf<Int>()

                for (chunk in result.chunks) {
                    val newMapId = MapIdManager.getNextId()
                    MapGenerator.saveMapDatFile(chunk, newMapId, minecraftMapsDir)
                    generatedIds.add(newMapId)
                }

                val projectId = java.util.UUID.randomUUID().toString()
                val previewFile = File(previewsDir, "$projectId.png")

                withContext(Dispatchers.IO) {
                    ImageIO.write(result.previewImage, "png", previewFile)
                }

                val project = UploadProject(
                    id = projectId,
                    session_id = sessionId,
                    grid_x = gridX,
                    grid_y = gridY,
                    map_ids = generatedIds,
                    timestamp = System.currentTimeMillis()
                )
                HistoryManager.addProject(project)

                // Respond with the project data
                call.respond(project)

            } catch (e: Exception) {
                call.application.log.error("Backend crashed during upload", e)
                val safeError = e.localizedMessage ?: "Unknown error"
                call.respond(
                    HttpStatusCode.InternalServerError,
                    UploadResponse(status = "error", message = "Backend crashed: $safeError")
                )
            }
        }

        post("/give-map") {
            try {
                // 1. Check if RCON is configured
                if (rconPassword.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        GiveMapResponse(status = "error", message = "Server RCON password not configured.")
                    )
                    return@post
                }

                // 2. Ktor automatically parses the JSON into your GiveMapRequest data class
                val request = call.receive<GiveMapRequest>()

                // 3. Build the command.
                val command = "function mapuploader:give_map {map_id:${request.map_id}, player_id:${request.player_number}}"

                // 4. Send via RCON on a background thread so it doesn't block the server
                withContext(Dispatchers.IO) {
                    Rcon.open(rconHost, rconPort).use { rcon ->
                        rcon.authenticate(rconPassword)
                        rcon.sendCommand(command)
                    }
                }

                call.respond(
                    HttpStatusCode.OK,
                    GiveMapResponse(status = "success")
                )

            } catch (e: Exception) {
                e.printStackTrace()
                val safeError = e.localizedMessage ?: "Unknown error"
                call.respond(
                    HttpStatusCode.InternalServerError,
                    GiveMapResponse(status = "error", message = "RCON failed: $safeError")
                )
            }
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get

            if (id.all { it.isDigit() }) {
                // If it's a number (e.g., /123), serve the index.html page
                val resource = call.resolveResource("index.html", "static")
                if (resource != null) {
                    call.respond(resource)
                } else {
                    call.respond(HttpStatusCode.NotFound, "index.html not found")
                }
            } else {
                // If it's not a number, it's a static asset request (like /script.js)
                val resource = call.resolveResource(id, "static")
                if (resource != null) {
                    call.respond(resource)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Resource not found")
                }
            }
        }

        staticResources("/", "static")
    }
}