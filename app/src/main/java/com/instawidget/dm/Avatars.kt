package com.instawidget.dm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws the little coloured initial circle on each widget row.
 *
 * Instagram's notifications carry no avatar we are allowed to reuse, and the
 * app has no network access to fetch one, so the next best thing is a stable
 * monogram: same sender, same colour, every time.
 */
object Avatars {

    private val PALETTE = intArrayOf(
        R.color.avatar_1,
        R.color.avatar_2,
        R.color.avatar_3,
        R.color.avatar_4,
        R.color.avatar_5,
        R.color.avatar_6
    )

    /** Size of the generated bitmap, matching the 34dp slot in the row. */
    private const val SIZE_DP = 34

    fun forSender(context: Context, sender: String): Bitmap {
        val sizePx = (SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = context.getColor(colorFor(sender))
        canvas.drawCircle(radius, radius, radius, paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sizePx * 0.42f

        // Centre on the glyph box rather than the baseline, or the letter sits
        // noticeably low in the circle.
        val metrics = paint.fontMetrics
        val baseline = radius - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(initialOf(sender), radius, baseline, paint)

        return bitmap
    }

    private fun initialOf(sender: String): String {
        val first = sender.trim().firstOrNull { it.isLetterOrDigit() }
        return first?.uppercase() ?: "?"
    }

    /**
     * Stable colour per sender. String.hashCode is consistent for a given
     * string across runs, which is all this needs.
     */
    private fun colorFor(sender: String): Int {
        val index = Math.floorMod(sender.lowercase().hashCode(), PALETTE.size)
        return PALETTE[index]
    }
}
