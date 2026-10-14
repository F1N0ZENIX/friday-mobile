package com.friday.ai.core

import android.graphics.Bitmap
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** An image for the vision model: scaled to [ImageBudget], compressed under its byte budget. */
object JpegEncoding {

    private const val HALVINGS = 2

    /** [bitmap] scaled down to [ImageBudget.bounded]; the same object when it already fits. */
    fun bounded(bitmap: Bitmap): Bitmap {
        val size = ImageBudget.bounded(bitmap.width, bitmap.height)
        if (size.width == bitmap.width && size.height == bitmap.height) return bitmap
        return bitmap.scale(size.width, size.height)
    }

    /**
     * Base64 JPEG of [bitmap] within [ImageBudget.MAX_JPEG_BYTES], halving the
     * size when no quality fits; null when even that fails. [bitmap] itself is
     * left to the caller; any smaller copies made here are recycled.
     */
    fun base64(bitmap: Bitmap): String? {
        var current = bitmap
        try {
            repeat(HALVINGS + 1) {
                val jpeg = ImageBudget.underBudget { quality -> compress(current, quality) }
                if (jpeg != null) return Base64.encodeToString(jpeg, Base64.NO_WRAP)
                val half = current.scale(max(1, current.width / 2), max(1, current.height / 2))
                if (current !== bitmap) current.recycle()
                current = half
            }
            return null
        } finally {
            if (current !== bitmap) current.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
