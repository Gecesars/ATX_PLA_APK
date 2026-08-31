package com.gecesars.atxplan.data.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.DeflaterOutputStream

class GeoTiffTerrainSourceTest {
    @Test
    fun readsFloatPredictorThreeTileAndTreatsZeroVerticalScaleAsValid() {
        val file = Files.createTempFile("atx-terrain-", ".tif").toFile()
        try {
            file.writeBytes(testGeoTiff(floatArrayOf(756.5f, 800.25f, -32767f, 912.75f)))

            CopernicusGeoTiffTerrainSource(file).use { source ->
                assertEquals(756.5, source.elevationMeters(-23.25, -46.75)!!, 0.0001)
                assertEquals(800.25, source.elevationMeters(-23.25, -46.25)!!, 0.0001)
                assertNull(source.elevationMeters(-23.75, -46.75))
                assertEquals(912.75, source.elevationMeters(-23.75, -46.25)!!, 0.0001)
                assertNull(source.elevationMeters(-22.99, -46.75))
                assertEquals(4_326, source.evidence.epsgCode)
            }
        } finally {
            file.delete()
        }
    }

    private fun testGeoTiff(values: FloatArray): ByteArray {
        require(values.size == 4)
        val compressedTile = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { compressed ->
                val shuffled = ByteArray(16)
                repeat(2) { row ->
                    repeat(2) { column ->
                        val bits = values[row * 2 + column].toBits()
                        shuffled[row * 8 + column] = (bits ushr 24).toByte()
                        shuffled[row * 8 + 2 + column] = (bits ushr 16).toByte()
                        shuffled[row * 8 + 4 + column] = (bits ushr 8).toByte()
                        shuffled[row * 8 + 6 + column] = bits.toByte()
                    }
                }
                val predicted = shuffled.copyOf()
                repeat(2) { row ->
                    val start = row * 8
                    for (index in 1 until 8) {
                        predicted[start + index] = (
                            (shuffled[start + index].toInt() and 0xff) -
                                (shuffled[start + index - 1].toInt() and 0xff)
                            ).toByte()
                    }
                }
                compressed.write(predicted)
            }
        }.toByteArray()

        val entryCount = 16
        val ifdOffset = 8
        val metadataOffset = ifdOffset + 2 + entryCount * 12 + 4
        val pixelScaleOffset = metadataOffset
        val tiePointOffset = pixelScaleOffset + 24
        val geoKeyOffset = tiePointOffset + 48
        val noDataOffset = geoKeyOffset + 16
        val noData = "-32767\u0000".toByteArray(Charsets.US_ASCII)
        val tileOffset = noDataOffset + noData.size
        val output = ByteBuffer.allocate(tileOffset + compressedTile.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        output.position(ifdOffset)
        output.putShort(entryCount.toShort())

        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            output.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
        }
        fun shortEntry(tag: Int, value: Int) {
            output.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0)
        }

        entry(256, 4, 1, 2)
        entry(257, 4, 1, 2)
        shortEntry(258, 32)
        shortEntry(259, 8)
        shortEntry(277, 1)
        shortEntry(284, 1)
        shortEntry(317, 3)
        entry(322, 4, 1, 2)
        entry(323, 4, 1, 2)
        entry(324, 4, 1, tileOffset)
        entry(325, 4, 1, compressedTile.size)
        shortEntry(339, 3)
        entry(33_550, 12, 3, pixelScaleOffset)
        entry(33_922, 12, 6, tiePointOffset)
        entry(34_735, 3, 8, geoKeyOffset)
        entry(42_113, 2, noData.size, noDataOffset)
        output.putInt(0)

        output.position(pixelScaleOffset)
        output.putDouble(0.5).putDouble(0.5).putDouble(0.0)
        output.position(tiePointOffset)
        doubleArrayOf(0.0, 0.0, 0.0, -47.0, -23.0, 0.0).forEach(output::putDouble)
        output.position(geoKeyOffset)
        intArrayOf(1, 1, 0, 1, 2_048, 0, 1, 4_326).forEach { output.putShort(it.toShort()) }
        output.position(noDataOffset)
        output.put(noData)
        output.position(tileOffset)
        output.put(compressedTile)
        return output.array()
    }
}
