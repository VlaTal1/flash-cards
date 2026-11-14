package com.vlatal.flashcards.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SortBy(@StringRes val label: Int) {
    ORDER(R.string.sort_order), ALPHA(R.string.sort_alpha), LEVEL(R.string.sort_level), LAPSES(R.string.sort_lapses)
}

private sealed interface CardFilter {
    data object All : CardFilter
    data object Hard : CardFilter
    data object New : CardFilter
    data object NoTag : CardFilter
    data class Tag(val tag: String) : CardFilter
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CardsScreen(repo: Repository, deckId: Long, speaker: Speaker, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val version by repo.version.collectAsStateWithLifecycle()
    var deck by remember { mutableStateOf<Deck?>(null) }
    var cards by remember { mutableStateOf<List<Card>>(emptyList()) }
    LaunchedEffect(version) {
        deck = repo.allDecks().firstOrNull { it.id == deckId }
        cards = repo.cards(deckId)
    }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortBy.ORDER) }
    var filter by remember { mutableStateOf<CardFilter>(CardFilter.All) }
    var editing by remember { mutableStateOf<Card?>(null) }
    var adding by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var tagDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val allTags = remember(cards) { cards.flatMap { it.tagList }.distinct().sorted() }
    val shown = remember(cards, query, sort, filter) {
        val q = TextUtil.stripDiacritics(query.trim().lowercase())
        cards.filter {
            when (val f = filter) {
                CardFilter.All -> true
                CardFilter.Hard -> it.isHard
                CardFilter.New -> it.isNew
                CardFilter.NoTag -> it.tagList.isEmpty()
                is CardFilter.Tag -> it.tagList.contains(f.tag)
            }
        }.filter {
            q.isEmpty() || TextUtil.stripDiacritics(it.front.lowercase()).contains(q) ||
                TextUtil.stripDiacritics(it.back.lowercase()).contains(q)
        }.let { list ->
            when (sort) {
                SortBy.ORDER -> list
                SortBy.ALPHA -> list.sortedBy { TextUtil.stripDiacritics(it.front.lowercase()) }
                SortBy.LEVEL -> list.sortedWith(compareBy({ it.level }, { it.nextReview }))
                SortBy.LAPSES -> list.sortedByDescending { it.lapses }
            }
        }
    }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected.clear() }

    Scaffold(
        topBar = {
            if (selecting) AppBar(stringResource(R.string.selected_count, selected.size), onBack = { selected.clear() }) {
                IconButton(onClick = {
                    val ids = shown.map { it.id }
                    if (selected.containsAll(ids)) selected.clear() else { selected.clear(); selected.addAll(ids) }
                }) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
                IconButton(onClick = { tagDialog = true }) { Icon(Icons.Default.Label, stringResource(R.string.topic)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            } else AppBar(stringResource(R.string.cards_title, cards.size), onBack = onBack)
        },
        floatingActionButton = {
            if (!selecting) FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            // filters
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hardCount = cards.count { it.isHard }
                FilterChip(filter == CardFilter.All, { filter = CardFilter.All }, { Text(stringResource(R.string.all)) })
                FilterChip(filter == CardFilter.Hard, { filter = CardFilter.Hard }, { Text(stringResource(R.string.filter_hard, hardCount)) },
                    leadingIcon = { Icon(Icons.Default.Warning, null, Modifier.size(16.dp)) })
                FilterChip(filter == CardFilter.New, { filter = CardFilter.New }, { Text(stringResource(R.string.filter_new)) })
                if (allTags.isNotEmpty()) FilterChip(filter == CardFilter.NoTag, { filter = CardFilter.NoTag }, { Text(stringResource(R.string.filter_no_topic)) })
                allTags.forEach { t ->
                    FilterChip(filter == CardFilter.Tag(t), { filter = CardFilter.Tag(t) }, { Text(t) })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Sort, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                SortBy.entries.forEach { FilterChip(it == sort, { sort = it }, { Text(stringResource(it.label)) }) }
            }
            if (filter == CardFilter.Hard && shown.isNotEmpty()) Text(
                stringResource(R.string.hard_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cards.isNotEmpty()) Text(
                stringResource(if (selecting) R.string.select_hint_selecting else R.string.select_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                maxLines = 2, minLines = 2,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(shown, key = { it.id }) { c ->
                    val isSel = c.id in selected
                    ListItem(
                        headlineContent = {
                            Text(buildString {
                                c.genderEnum?.let { append(it.article).append(' ') }
                                append(c.front)
                            })
                        },
                        supportingContent = {
                            Column {
                                Text(c.back)
                                if (c.memo.isNotBlank()) Text(c.memo, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (c.tagList.isNotEmpty() || c.isHard) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (c.isHard) Text(stringResource(R.string.lapses_badge, c.lapses), style = MaterialTheme.typography.labelSmall, color = Bad)
                                    c.tagList.forEach { t ->
                                        Text("#$t", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            if (selecting) Checkbox(isSel, onCheckedChange = null)
                            else LevelDot(c.level, Modifier.size(14.dp))
                        },
                        trailingContent = {
                            if (!selecting) IconButton(onClick = { speaker.speak(c.front, deck?.frontLang ?: "sk-SK") }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.speak))
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selecting) { if (isSel) selected.remove(c.id) else selected.add(c.id) }
                                else editing = c
                            },
                            onLongClick = { if (isSel) selected.remove(c.id) else selected.add(c.id) },
                        ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val openExisting: (Card) -> Unit = { adding = false; editing = it }
    if (adding) CardDialog(null, allTags, cards, openExisting, onDismiss = { adding = false }, onDelete = null) { f, b, m, t, g ->
        scope.launch { repo.addCard(deckId, f, b, m, t, g) }
        // the dialog stays open so the next card can be added right away
    }
    editing?.let { c ->
        key(c.id) {
            CardDialog(c, allTags, cards, openExisting, onDismiss = { editing = null }, onDelete = {
                editing = null; scope.launch { repo.deleteCards(listOf(c.id)) }
            }) { f, b, m, t, g ->
                editing = null; scope.launch { repo.updateCard(c, f, b, m, t, g) }
            }
        }
    }
    if (tagDialog) {
        val chosen = cards.filter { it.id in selected }
        BulkTagDialog(allTags, onDismiss = { tagDialog = false },
            onAdd = { t -> tagDialog = false; scope.launch { repo.addTag(chosen, t); selected.clear() } },
            onRemove = { t -> tagDialog = false; scope.launch { repo.removeTag(chosen, t); selected.clear() } })
    }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.confirm_delete_selected, selected.size), stringResource(R.string.delete),
        onDismiss = { confirmDelete = false }) {
        confirmDelete = false
        val ids = selected.toList(); selected.clear()
        scope.launch { repo.deleteCards(ids) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkTagDialog(allTags: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_topic_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.topic_field)) },
                    modifier = Modifier.fillMaxWidth())
                if (allTags.isNotEmpty()) {
                    Text(stringResource(R.string.tap_topic), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allTags.forEach { t -> SuggestionChip(onClick = { text = t }, label = { Text(t) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text.trim()) }) { Text(stringResource(R.string.add)) } },
        dismissButton = {
            Row {
                TextButton(enabled = text.isNotBlank(), onClick = { onRemove(text.trim()) }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardDialog(
    card: Card?, allTags: List<String>, allCards: List<Card>, onOpen: (Card) -> Unit,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?,
    onSave: (front: String, back: String, memo: String, tags: String, gender: String) -> Unit,
) {
    var front by remember { mutableStateOf(card?.front ?: "") }
    var back by remember { mutableStateOf(card?.back ?: "") }
    var memo by remember { mutableStateOf(card?.memo ?: "") }
    var tags by remember { mutableStateOf(card?.tags?.replace(",", ", ") ?: "") }
    var gender by remember { mutableStateOf(card?.gender ?: "") }
    var savedCount by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (card == null) R.string.new_card else R.string.card)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val dups = remember(front, back, allCards) { Duplicates.find(front, back, allCards, card?.id) }
                OutlinedTextField(front, { front = it }, label = { Text(stringResource(R.string.field_word)) }, modifier = Modifier.fillMaxWidth(),
                    isError = dups.sameWord.isNotEmpty())
                DuplicateHints(dups.sameWord, stringResource(R.string.dup_same), Bad, onOpen)
                DuplicateHints(dups.similarWord, stringResource(R.string.dup_similar), MaterialTheme.colorScheme.onSurfaceVariant, onOpen)
                DiacriticsRow { front += it }
                OutlinedTextField(back, { back = it }, label = { Text(stringResource(R.string.field_translation)) }, modifier = Modifier.fillMaxWidth())
                DuplicateHints(dups.sameTranslation, stringResource(R.string.dup_translation), Color(0xFFE08A1E), onOpen)
                OutlinedTextField(memo, { memo = it }, label = { Text(stringResource(R.string.field_note)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.field_gender), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(gender.isEmpty(), { gender = "" }, { Text("—") })
                    Gender.entries.forEach { g ->
                        FilterChip(gender == g.code, { gender = g.code }, { Text(g.article, fontWeight = FontWeight.SemiBold) })
                    }
                }
                OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.field_topics)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                val current = tags.split(',').map { it.trim().lowercase() }
                val suggestions = allTags.filter { it !in current }
                if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { t ->
                        SuggestionChip(onClick = {
                            tags = (current.filter { it.isNotEmpty() } + t).joinToString(", ")
                        }, label = { Text("+ $t") })
                    }
                }
                if (card != null) {
                    val locale = uiLocale()
                    val date = if (card.isNew) stringResource(R.string.card_new_status)
                    else SimpleDateFormat("d MMM yyyy", locale).format(Date(card.nextReview))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LevelDot(card.level); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.card_status, card.level, date, card.lapses),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (card == null && savedCount > 0) Text(stringResource(R.string.added_count, savedCount), color = Good,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = front.isNotBlank() && back.isNotBlank(), onClick = {
                onSave(front.trim(), back.trim(), memo.trim(), tags, gender)
                if (card == null) { front = ""; back = ""; memo = ""; gender = ""; savedCount++ }
            }) { Text(stringResource(if (card == null) R.string.add else R.string.save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(if (card == null) R.string.done else R.string.cancel)) }
            }
        },
    )
}

/** List of matching cards; tapping one opens it for editing. */
@Composable
private fun DuplicateHints(cards: List<Card>, title: String, color: Color, onOpen: (Card) -> Unit) {
    if (cards.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        }
        cards.take(5).forEach { c ->
            Surface(
                onClick = { onOpen(c) },
                shape = MaterialTheme.shapes.small,
                color = color.copy(alpha = 0.10f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.front} — ${c.back}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.open), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (cards.size > 5) Text(stringResource(R.string.and_more, cards.size - 5), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private val SK_LETTERS = listOf("á", "ä", "č", "ď", "é", "í", "ĺ", "ľ", "ň", "ó", "ô", "ŕ", "š", "ť", "ú", "ý", "ž")

/** A row of Slovak letters, in case the keyboard lacks them. */
@Composable
fun DiacriticsRow(onLetter: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SK_LETTERS.forEach { l ->
            Surface(
                onClick = { onLetter(l) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Text(l, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
