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

    val dataDir = File(configPath)

    MapIdManager.initialize(dataDir)

    routing {
        post("/upload-map") {
            try {
                val multipartData = call.receiveMultipart()
                var uploadedImage: BufferedImage? = null
                var gridX = 1
                var gridY = 1

                // Extract the image AND the grid dimensions
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
                        }
                        else -> {}
                    }
                    part.release()
                }

                if (uploadedImage == null) {
                    call.respondText("""{"status": "error", "message": "Failed to read image."}""", ContentType.Application.Json)
                    return@post
                }

                // Process the image into a list of 128x128 chunks
                val mapChunks = ImageProcessor.processMapGrid(uploadedImage, gridX, gridY)
                val generatedIds = mutableListOf<Int>()

                // Loop through the chunks, generate an ID, and save the .dat file
                for (chunk in mapChunks) {
                    val newMapId = MapIdManager.getNextId()
                    MapGenerator.saveMapDatFile(chunk, newMapId, dataDir)
                    generatedIds.add(newMapId)
                }

                // Send the LIST of IDs back to the frontend
                // (We format it manually as a JSON array like [5000, 5001, 5002, 5003])
                val idJsonArray = generatedIds.joinToString(prefix = "[", postfix = "]")

                call.respondText(
                    """{"status": "success", "map_ids": $idJsonArray, "grid_x": $gridX, "grid_y": $gridY}""",
                    ContentType.Application.Json
                )
            } catch (e: Exception) {
                e.printStackTrace()
                val safeError = e.localizedMessage?.replace("\"", "'") ?: "Unknown error"
                call.respondText(
                    """{"status": "error", "message": "Backend crashed: $safeError"}""",
                    ContentType.Application.Json
                )
            }
        }
        staticResources("/", "static")
    }
}