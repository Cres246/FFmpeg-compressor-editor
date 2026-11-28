package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.example.domain.SubtitleCue
import com.example.domain.SubtitleFontOption
import com.example.domain.SubtitleFontSizeOption
import com.example.domain.SubtitleParser
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Zero-garbage Media3 GPU [BitmapOverlay] that burns edited subtitles into hardware-accelerated
 * MediaCodec Turbo outputs with identical typography, full UTF-8/Turkish glyph coverage, outline,
 * and placement as FFmpeg `libass` and the live Compose studio preview.
 *
 * Caching strategy:
 * - Blank intervals return a single reusable 1×1 transparent bitmap (0 allocations, 0 texture uploads).
 * - Active cue frames return the exact same cached [Bitmap] instance for the entire duration of the
 *   cue so Media3's [BitmapOverlay] only uploads a GL texture ONCE per cue transition.
 */
@UnstableApi
class SubtitleBitmapOverlay(
    private val context: Context,
    cues: List<SubtitleCue>,
    private val fontOption: SubtitleFontOption,
    private val fontSizeOption: SubtitleFontSizeOption
) : BitmapOverlay() {

    private val sortedCues: List<SubtitleCue> = cues
        .filter { it.text.isNotBlank() }
        .sortedWith(compareBy<SubtitleCue> { it.startSec }.thenBy { it.endSec })

    private val typeface: Typeface = runCatching {
        Typeface.createFromAsset(context.assets, fontOption.assetPath)
    }.getOrDefault(Typeface.DEFAULT_BOLD)

    private val emptyBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    // Bottom-center anchor with ~5.5% safe margin in OpenGL NDC (y = -1 is bottom, +1 is top)
    private val bottomCenterSettings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -0.89f)
        .setOverlayFrameAnchor(0f, -1.0f)
        .build()

    private val invisibleSettings: OverlaySettings = StaticOverlaySettings.Builder()
        .setAlphaScale(0f)
        .build()

    private var videoWidth: Int = 1920
    private var videoHeight: Int = 1080
    private var lastCueIdx: Int = -2
    private var lastRenderedBitmap: Bitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val w = videoSize.width.coerceAtLeast(64)
        val h = videoSize.height.coerceAtLeast(64)
        if (w != videoWidth || h != videoHeight) {
            videoWidth = w
            videoHeight = h
            lastCueIdx = -2
            lastRenderedBitmap = null
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeSec = presentationTimeUs / 1_000_000.0
        val cueIdx = SubtitleParser.findActiveCueIndex(sortedCues, timeSec)
        if (cueIdx < 0) {
            return emptyBitmap
        }
        if (cueIdx == lastCueIdx && lastRenderedBitmap != null) {
            return lastRenderedBitmap!!
        }
        val cue = sortedCues[cueIdx]
        val rendered = renderCueBitmap(cue.text)
        lastCueIdx = cueIdx
        lastRenderedBitmap = rendered
        return rendered
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeSec = presentationTimeUs / 1_000_000.0
        return if (SubtitleParser.findActiveCueIndex(sortedCues, timeSec) >= 0) {
            bottomCenterSettings
        } else {
            invisibleSettings
        }
    }

    private fun renderCueBitmap(text: String): Bitmap {
        val shortEdge = minOf(videoWidth, videoHeight).coerceAtLeast(240)
        val fontPx = (shortEdge * fontSizeOption.relativeHeightFraction).coerceIn(22f, 180f)
        val outlineWidth = (fontPx * 0.125f).coerceIn(3.5f, 14f)
        val shadowRadius = (fontPx * 0.08f).coerceIn(2f, 8f)
        val shadowDy = (fontPx * 0.04f).coerceIn(1f, 5f)
        val padX = ceil(outlineWidth + shadowRadius + 10f).toInt()
        val padY = ceil(outlineWidth + shadowRadius + shadowDy + 8f).toInt()
        val maxTextWidth = ((videoWidth * 0.88f).roundToInt() - padX * 2).coerceAtLeast(120)

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = this@SubtitleBitmapOverlay.typeface
            textSize = fontPx
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(shadowRadius, 0f, shadowDy, Color.argb(190, 12, 9, 6))
        }

        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = this@SubtitleBitmapOverlay.typeface
            textSize = fontPx
            color = Color.rgb(18, 14, 10)
            style = Paint.Style.STROKE
            strokeWidth = outlineWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val strokeLayout = buildStaticLayout(text, strokePaint, maxTextWidth)
        val fillLayout = buildStaticLayout(text, fillPaint, maxTextWidth)

        var maxLineW = 0f
        for (line in 0 until fillLayout.lineCount) {
            maxLineW = maxOf(maxLineW, fillLayout.getLineWidth(line))
        }
        val tightWidth = (ceil(maxLineW).toInt() + padX * 2).coerceIn(4, videoWidth)
        val tightHeight = (fillLayout.height + padY * 2).coerceIn(4, videoHeight)

        // Re-layout centered inside the tight width so the bitmap has minimal transparent margin
        val finalInnerW = (tightWidth - padX * 2).coerceAtLeast(16)
        val finalStroke = if (finalInnerW < maxTextWidth) buildStaticLayout(text, strokePaint, finalInnerW) else strokeLayout
        val finalFill = if (finalInnerW < maxTextWidth) buildStaticLayout(text, fillPaint, finalInnerW) else fillLayout

        val bmp = Bitmap.createBitmap(tightWidth, tightHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.translate(padX.toFloat(), padY.toFloat())
        finalStroke.draw(canvas)
        finalFill.draw(canvas)
        canvas.restore()
        return bmp
    }

    @Suppress("DEPRECATION")
    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1.12f, 0f, false)
        }
    }

    override fun release() {
        super.release()
        lastRenderedBitmap = null
        lastCueIdx = -2
    }
}
