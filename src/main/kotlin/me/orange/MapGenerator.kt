package me.orange

import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.ByteArrayTag
import net.querz.nbt.tag.CompoundTag
import java.io.File

object MapGenerator {
    fun saveMapDatFile(colorArray: ByteArray, mapId: Int, dataDir: File) {
        val root = CompoundTag()
        val data = CompoundTag()

        data.putByte("scale", 0)
        data.putString("dimension", "minecraft:overworld")
        data.putByte("locked", 1)
        data.putByte("trackingPosition", 0)
        data.putByte("unlimitedTracking", 0)
        data.putShort("width", 128)
        data.putShort("height", 128)
        data.putInt("xCenter", 0)
        data.putInt("zCenter", 0)
        data.put("colors", ByteArrayTag(colorArray))

        root.put("data", data)
        root.putInt("DataVersion", 3465)

        val mapFile = File(dataDir, "$mapId.dat")

        // Wrap 'root' in a NamedTag with an empty string name
        NBTUtil.write(NamedTag("", root), mapFile, true)
    }
}