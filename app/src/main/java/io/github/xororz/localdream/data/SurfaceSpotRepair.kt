package io.github.xororz.localdream.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bounded classical spot repair for small marks on smooth surfaces.
 * No generated content, prompts, model inference or human-part segmentation.
 */
object SurfaceSpotRepair {
    const val MAX_SIDE = 512
    const val MAX_REPAIR_PIXELS = 40_000

    data class Selection(val width: Int, val height: Int, val mask: BooleanArray) {
        val count: Int get() = mask.count { it }
    }

    private fun distance(a: Int, b: Int): Int {
        val r = ((a ushr 16) and 255) - ((b ushr 16) and 255)
        val g = ((a ushr 8) and 255) - ((b ushr 8) and 255)
        val bl = (a and 255) - (b and 255)
        return r * r + g * g + bl * bl
    }

    /** Return null instead of inventing a mask for a large/ambiguous region. */
    fun select(
        source: IntArray, width: Int, height: Int, x: Int, y: Int, tolerance: Int = 40,
    ): Selection? {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE)
        require(source.size == width * height && x in 0 until width && y in 0 until height)
        val seed = y * width + x
        if ((source[seed] ushr 24) != 255) return null
        val limit = minOf(4000, source.size / 25)
        if (limit < 1) return null
        val radius = minOf(64, minOf(width, height) / 3).coerceAtLeast(2)
        val threshold = tolerance.coerceIn(10, 65).let { it * it }
        val selected = BooleanArray(source.size)
        val queue = IntArray(limit)
        selected[seed] = true
        queue[0] = seed
        var size = 1
        var cursor = 0
        while (cursor < size) {
            val i = queue[cursor++]
            val px = i % width
            val py = i / width
            if (px == 0 || px == width - 1 || py == 0 || py == height - 1 ||
                abs(px - x) >= radius || abs(py - y) >= radius
            ) return null
            for (next in intArrayOf(i - 1, i + 1, i - width, i + width)) {
                if (selected[next] || (source[next] ushr 24) != 255) continue
                if (distance(source[next], source[seed]) > threshold) continue
                if (size == limit) return null
                selected[next] = true
                queue[size++] = next
            }
        }
        return Selection(width, height, selected)
    }

    /**
     * Boundary-seeded harmonic interpolation. Changes ONLY selected pixels;
     * no inferred anatomical detail, texture synthesis or diffusion model.
     */
    fun repair(source: IntArray, width: Int, height: Int, mask: BooleanArray): IntArray {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE)
        require(source.size == width * height && mask.size == source.size)
        val count = mask.count { it }
        require(count in 1..MAX_REPAIR_PIXELS)
        val indices = IntArray(count)
        val queue = IntArray(count)
        val filled = BooleanArray(source.size)
        val rgb = source.copyOf()
        var size = 0
        var tail = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            require(i % width in 1 until width - 1 && i / width in 1 until height - 1)
            require((source[i] ushr 24) == 255)
            indices[size++] = i
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (j in intArrayOf(i - 1, i + 1, i - width, i + width)) {
                if (!mask[j]) {
                    // An alpha edge is not an observed opaque background.
                    // Reject rather than pull hidden RGB values into the repair.
                    require((source[j] ushr 24) == 255) {
                        "Repair touches a transparent boundary"
                    }
                    r += (source[j] ushr 16) and 255
                    g += (source[j] ushr 8) and 255
                    b += source[j] and 255
                    n++
                }
            }
            if (n > 0) {
                rgb[i] = -0x1000000 or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                filled[i] = true
                queue[tail++] = i
            }
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            for (j in intArrayOf(i - 1, i + 1, i - width, i + width)) {
                if (mask[j] && !filled[j]) {
                    rgb[j] = rgb[i]
                    filled[j] = true
                    queue[tail++] = j
                }
            }
        }
        require(tail == count) { "No opaque background boundary" }
        val red = FloatArray(source.size) { ((rgb[it] ushr 16) and 255).toFloat() }
        val green = FloatArray(source.size) { ((rgb[it] ushr 8) and 255).toFloat() }
        val blue = FloatArray(source.size) { (rgb[it] and 255).toFloat() }
        // Bounded convergence: a flat background needs almost no iteration,
        // whereas a lighting gradient benefits from more relaxation sweeps.
        for (iteration in 0 until 160) {
            var maxDelta = 0f
            for (i in indices) {
                val a = i - 1
                val b = i + 1
                val c = i - width
                val d = i + width
                val nr = (red[a] + red[b] + red[c] + red[d]) * .25f
                val ng = (green[a] + green[b] + green[c] + green[d]) * .25f
                val nb = (blue[a] + blue[b] + blue[c] + blue[d]) * .25f
                maxDelta = maxOf(maxDelta, abs(nr - red[i]), abs(ng - green[i]), abs(nb - blue[i]))
                red[i] = nr
                green[i] = ng
                blue[i] = nb
            }
            if (maxDelta < .05f) break
        }
        for (i in indices) {
            rgb[i] = -0x1000000 or
                (red[i].roundToInt().coerceIn(0, 255) shl 16) or
                (green[i].roundToInt().coerceIn(0, 255) shl 8) or
                blue[i].roundToInt().coerceIn(0, 255)
        }
        return rgb
    }
}
