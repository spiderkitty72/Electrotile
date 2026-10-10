package com.electrotile.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import com.electrotile.core.CircuitResult
import com.electrotile.core.Dir
import com.electrotile.core.Kind
import com.electrotile.core.Tile
import com.electrotile.core.formatResistance
import kotlin.math.abs

enum class LampState { OFF, DIM, LIT, BLOWN }

/** What the simulation says about one tile, in the shape the renderer needs. */
class TileLook(
    /** Indexed by the tile's actual (rotated) [Dir] ordinal. */
    val armLive: BooleanArray,
    val live: Boolean,
    val lamp: LampState,
    val charge: Double,
) {
    companion object {
        private const val LIVE_CURRENT = 1e-4

        fun of(tile: Tile, index: Int, result: CircuitResult): TileLook {
            val arms = BooleanArray(4) { result.armCurrent[index][it] > LIVE_CURRENT && !result.shortCircuit }
            val current = abs(result.componentCurrent[index])
            val lamp = when {
                tile.kind != Kind.LAMP -> LampState.OFF
                result.shortCircuit || current > tile.maxCurrent -> if (current > LIVE_CURRENT) LampState.BLOWN else LampState.OFF
                current >= tile.minCurrent -> LampState.LIT
                current > 1e-3 -> LampState.DIM
                else -> LampState.OFF
            }
            val charge = if (tile.kind == Kind.CAPACITOR && !result.shortCircuit) abs(result.componentVoltage[index]) else 0.0
            return TileLook(arms, arms.any { it }, lamp, charge)
        }
    }
}

/**
 * Draws [tile] filling the canvas. The component is drawn in its rotation-0 pose (terminal A
 * at the top) and the canvas is turned by [angle] degrees, so taps animate smoothly.
 */
