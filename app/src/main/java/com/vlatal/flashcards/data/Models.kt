package com.vlatal.flashcards.data

import androidx.annotation.StringRes
import com.vlatal.flashcards.R
import java.time.LocalDate
import kotlin.random.Random

data class Deck(
    val id: Long,
    val name: String,
    val frontLang: String,
    val backLang: String,
    val uuid: String,
)

data class DeckSummary(
    val deck: Deck,
    val total: Int,
    val reviewsDue: Int,     // learned cards that are due for review
    val newTotal: Int,       // cards never studied yet
    val newToday: Int,       // new cards available today within the daily limit
    val learned: Int,
    val hard: Int,           // "hard" cards — forgotten often
    val levels: IntArray,    // number of cards on each level 0..MAX_LEVEL
) {
    val todayTotal get() = reviewsDue + newToday
}

enum class Gender(val code: String, val article: String, @StringRes val label: Int) {
    M("m", "ten", R.string.gender_m), F("f", "tá", R.string.gender_f), N("n", "to", R.string.gender_n);

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

data class Card(
    val id: Long,
    val deckId: Long,
    val front: String,
    val back: String,
    val memo: String,
    val level: Int,
    val nextReview: Long,
    val uuid: String,
    val reps: Int = 0,       // number of answers; 0 means a new card
    val lapses: Int = 0,     // how many times a learned card was forgotten
    val tags: String = "",   // comma-separated
    val gender: String = "", // m / f / n or empty
) {
    val isNew get() = reps == 0
    val isHard get() = lapses >= Srs.HARD_LAPSES
    val tagList get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val genderEnum get() = Gender.of(gender)

    fun isDue(now: Long = System.currentTimeMillis()) = isNew || nextReview <= now
}

enum class Grade(@StringRes val label: Int) {
    AGAIN(R.string.grade_again), HARD(R.string.grade_hard), GOOD(R.string.grade_good), EASY(R.string.grade_easy)
}

data class Schedule(val level: Int, val nextReview: Long, val lapsed: Boolean)

/**
 * Spaced repetition: levels 0..MAX_LEVEL (compatible with Flashcards Maker, which uses 0..6)
 * with growing intervals and four answer grades.
 */
object Srs {
    const val MAX_LEVEL = 7
    const val HARD_LAPSES = 4
    const val MINUTE = 60_000L
    const val DAY = 24L * 60 * MINUTE
    const val RELEARN_DELAY = 10 * MINUTE

    fun intervalFor(level: Int): Long = when {
        level <= 0 -> 0
        else -> DAY shl (level.coerceAtMost(MAX_LEVEL) - 1) // 1, 2, 4, 8, 16, 32, 64 days
    }

    /**
     * New level and review date.
     *  - Forgot: level −2 (not down to zero), shown again in 10 minutes.
     *  - Hard: level stays, the interval is halved.
     *  - Good: level +1. Easy: level +2.
     * If the card is not due yet (free practice), a correct answer changes nothing —
     * so playing several games in a row doesn't inflate progress.
     */
    fun schedule(card: Card, grade: Grade, now: Long = System.currentTimeMillis(), random: Random = Random): Schedule {
        if (grade == Grade.AGAIN) {
            return Schedule((card.level - 2).coerceAtLeast(0), now + RELEARN_DELAY, lapsed = !card.isNew)
        }
        if (!card.isDue(now)) return Schedule(card.level, card.nextReview, false)
        val (level, interval) = when (grade) {
            Grade.HARD -> {
                val l = card.level.coerceAtLeast(1)
                l to maxOf(DAY, intervalFor(l) / 2)
            }
            Grade.GOOD -> (card.level + 1).coerceAtMost(MAX_LEVEL).let { it to intervalFor(it) }
            else -> (card.level + 2).coerceAtMost(MAX_LEVEL).let { it to intervalFor(it) }
        }
        // ±10% fuzz so that reviews don't pile up on a single day
        val fuzzed = if (interval >= 4 * DAY) (interval * (0.9 + random.nextDouble() * 0.2)).toLong() else interval
        return Schedule(level, now + fuzzed, false)
    }

    /** Time until the next review for the grade button hint; null when the answer changes nothing. */
    fun previewDelay(card: Card, grade: Grade, now: Long = System.currentTimeMillis()): Long? {
        if (!card.isDue(now) && grade != Grade.AGAIN) return null
        return schedule(card, grade, now, Random(0)).nextReview - now
    }
}

object Day {
    fun today(): String = LocalDate.now().toString()
    fun of(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}
