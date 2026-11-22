package com.electrotile.core

import kotlin.math.abs

/**
 * Steady-state (DC) result of simulating a board.
 *
 * @param componentCurrent per tile, current flowing from terminal A to terminal B through a
 *   two-terminal component (amps); 0 for wires and empty tiles.
 * @param componentVoltage per tile, V(A) − V(B) across a two-terminal component (volts).
 * @param armCurrent per tile and [Dir] ordinal, magnitude of the current flowing through that
 *   edge of the tile. Used to draw which conductors are live.
 * @param sourceCurrent current the battery pushes out of its + terminal (amps).
 * @param shortCircuit true when the battery is (nearly) shorted.
 */
class CircuitResult(
    val componentCurrent: DoubleArray,
    val componentVoltage: DoubleArray,
    val armCurrent: Array<DoubleArray>,
    val sourceCurrent: Double,
    val shortCircuit: Boolean,
)

/**
 * Modified nodal analysis of the tile grid.
 *
 * Every tile edge that carries a port gets its own circuit node, and touching ports on
 * neighbouring tiles are merged. Wire tiles join their ports to a centre node through a tiny
 * resistance, which lets us read the current in each arm of a wire. Capacitors are open
 * circuits at DC (we only report the voltage they charge to); diodes use a piecewise-linear
 * model iterated until every diode's on/off state is consistent.
 */
object CircuitSolver {
    const val WIRE_RESISTANCE = 1e-3
    const val DIODE_FORWARD_DROP = 0.7
    const val DIODE_ON_RESISTANCE = 0.05
    const val SHORT_CIRCUIT_CURRENT = 5.0
    private const val GMIN = 1e-9
    private const val MAX_DIODE_ITERATIONS = 40

    private class Conductor(val a: Int, val b: Int, val g: Double, val tile: Int, val arm: Dir?)

    fun solve(board: Board): CircuitResult {
        val n = board.tiles.size
        // Terminal ids: one per (tile, port), plus one centre terminal per wire tile.
        val portTerminal = Array(n) { IntArray(4) { -1 } }
        val centreTerminal = IntArray(n) { -1 }
        var terminals = 0
        board.tiles.forEachIndexed { i, tile ->
            for (d in tile.ports) portTerminal[i][d.ordinal] = terminals++
            if (tile.kind.isWire) centreTerminal[i] = terminals++
        }

        val uf = UnionFind(terminals)
        for (y in 0 until board.height) for (x in 0 until board.width) {
            val i = board.index(x, y)
            for (d in board.tiles[i].ports) {
                val nx = x + d.dx
                val ny = y + d.dy
                if (!board.inBounds(nx, ny)) continue
                val other = portTerminal[board.index(nx, ny)][d.opposite.ordinal]
                if (other >= 0) uf.union(portTerminal[i][d.ordinal], other)
            }
        }

        // Compact union-find roots into node numbers.
        val nodeOfRoot = HashMap<Int, Int>()
        val nodeOf = IntArray(terminals) { t -> nodeOfRoot.getOrPut(uf.find(t)) { nodeOfRoot.size } }
        val nodeCount = nodeOfRoot.size

        val conductors = ArrayList<Conductor>()
        val diodes = ArrayList<Int>()
        var battery = -1
        board.tiles.forEachIndexed { i, tile ->
            if (tile.kind.isWire) {
                for (d in tile.ports) {
                    conductors += Conductor(
                        nodeOf[portTerminal[i][d.ordinal]], nodeOf[centreTerminal[i]],
                        1.0 / WIRE_RESISTANCE, i, d,
                    )
                }
            } else if (tile.kind.isTwoTerminal) {
                when (tile.kind) {
                    Kind.RESISTOR, Kind.LAMP, Kind.METER -> conductors += Conductor(
                        nodeOf[portTerminal[i][tile.terminalA.ordinal]],
                        nodeOf[portTerminal[i][tile.terminalB.ordinal]],
                        1.0 / tile.value.coerceAtLeast(WIRE_RESISTANCE), i, null,
                    )
                    Kind.DIODE -> diodes += i
                    Kind.BATTERY -> if (battery < 0) battery = i
                    else -> Unit // Capacitor: open circuit at DC.
                }
            }
        }

        fun nodeA(i: Int) = nodeOf[portTerminal[i][board.tiles[i].terminalA.ordinal]]
        fun nodeB(i: Int) = nodeOf[portTerminal[i][board.tiles[i].terminalB.ordinal]]

        val ground = if (battery >= 0) nodeB(battery) else 0
        val diodeOn = BooleanArray(diodes.size)
        var voltages = DoubleArray(nodeCount)
        var batteryCurrent = 0.0

        for (iteration in 0 until MAX_DIODE_ITERATIONS) {
            val solution = solveNetwork(nodeCount, ground, conductors, diodes, diodeOn, ::nodeA, ::nodeB, battery, board)
            voltages = solution.first
            batteryCurrent = solution.second
            var changed = false
            diodes.forEachIndexed { k, d ->
                val drop = voltages[nodeA(d)] - voltages[nodeB(d)]
                val shouldBeOn = if (diodeOn[k]) drop >= DIODE_FORWARD_DROP else drop > DIODE_FORWARD_DROP + 1e-9
                if (shouldBeOn != diodeOn[k]) {
                    diodeOn[k] = shouldBeOn
                    changed = true
                }
            }
            if (!changed) break
        }

        val componentCurrent = DoubleArray(n)
        val componentVoltage = DoubleArray(n)
        val armCurrent = Array(n) { DoubleArray(4) }
        for (c in conductors) {
            val current = (voltages[c.a] - voltages[c.b]) * c.g
            if (c.arm != null) {
                armCurrent[c.tile][c.arm.ordinal] = abs(current)
            } else {
                componentCurrent[c.tile] = current
            }
        }
        diodes.forEachIndexed { k, d ->
            val drop = voltages[nodeA(d)] - voltages[nodeB(d)]
            componentCurrent[d] = if (diodeOn[k]) ((drop - DIODE_FORWARD_DROP) / DIODE_ON_RESISTANCE).coerceAtLeast(0.0) else 0.0
        }
        if (battery >= 0) componentCurrent[battery] = -batteryCurrent
        board.tiles.forEachIndexed { i, tile ->
            if (tile.kind.isTwoTerminal) {
                componentVoltage[i] = voltages[nodeA(i)] - voltages[nodeB(i)]
                val magnitude = abs(componentCurrent[i])
                for (d in tile.ports) armCurrent[i][d.ordinal] = magnitude
            }
        }

        val short = battery >= 0 && abs(batteryCurrent) > SHORT_CIRCUIT_CURRENT
        return CircuitResult(componentCurrent, componentVoltage, armCurrent, batteryCurrent, short)
    }

