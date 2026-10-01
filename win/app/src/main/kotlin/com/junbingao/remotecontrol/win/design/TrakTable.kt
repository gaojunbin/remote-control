package com.junbingao.remotecontrol.win.design

import org.jetbrains.skia.Typeface
import java.nio.ByteBuffer

/**
 * A font's AAT `trak` table, as CoreText applies it to the system face: the normal track's value
 * at each point size, in font units, linearly interpolated between the sizes it lists and held at
 * either end. Skia's shaping never reads the table, so the Mac's face adds it back as letter
 * spacing (`SystemFace`).
 */
internal class TrakTable(private val sizes: FloatArray, private val values: FloatArray) {
    /** The tracking at `size`, in ems. */
    fun tracking(size: Float, unitsPerEm: Int): Float {
        if (sizes.isEmpty() || unitsPerEm <= 0) return 0f
        val units = when {
            size <= sizes.first() -> values.first()
            size >= sizes.last() -> values.last()
            else -> {
                val upper = sizes.indexOfFirst { it >= size }
                val lower = upper - 1
                val t = (size - sizes[lower]) / (sizes[upper] - sizes[lower])
                values[lower] + t * (values[upper] - values[lower])
            }
        }
        return units / unitsPerEm
    }

    companion object {
        fun read(typeface: Typeface): TrakTable? = typeface.getTableData("trak")?.bytes?.let(::parse)

        /** The horizontal normal track (track value 0), or null for a table without one. */
        fun parse(bytes: ByteArray): TrakTable? {
            val table = ByteBuffer.wrap(bytes)
            if (bytes.size < 12) return null
            val horizontal = table.getShort(6).toInt() and 0xFFFF
            if (horizontal == 0 || horizontal + 8 > bytes.size) return null
            val tracks = table.getShort(horizontal).toInt() and 0xFFFF
            val count = table.getShort(horizontal + 2).toInt() and 0xFFFF
            val sizeTable = table.getInt(horizontal + 4)
            val sizes = FloatArray(count) { table.getInt(sizeTable + 4 * it) / 65536f }
            for (index in 0 until tracks) {
                val entry = horizontal + 8 + 8 * index
                if (table.getInt(entry) != 0) continue
                val offset = table.getShort(entry + 6).toInt() and 0xFFFF
                val values = FloatArray(count) { table.getShort(offset + 2 * it).toFloat() }
                return TrakTable(sizes, values)
            }
            return null
        }
    }
}
