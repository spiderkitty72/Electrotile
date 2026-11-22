package com.electrotile.core

/** Tile edges, in clockwise order. */
enum class Dir(val dx: Int, val dy: Int) {
    N(0, -1), E(1, 0), S(0, 1), W(-1, 0);

    /** This direction turned clockwise by [quarterTurns] × 90°. */
    fun rotate(quarterTurns: Int): Dir = entries[(ordinal + quarterTurns).mod(4)]

    val opposite: Dir get() = rotate(2)
}

/**
 * The component printed on a tile.
 *
 * Wire kinds join all their ports into one conductor. Every other non-empty kind is a
 * two-terminal component whose terminal A faces north and terminal B faces south at
 * rotation 0. For polarised parts A is the "positive" side: the battery's + terminal,
 * the diode's anode and the output meter's + probe.
 */
enum class Kind(val basePorts: List<Dir>, val isWire: Boolean = false) {
    EMPTY(emptyList()),
    WIRE_STRAIGHT(listOf(Dir.N, Dir.S), isWire = true),
    WIRE_CORNER(listOf(Dir.N, Dir.E), isWire = true),
    WIRE_TEE(listOf(Dir.N, Dir.E, Dir.S), isWire = true),
    WIRE_CROSS(listOf(Dir.N, Dir.E, Dir.S, Dir.W), isWire = true),
    RESISTOR(listOf(Dir.N, Dir.S)),
    CAPACITOR(listOf(Dir.N, Dir.S)),
    DIODE(listOf(Dir.N, Dir.S)),
    LAMP(listOf(Dir.N, Dir.S)),
    BATTERY(listOf(Dir.N, Dir.S)),
    METER(listOf(Dir.N, Dir.S));

    val isTwoTerminal: Boolean get() = this != EMPTY && !isWire

    /** Number of distinct orientations; rotating by this many quarter turns is a no-op. */
    val symmetry: Int
        get() = when (this) {
            EMPTY, WIRE_CROSS -> 1
            WIRE_STRAIGHT, RESISTOR, CAPACITOR, LAMP -> 2
            else -> 4
        }
}

/**
 * One grid cell.
 *
 * @param value resistance in ohms for resistors, lamps and meters; volts for batteries.
 * @param minCurrent / [maxCurrent] the current window (amps) in which a lamp glows properly.
 */
data class Tile(
    val kind: Kind,
    val rotation: Int = 0,
    val fixed: Boolean = false,
    val value: Double = 0.0,
    val minCurrent: Double = 0.0,
    val maxCurrent: Double = Double.MAX_VALUE,
) {
    val rotatable: Boolean get() = !fixed && kind.symmetry > 1

    val ports: List<Dir> get() = kind.basePorts.map { it.rotate(rotation) }

    /** Side terminal A faces, for two-terminal components. */
    val terminalA: Dir get() = Dir.N.rotate(rotation)

    /** Side terminal B faces, for two-terminal components. */
    val terminalB: Dir get() = Dir.S.rotate(rotation)

    fun rotated(quarterTurns: Int = 1): Tile = copy(rotation = (rotation + quarterTurns).mod(4))
}

/** A rectangular grid of tiles stored row-major. */
data class Board(val width: Int, val height: Int, val tiles: List<Tile>) {
    init {
        require(tiles.size == width * height) { "Expected ${width * height} tiles, got ${tiles.size}" }
    }

    operator fun get(x: Int, y: Int): Tile = tiles[index(x, y)]

    fun index(x: Int, y: Int): Int = y * width + x

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun withRotations(rotations: IntArray): Board =
        copy(tiles = tiles.mapIndexed { i, t -> t.copy(rotation = rotations[i].mod(4)) })

    val rotations: IntArray get() = IntArray(tiles.size) { tiles[it].rotation }
}