    /** Returns node voltages and the current the battery delivers from its + terminal. */
    private fun solveNetwork(
        nodeCount: Int,
        ground: Int,
        conductors: List<Conductor>,
        diodes: List<Int>,
        diodeOn: BooleanArray,
        nodeA: (Int) -> Int,
        nodeB: (Int) -> Int,
        battery: Int,
        board: Board,
    ): Pair<DoubleArray, Double> {
        // Unknowns: every node voltage except ground, then the battery's branch current.
        val index = IntArray(nodeCount) { -1 }
        var size = 0
        for (k in 0 until nodeCount) if (k != ground) index[k] = size++
        val sourceRow = if (battery >= 0) size++ else -1
        if (size == 0) return DoubleArray(nodeCount) to 0.0

        val a = Array(size) { DoubleArray(size) }
        val rhs = DoubleArray(size)

        fun stamp(p: Int, q: Int, g: Double) {
            val ip = index[p]
            val iq = index[q]
            if (ip >= 0) a[ip][ip] += g
            if (iq >= 0) a[iq][iq] += g
            if (ip >= 0 && iq >= 0) {
                a[ip][iq] -= g
                a[iq][ip] -= g
            }
        }

        for (k in 0 until nodeCount) index[k].takeIf { it >= 0 }?.let { a[it][it] += GMIN }
        for (c in conductors) stamp(c.a, c.b, c.g)
        diodes.forEachIndexed { k, d ->
            if (!diodeOn[k]) return@forEachIndexed
            val g = 1.0 / DIODE_ON_RESISTANCE
            val anode = nodeA(d)
            val cathode = nodeB(d)
            stamp(anode, cathode, g)
            index[anode].takeIf { it >= 0 }?.let { rhs[it] += g * DIODE_FORWARD_DROP }
            index[cathode].takeIf { it >= 0 }?.let { rhs[it] -= g * DIODE_FORWARD_DROP }
        }
        if (battery >= 0) {
            val plus = index[nodeA(battery)]
            val minus = index[nodeB(battery)]
            if (plus >= 0) {
                a[plus][sourceRow] += 1.0
                a[sourceRow][plus] += 1.0
            }
            if (minus >= 0) {
                a[minus][sourceRow] -= 1.0
                a[sourceRow][minus] -= 1.0
            }
            rhs[sourceRow] = board.tiles[battery].value
        }

        val x = LinearSolver.solve(a, rhs)
        val voltages = DoubleArray(nodeCount) { k -> if (index[k] >= 0) x[index[k]] else 0.0 }
        // The MNA unknown is the current flowing into the + terminal; the battery delivers the opposite.
        val delivered = if (sourceRow >= 0) -x[sourceRow] else 0.0
        return voltages to delivered
    }
}

internal class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) {
            val next = parent[c]
            parent[c] = r
            c = next
        }
        return r
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}

internal object LinearSolver {
    /** Gaussian elimination with partial pivoting. Singular pivots resolve to zero. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-15) continue
            if (pivot != col) {
                val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
                val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
            }
            val p = a[col][col]
            for (r in col + 1 until n) {
                val f = a[r][col] / p
                if (f == 0.0) continue
                val row = a[r]
                val pivotRow = a[col]
                for (c in col until n) row[c] -= f * pivotRow[c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            if (abs(a[r][r]) < 1e-15) continue
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }
}
