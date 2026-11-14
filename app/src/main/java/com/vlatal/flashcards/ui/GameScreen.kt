package com.vlatal.flashcards.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.Card
import com.vlatal.flashcards.data.Gender
import com.vlatal.flashcards.data.Grade
import com.vlatal.flashcards.data.Srs
import com.vlatal.flashcards.data.Repository
import com.vlatal.flashcards.data.Speaker
import com.vlatal.flashcards.data.TextUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class GameData(val questions: List<Question>, val all: List<Card>)

/** Shared interface for all game modes. */
private class GameHost(
    val all: List<Card>,
    val speaker: Speaker,
    /** Grades an answer (only the first attempt per card counts). */
    val grade: (Question, Grade) -> Unit,
    val progress: (done: Int, total: Int) -> Unit,
    val finish: () -> Unit,
    /** "I was right" — accept an answer that was wrongly marked as incorrect. */
    val overrideCorrect: (Question) -> Unit,
) {
    fun report(q: Question, ok: Boolean) = grade(q, if (ok) Grade.GOOD else Grade.AGAIN)
}

@Composable
fun GameScreen(
    repo: Repository, config: GameConfig, speaker: Speaker,
    onExit: () -> Unit, onRestart: (GameConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<GameData?>(null) }
    LaunchedEffect(Unit) {
        val deck = repo.allDecks().first { it.id == config.deckId }
        val all = repo.cards(config.deckId)
        data = GameData(GameBuilder.questions(config, deck, all, repo.newQuota()), all)
    }
    // result of the first attempt for each card
    val results = remember { mutableStateMapOf<Long, Boolean>() }
    var finished by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    val d = data
    Scaffold(
        topBar = {
            AppBar(stringResource(config.mode.title), onBack = onExit) {
                if (d != null && !finished && total > 0) Text("$done / $total", Modifier.padding(end = 16.dp))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (d != null && !finished && total > 0) LinearProgressIndicator(
                progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    d.questions.isEmpty() -> EmptyGame(config, onExit)
                    config.mode.isAudio && speaker.hasVoice(d.questions.first().deck.frontLang) == false -> Message(
                        stringResource(R.string.no_voice), onExit,
                    )
                    config.mode.needsDistractors && d.all.size < 4 -> Message(
                        stringResource(R.string.needs_4_cards), onExit,
                    )
                    finished -> ResultView(
                        d.questions, results,
                        onRepeatMistakes = { ids -> onRestart(config.copy(onlyIds = ids)) },
                        onAgain = { onRestart(config) },
                        onDone = onExit,
                    )
                    else -> {
                        val host = remember(d) {
                            GameHost(
                                all = d.all, speaker = speaker,
                                grade = { q, g ->
                                    if (q.card.id !in results) {
                                        results[q.card.id] = g != Grade.AGAIN
                                        scope.launch { repo.recordAnswer(q.card, g) }
                                    }
                                },
                                progress = { a, b -> done = a; total = b },
                                finish = { finished = true },
                                overrideCorrect = { q ->
                                    results[q.card.id] = true
                                    // q.card is the snapshot from before the answer, so the level is computed as for a correct one
                                    scope.launch { repo.overrideAnswer(q.card) }
                                },
                            )
                        }
                        when (config.mode) {
                            GameMode.FLASHCARDS -> FlashcardsGame(d.questions, host)
                            GameMode.QUIZ -> QuizGame(d.questions, host)
                            GameMode.TYPING -> TypingGame(d.questions, host)
                            GameMode.MATCH -> MatchGame(d.questions, host)
                            GameMode.TRUE_FALSE -> TrueFalseGame(d.questions, host)
                            GameMode.SCRAMBLE -> ScrambleGame(d.questions, host)
                            GameMode.LISTENING -> QuizGame(d.questions, host, audio = true)
                            GameMode.DICTATION -> TypingGame(d.questions, host, audio = true)
                            GameMode.GENDER -> GenderGame(d.questions, host)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGame(config: GameConfig, onExit: () -> Unit) {
    val text = when {
        config.mode == GameMode.GENDER ->
            stringResource(R.string.empty_gender)
        config.selection == Selection.TODAY -> stringResource(R.string.empty_today)
        config.selection == Selection.REVIEW -> stringResource(R.string.empty_review)
        config.selection == Selection.NEW -> stringResource(R.string.empty_new)
        config.selection == Selection.HARD -> stringResource(R.string.empty_hard)
        config.selection == Selection.WEAK -> stringResource(R.string.empty_weak)
        config.tag != null -> stringResource(R.string.empty_topic, config.tag)
        else -> stringResource(R.string.empty_deck)
    }
    Message(text, onExit)
}

@Composable
private fun Message(text: String, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onExit) { Text(stringResource(R.string.back)) }
    }
}

// ============================================================
// Shared elements
// ============================================================

@Composable
private fun PromptCard(q: Question, host: GameHost, minHeight: androidx.compose.ui.unit.Dp = 140.dp, subtitle: String? = null) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(24.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(q.prompt, fontSize = promptSize(q.prompt), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (subtitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
            SpeakButton(q.prompt, q.promptLang, host, Modifier.align(Alignment.TopEnd).offset(12.dp, (-12).dp))
        }
    }
}

@Composable
private fun SpeakButton(text: String, lang: String, host: GameHost, modifier: Modifier = Modifier) {
    IconButton(onClick = { host.speaker.speak(text, lang) }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.speak), tint = MaterialTheme.colorScheme.primary)
    }
}

/** "10 min" / "3 h" / "4 d" for a delay, or "—" when the answer changes nothing. */
@Composable
private fun durationLabel(ms: Long?): String = when {
    ms == null -> "—"
    ms < 60 * Srs.MINUTE -> stringResource(R.string.dur_min, (ms / Srs.MINUTE).toInt())
    ms < Srs.DAY -> stringResource(R.string.dur_hours, (ms / (60 * Srs.MINUTE)).toInt())
    else -> stringResource(R.string.dur_days, ((ms + Srs.DAY / 2) / Srs.DAY).toInt())
}

private fun promptSize(s: String) = when {
    s.length <= 12 -> 34.sp
    s.length <= 25 -> 28.sp
    s.length <= 50 -> 22.sp
    else -> 18.sp
}

@Composable
private fun FeedbackBanner(ok: Boolean, text: String, extra: String? = null) {
    Surface(
        color = (if (ok) Good else Bad).copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel, null, tint = if (ok) Good else Bad)
                Spacer(Modifier.width(8.dp))
                Text(text, fontWeight = FontWeight.SemiBold, color = if (ok) Good else Bad)
            }
            if (extra != null) Text(extra, Modifier.padding(start = 32.dp, top = 4.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ============================================================
// 1. Flashcards
// ============================================================

@Composable
private fun FlashcardsGame(questions: List<Question>, host: GameHost) {
    val queue = remember { mutableStateListOf<Question>().apply { addAll(questions) } }
    var known by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    LaunchedEffect(known) { host.progress(known, questions.size) }
    if (queue.isEmpty()) { LaunchedEffect(Unit) { host.finish() }; return }
    val q = queue.first()
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(350), label = "flip")

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .graphicsLayer { rotationY = rotation; cameraDistance = 14 * density },
        ) {
            ElevatedCard(
                onClick = { flipped = !flipped },
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (rotation > 90f) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { if (rotation > 90f) rotationY = 180f }.padding(24.dp)) {
                    val showBack = rotation > 90f
                    val text = if (showBack) q.answer else q.prompt
                    val lang = if (showBack) q.answerLang else q.promptLang
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showBack) {
                            Text(q.prompt, style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(text, fontSize = promptSize(text), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        if (showBack && q.card.memo.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(q.card.memo, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                    SpeakButton(text, lang, host, Modifier.align(Alignment.TopEnd))
                    if (!showBack) Text(stringResource(R.string.tap_to_flip), Modifier.align(Alignment.BottomCenter),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!flipped) {
            Button(onClick = { flipped = true }, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.show_answer)) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val now = remember(q) { System.currentTimeMillis() }
            Grade.entries.forEach { g ->
                val color = when (g) {
                    Grade.AGAIN -> Bad
                    Grade.HARD -> Color(0xFFE08A1E)
                    Grade.GOOD -> Good
                    Grade.EASY -> SkBlue
                }
                Button(
                    onClick = {
                        host.grade(q, g)
                        queue.removeAt(0)
                        if (g == Grade.AGAIN) queue.add(q) else known++ // a forgotten card is shown again at the end
                        flipped = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(g.label), maxLines = 1)
                        Text(durationLabel(Srs.previewDelay(q.card, g, now)), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ============================================================
// 2. Quiz (4 options) — also used by Listening
// ============================================================

@Composable
private fun QuizGame(questions: List<Question>, host: GameHost, audio: Boolean = false) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) { host.progress(index, questions.size) }
    if (index >= questions.size) { LaunchedEffect(Unit) { host.finish() }; return }
    val q = questions[index]
    val options = remember(index) { (GameBuilder.distractors(q, host.all, 3) + q.answer).shuffled() }
    var chosen by remember(index) { mutableStateOf<String?>(null) }
    val correct = chosen == q.answer

    LaunchedEffect(chosen) {
        if (chosen != null && correct) { delay(if (audio) 1100 else 700); index++ }
    }
    if (audio) LaunchedEffect(index) { delay(300); host.speaker.speak(q.prompt, q.promptLang) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (audio) AudioPrompt(q.prompt, q.promptLang, host, revealed = if (chosen != null) q.prompt else null)
        else PromptCard(q, host, 160.dp)
        Spacer(Modifier.height(8.dp))
        options.forEach { opt ->
            val isRight = opt == q.answer
            val color = when {
                chosen == null -> MaterialTheme.colorScheme.outlineVariant
                isRight -> Good
                opt == chosen -> Bad
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            OutlinedCard(
                onClick = {
                    if (chosen == null) {
                        chosen = opt
                        host.report(q, isRight)
                        if (!audio && q.answerLang.startsWith("sk")) host.speaker.speak(q.answer, q.answerLang)
                    }
                },
                border = BorderStroke(if (chosen != null && (isRight || opt == chosen)) 2.dp else 1.dp, color),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (chosen != null && (isRight || opt == chosen)) color.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(opt, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (chosen != null && !correct) {
            Button(onClick = { index++ }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.next)) }
        }
    }
}

// ============================================================
// 3. Typing — also used by Dictation
// ============================================================

@Composable
private fun TypingGame(questions: List<Question>, host: GameHost, audio: Boolean = false) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) { host.progress(index, questions.size) }
    if (index >= questions.size) { LaunchedEffect(Unit) { host.finish() }; return }
    val q = questions[index]
    var input by remember(index) { mutableStateOf(TextFieldValue("")) }
    var verdict by remember(index) { mutableStateOf<TextUtil.Verdict?>(null) }
    var overridden by remember(index) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(index) {
        runCatching { focus.requestFocus() }
        if (audio) { delay(300); host.speaker.speak(q.answer, q.answerLang) }
    }

    fun submit() {
        if (verdict != null) { index++; return }
        val v = if (input.text.isBlank()) TextUtil.Verdict.WRONG else TextUtil.check(input.text, q.answer)
        verdict = v
        host.grade(q, when (v) {
            TextUtil.Verdict.CORRECT -> Grade.GOOD
            TextUtil.Verdict.ALMOST -> Grade.HARD
            TextUtil.Verdict.WRONG -> Grade.AGAIN
        })
        if (!audio) host.speaker.speak(q.answer, q.answerLang)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (audio) AudioPrompt(q.answer, q.answerLang, host, revealed = null, hint = q.prompt)
        else PromptCard(q, host)
        OutlinedTextField(
            value = input,
            onValueChange = { if (verdict == null) input = it },
            label = { Text(stringResource(if (audio) R.string.what_heard else R.string.field_translation)) },
            singleLine = true,
            readOnly = verdict != null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (verdict == null && q.answerLang.startsWith("sk")) DiacriticsRow { l ->
            val t = input.text.substring(0, input.selection.start) + l + input.text.substring(input.selection.end)
            input = TextFieldValue(t, TextRange(input.selection.start + l.length))
        }
        when (verdict) {
            TextUtil.Verdict.CORRECT -> FeedbackBanner(true, stringResource(R.string.correct), q.answer.takeIf { TextUtil.variants(it).size > 1 })
            TextUtil.Verdict.ALMOST -> FeedbackBanner(true, stringResource(R.string.almost), q.answer)
            TextUtil.Verdict.WRONG -> if (overridden) FeedbackBanner(true, stringResource(R.string.counted), q.answer)
            else FeedbackBanner(false, stringResource(R.string.correct_answer), q.answer)
            null -> {}
        }
        if (verdict == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { input = TextFieldValue(""); submit() }, Modifier.weight(1f).height(52.dp)) {
                Text(stringResource(R.string.dont_know))
            }
            Button(onClick = { submit() }, Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.check)) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (verdict == TextUtil.Verdict.WRONG && !overridden && input.text.isNotBlank()) {
                OutlinedButton(onClick = { overridden = true; host.overrideCorrect(q) }, Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.i_was_right))
                }
            }
            Button(onClick = { index++ }, Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.next)) }
        }
    }
}

/** A big "listen" button instead of the question text. */
@Composable
private fun AudioPrompt(text: String, lang: String, host: GameHost, revealed: String?, hint: String? = null) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FilledIconButton(onClick = { host.speaker.speak(text, lang) }, modifier = Modifier.size(80.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.listen), Modifier.size(40.dp))
            }
            Spacer(Modifier.height(8.dp))
            when {
                revealed != null -> Text(revealed, fontSize = promptSize(revealed), fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                hint != null -> Text(stringResource(R.string.hint, hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                else -> Text(stringResource(R.string.tap_to_listen), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ============================================================
// Gender: ten / tá / to
// ============================================================

@Composable
private fun GenderGame(questions: List<Question>, host: GameHost) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) { host.progress(index, questions.size) }
    if (index >= questions.size) { LaunchedEffect(Unit) { host.finish() }; return }
    val q = questions[index]
    val right = q.card.genderEnum ?: Gender.M
    var chosen by remember(index) { mutableStateOf<Gender?>(null) }
    LaunchedEffect(chosen) {
        if (chosen == right) { delay(800); index++ }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PromptCard(q, host, 180.dp, subtitle = q.card.back)
        Text(stringResource(R.string.which_gender), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Gender.entries.forEach { g ->
                val color = when {
                    chosen == null -> MaterialTheme.colorScheme.primary
                    g == right -> Good
                    g == chosen -> Bad
                    else -> MaterialTheme.colorScheme.outline
                }
                Button(
                    onClick = {
                        if (chosen == null) {
                            chosen = g
                            host.report(q, g == right)
                            host.speaker.speak("${right.article} ${q.card.front}", q.deck.frontLang)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (chosen == null) MaterialTheme.colorScheme.onPrimary else Color.White),
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(g.article, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(g.label), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (chosen != null && chosen != right) {
            FeedbackBanner(false, stringResource(R.string.correct_is), "${right.article} ${q.card.front} (${stringResource(right.label)})")
            Button(onClick = { index++ }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.next)) }
        }
    }
}

// ============================================================
// 4. Pairs
// ============================================================

private const val MATCH_BATCH = 6

@Composable
private fun MatchGame(questions: List<Question>, host: GameHost) {
    val batches = remember { questions.chunked(MATCH_BATCH) }
    var batchIndex by remember { mutableIntStateOf(0) }
    var matchedTotal by remember { mutableIntStateOf(0) }
    LaunchedEffect(matchedTotal) { host.progress(matchedTotal, questions.size) }
    if (batchIndex >= batches.size) { LaunchedEffect(Unit) { host.finish() }; return }

    val batch = batches[batchIndex]
    val left = remember(batchIndex) { batch.shuffled() }
    val right = remember(batchIndex) { batch.shuffled() }
    var selLeft by remember(batchIndex) { mutableStateOf<Question?>(null) }
    var selRight by remember(batchIndex) { mutableStateOf<Question?>(null) }
    val matched = remember(batchIndex) { mutableStateListOf<Long>() }
    var wrong by remember(batchIndex) { mutableStateOf<Pair<Question, Question>?>(null) }

    LaunchedEffect(selLeft, selRight) {
        val l = selLeft; val r = selRight
        if (l != null && r != null) {
            // also match by text, in case different cards share the same translation
            if (l.card.id == r.card.id || TextUtil.normalize(l.answer) == TextUtil.normalize(r.answer)) {
                host.report(l, true)
                if (l.card.id != r.card.id) host.report(r, true)
                matched += l.card.id; if (l.card.id != r.card.id) matched += r.card.id
                matchedTotal += if (l.card.id != r.card.id) 2 else 1
                if (l.promptLang.startsWith("sk")) host.speaker.speak(l.prompt, l.promptLang)
                else host.speaker.speak(l.answer, l.answerLang)
            } else {
                host.report(l, false)
                wrong = l to r
            }
            selLeft = null; selRight = null
        }
    }
    LaunchedEffect(wrong) {
        if (wrong != null) { delay(600); wrong = null }
    }
    LaunchedEffect(matched.size) {
        if (matched.size >= batch.size) { delay(400); batchIndex++ }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.match_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                left.forEach { q ->
                    MatchTile(q.prompt, q.card.id in matched, selLeft == q, wrong?.first == q, Modifier.weight(1f)) {
                        selLeft = if (selLeft == q) null else q
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                right.forEach { q ->
                    MatchTile(q.answer, q.card.id in matched, selRight == q, wrong?.second == q, Modifier.weight(1f)) {
                        selRight = if (selRight == q) null else q
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchTile(text: String, done: Boolean, selected: Boolean, wrong: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val alpha by animateFloatAsState(if (done) 0f else 1f, tween(300), label = "fade")
    val border = when { wrong -> Bad; selected -> cs.primary; else -> cs.outlineVariant }
    OutlinedCard(
        onClick = { if (!done) onClick() },
        enabled = !done,
        border = BorderStroke(if (selected || wrong) 2.dp else 1.dp, border),
        colors = CardDefaults.outlinedCardColors(
            containerColor = when { wrong -> Bad.copy(alpha = 0.12f); selected -> cs.primaryContainer; else -> cs.surface },
            disabledContainerColor = cs.surface,
        ),
        modifier = modifier.fillMaxWidth().heightIn(max = 110.dp).alpha(alpha),
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(text, textAlign = TextAlign.Center, style = if (text.length > 25) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.titleMedium, maxLines = 4)
        }
    }
}

// ============================================================
// 5. True / False
// ============================================================

@Composable
private fun TrueFalseGame(questions: List<Question>, host: GameHost) {
    var index by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) { host.progress(index, questions.size) }
    if (index >= questions.size) { LaunchedEffect(Unit) { host.finish() }; return }
    val q = questions[index]
    val shown = remember(index) {
        if (Math.random() < 0.5) q.answer else GameBuilder.distractors(q, host.all, 1).firstOrNull() ?: q.answer
    }
    val isTrue = TextUtil.normalize(shown) == TextUtil.normalize(q.answer)
    var answered by remember(index) { mutableStateOf<Boolean?>(null) } // whether the answer was correct

    LaunchedEffect(answered) {
        if (answered == true) { delay(550); index++ }
    }

    fun answer(saysTrue: Boolean) {
        if (answered != null) return
        val ok = saysTrue == isTrue
        answered = ok
        host.report(q, ok)
        if (ok) { streak++; best = maxOf(best, streak) } else streak = 0
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.streak_record, streak, best), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(q.prompt, fontSize = promptSize(q.prompt), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Icon(Icons.Default.SwapVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(shown, fontSize = promptSize(shown), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
        when (answered) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { answer(false) }, colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(64.dp)) { Text(stringResource(R.string.false_), style = MaterialTheme.typography.titleMedium) }
                Button(onClick = { answer(true) }, colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(64.dp)) { Text(stringResource(R.string.true_), style = MaterialTheme.typography.titleMedium) }
            }
            true -> FeedbackBanner(true, stringResource(R.string.correct))
            false -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeedbackBanner(false, stringResource(R.string.mistake_correct_is), "${q.prompt} — ${q.answer}")
                Button(onClick = { index++ }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.next)) }
            }
        }
    }
}

// ============================================================
// 6. Build the word
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrambleGame(questions: List<Question>, host: GameHost) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) { host.progress(index, questions.size) }
    if (index >= questions.size) { LaunchedEffect(Unit) { host.finish() }; return }
    val q = questions[index]
    // take the first answer variant: "live, reside" → "live"
    val target = remember(index) {
        val a = java.text.Normalizer.normalize(q.answer, java.text.Normalizer.Form.NFC)
        a.split(',', ';', '/', '\n').first().trim().ifEmpty { a.trim() }
    }
    val tiles = remember(index) {
        val letters = target.filter { !it.isWhitespace() }.toList()
        var s = letters.shuffled()
        repeat(5) { if (s == letters && letters.distinct().size > 1) s = letters.shuffled() }
        s
    }
    val picked = remember(index) { mutableStateListOf<Int>() } // tile indices
    var result by remember(index) { mutableStateOf<Boolean?>(null) }
    val goal = target.filter { !it.isWhitespace() }

    LaunchedEffect(picked.size) {
        if (result == null && picked.size == tiles.size && tiles.isNotEmpty()) {
            val built = picked.map { tiles[it] }.joinToString("")
            val ok = built.equals(goal, ignoreCase = true)
            result = ok
            host.report(q, ok)
            host.speaker.speak(target, q.answerLang)
            if (ok) { delay(900); index++ }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PromptCard(q, host)

        // the word built so far (spaces stay in place)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(2.dp, when (result) { true -> Good; false -> Bad; null -> Color.Transparent }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            FlowRow(
                Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var p = 0
                target.forEach { ch ->
                    if (ch.isWhitespace()) Spacer(Modifier.width(16.dp))
                    else {
                        val tileIdx = picked.getOrNull(p)
                        val pos = p
                        p++
                        LetterSlot(tileIdx?.let { tiles[it].toString() }) {
                            if (result == null && tileIdx != null) {
                                // remove this letter and everything after it
                                while (picked.size > pos) picked.removeAt(picked.lastIndex)
                            }
                        }
                    }
                }
            }
        }

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tiles.forEachIndexed { i, ch ->
                val used = i in picked
                FilledTonalButton(
                    onClick = { if (!used && result == null) picked += i },
                    enabled = !used && result == null,
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(48.dp).alpha(if (used) 0.3f else 1f),
                ) { Text(ch.toString(), style = MaterialTheme.typography.titleLarge) }
            }
        }

        when (result) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { if (picked.isNotEmpty()) picked.removeAt(picked.lastIndex) },
                    Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, null)
                }
                OutlinedButton(onClick = {
                    result = false; host.report(q, false); host.speaker.speak(target, q.answerLang)
                }, Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.dont_know)) }
            }
            true -> FeedbackBanner(true, stringResource(R.string.correct), q.answer.takeIf { it != target })
            false -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeedbackBanner(false, stringResource(R.string.correct_is), q.answer)
                Button(onClick = { index++ }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.next)) }
            }
        }
    }
}

