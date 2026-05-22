package me.orange

import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object MapIdManager {

    private var currentHighestMapId: AtomicInteger? = null
    private lateinit var lastIdFile: File

    fun initialize(dataDir: File) {
        // Point this directly to the last_id.dat file.
        // If it sits in the 'data' folder instead of 'maps', just adjust the path!
        lastIdFile = File(dataDir, "last_id.dat")

        if (currentHighestMapId == null) {
            val nextId = readHighestIdFromNbt() + 1
            currentHighestMapId = AtomicInteger(nextId)
            println("me.orange.MapIdManager initialized. Next available Map ID: $nextId")
        }
    }

    fun getNextId(): Int {
        val counter = currentHighestMapId
            ?: throw IllegalStateException("me.orange.MapIdManager was not initialized before use!")

        // Safely get the next number for our new map
        val assignedId = counter.getAndIncrement()

        // CRITICAL: We must instantly save this new number back into last_id.dat!
        // If we don't, the Minecraft server won't know we used this ID, and
        // the next time a player crafts a map in-game, it will overwrite your map art!
        updateLastIdFile(assignedId)

        return assignedId
    }

    private fun readHighestIdFromNbt(): Int {
        if (!lastIdFile.exists()) return -1

        try {
            val root = NBTUtil.read(lastIdFile).tag as CompoundTag

            // 1. Check if the "data" tag exists first
            if (root.containsKey("data")) {
                val dataTag = root.getCompoundTag("data")

                // 2. Read the "map" integer from inside the data tag
                if (dataTag.containsKey("map")) {
                    return dataTag.getInt("map")
                }
            }
            return -1
        } catch (_: Exception) {
            println("Warning: Failed to read last_id.dat. Defaulting to -1.")
            return -1
        }
    }

    private fun updateLastIdFile(newLatestId: Int) {
        try {
            // Read the existing file, or create a brand new nested structure if it's missing
            val root = if (lastIdFile.exists()) {
                NBTUtil.read(lastIdFile).tag as CompoundTag
            } else {
                val newRoot = CompoundTag()
                newRoot.putInt("DataVersion", 3465) // Add a default data version
                newRoot.put("data", CompoundTag())  // Add the empty 'data' compound
                newRoot
            }

            // Safely get the "data" compound tag
            val dataTag = if (root.containsKey("data")) {
                root.getCompoundTag("data")
            } else {
                val newData = CompoundTag()
                root.put("data", newData)
                newData
            }

            // Update the map ID *inside* the data tag
            dataTag.putInt("map", newLatestId)

            // Save it back uncompressed!
            NBTUtil.write(NamedTag("", root), lastIdFile, false)

        } catch (e: Exception) {
            println("CRITICAL ERROR: Could not save last_id.dat! Minecraft might overwrite your maps!")
            e.printStackTrace()
        }
    }
}