fun DrawScope.drawTile(tile: Tile, angle: Float, look: TileLook, textMeasurer: TextMeasurer) {
    val s = size.minDimension
    val inset = s * 0.03f
    drawRoundRect(
        color = if (tile.rotatable) Palette.Tile else Palette.TileFixed,
        topLeft = Offset(inset, inset),
        size = Size(s - 2 * inset, s - 2 * inset),
        cornerRadius = CornerRadius(s * 0.12f),
    )
    if (tile.kind == Kind.EMPTY) return

    val c = Offset(s / 2, s / 2)
    val h = s / 2
    val stroke = s * 0.09f

    fun conductorColor(live: Boolean) = if (live) Palette.WireLive else Palette.WireIdle

    fun line(from: Offset, to: Offset, live: Boolean, width: Float = stroke) {
        if (live) drawLine(Palette.Glow.copy(alpha = 0.25f), from, to, width * 2.4f, StrokeCap.Round)
        drawLine(conductorColor(live), from, to, width, StrokeCap.Round)
    }

    /** Edge midpoint for a direction in the unrotated frame. */
    fun edge(d: Dir) = Offset(c.x + d.dx * h, c.y + d.dy * h)

    fun liveSide(base: Dir) = look.armLive[base.rotate(tile.rotation).ordinal]

    rotate(degrees = angle, pivot = c) {
        if (tile.kind.isWire) {
            for (port in tile.kind.basePorts) line(c, edge(port), liveSide(port))
            drawCircle(conductorColor(look.live), radius = stroke * 0.75f, center = c)
            return@rotate
        }

        val live = look.live
        val bodyTop = c.y - h * 0.42f
        val bodyBottom = c.y + h * 0.42f
        val leadTop = Offset(c.x, bodyTop)
        val leadBottom = Offset(c.x, bodyBottom)
        val color = conductorColor(live)
        val thin = stroke * 0.6f

        when (tile.kind) {
            Kind.RESISTOR -> {
                line(edge(Dir.N), leadTop, live)
                line(leadBottom, edge(Dir.S), live)
                val zig = Path().apply {
                    moveTo(c.x, bodyTop)
                    val steps = 6
                    for (k in 1..steps) {
                        val y = bodyTop + (bodyBottom - bodyTop) * k / steps
                        val x = if (k == steps) c.x else c.x + (if (k % 2 == 1) 1 else -1) * h * 0.28f
                        lineTo(x, y)
                    }
                }
                drawPath(zig, color, style = Stroke(width = thin, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            Kind.CAPACITOR -> {
                val gap = h * 0.12f
                line(edge(Dir.N), Offset(c.x, c.y - gap), live)
                line(Offset(c.x, c.y + gap), edge(Dir.S), live)
                val charged = look.charge > 0.05
                val plateColor = if (charged) Palette.Charge else Palette.WireIdle
                if (charged) {
                    drawRect(
                        Palette.Charge.copy(alpha = (look.charge / 10.0).coerceIn(0.15, 0.6).toFloat()),
                        topLeft = Offset(c.x - h * 0.4f, c.y - gap),
                        size = Size(h * 0.8f, gap * 2),
                    )
                }
                drawLine(plateColor, Offset(c.x - h * 0.45f, c.y - gap), Offset(c.x + h * 0.45f, c.y - gap), thin * 1.4f, StrokeCap.Round)
                drawLine(plateColor, Offset(c.x - h * 0.45f, c.y + gap), Offset(c.x + h * 0.45f, c.y + gap), thin * 1.4f, StrokeCap.Round)
            }

            Kind.DIODE -> {
                val top = c.y - h * 0.3f
                val bottom = c.y + h * 0.3f
                line(edge(Dir.N), Offset(c.x, top), live)
                line(Offset(c.x, bottom), edge(Dir.S), live)
                // Arrow points from anode (A, top) to cathode (B, bottom).
                val triangle = Path().apply {
                    moveTo(c.x - h * 0.34f, top)
                    lineTo(c.x + h * 0.34f, top)
                    lineTo(c.x, bottom)
                    close()
                }
                drawPath(triangle, if (live) Palette.WireLive else Palette.TileEdge)
                drawPath(triangle, color, style = Stroke(width = thin, join = StrokeJoin.Round))
                drawLine(color, Offset(c.x - h * 0.34f, bottom), Offset(c.x + h * 0.34f, bottom), thin * 1.3f, StrokeCap.Round)
            }

            Kind.LAMP -> {
                val r = h * 0.42f
                line(edge(Dir.N), Offset(c.x, c.y - r), live)
                line(Offset(c.x, c.y + r), edge(Dir.S), live)
                val glow = when (look.lamp) {
                    LampState.LIT -> Palette.Glow
                    LampState.DIM -> Palette.Glow.copy(alpha = 0.35f)
                    LampState.BLOWN -> Palette.Danger
                    LampState.OFF -> null
                }
                if (glow != null) {
                    drawCircle(
                        Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.8f), Color.Transparent), center = c, radius = h),
                        radius = h,
                        center = c,
                    )
                    drawCircle(glow, radius = r, center = c)
                } else {
                    drawCircle(Palette.TileFixed, radius = r, center = c)
                }
                drawCircle(color, radius = r, center = c, style = Stroke(width = thin))
                val d = r * 0.62f
                val cross = if (look.lamp == LampState.OFF) Palette.WireIdle else Color(0xFF6B4300)
                drawLine(cross, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), thin, StrokeCap.Round)
                drawLine(cross, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), thin, StrokeCap.Round)
            }

            Kind.BATTERY -> {
                val gap = h * 0.14f
                line(edge(Dir.N), Offset(c.x, c.y - gap), live)
                line(Offset(c.x, c.y + gap), edge(Dir.S), live)
                drawLine(Palette.WireLive, Offset(c.x - h * 0.5f, c.y - gap), Offset(c.x + h * 0.5f, c.y - gap), thin * 1.2f, StrokeCap.Round)
                drawLine(Palette.WireLive, Offset(c.x - h * 0.26f, c.y + gap), Offset(c.x + h * 0.26f, c.y + gap), thin * 2.4f, StrokeCap.Round)
                drawSign("+", Offset(c.x + h * 0.42f, c.y - h * 0.42f), s, textMeasurer, Palette.WireLive)
                drawSign("−", Offset(c.x + h * 0.42f, c.y + h * 0.42f), s, textMeasurer, Palette.TextMuted)
            }

            Kind.METER -> {
                val r = h * 0.46f
                line(edge(Dir.N), Offset(c.x, c.y - r), live)
                line(Offset(c.x, c.y + r), edge(Dir.S), live)
                drawCircle(Palette.TileFixed, radius = r, center = c)
                drawCircle(if (live) Palette.Charge else Palette.WireIdle, radius = r, center = c, style = Stroke(width = thin))
                drawSign("V", c, s * 1.4f, textMeasurer, if (live) Palette.Charge else Palette.TextMuted)
                drawSign("+", Offset(c.x + h * 0.55f, c.y - h * 0.6f), s, textMeasurer, Palette.Charge)
            }

            else -> Unit
        }
    }

    label(tile)?.let { text ->
        val style = TextStyle(color = Palette.TextMuted, fontSize = (s * 0.13f).toSp(), fontWeight = FontWeight.Medium)
        val layout = textMeasurer.measure(text, style)
        drawText(layout, topLeft = Offset(s - inset * 2.5f - layout.size.width, s - inset * 2f - layout.size.height))
    }
}

private fun label(tile: Tile): String? = when (tile.kind) {
    Kind.RESISTOR, Kind.LAMP -> formatResistance(tile.value)
    Kind.BATTERY -> "%.0fV".format(tile.value)
    else -> null
}

/** Draws a short glyph centred on [at]. */
private fun DrawScope.drawSign(text: String, at: Offset, tileSize: Float, textMeasurer: TextMeasurer, color: Color) {
    val style = TextStyle(color = color, fontSize = (tileSize * 0.16f).toSp(), fontWeight = FontWeight.Bold)
    val layout = textMeasurer.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}
