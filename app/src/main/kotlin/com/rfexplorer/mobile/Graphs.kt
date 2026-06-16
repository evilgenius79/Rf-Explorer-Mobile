package com.rfexplorer.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val MarkerCyan = Color(0xFF66D9FF)
internal val MarkerMagenta = Color(0xFFFF6FD8)

/** Amplitude window actually drawn — either the configured window or a data fit. */
data class AmpWindow(val top: Float, val bottom: Float) {
    val span: Float get() = (top - bottom).takeIf { it > 0.5f } ?: 1f
}

fun ampWindowFor(trace: Trace?, configTop: Int?, configBottom: Int?, autoscale: Boolean): AmpWindow {
    if (autoscale && trace != null && trace.amplitudesDbm.isNotEmpty()) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (a in trace.amplitudesDbm) { if (a < lo) lo = a; if (a > hi) hi = a }
        val margin = ((hi - lo) * 0.1f).coerceAtLeast(3f)
        return AmpWindow(top = hi + margin, bottom = lo - margin)
    }
    return AmpWindow(top = (configTop ?: 0).toFloat(), bottom = (configBottom ?: -120).toFloat())
}

@Composable
fun SpectrumCanvas(
    trace: Trace?,
    window: AmpWindow,
    markerA: Int?,
    markerB: Int?,
    modifier: Modifier = Modifier,
    onTapBin: (Int) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFF9AA4B2), fontSize = 9.sp)

    Canvas(
        modifier = modifier.pointerInput(trace?.pointCount) {
            detectTapGestures { offset ->
                val n = trace?.pointCount ?: return@detectTapGestures
                if (n < 2) return@detectTapGestures
                val bin = (offset.x / size.width * (n - 1)).roundToInt().coerceIn(0, n - 1)
                onTapBin(bin)
            }
        },
    ) {
        runCatching {
            drawGrid()
            drawAmplitudeLabels(measurer, labelStyle, window)
        }

        val amps = trace?.amplitudesDbm
        if (trace != null && amps != null && amps.size >= 2) {
            val n = amps.size
            val w = size.width
            val h = size.height
            val top = window.top
            val span = window.span
            fun xAt(i: Int) = w * i / (n - 1)
            fun yAt(dbm: Float) = (h * (top - dbm) / span).coerceIn(0f, h)

            val err = runCatching {
                val line = Path().apply {
                    moveTo(xAt(0), yAt(amps[0]))
                    for (i in 1 until n) lineTo(xAt(i), yAt(amps[i]))
                }
                val fill = Path().apply {
                    moveTo(xAt(0), yAt(amps[0]))
                    for (i in 1 until n) lineTo(xAt(i), yAt(amps[i]))
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(fill, color = TraceGreen.copy(alpha = 0.15f))
                drawPath(line, color = TraceGreen, style = Stroke(width = 2.5f))

                if (trace.peakIndex in 0 until n) {
                    drawCircle(PeakAmber, 5f, Offset(xAt(trace.peakIndex), yAt(amps[trace.peakIndex])))
                }
                markerA?.takeIf { it in 0 until n }?.let { mi ->
                    drawLine(MarkerCyan, Offset(xAt(mi), 0f), Offset(xAt(mi), h), strokeWidth = 1.5f)
                    drawCircle(MarkerCyan, 4f, Offset(xAt(mi), yAt(amps[mi])))
                }
                markerB?.takeIf { it in 0 until n }?.let { mi ->
                    drawLine(MarkerMagenta, Offset(xAt(mi), 0f), Offset(xAt(mi), h), strokeWidth = 1.5f)
                    drawCircle(MarkerMagenta, 4f, Offset(xAt(mi), yAt(amps[mi])))
                }
            }.exceptionOrNull()

            // Surface a draw failure on the graph instead of silently showing nothing.
            if (err != null) {
                drawText(
                    measurer,
                    "draw error: ${err.javaClass.simpleName}: ${err.message}".take(140),
                    topLeft = Offset(4f, 14f),
                    style = TextStyle(color = Color(0xFFFF5555), fontSize = 9.sp),
                )
            }
        }
    }
}

private fun DrawScope.drawGrid(rows: Int = 6, cols: Int = 8) {
    for (r in 0..rows) {
        val y = size.height * r / rows
        drawLine(GridGray, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
    for (c in 0..cols) {
        val x = size.width * c / cols
        drawLine(GridGray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
}

private fun DrawScope.drawAmplitudeLabels(measurer: TextMeasurer, style: TextStyle, window: AmpWindow) {
    val steps = 3
    for (s in 0..steps) {
        val dbm = window.top - window.span * s / steps
        val y = size.height * s / steps
        drawText(measurer, "${dbm.roundToInt()}", topLeft = Offset(2f, y + 1f), style = style)
    }
}

@Composable
fun WaterfallCanvas(
    history: List<FloatArray>,
    window: AmpWindow,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        runCatching {
            if (history.isEmpty()) {
                drawGrid()
                return@runCatching
            }
            val cols = history.last().size
            val rows = history.size
            if (cols < 1) return@runCatching

            val pixels = IntArray(cols * rows)
            // Newest sweep on top: y=0 maps to history.last().
            for (y in 0 until rows) {
                val row = history[rows - 1 - y]
                val base = y * cols
                val n = minOf(cols, row.size)
                for (x in 0 until n) {
                    val t = (row[x] - window.bottom) / window.span
                    pixels[base + x] = heatArgb(t)
                }
            }
            val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, cols, 0, 0, cols, rows)
            drawImage(
                image = bmp.asImageBitmap(),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }
}

private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).roundToInt()

/** Spectrogram colour ramp: blue → cyan → green → yellow → red. */
private fun heatArgb(tRaw: Float): Int {
    val t = tRaw.coerceIn(0f, 1f)
    val r: Int; val g: Int; val b: Int
    when {
        t < 0.25f -> { val u = t / 0.25f; r = 0; g = lerp(0, 255, u); b = 255 }
        t < 0.50f -> { val u = (t - 0.25f) / 0.25f; r = 0; g = 255; b = lerp(255, 0, u) }
        t < 0.75f -> { val u = (t - 0.50f) / 0.25f; r = lerp(0, 255, u); g = 255; b = 0 }
        else -> { val u = (t - 0.75f) / 0.25f; r = 255; g = lerp(255, 0, u); b = 0 }
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
