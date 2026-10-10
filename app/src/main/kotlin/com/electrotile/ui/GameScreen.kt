package com.electrotile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.electrotile.core.CircuitResult
import com.electrotile.core.CircuitSolver
import com.electrotile.core.Level
import com.electrotile.core.RequirementStatus
import com.electrotile.core.Tile
import kotlinx.coroutines.delay

@Composable
fun GameScreen(
    level: Level,
    hasNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSolved: () -> Unit,
) {
    val start = remember(level) { level.startingBoard() }
    // Extra quarter turns the player has applied to each tile. Always replaced, never mutated,
    // so every tap is a new state and the rotation animation keeps spinning forwards.
    var turns by rememberSaveable(level.number) { mutableStateOf(IntArray(start.tiles.size)) }
    var showWin by rememberSaveable(level.number) { mutableStateOf(false) }

    val board = remember(turns) {
        start.withRotations(IntArray(start.tiles.size) { start.tiles[it].rotation + turns[it] })
    }
    val result = remember(board) { CircuitSolver.solve(board) }
    val statuses = level.requirements.map { it.evaluate(board, result) }
    val solved = level.isSolved(board, result)
    val moves = turns.sum()

    LaunchedEffect(solved) {
        if (solved) {
            onSolved()
            delay(450)
            showWin = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Levels") }
            Spacer(Modifier.weight(1f))
            Text("Moves $moves", color = Palette.TextMuted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { turns = IntArray(start.tiles.size) }) { Text("Reset") }
        }
        Text(
            "${level.number}. ${level.title}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(level.tip, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        Spacer(Modifier.height(12.dp))
        RequirementsPanel(level, statuses, result)
        Spacer(Modifier.height(16.dp))

        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            val tileSize: Dp = min(maxWidth / board.width, maxHeight / board.height)
            BoardGrid(
                start = start.tiles,
                turns = turns,
                width = board.width,
                height = board.height,
                result = result,
                tileSize = tileSize,
                enabled = !solved,
                onTap = { index ->
                    turns = turns.copyOf().also { it[index]++ }
                },
            )
        }
    }

    if (showWin) {
        AlertDialog(
            onDismissRequest = { showWin = false },
            title = { Text("Circuit complete!") },
            text = { Text("Solved in $moves moves.") },
            confirmButton = {
                if (hasNext) {
                    TextButton(onClick = onNext) { Text("Next level") }
                } else {
                    TextButton(onClick = onBack) { Text("All levels done") }
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("Levels") }
            },
        )
    }
}

@Composable
private fun RequirementsPanel(level: Level, statuses: List<RequirementStatus>, result: CircuitResult) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (result.shortCircuit) {
            Text(
                "⚡ Short circuit! The battery is wired straight back to itself.",
                color = Palette.Danger,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        level.requirements.zip(statuses).forEach { (requirement, status) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (status.met) "✓" else "○",
                    color = if (status.met) Palette.Success else Palette.TextMuted,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(requirement.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Now: ${status.reading}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status.met) Palette.Success else Palette.WireLive,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardGrid(
    start: List<Tile>,
    turns: IntArray,
    width: Int,
    height: Int,
    result: CircuitResult,
    tileSize: Dp,
    enabled: Boolean,
    onTap: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    Column {
        for (y in 0 until height) {
            Row {
                for (x in 0 until width) {
                    val index = y * width + x
                    val tile = start[index]
                    val rotation = tile.rotation + turns[index]
                    val angle by animateFloatAsState(
                        targetValue = rotation * 90f,
                        animationSpec = tween(durationMillis = 160),
                        label = "tile-rotation",
                    )
                    val current = tile.copy(rotation = rotation.mod(4))
                    val look = TileLook.of(current, index, result)
                    Box(
                        Modifier
                            .size(tileSize)
                            .clickable(
                                enabled = enabled && tile.rotatable,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTap(index)
                            },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawTile(current, angle, look, textMeasurer)
                        }
                    }
                }
            }
        }
    }
}
