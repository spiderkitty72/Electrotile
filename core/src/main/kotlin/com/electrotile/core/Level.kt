package com.electrotile.core

import kotlin.random.Random

/**
 * A handcrafted puzzle.
 *
 * [solution] is the board in one solved orientation; players start from [startingBoard],
 * which turns every rotatable tile to a random orientation.
 */
class Level(
    val number: Int,
    val title: String,
    val tip: String,
    val solution: Board,
    val requirements: List<Requirement>,
) {
    fun isSolved(board: Board, result: CircuitResult = CircuitSolver.solve(board)): Boolean =
        !result.shortCircuit && requirements.all { it.evaluate(board, result).met }

    fun startingBoard(): Board {
        val random = Random(number * 7919 + 17)
        repeat(100) {
            val rotations = IntArray(solution.tiles.size) { i ->
                val tile = solution.tiles[i]
                if (tile.rotatable) random.nextInt(4) else tile.rotation
            }
            val board = solution.withRotations(rotations)
            if (!isSolved(board)) return board
        }
        error("Could not scramble level $number")
    }
}

/**
 * Parses a level layout. Tiles are separated by whitespace, rows by newlines.
 *
 * Token format: `<code><rotation>[!][:param...]`, where rotation is 0–3 clockwise quarter
 * turns and `!` pins the tile so it cannot be turned. Codes:
 * `.` empty, `I` straight wire, `L` corner, `T` tee, `+` cross,
 * `R<rot>:ohms` resistor, `C` capacitor, `D` diode (anode on side A),
 * `B<rot>:ohms:minAmps:maxAmps` lamp, `V<rot>:volts` battery (always fixed),
 * `M<rot>:ohms` output meter (+ probe on side A).
 */
fun parseBoard(layout: String): Board {
    val rows = layout.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() }
        .map { row -> row.split(Regex("\\s+")).map(::parseTile) }
    val width = rows.first().size
    require(rows.all { it.size == width }) { "Ragged layout:\n$layout" }
    return Board(width, rows.size, rows.flatten())
}

private fun parseTile(token: String): Tile {
    val head = token.substringBefore(':')
    val params = token.split(':').drop(1).map { it.toDouble() }
    val kind = when (head[0]) {
        '.' -> Kind.EMPTY
        'I' -> Kind.WIRE_STRAIGHT
        'L' -> Kind.WIRE_CORNER
        'T' -> Kind.WIRE_TEE
        '+' -> Kind.WIRE_CROSS
        'R' -> Kind.RESISTOR
        'C' -> Kind.CAPACITOR
        'D' -> Kind.DIODE
        'B' -> Kind.LAMP
        'V' -> Kind.BATTERY
        'M' -> Kind.METER
        else -> error("Unknown tile code in '$token'")
    }
    val rotation = head.drop(1).firstOrNull()?.takeIf { it.isDigit() }?.digitToInt() ?: 0
    val fixed = head.endsWith('!') || kind == Kind.BATTERY
    return Tile(
        kind = kind,
        rotation = rotation,
        fixed = fixed,
        value = params.getOrElse(0) { 0.0 },
        minCurrent = params.getOrElse(1) { 0.0 },
        maxCurrent = params.getOrElse(2) { Double.MAX_VALUE },
    )
}

object Levels {
    val all: List<Level> = listOf(
        Level(
            number = 1,
            title = "First Light",
            tip = "Tap tiles to turn them. Join the battery to the lamp in a closed loop.",
            solution = parseBoard(
                """
                L1    I1  L2
                V0:6  .   B0:60:0.05:0.2
                L0    I1  L3
                """
            ),
            requirements = listOf(Requirement.LightAllLamps),
        ),
        Level(
            number = 2,
            title = "Too Bright",
            tip = "Straight wire lets too much current through. Make it flow through the resistor instead.",
            solution = parseBoard(
                """
                L1    I1  I1     L2
                V0:9  .   .      B0:30:0.08:0.15
                T0    I1  R1:60  T2
                L0    I0  I1     L3
                """
            ),
            requirements = listOf(Requirement.LightAllLamps),
        ),
        Level(
            number = 3,
            title = "One Way Street",
            tip = "Diodes pass current only in the direction of their arrow, from + round to −.",
            solution = parseBoard(
                """
                L1    D3  L2
                V0:9  .   B0:30:0.1:0.3
                L0    D1  L3
                """
            ),
            requirements = listOf(Requirement.LightAllLamps),
        ),
        Level(
            number = 4,
            title = "Divide and Conquer",
            tip = "Two resistors in series split the voltage. Pick the branches that set the output just right, and mind the meter's + side.",
            solution = parseBoard(
                """
                L1    R1:200  T1      T1      L2
                V0:9  .       R0:100  R1:300  M0:10000
                L0    I1      T3      T3      L3
                """
            ),
            requirements = listOf(Requirement.OutputVoltage(2.8, 3.2)),
        ),
        Level(
            number = 5,
            title = "Current Budget",
            tip = "Resistors side by side each add their own current. Connect the set that adds up to the target.",
            solution = parseBoard(
                """
                L1     T1      T1      T1      L2
                V0:12  R0:120  R0:240  R1:400  R1:600
                L0     T3      T3      T3      L3
                """
            ),
            requirements = listOf(Requirement.BatteryCurrent(0.145, 0.155)),
        ),
        Level(
            number = 6,
            title = "Charge Up",
            tip = "A charged capacitor passes no current. The diode in front of it costs 0.7 V.",
            solution = parseBoard(
                """
                L1    R1:100  T1      T1      L2
                V0:9  .       R1:100  R0:200  D0
                I0    .       I0      I0      C0
                L0    I1      T3      T3      L3
                """
            ),
            requirements = listOf(Requirement.ChargeCapacitors(5.0, 5.6)),
        ),
        Level(
            number = 7,
            title = "Workbench",
            tip = "Two circuits share one battery. Light the lamp and set the output at the same time.",
            solution = parseBoard(
                """
                L1     T1               I1     L2      .       .
                V0:12  B0:40:0.11:0.15  .      R0:100  .       .
                I0     T0               L2     T0      T1      L2
                I0     R1:30            R0:60  R0:100  R0:200  M0:10000
                L0     T3               T3     T3      T3      L3
                """
            ),
            requirements = listOf(Requirement.LightAllLamps, Requirement.OutputVoltage(4.6, 5.0)),
        ),
    )
}
