package com.vlatal.flashcards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.Repository
import com.vlatal.flashcards.data.Stats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(repo: Repository, onBack: () -> Unit) {
    val version by repo.version.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<Stats?>(null) }
    LaunchedEffect(version) { stats = repo.stats() }

    val locale = uiLocale()
    Scaffold(topBar = { AppBar(stringResource(R.string.statistics), onBack = onBack) }) { pad ->
        val s = stats ?: return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // streak and today
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.streak_in_row, plural(R.plurals.days_count, s.streak)), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.best_streak, plural(R.plurals.days_count, s.bestStreak)), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.today_stats, s.todayAnswers, s.goal, s.newToday, s.newPerDay),
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (s.todayAnswers.toFloat() / s.goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (s.todayAnswers >= s.goal) Good else MaterialTheme.colorScheme.primary,
                        drawStopIndicator = {},
                    )
                    Text(stringResource(R.string.streak_rule),
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("${s.learned}", stringResource(R.string.tile_learned), stringResource(R.string.tile_of, s.total), Modifier.weight(1f))
                Tile("${s.mastered}", stringResource(R.string.tile_mastered), stringResource(R.string.tile_level5), Modifier.weight(1f))
                Tile(s.accuracy7?.let { "$it%" } ?: "—", stringResource(R.string.tile_accuracy), stringResource(R.string.tile_7days), Modifier.weight(1f))
            }

            // answers over the last 30 days
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.answers_30), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    var sel by remember(s) { mutableIntStateOf(s.last30.lastIndex) }
                    val d = s.last30[sel]
                    val fmt = DateTimeFormatter.ofPattern("d MMMM", locale)
                    val dayName = if (d.day == LocalDate.now()) stringResource(R.string.today) else d.day.format(fmt)
                    val accuracy = if (d.answers > 0) stringResource(R.string.day_accuracy, d.correct * 100 / d.answers) else ""
                    Text(
                        stringResource(R.string.day_answers, dayName, plural(R.plurals.answers_count, d.answers)) + accuracy,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    BarChart(
                        values = s.last30.map { it.answers },
                        selected = sel,
                        goal = s.goal,
                        onSelect = { sel = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(s.last30.first().day.format(DateTimeFormatter.ofPattern("d MMM", locale)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.goal_dashed, s.goal), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.today_lower), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.accuracy30 != null) Text(stringResource(R.string.accuracy_30, s.accuracy30),
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            // forecast
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.week_reviews), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.week_reviews_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val today = LocalDate.now()
                    Row(Modifier.fillMaxWidth()) {
                        s.forecast.forEach { v ->
                            Text("$v", Modifier.weight(1f), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    BarChart(values = s.forecast, selected = 0, goal = null, onSelect = {},
                        modifier = Modifier.fillMaxWidth().height(90.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        s.forecast.indices.forEach { i ->
                            val day = today.plusDays(i.toLong())
                            Text(
                                if (i == 0) stringResource(R.string.today_short) else day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                                Modifier.weight(1f), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (s.hardest.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.most_forgotten), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    s.hardest.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LevelDot(c.level)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.front, style = MaterialTheme.typography.bodyLarge)
                                Text(c.back, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${c.lapses}×", style = MaterialTheme.typography.labelLarge,
                                color = if (c.isHard) Bad else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, sub: String, modifier: Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A simple single-series bar chart: thin bars with rounded tops and a gap between them,
 * the selected bar highlighted, and an optional dashed goal line.
 */
@Composable
private fun BarChart(values: List<Int>, selected: Int, goal: Int?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val dim = bar.copy(alpha = 0.45f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = maxOf(values.maxOrNull() ?: 0, goal ?: 0, 1)
    Canvas(
        modifier.pointerInput(values) {
            detectTapGestures { pos ->
                val i = (pos.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                onSelect(i)
            }
        },
    ) {
        val slot = size.width / values.size
        val gap = 2.dp.toPx()
        val radius = 4.dp.toPx()
        // baseline
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val h = (v.toFloat() / max) * size.height
            roundedTopBar(
                left = i * slot + gap / 2, top = size.height - h, width = slot - gap, height = h,
                radius = radius.coerceAtMost((slot - gap) / 2),
                color = if (i == selected) bar else dim,
            )
        }
        if (goal != null && goal > 0) {
            val y = size.height - goal.toFloat() / max * size.height
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
    }
}

private fun DrawScope.roundedTopBar(left: Float, top: Float, width: Float, height: Float, radius: Float,
                                    color: androidx.compose.ui.graphics.Color) {
    val r = radius.coerceAtMost(height)
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left, top, left + width, top + height,
                topLeftCornerRadius = CornerRadius(r, r), topRightCornerRadius = CornerRadius(r, r),
                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
            )
        )
    }
    drawPath(path, color)
}
