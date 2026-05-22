package me.orange

import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object MapIdManager {
    private val logger = LoggerFactory.getLogger(MapIdManager::class.java)

    private var currentHighestMapId: AtomicInteger? = null
    private lateinit var lastIdFile: File

    fun initialize(dataDir: File) {
        lastIdFile = File(dataDir, "last_id.dat")

        if (currentHighestMapId == null) {
            val nextId = readHighestIdFromNbt() + 1
            currentHighestMapId = AtomicInteger(nextId)

            // 2. Use logger instead of println
            logger.info("MapIdManager initialized. Next available Map ID: {}", nextId)

            logger.warn("=========================================================")
            logger.warn("WARNING: If the Minecraft server is currently running,")
            logger.warn("it may overwrite last_id.dat from its RAM, erasing IDs!")
            logger.warn("=========================================================")
        }
    }

    @Synchronized
    fun getNextId(): Int {
        val counter = currentHighestMapId
            ?: throw IllegalStateException("MapIdManager was not initialized before use!")

        // Safely get the next number for our new map
        val assignedId = counter.getAndIncrement()

        // Write to the file while locked so no other thread can interrupt
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