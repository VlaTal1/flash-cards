package com.vlatal.flashcards.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.DeckSummary
import com.vlatal.flashcards.data.Repository
import com.vlatal.flashcards.data.Srs
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeckScreen(repo: Repository, deckId: Long, onBack: () -> Unit, onCards: () -> Unit, onPlay: (GameConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    val version by repo.version.collectAsStateWithLifecycle()
    var summary by remember { mutableStateOf<DeckSummary?>(null) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(version) {
        summary = repo.deckSummary(deckId)
        if (summary == null) onBack()
        tags = repo.cards(deckId).flatMap { it.tagList }.distinct().sorted()
    }

    // the last free-practice settings are remembered per deck
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("game_settings", Context.MODE_PRIVATE) }
    fun <T : Enum<T>> load(key: String, values: Array<T>, def: T): T =
        values.firstOrNull { it.name == prefs.getString("$deckId.$key", null) } ?: def
    var mode by remember { mutableStateOf(load("mode", GameMode.entries.toTypedArray(), GameMode.QUIZ)) }
    var direction by remember { mutableStateOf(load("dir", Direction.entries.toTypedArray(), Direction.FRONT_TO_BACK)) }
    var selection by remember { mutableStateOf(load("sel", Selection.entries.toTypedArray(), Selection.ALL)) }
    var count by remember { mutableIntStateOf(prefs.getInt("$deckId.count", 20)) }
    var tag by remember { mutableStateOf(prefs.getString("$deckId.tag", null)) }
    LaunchedEffect(mode, direction, selection, count, tag) {
        prefs.edit().putString("$deckId.mode", mode.name).putString("$deckId.dir", direction.name)
            .putString("$deckId.sel", selection.name).putInt("$deckId.count", count)
            .putString("$deckId.tag", tag).apply()
    }
    // the topic may have disappeared after cards were edited
    LaunchedEffect(tags) { if (tag != null && tag !in tags) tag = null }

    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val s = summary ?: return
    Scaffold(
        topBar = {
            AppBar(s.deck.name, onBack = onBack) {
                IconButton(onClick = onCards) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.cards)) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reset_progress)) }, onClick = { menu = false; confirmReset = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_deck)) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TodayCard(s) {
                onPlay(GameConfig(deckId, GameMode.FLASHCARDS, Direction.FRONT_TO_BACK, Selection.TODAY, 0))
            }
            StatsCard(s, onCards)

            Text(stringResource(R.string.free_practice), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.mode), style = MaterialTheme.typography.titleMedium)
            GameMode.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { m ->
                        ModeTile(m, selected = m == mode, Modifier.weight(1f)) {
                            mode = m
                            // for writing modes it is more useful to type the Slovak word
                            if (m.prefersReverse) direction = Direction.BACK_TO_FRONT
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (!mode.fixedDirection) {
                Text(stringResource(R.string.direction), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Direction.entries.forEach { d ->
                        FilterChip(selected = d == direction, onClick = { direction = d }, label = { Text(d.label(s.deck) ?: stringResource(R.string.direction_mixed)) })
                    }
                }
            }

            Text(stringResource(R.string.which_cards), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Selection.entries.forEach { sel ->
                    FilterChip(selected = sel == selection, onClick = { selection = sel }, label = {
                        val label = stringResource(sel.label)
                        val n = when (sel) {
                            Selection.TODAY -> s.todayTotal
                            Selection.REVIEW -> s.reviewsDue
                            Selection.NEW -> s.newTotal
                            Selection.HARD -> s.hard
                            else -> null
                        }
                        Text(if (n == null) label else stringResource(R.string.chip_count, label, n))
                    })
                }
            }

            if (tags.isNotEmpty()) {
                Text(stringResource(R.string.topic), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tag == null, onClick = { tag = null }, label = { Text(stringResource(R.string.all_topics)) })
                    tags.forEach { t -> FilterChip(selected = t == tag, onClick = { tag = t }, label = { Text(t) }) }
                }
            }

            if (selection != Selection.TODAY) {
                Text(stringResource(R.string.how_many), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 50, 0).forEach { n ->
                        FilterChip(selected = n == count, onClick = { count = n }, label = { Text(if (n == 0) stringResource(R.string.all) else "$n") })
                    }
                }
            }

            Button(
                onClick = { onPlay(GameConfig(deckId, mode, direction, selection, count, tag)) },
                enabled = s.total > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_practice), style = MaterialTheme.typography.titleMedium)
            }
            if (s.total < 4 && mode.needsDistractors) Text(
                stringResource(R.string.needs_4_cards),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.practice_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (rename) NameDialog(stringResource(R.string.rename_deck), s.deck.name, stringResource(R.string.save), { rename = false }) {
        rename = false; scope.launch { repo.renameDeck(deckId, it) }
    }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.confirm_delete_deck, s.deck.name), stringResource(R.string.delete),
        onDismiss = { confirmDelete = false }) {
        confirmDelete = false; scope.launch { repo.deleteDeck(deckId) } // the screen closes itself once the deck is gone
    }
    if (confirmReset) ConfirmDialog(stringResource(R.string.confirm_reset), stringResource(R.string.reset),
        onDismiss = { confirmReset = false }) {
        confirmReset = false; scope.launch { repo.resetProgress(deckId) }
    }
}

@Composable
private fun TodayCard(s: DeckSummary, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = s.todayTotal == 0
    // a softer container in the dark theme, so the card doesn't glare
    val dark = cs.background.luminance() < 0.5f
    Card(
        colors = CardDefaults.cardColors(containerColor = when {
            done -> cs.surfaceContainer
            dark -> cs.primaryContainer
            else -> cs.primary
        }),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (done) {
                Text(stringResource(R.string.today_done), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.today_done_hint),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            } else {
                val fg = if (dark) cs.onPrimaryContainer else cs.onPrimary
                Text(stringResource(R.string.learn_today), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = fg)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    TodayNum("${s.reviewsDue}", stringResource(R.string.to_review), fg)
                    TodayNum("${s.newToday}", stringResource(R.string.new_label), fg)
                }
                if (s.newTotal > s.newToday) Text(
                    stringResource(R.string.not_started, s.newTotal - s.newToday),
                    style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStart,
                    colors = if (dark) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = cs.onPrimary, contentColor = cs.primary),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun TodayNum(value: String, label: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.85f))
    }
}

@Composable
private fun StatsCard(s: DeckSummary, onCards: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onCards)) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Stat("${s.total}", stringResource(R.string.stat_total), Modifier.weight(1f))
                Stat("${s.learned}", stringResource(R.string.stat_learned), Modifier.weight(1f))
                Stat("${s.hard}", stringResource(R.string.stat_hard), Modifier.weight(1f), if (s.hard > 0) Bad else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            LevelBar(s.levels)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                (0..Srs.MAX_LEVEL).forEach { lvl ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LevelDot(lvl)
                        Text("${s.levels[lvl]}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModeTile(m: GameMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) cs.primaryContainer else cs.surface),
    ) {
        Column(Modifier.padding(12.dp).heightIn(min = 88.dp)) {
            Icon(m.icon, null, tint = if (selected) cs.onPrimaryContainer else cs.primary)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(m.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(m.description), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun ConfirmDialog(text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
