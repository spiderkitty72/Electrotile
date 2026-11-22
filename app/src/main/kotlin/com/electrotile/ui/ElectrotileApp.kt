package com.electrotile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.electrotile.Progress
import com.electrotile.core.Level
import com.electrotile.core.Levels

@Composable
fun ElectrotileApp(progress: Progress) {
    ElectrotileTheme {
        var completed by remember { mutableStateOf(progress.completedLevels()) }
        var current by rememberSaveable { mutableStateOf<Int?>(null) }

        Scaffold(containerColor = Palette.Background) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val levelNumber = current
                if (levelNumber == null) {
                    LevelSelectScreen(
                        completed = completed,
                        onOpen = { current = it.number },
                    )
                } else {
                    val level = Levels.all.first { it.number == levelNumber }
                    val next = Levels.all.firstOrNull { it.number == levelNumber + 1 }
                    BackHandler { current = null }
                    key(levelNumber) {
                        GameScreen(
                            level = level,
                            hasNext = next != null,
                            onBack = { current = null },
                            onNext = { current = next?.number },
                            onSolved = {
                                progress.markCompleted(level.number)
                                completed = progress.completedLevels()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun isUnlocked(level: Level, completed: Set<Int>) =
    level.number == 1 || (level.number - 1) in completed || level.number in completed

@Composable
private fun LevelSelectScreen(completed: Set<Int>, onOpen: (Level) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text(
            "ELECTROTILE",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Palette.WireLive,
            letterSpacing = 4.sp,
        )
        Text(
            "Turn the tiles. Close the circuit. Hit the numbers.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
        )
        Spacer(Modifier.height(24.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(Levels.all, key = { it.number }) { level ->
                val unlocked = isUnlocked(level, completed)
                val done = level.number in completed
                val shape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .aspectRatio(0.85f)
                        .alpha(if (unlocked) 1f else 0.4f)
                        .background(Palette.Tile, shape)
                        .border(1.dp, if (done) Palette.WireLive else Palette.TileEdge, shape)
                        .clickable(enabled = unlocked) { onOpen(level) }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        level.number.toString(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (done) Palette.WireLive else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        level.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        when {
                            done -> "✓ lit"
                            unlocked -> "play"
                            else -> "locked"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (done) Palette.Success else Palette.TextMuted,
                    )
                }
            }
        }
    }
}
