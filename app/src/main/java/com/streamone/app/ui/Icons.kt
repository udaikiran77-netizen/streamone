package com.streamone.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app has no material-icons dependency, so the Vega icon set is drawn
 * directly on a Canvas in a 24x24 viewport: outline (stroke) style like
 * Vega's unfocused tab icons, filled style for the focused/active state.
 */
enum class VegaIconName {
    Home, Search, Bookmark, Download, Settings,
    Play, Pause, Back, ChevronRight, Replay10, Forward10,
    Fullscreen, FullscreenExit, Close, Delete, Info, Check,
    Grid, Puzzle, Next, List, File
}

@Composable
fun VegaIcon(
    name: VegaIconName,
    modifier: Modifier = Modifier,
    tint: Color = VegaText,
    size: Dp = 24.dp,
    filled: Boolean = false
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.width / 24f
        fun px(v: Float) = v * s
        fun pt(x: Float, y: Float) = Offset(px(x), px(y))
        val stroke = Stroke(width = 1.9f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val style = if (filled) Fill else stroke

        fun pathOf(block: Path.() -> Unit): Path {
            val p = Path()
            p.block()
            return p
        }

        when (name) {
            VegaIconName.Home -> {
                val p = pathOf {
                    moveTo(px(4f), px(11f)); lineTo(px(12f), px(4f)); lineTo(px(20f), px(11f))
                    moveTo(px(6.5f), px(9.5f)); lineTo(px(6.5f), px(20f)); lineTo(px(17.5f), px(20f))
                    lineTo(px(17.5f), px(9.5f))
                    moveTo(px(10f), px(20f)); lineTo(px(10f), px(15f)); lineTo(px(14f), px(15f))
                    lineTo(px(14f), px(20f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Search -> {
                drawCircle(
                    color = tint, radius = px(6.5f), center = pt(11f, 11f), style = stroke
                )
                drawLine(
                    color = tint, start = pt(16f, 16f), end = pt(20.5f, 20.5f),
                    strokeWidth = 1.9f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            VegaIconName.Bookmark -> {
                val p = pathOf {
                    moveTo(px(6.5f), px(3.5f)); lineTo(px(17.5f), px(3.5f))
                    lineTo(px(17.5f), px(20.5f)); lineTo(px(12f), px(16f))
                    lineTo(px(6.5f), px(20.5f)); close()
                }
                drawPath(p, color = tint, style = style)
            }

            VegaIconName.Download -> {
                val p = pathOf {
                    moveTo(px(12f), px(4f)); lineTo(px(12f), px(15f))
                    moveTo(px(6.5f), px(10.5f)); lineTo(px(12f), px(16f)); lineTo(px(17.5f), px(10.5f))
                    moveTo(px(4f), px(16.5f)); lineTo(px(4f), px(20f)); lineTo(px(20f), px(20f))
                    lineTo(px(20f), px(16.5f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Settings -> {
                // Cog: outer spoke ring + centre circle.
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val inner = 5.1f
                    val outer = 7.6f
                    drawLine(
                        color = tint,
                        start = pt(
                            12f + (inner * cos(angle)).toFloat(),
                            12f + (inner * sin(angle)).toFloat()
                        ),
                        end = pt(
                            12f + (outer * cos(angle)).toFloat(),
                            12f + (outer * sin(angle)).toFloat()
                        ),
                        strokeWidth = 2.4f * s,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                drawCircle(color = tint, radius = px(5.4f), center = pt(12f, 12f), style = stroke)
                drawCircle(color = tint, radius = px(2.6f), center = pt(12f, 12f), style = style)
            }

            VegaIconName.Play -> {
                val p = pathOf {
                    moveTo(px(8f), px(5.5f)); lineTo(px(19f), px(12f)); lineTo(px(8f), px(18.5f))
                    close()
                }
                drawPath(p, color = tint, style = Fill)
            }

            VegaIconName.Pause -> {
                drawRect(
                    color = tint, topLeft = pt(7f, 5.5f), size = Size(px(3.4f), px(13f))
                )
                drawRect(
                    color = tint, topLeft = pt(13.6f, 5.5f), size = Size(px(3.4f), px(13f))
                )
            }

            VegaIconName.Back -> {
                val p = pathOf {
                    moveTo(px(19f), px(12f)); lineTo(px(5.5f), px(12f))
                    moveTo(px(11f), px(6f)); lineTo(px(5.5f), px(12f)); lineTo(px(11f), px(18f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.ChevronRight -> {
                val p = pathOf {
                    moveTo(px(9.5f), px(5.5f)); lineTo(px(16f), px(12f)); lineTo(px(9.5f), px(18.5f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Replay10 -> {
                drawArc(
                    color = tint,
                    startAngle = -30f,
                    sweepAngle = -300f,
                    useCenter = false,
                    topLeft = pt(4f, 4f),
                    size = Size(px(16f), px(16f)),
                    style = stroke
                )
                val head = pathOf {
                    moveTo(px(19.6f), px(3.2f)); lineTo(px(20f), px(9.4f)); lineTo(px(14.4f), px(7.2f))
                    close()
                }
                drawPath(head, color = tint, style = Fill)
                drawTen(tint, s)
            }

            VegaIconName.Forward10 -> {
                drawArc(
                    color = tint,
                    startAngle = -150f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = pt(4f, 4f),
                    size = Size(px(16f), px(16f)),
                    style = stroke
                )
                val head = pathOf {
                    moveTo(px(4.4f), px(3.2f)); lineTo(px(4f), px(9.4f)); lineTo(px(9.6f), px(7.2f))
                    close()
                }
                drawPath(head, color = tint, style = Fill)
                drawTen(tint, s)
            }

            VegaIconName.Fullscreen -> {
                val p = pathOf {
                    moveTo(px(3.5f), px(9f)); lineTo(px(3.5f), px(3.5f)); lineTo(px(9f), px(3.5f))
                    moveTo(px(15f), px(3.5f)); lineTo(px(20.5f), px(3.5f)); lineTo(px(20.5f), px(9f))
                    moveTo(px(20.5f), px(15f)); lineTo(px(20.5f), px(20.5f)); lineTo(px(15f), px(20.5f))
                    moveTo(px(9f), px(20.5f)); lineTo(px(3.5f), px(20.5f)); lineTo(px(3.5f), px(15f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.FullscreenExit -> {
                val p = pathOf {
                    moveTo(px(9f), px(3.5f)); lineTo(px(9f), px(9f)); lineTo(px(3.5f), px(9f))
                    moveTo(px(15f), px(3.5f)); lineTo(px(15f), px(9f)); lineTo(px(20.5f), px(9f))
                    moveTo(px(15f), px(20.5f)); lineTo(px(15f), px(15f)); lineTo(px(20.5f), px(15f))
                    moveTo(px(9f), px(20.5f)); lineTo(px(9f), px(15f)); lineTo(px(3.5f), px(15f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Close -> {
                drawLine(
                    color = tint, start = pt(6f, 6f), end = pt(18f, 18f),
                    strokeWidth = 1.9f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawLine(
                    color = tint, start = pt(18f, 6f), end = pt(6f, 18f),
                    strokeWidth = 1.9f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            VegaIconName.Delete -> {
                val p = pathOf {
                    moveTo(px(4.5f), px(6.5f)); lineTo(px(19.5f), px(6.5f))
                    moveTo(px(9.5f), px(6.5f)); lineTo(px(9.5f), px(4f)); lineTo(px(14.5f), px(4f))
                    lineTo(px(14.5f), px(6.5f))
                    moveTo(px(6.5f), px(6.5f)); lineTo(px(7.6f), px(20f)); lineTo(px(16.4f), px(20f))
                    lineTo(px(17.5f), px(6.5f))
                    moveTo(px(10.2f), px(10.5f)); lineTo(px(10.2f), px(16f))
                    moveTo(px(13.8f), px(10.5f)); lineTo(px(13.8f), px(16f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Info -> {
                drawCircle(color = tint, radius = px(8.8f), center = pt(12f, 12f), style = stroke)
                drawLine(
                    color = tint, start = pt(12f, 11f), end = pt(12f, 16f),
                    strokeWidth = 1.9f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawCircle(color = tint, radius = px(1.3f), center = pt(12f, 7.8f), style = Fill)
            }

            VegaIconName.Check -> {
                val p = pathOf {
                    moveTo(px(5f), px(12.5f)); lineTo(px(10f), px(17.5f)); lineTo(px(19f), px(7f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Grid -> {
                val cells = listOf(4f to 4f, 13.5f to 4f, 4f to 13.5f, 13.5f to 13.5f)
                for ((cx, cy) in cells) {
                    drawRoundRect(
                        color = tint,
                        topLeft = pt(cx, cy),
                        size = Size(px(6.5f), px(6.5f)),
                        cornerRadius = CornerRadius(px(2f), px(2f)),
                        style = style
                    )
                }
            }

            VegaIconName.Puzzle -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(5f, 5f),
                    size = Size(px(14f), px(14f)),
                    cornerRadius = CornerRadius(px(3f), px(3f)),
                    style = stroke
                )
                val p = pathOf {
                    moveTo(px(12f), px(9f)); lineTo(px(12f), px(15f))
                    moveTo(px(9f), px(12f)); lineTo(px(15f), px(12f))
                }
                drawPath(p, color = tint, style = stroke)
            }

            VegaIconName.Next -> {
                val p = pathOf {
                    moveTo(px(6f), px(6f)); lineTo(px(15f), px(12f)); lineTo(px(6f), px(18f))
                    close()
                }
                drawPath(p, color = tint, style = Fill)
                drawRect(color = tint, topLeft = pt(16.8f, 6f), size = Size(px(2.2f), px(12f)))
            }

            VegaIconName.List -> {
                val rows = listOf(6.5f, 12f, 17.5f)
                for (y in rows) {
                    drawLine(
                        color = tint, start = pt(8.5f, y), end = pt(20f, y),
                        strokeWidth = 1.9f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawCircle(color = tint, radius = px(1.4f), center = pt(4.8f, y), style = Fill)
                }
            }

            VegaIconName.File -> {
                val p = pathOf {
                    moveTo(px(6f), px(3.5f)); lineTo(px(14.5f), px(3.5f)); lineTo(px(18.5f), px(7.5f))
                    lineTo(px(18.5f), px(20.5f)); lineTo(px(6f), px(20.5f)); close()
                    moveTo(px(14f), px(3.5f)); lineTo(px(14f), px(8f)); lineTo(px(18.5f), px(8f))
                }
                drawPath(p, color = tint, style = stroke)
                val tri = pathOf {
                    moveTo(px(10.5f), px(10.5f)); lineTo(px(15.5f), px(13.5f))
                    lineTo(px(10.5f), px(16.5f)); close()
                }
                drawPath(tri, color = tint, style = Fill)
            }
        }
    }
}

/** Tiny "10" label drawn inside the seek +/-10s icons. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTen(tint: Color, s: Float) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(
            (tint.alpha * 255).toInt(),
            (tint.red * 255).toInt(),
            (tint.green * 255).toInt(),
            (tint.blue * 255).toInt()
        )
        textSize = 8.5f * s
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    val baseline = 12f * s - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText("10", 12f * s, baseline, paint)
}
