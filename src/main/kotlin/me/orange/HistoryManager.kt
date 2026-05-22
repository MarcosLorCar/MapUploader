package me.orange

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UploadProject(
    val id: String,
    val session_id: String,
    val grid_x: Int,
    val grid_y: Int,
    val map_ids: List<Int>,
    val timestamp: Long
)

object HistoryManager {
    private lateinit var historyFile: File
    private val projects = mutableListOf<UploadProject>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun initialize(dataDir: File) {
        historyFile = File(dataDir, "uploader_history.json")
        if (historyFile.exists()) {
            val text = historyFile.readText()
            if (text.isNotBlank()) {
                projects.addAll(json.decodeFromString<List<UploadProject>>(text))
            }
        }
    }

    @Synchronized
    fun addProject(project: UploadProject) {
        projects.add(0, project)
        historyFile.writeText(json.encodeToString(projects))
    }

    @Synchronized
    fun getHistoryForSession(sessionId: String): List<UploadProject> {
        return projects.filter { it.session_id == sessionId }
    }
}