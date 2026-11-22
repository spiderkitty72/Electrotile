package com.electrotile.core

import kotlin.math.abs

/** How one requirement currently stands, ready to show in a checklist. */
data class RequirementStatus(val met: Boolean, val reading: String)

/** A condition on the circuit that must hold for a level to be complete. */
sealed interface Requirement {
    /** Short instruction shown to the player, e.g. "Output reads 2.80–3.20 V". */
    val description: String

    fun evaluate(board: Board, result: CircuitResult): RequirementStatus

    /** Every lamp carries a current inside its own window: lit, but not burnt out. */
    data object LightAllLamps : Requirement {
        override val description = "Light every lamp without burning it out"

        override fun evaluate(board: Board, result: CircuitResult): RequirementStatus {
            val lamps = board.tiles.indices.filter { board.tiles[it].kind == Kind.LAMP }
            val readings = lamps.map { i ->
                val tile = board.tiles[i]
                val current = abs(result.componentCurrent[i])
                val ok = current >= tile.minCurrent && current <= tile.maxCurrent
                ok to "${formatCurrent(current)} (needs ${formatRange(tile.minCurrent, tile.maxCurrent, ::formatCurrent)})"
            }
            return RequirementStatus(
                met = !result.shortCircuit && readings.isNotEmpty() && readings.all { it.first },
                reading = readings.joinToString(" · ") { it.second },
            )
        }
    }

    /** Voltage between the output meter's + and − probes lies in a window. */
    data class OutputVoltage(val min: Double, val max: Double) : Requirement {
        override val description = "Output reads ${formatRange(min, max, ::formatVoltage)}"

        override fun evaluate(board: Board, result: CircuitResult): RequirementStatus {
            val meter = board.tiles.indexOfFirst { it.kind == Kind.METER }
            if (meter < 0) return RequirementStatus(false, "no meter")
            val v = result.componentVoltage[meter]
            return RequirementStatus(!result.shortCircuit && v in min..max, formatVoltage(v))
        }
    }

    /** Current drawn from the battery lies in a window. */
    data class BatteryCurrent(val min: Double, val max: Double) : Requirement {
        override val description = "Battery supplies ${formatRange(min, max, ::formatCurrent)}"

        override fun evaluate(board: Board, result: CircuitResult): RequirementStatus {
            val i = result.sourceCurrent
            return RequirementStatus(!result.shortCircuit && i in min..max, formatCurrent(i))
        }
    }

    /** Every capacitor charges to a voltage (either polarity) inside a window. */
    data class ChargeCapacitors(val min: Double, val max: Double) : Requirement {
        override val description = "Charge the capacitor to ${formatRange(min, max, ::formatVoltage)}"

        override fun evaluate(board: Board, result: CircuitResult): RequirementStatus {
            val caps = board.tiles.indices.filter { board.tiles[it].kind == Kind.CAPACITOR }
            val volts = caps.map { abs(result.componentVoltage[it]) }
            return RequirementStatus(
                met = !result.shortCircuit && volts.isNotEmpty() && volts.all { it in min..max },
                reading = volts.joinToString(" · ") { formatVoltage(it) },
            )
        }
    }
}

fun formatCurrent(amps: Double): String {
    val a = if (abs(amps) < 5e-7) 0.0 else amps
    return if (abs(a) >= 1.0) "%.2f A".format(a) else "%.0f mA".format(a * 1000)
}

fun formatVoltage(volts: Double): String {
    val v = if (abs(volts) < 5e-4) 0.0 else volts
    return "%.2f V".format(v)
}

fun formatResistance(ohms: Double): String =
    if (ohms >= 1000) "%.0fkΩ".format(ohms / 1000) else "%.0fΩ".format(ohms)

private fun formatRange(min: Double, max: Double, format: (Double) -> String): String {
    val lo = format(min)
    val hi = format(max)
    val unit = lo.substringAfter(' ')
    return if (unit == hi.substringAfter(' ')) "${lo.substringBefore(' ')}–$hi" else "$lo–$hi"
}
