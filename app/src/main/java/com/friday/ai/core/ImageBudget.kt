package com.friday.ai.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How big an image Friday handles, decided before anything is allocated.
 *
 * A screen or a photo goes to the vision model as a JPEG in Base64. Full
 * resolution buys nothing there and costs memory on the phone: a 50-megapixel
 * photo decoded whole is 200 MB. So every image is held to these sizes on
 * the way in, and to a byte budget on the way out.
 */
object ImageBudget {

    /** Longest side kept: enough to read a screen's text. */
    const val MAX_EDGE = 1600

    /** Pixels kept after scaling (about 2.4 MP). */
    const val MAX_PIXELS = 2_400_000

    /** Pixels a photo may be decoded at before scaling; sampling keeps it under this. */
    const val MAX_DECODE_PIXELS = 4_000_000

    /** JPEG bytes sent to the vision model (Base64 adds a third on top). */
    const val MAX_JPEG_BYTES = 900_000

    /** Qualities tried in turn until the JPEG fits [MAX_JPEG_BYTES]. */
    val QUALITIES = listOf(80, 65, 50, 35)

    data class Size(val width: Int, val height: Int)

    /** [width]×[height] scaled down, same shape, to fit [maxEdge] and [maxPixels]; never scaled up. */
    fun bounded(width: Int, height: Int, maxEdge: Int = MAX_EDGE, maxPixels: Int = MAX_PIXELS): Size {
        require(width > 0 && height > 0) { "empty image" }
        val byEdge = maxEdge.toDouble() / max(width, height)
        val byPixels = sqrt(maxPixels.toDouble() / (width.toLong() * height))
        val scale = min(1.0, min(byEdge, byPixels))
        return Size(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    /** The power-of-two sample size that decodes [width]×[height] within [maxPixels]. */
    fun sampleSize(width: Int, height: Int, maxPixels: Int = MAX_DECODE_PIXELS): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
        return sample
    }

    /** The first of [QUALITIES] whose JPEG fits [budget]; null if none does. */
    fun underBudget(budget: Int = MAX_JPEG_BYTES, compress: (quality: Int) -> ByteArray): ByteArray? =
        QUALITIES.asSequence().map(compress).firstOrNull { it.size <= budget }
}
