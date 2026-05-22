package me.orange

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

object ImageProcessor {
    @Serializable
    data class ColorSet(
        val mapdatId: Int,
        val tonesRGB: Map<String, IntArray>
    )

    class MapProcessingResult(
        val chunks: List<ByteArray>,
        val previewImage: BufferedImage
    )

    private data class PrecomputedColor(
        val finalMapdatId: Int,
        val lab: DoubleArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PrecomputedColor

            if (finalMapdatId != other.finalMapdatId) return false
            if (!lab.contentEquals(other.lab)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = finalMapdatId
            result = 31 * result + lab.contentHashCode()
            return result
        }
    }

    private val precomputedPalette: List<PrecomputedColor> = loadAndPrecomputePalette()

    private val colorCache = ConcurrentHashMap<Int, Byte>()

    private fun loadAndPrecomputePalette(): List<PrecomputedColor> {
        val inputStream = this::class.java.classLoader.getResourceAsStream("coloursJSON.json")
            ?: throw IllegalStateException("coloursJSON.json not found!")

        val jsonString = inputStream.bufferedReader().use { it.readText() }

        val lenientJson = Json { ignoreUnknownKeys = true }

        val rawPalette: Map<String, ColorSet> = lenientJson.decodeFromString(jsonString)

        val precomputed = mutableListOf<PrecomputedColor>()
        val toneOffsets = mapOf("dark" to 0, "normal" to 1, "light" to 2, "unobtainable" to 3)

        for ((_, colorData) in rawPalette) {
            val baseId = colorData.mapdatId
            for ((tone, toneRgb) in colorData.tonesRGB) {
                val offset = toneOffsets[tone] ?: 1
                val finalId = (4 * baseId) + offset
                val lab = rgb2lab(toneRgb)
                precomputed.add(PrecomputedColor(finalId, lab))
            }
        }
        return precomputed
    }

    // The Multi-Threaded Processing Loop
    suspend fun processMapGrid(originalImage: BufferedImage, gridX: Int, gridY: Int): MapProcessingResult = coroutineScope {
        val totalWidth = gridX * 128
        val totalHeight = gridY * 128

        val resizedImg = BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB)
        val g = resizedImg.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(originalImage, 0, 0, totalWidth, totalHeight, null)
        g.dispose()

        // 3. The Pixel Buffer: Grab all pixels at once (MASSIVE speed boost)
        val rgbArray = IntArray(totalWidth * totalHeight)
        resizedImg.getRGB(0, 0, totalWidth, totalHeight, rgbArray, 0, totalWidth)

        // 4. Parallel Processing: async { } runs each map chunk on a different CPU thread concurrently
        val deferredChunks = (0 until gridY).flatMap { mapY ->
            (0 until gridX).map { mapX ->

                // Dispatchers.Default is specifically designed for heavy CPU math tasks
                async(Dispatchers.Default) {
                    val colorArray = ByteArray(16384)
                    var index = 0

                    for (pixelY in 0 until 128) {
                        for (pixelX in 0 until 128) {
                            val absX = (mapX * 128) + pixelX
                            val absY = (mapY * 128) + pixelY

                            val rgb = rgbArray[absY * totalWidth + absX]

                            val cleanRgb = rgb and 0xFFFFFF

                            val bestId = colorCache.getOrPut(cleanRgb) {
                                val red = (rgb shr 16) and 0xFF
                                val green = (rgb shr 8) and 0xFF
                                val blue = rgb and 0xFF
                                getClosestMapdatId(intArrayOf(red, green, blue)).toByte()
                            }
                            colorArray[index] = bestId.toByte()
                            index++
                        }
                    }
                    colorArray // Return the finished chunk
                }
            }
        }

        // Wait for all CPU threads to finish their chunks, then return the list
        // Wait for chunks, then return them alongside the resized image
        MapProcessingResult(deferredChunks.awaitAll(), resizedImg)
    }

    private fun getClosestMapdatId(pixelRgb: IntArray): Int {
        val pixelLab = rgb2lab(pixelRgb)

        var shortestDistance = Double.MAX_VALUE
        var bestMapdatId = 0

        // Look how clean this is now! We just compare against the precomputed list.
        for (precomputed in precomputedPalette) {
            val dl = pixelLab[0] - precomputed.lab[0]
            val da = pixelLab[1] - precomputed.lab[1]
            val db = pixelLab[2] - precomputed.lab[2]

            val distance = (dl * dl) + (da * da) + (db * db)

            if (distance < shortestDistance) {
                shortestDistance = distance
                bestMapdatId = precomputed.finalMapdatId
            }
        }
        return bestMapdatId
    }

    private fun rgb2lab(rgb: IntArray): DoubleArray {
        var r = rgb[0] / 255.0
        var g = rgb[1] / 255.0
        var b = rgb[2] / 255.0

        r = if (r <= 0.04045) r / 12.92 else ((r + 0.055) / 1.055).pow(2.4)
        g = if (g <= 0.04045) g / 12.92 else ((g + 0.055) / 1.055).pow(2.4)
        b = if (b <= 0.04045) b / 12.92 else ((b + 0.055) / 1.055).pow(2.4)

        val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047
        val y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750) / 1.00000
        val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883

        val fX = if (x > 0.008856) x.pow(1.0 / 3.0) else (7.787 * x) + (16.0 / 116.0)
        val fY = if (y > 0.008856) y.pow(1.0 / 3.0) else (7.787 * y) + (16.0 / 116.0)
        val fZ = if (z > 0.008856) z.pow(1.0 / 3.0) else (7.787 * z) + (16.0 / 116.0)

        val l = (116.0 * fY) - 16.0
        val a = 500.0 * (fX - fY)
        val bVal = 200.0 * (fY - fZ)

        return doubleArrayOf(l, a, bVal)
    }
}