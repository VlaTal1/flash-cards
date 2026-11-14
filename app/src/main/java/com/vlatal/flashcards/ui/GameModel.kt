package com.vlatal.flashcards.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.Card
import com.vlatal.flashcards.data.Deck
import com.vlatal.flashcards.data.TextUtil

enum class GameMode(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val icon: ImageVector,
    val needsDistractors: Boolean = false,
    val prefersReverse: Boolean = false,
    /** The mode defines the direction itself, so the direction picker is hidden. */
    val fixedDirection: Boolean = false,
) {
    FLASHCARDS(R.string.mode_flashcards, R.string.mode_flashcards_desc, Icons.Default.Style),
    QUIZ(R.string.mode_quiz, R.string.mode_quiz_desc, Icons.Default.Checklist, needsDistractors = true),
    TYPING(R.string.mode_typing, R.string.mode_typing_desc, Icons.Default.Keyboard, prefersReverse = true),
    LISTENING(R.string.mode_listening, R.string.mode_listening_desc, Icons.Default.Hearing,
        needsDistractors = true, fixedDirection = true),
    DICTATION(R.string.mode_dictation, R.string.mode_dictation_desc, Icons.Default.RecordVoiceOver, fixedDirection = true),
    MATCH(R.string.mode_match, R.string.mode_match_desc, Icons.Default.JoinInner, needsDistractors = true),
    TRUE_FALSE(R.string.mode_true_false, R.string.mode_true_false_desc, Icons.Default.ThumbsUpDown, needsDistractors = true),
    SCRAMBLE(R.string.mode_scramble, R.string.mode_scramble_desc, Icons.Default.Abc, prefersReverse = true),
    GENDER(R.string.mode_gender, R.string.mode_gender_desc, Icons.Default.Wc, fixedDirection = true);

    val isAudio get() = this == LISTENING || this == DICTATION
}

enum class Direction {
    FRONT_TO_BACK, BACK_TO_FRONT, MIXED;

    /** "SK → UK"-style label; null for [MIXED], whose label is a string resource. */
    fun label(deck: Deck): String? {
        val f = deck.frontLang.substringBefore('-').uppercase()
        val b = deck.backLang.substringBefore('-').uppercase()
        return when (this) {
            FRONT_TO_BACK -> "$f → $b"
            BACK_TO_FRONT -> "$b → $f"
            MIXED -> null
        }
    }
}

enum class Selection(@StringRes val label: Int) {
    TODAY(R.string.sel_today),
    REVIEW(R.string.sel_review),
    NEW(R.string.sel_new),
    HARD(R.string.sel_hard),
    WEAK(R.string.sel_weak),
    ALL(R.string.sel_all),
}

data class GameConfig(
    val deckId: Long,
    val mode: GameMode,
    val direction: Direction,
    val selection: Selection,
    val count: Int, // 0 = all
    val tag: String? = null,
    val onlyIds: List<Long>? = null, // "repeat mistakes"
)

data class Question(val card: Card, val reversed: Boolean, val deck: Deck) {
    val prompt get() = if (reversed) card.back else card.front
    val answer get() = if (reversed) card.front else card.back
    val promptLang get() = if (reversed) deck.backLang else deck.frontLang
    val answerLang get() = if (reversed) deck.frontLang else deck.backLang
}

object GameBuilder {
    /**
     * @param newQuota how many new cards can still be started today (for "For today").
     */
    fun questions(config: GameConfig, deck: Deck, all: List<Card>, newQuota: Int): List<Question> {
        val now = System.currentTimeMillis()
        var pool = all
        if (config.tag != null) pool = pool.filter { c -> c.tagList.any { it.equals(config.tag, true) } }
        if (config.mode == GameMode.GENDER) pool = pool.filter { it.genderEnum != null }

        val reviews = { pool.filter { !it.isNew && it.nextReview <= now }.sortedBy { it.nextReview } }
        val picked = if (config.onlyIds != null) {
            val ids = config.onlyIds.toSet()
            pool.filter { it.id in ids }.shuffled()
        } else when (config.selection) {
            // all reviews first (most overdue first), then new cards within the daily limit
            Selection.TODAY -> reviews() + pool.filter { it.isNew }.take(newQuota)
            Selection.REVIEW -> reviews()
            Selection.NEW -> pool.filter { it.isNew }
            Selection.HARD -> pool.filter { it.isHard }.sortedByDescending { it.lapses }
            Selection.WEAK -> pool.filter { !it.isNew && it.level <= 2 }.shuffled().sortedBy { it.level }
            Selection.ALL -> pool.shuffled()
        }
        val limited = if (config.count > 0 && config.onlyIds == null && config.selection != Selection.TODAY)
            picked.take(config.count) else picked
        return limited.shuffled().map { c ->
            val rev = when {
                config.mode == GameMode.DICTATION -> true // the answer is the Slovak word
                config.mode.fixedDirection -> false
                config.direction == Direction.FRONT_TO_BACK -> false
                config.direction == Direction.BACK_TO_FRONT -> true
                else -> Math.random() < 0.5
            }
            Question(c, rev, deck)
        }
    }

    /** Wrong options: answers of other cards with a similar length. */
    fun distractors(q: Question, all: List<Card>, n: Int): List<String> {
        val right = TextUtil.normalize(q.answer)
        return all.asSequence()
            .filter { it.id != q.card.id }
            .map { if (q.reversed) it.front else it.back }
            .filter { TextUtil.normalize(it) != right && it.isNotBlank() }
            .distinctBy { TextUtil.normalize(it) }
            .toList()
            .shuffled()
            .sortedBy { kotlin.math.abs(it.length - q.answer.length) }
            .take(maxOf(n * 4, 12))
            .shuffled()
            .take(n)
    }
}
