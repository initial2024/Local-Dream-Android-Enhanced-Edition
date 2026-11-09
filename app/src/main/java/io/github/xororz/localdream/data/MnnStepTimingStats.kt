package io.github.xororz.localdream.data

/**
 * Pure statistics for independent native UNet calls. This measures elapsed
 * host-visible work (GPU transfers, inference and synchronization), NOT GPU
 * kernel-only time; variation does not identify thermal throttling by itself.
 */
object MnnStepTimingStats {
    data class Summary(
        val minimumMs: Long,
        val medianMs: Long,
        val maximumMs: Long,
        val firstFiveMedianMs: Long,
        val lastFiveMedianMs: Long,
    )

    fun median(values: List<Long>): Long {
        require(values.isNotEmpty())
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun summarize(series: LongArray?): Summary? {
        if (series == null || series.isEmpty() || series.size > 50 ||
            series.any { it < 0L || it > 120_000L }
        ) return null
        val values = series.toList()
        val head = values.take(5)
        val tail = values.takeLast(5)
        return Summary(
            minimumMs = values.min(),
            medianMs = median(values),
            maximumMs = values.max(),
            firstFiveMedianMs = median(head),
            lastFiveMedianMs = median(tail),
        )
    }
}
