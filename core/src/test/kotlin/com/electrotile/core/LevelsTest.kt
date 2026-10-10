package com.electrotile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelsTest {
    @Test
    fun everySolutionMeetsItsRequirements() {
        for (level in Levels.all) {
            val result = CircuitSolver.solve(level.solution)
            val statuses = level.requirements.map { it.evaluate(level.solution, result) }
            assertTrue(level.isSolved(level.solution), "Level ${level.number} solution fails: $statuses")
        }
    }

    @Test
    fun startingBoardsAreScrambledOnlyByRotation() {
        for (level in Levels.all) {
            val start = level.startingBoard()
            assertFalse(level.isSolved(start), "Level ${level.number} starts solved")
            level.solution.tiles.zip(start.tiles).forEach { (solved, scrambled) ->
                assertEquals(solved.copy(rotation = 0), scrambled.copy(rotation = 0))
                if (!solved.rotatable) assertEquals(solved.rotation, scrambled.rotation)
            }
        }
    }

    @Test
    fun levelsAreNumberedInOrder() {
        assertEquals((1..Levels.all.size).toList(), Levels.all.map { it.number })
    }

    @Test
    fun obviousWrongAnswersFail() {
        // Level 2: leaving the wire bypass connected overloads the lamp.
        val tooBright = Levels.all[1]
        val bypass = tooBright.solution.withRotations(
            tooBright.solution.rotations.also { it[tooBright.solution.index(1, 3)] = 1 },
        )
        assertFalse(tooBright.isSolved(bypass))

        // Level 4: connecting both lower resistors pulls the output too low.
        val divider = Levels.all[3]
        val both = divider.solution.withRotations(
            divider.solution.rotations.also { it[divider.solution.index(3, 1)] = 0 },
        )
        assertFalse(divider.isSolved(both))
    }
}
