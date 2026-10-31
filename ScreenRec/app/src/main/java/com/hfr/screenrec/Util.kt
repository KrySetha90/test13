package com.hfr.screenrec

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Display

object Util {

    class Limits(val maxFps: Int?, val maxBitrate: Int?)

    /** Returns [width, height, densityDpi] of the real screen in its current rotation. */
    @Suppress("DEPRECATION")
    fun realSize(ctx: Context): IntArray {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        return intArrayOf(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    /**
     * Output size for a target short side (720 or 1080). Never upscales.
     * orient: 0 = match phone now, 1 = portrait, 2 = landscape.
     */
    fun targetSize(w: Int, h: Int, shortTarget: Int, orient: Int): Pair<Int, Int> {
        val shortSide = minOf(w, h)
        val longSide = maxOf(w, h)
        val scale = minOf(1f, shortTarget.toFloat() / shortSide)
        val s = even(shortSide * scale)
        val l = even(longSide * scale)
        val landscape = when (orient) {
            1 -> false
            2 -> true
            else -> w > h
        }
        return if (landscape) Pair(l, s) else Pair(s, l)
    }

    private fun even(v: Float): Int = Math.round(v / 2f) * 2

    fun mime(codec: String): String =
        if (codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** Best frame rate / bitrate any encoder on this phone reports for this size. */
    fun limits(mime: String, w: Int, h: Int): Limits {
        var fps: Int? = null
        var br: Int? = null
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder || !info.supportedTypes.contains(mime)) continue
                try {
                    val vc = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
                    val f = vc.getSupportedFrameRatesFor(w, h).upper.toInt()
                    val b = vc.bitrateRange.upper
                    if (f > (fps ?: 0)) fps = f
                    if (b > (br ?: 0)) br = b
                } catch (_: Exception) {
                    // size not supported by this encoder, try the next one
                }
            }
        } catch (_: Exception) {
        }
        return Limits(fps, br)
    }

    fun bitrate(w: Int, h: Int, fps: Int, hevc: Boolean, max: Int?): Int {
        val bitsPerPixel = if (hevc) 0.07 else 0.10
        var v = (w.toDouble() * h * fps * bitsPerPixel).toLong().coerceIn(4_000_000L, 100_000_000L)
        if (max != null && v > max) v = max.toLong()
        return v.toInt()
    }
}
