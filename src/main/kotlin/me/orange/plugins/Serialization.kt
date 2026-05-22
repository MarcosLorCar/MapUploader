package me.orange.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.Serializable

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

@Serializable
data class GiveMapRequest(
    val map_id: Int,
    val player_number: Int
)

@Serializable
data class UploadResponse(
    val status: String,
    val message: String? = null,
    val map_ids: List<Int>? = null,
    val grid_x: Int? = null,
    val grid_y: Int? = null
)

@Serializable
data class GiveMapResponse(
    val status: String,
    val message: String? = null
)