package me.orange

import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import org.slf4j.LoggerFactory
import java.io.File

object MapIdManager {
    private val logger = LoggerFactory.getLogger(MapIdManager::class.java)

    private var currentHighestMapId: Int? = null
    private lateinit var lastIdFile: File

    fun initialize(dataDir: File) {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            logger.info("Created missing maps directory at: {}", dataDir.absolutePath)
        }

        lastIdFile = File(dataDir, "last_id.dat")

        if (currentHighestMapId == null) {
            // Edge Case 2: Missing last_id.dat is handled smoothly here.
            // readHighestId() returns -1 if missing, so nextId starts at 0.
            val nextId = readHighestIdFromNbt() + 1
            currentHighestMapId = nextId

            logger.info("MapIdManager initialized. Next available Map ID: {}", nextId)
        }
    }

    @Synchronized
    fun getNextId(): Int {
        val current = currentHighestMapId
            ?: throw IllegalStateException("MapIdManager was not initialized before use!")

        // Safely use and increment the standard Int
        val assignedId = current
        currentHighestMapId = current + 1

        updateLastIdFile(assignedId)

        return assignedId
    }

    private fun readHighestIdFromNbt(): Int {
        if (!lastIdFile.exists()) return -1

        try {
            val root = NBTUtil.read(lastIdFile).tag as CompoundTag

            if (root.containsKey("data")) {
                val dataTag = root.getCompoundTag("data")

                if (dataTag.containsKey("map")) {
                    return dataTag.getInt("map")
                }
            }
            return -1
        } catch (e: Exception) {
            // Log the warning safely
            logger.warn("Failed to read last_id.dat. Defaulting to -1.", e)
            return -1
        }
    }

    private fun updateLastIdFile(newLatestId: Int) {
        try {
            val root = if (lastIdFile.exists()) {
                NBTUtil.read(lastIdFile).tag as CompoundTag
            } else {
                val newRoot = CompoundTag()
                newRoot.putInt("DataVersion", 3465)
                newRoot.put("data", CompoundTag())
                newRoot
            }

            val dataTag = if (root.containsKey("data")) {
                root.getCompoundTag("data")
            } else {
                val newData = CompoundTag()
                root.put("data", newData)
                newData
            }

            dataTag.putInt("map", newLatestId)

            NBTUtil.write(NamedTag("", root), lastIdFile, false)

        } catch (e: Exception) {
            logger.error("CRITICAL ERROR: Could not save last_id.dat! Minecraft might overwrite your maps!", e)
        }
    }
}