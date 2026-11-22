package com.electrotile.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitSolverTest {
    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 5e-3) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

    @Test
    fun seriesLoopFollowsOhmsLaw() {
        val board = parseBoard(
            """
            L1    I1  L2
            V0:6  .   R0:60
            L0    I1  L3
            """
        )
        val result = CircuitSolver.solve(board)
        assertNear(0.1, result.sourceCurrent)
        assertNear(0.1, abs(result.componentCurrent[board.index(2, 1)]))
        assertNear(6.0, abs(result.componentVoltage[board.index(2, 1)]))
        assertFalse(result.shortCircuit)
        // Every arm of the loop carries the current.
        assertNear(0.1, result.armCurrent[board.index(1, 0)][Dir.E.ordinal])
    }

    @Test
    fun openLoopCarriesNoCurrent() {
        val board = parseBoard(
            """
            L1    I0  L2
            V0:6  .   R0:60
            L0    I1  L3
            """
        )
        assertNear(0.0, CircuitSolver.solve(board).sourceCurrent, 1e-5)
    }

    @Test
    fun wireAcrossBatteryIsAShort() {
        val board = parseBoard(
            """
            L1    L2
            V0:6  I0
            L0    L3
            """
        )
        assertTrue(CircuitSolver.solve(board).shortCircuit)
    }

    @Test
    fun diodeConductsOnlyForward() {
        val forward = parseBoard(
            """
            L1    D3  L2
            V0:9  .   R0:83
            L0    I1  L3
            """
        )
        val result = CircuitSolver.solve(forward)
        assertNear(0.1, result.sourceCurrent, 2e-3)

        val reversed = forward.withRotations(forward.rotations.also { it[forward.index(1, 0)] = 1 })
        assertNear(0.0, CircuitSolver.solve(reversed).sourceCurrent, 1e-5)
    }

    @Test
    fun capacitorChargesToDividerVoltage() {
        val board = parseBoard(
            """
            L1    R1:100  T1      L2
            V0:9  .       R0:200  C0
            L0    I1      T3      L3
            """
        )
        val result = CircuitSolver.solve(board)
        assertNear(6.0, abs(result.componentVoltage[board.index(3, 1)]))
        assertNear(0.03, result.sourceCurrent)
    }

    @Test
    fun rotationTurnsPortsClockwise() {
        val corner = Tile(Kind.WIRE_CORNER, rotation = 1)
        assertEquals(listOf(Dir.E, Dir.S), corner.ports)
        val diode = Tile(Kind.DIODE, rotation = 3)
        assertEquals(Dir.W, diode.terminalA)
        assertEquals(Dir.E, diode.terminalB)
    }
}