@Composable
private fun LetterSlot(letter: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (letter != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(width = 32.dp, height = 42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter ?: "", style = MaterialTheme.typography.titleLarge)
        }
    }
}

// ============================================================
// Results
// ============================================================

@Composable
private fun ResultView(
    questions: List<Question>, results: Map<Long, Boolean>,
    onRepeatMistakes: (List<Long>) -> Unit, onAgain: () -> Unit, onDone: () -> Unit,
) {
    val unique = questions.distinctBy { it.card.id }
    val mistakes = unique.filter { results[it.card.id] == false }
    val correct = unique.count { results[it.card.id] == true }
    val total = unique.count { it.card.id in results }
    val pct = if (total == 0) 0 else correct * 100 / total
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when {
                    pct == 100 -> stringResource(R.string.result_perfect)
                    pct >= 80 -> stringResource(R.string.result_great)
                    pct >= 50 -> stringResource(R.string.result_ok)
                    else -> stringResource(R.string.result_more)
                }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("$pct%", fontSize = 56.sp, fontWeight = FontWeight.Bold,
                    color = if (pct >= 80) Good else if (pct >= 50) MaterialTheme.colorScheme.primary else Bad)
                Text(stringResource(R.string.first_try, correct, total), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mistakes.isNotEmpty()) Button(
                    onClick = { onRepeatMistakes(mistakes.map { it.card.id }) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.repeat_mistakes, mistakes.size)) }
                OutlinedButton(onClick = onAgain, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.another_round)) }
                TextButton(onClick = onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
            }
        }
        if (mistakes.isNotEmpty()) {
            item { Text(stringResource(R.string.mistakes), style = MaterialTheme.typography.titleMedium) }
            items(mistakes, key = { it.card.id }) { q ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(q.card.front, style = MaterialTheme.typography.titleMedium)
                        Text(q.card.back, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
