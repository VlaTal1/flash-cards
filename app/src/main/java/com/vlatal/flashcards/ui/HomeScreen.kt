package com.vlatal.flashcards.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.BackupIO
import com.vlatal.flashcards.data.DeckSummary
import com.vlatal.flashcards.data.Repository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(repo: Repository, onOpenDeck: (Long) -> Unit, onStats: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by repo.version.collectAsStateWithLifecycle()
    var decks by remember { mutableStateOf<List<DeckSummary>?>(null) }
    var today by remember { mutableStateOf<Repository.Today?>(null) }
    LaunchedEffect(version) { decks = repo.deckSummaries(); today = repo.today() }

    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var showNewDeck by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val msg = try {
                val r = repo.importDecks(BackupIO.import(context, uri))
                val parts = buildList {
                    if (r.decksCreated > 0) add(context.getString(R.string.import_new_decks, r.decksCreated))
                    if (r.decksMerged > 0) add(context.getString(R.string.import_merged_decks, r.decksMerged))
                    add(context.getString(R.string.import_added, r.cardsAdded))
                    if (r.cardsUpdated > 0) add(context.getString(R.string.import_updated, r.cardsUpdated))
                }
                context.getString(R.string.import_done, parts.joinToString(", "))
            } catch (e: Exception) {
                context.getString(R.string.import_error, e.message)
            }
            busy = false
            snackbar.showSnackbar(msg, withDismissAction = true)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = try {
                BackupIO.exportJson(context, uri, repo.exportAll()); context.getString(R.string.export_done)
            } catch (e: Exception) {
                context.getString(R.string.export_error, e.message)
            }
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            AppBar("Kartičky") {
                IconButton(onClick = onStats) { Icon(Icons.Default.BarChart, stringResource(R.string.statistics)) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_backup)) },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        onClick = { menu = false; importLauncher.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_backup)) },
                        leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                        onClick = {
                            menu = false
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("karticky-$date.json")
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewDeck = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_deck_fab)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val list = decks
            when {
                list == null -> {}
                list.isEmpty() -> EmptyHome(onImport = { importLauncher.launch(arrayOf("*/*")) }, onCreate = { showNewDeck = true })
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    today?.let { t -> item { TodayHeader(t, onStats) } }
                    items(list, key = { it.deck.id }) { DeckCard(it) { onOpenDeck(it.deck.id) } }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (showNewDeck) NameDialog(
        title = stringResource(R.string.new_deck), initial = "", confirm = stringResource(R.string.create),
        onDismiss = { showNewDeck = false },
        onConfirm = { name -> showNewDeck = false; scope.launch { repo.createDeck(name) } },
    )
}

@Composable
private fun EmptyHome(onImport: () -> Unit, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Style, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.no_decks), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.no_decks_hint),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) {
            Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.import_backup))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCreate) { Text(stringResource(R.string.create_deck)) }
    }
}

@Composable
private fun DeckCard(s: DeckSummary, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.deck.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (s.todayTotal > 0) Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                    Text("${s.todayTotal}", Modifier.padding(horizontal = 4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.deck_summary, plural(R.plurals.cards_count, s.total), s.learned) + "\n" +
                    stringResource(R.string.deck_today, s.reviewsDue, s.newToday),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LevelBar(s.levels)
        }
    }
}

@Composable
private fun TodayHeader(t: Repository.Today, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val goalDone = t.answers >= t.goal
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.streak > 0) "🔥" else "🌱", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (t.streak > 0) stringResource(R.string.streak_in_row, plural(R.plurals.days_count, t.streak))
                    else stringResource(R.string.start_streak),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (goalDone) stringResource(R.string.goal_done, plural(R.plurals.answers_count, t.answers))
                    else stringResource(R.string.goal_progress, t.answers, t.goal),
                    style = MaterialTheme.typography.bodySmall, color = if (goalDone) Good else cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (t.answers.toFloat() / t.goal).coerceIn(0f, 1f) },
                    color = if (goalDone) Good else cs.primary, drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.name)) }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
