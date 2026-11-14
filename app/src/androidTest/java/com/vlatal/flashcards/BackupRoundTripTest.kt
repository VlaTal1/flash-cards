package com.vlatal.flashcards

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlatal.flashcards.data.BackupIO
import com.vlatal.flashcards.data.Card
import com.vlatal.flashcards.data.Grade
import com.vlatal.flashcards.data.Repository
import com.vlatal.flashcards.data.Repository.ImportedCard
import com.vlatal.flashcards.data.Repository.ImportedDeck
import com.vlatal.flashcards.data.Repository.ImportedReview
import com.vlatal.flashcards.data.Settings
import com.vlatal.flashcards.data.Srs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Export → import round trip on a real device: real SQLite, real JSON export and the real
 * import code path (reading the file through the ContentResolver). Uses its own database
 * files, so the app's data is never touched.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbNames = listOf("roundtrip-src.db", "roundtrip-dst.db")
    private val opened = mutableListOf<Repository>()

    private fun open(name: String) = Repository.openSeparate(context, name).also { opened += it }

    @Before fun cleanUp() = dbNames.forEach { context.deleteDatabase(it) }

    @After fun tearDown() {
        opened.forEach { it.close() }
        dbNames.forEach { context.deleteDatabase(it) }
    }

    private fun at(daysAgo: Long, minute: Int): Long = LocalDate.now().minusDays(daysAgo)
        .atTime(10 + minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Ten cards and a history that reaches the daily goal on days 0..3 (current streak 4)
     * and on days 6..10 (best streak 5), with some mistakes and some new-card answers.
     */
    private fun history(goal: Int): ImportedDeck {
        val streakDays = (0L..3L) + (6L..10L)
        val cards = (0 until 10).map { i ->
            ImportedCard(
                uuid = "card-$i", front = "slovo$i", back = "слово$i", memo = if (i == 0) "note" else "",
                level = i % (Srs.MAX_LEVEL + 1),
                nextReview = if (i % 3 == 0) 0 else System.currentTimeMillis() + (i - 5) * Srs.DAY,
                position = i, reps = i, lapses = if (i == 1) 5 else i % 3,
                tags = if (i < 4) "slovesa" else "", gender = listOf("", "m", "f", "n")[i % 4],
                reviews = streakDays.flatMap { day ->
                    // spread `goal` answers per day over the cards
                    (0 until goal).filter { it % 10 == i }.map { n ->
                        ImportedReview(at(day, n), grade = n % 4, correct = n % 4 != 0, wasNew = n % 7 == 0)
                    }
                },
            )
        }
        return ImportedDeck("deck-roundtrip", "Round trip", "sk-SK", "uk-UA", cards)
    }

    private data class CardState(
        val front: String, val back: String, val memo: String, val level: Int, val nextReview: Long,
        val reps: Int, val lapses: Int, val tags: String, val gender: String,
    )

    private fun Card.state() = CardState(front, back, memo, level, nextReview, reps, lapses, tags, gender)

    private suspend fun snapshot(repo: Repository): Map<String, Any> {
        val deck = repo.allDecks().single()
        val s = repo.stats()
        return mapOf(
            "stats" to s.copy(hardest = emptyList()),
            "hardest" to s.hardest.map { it.uuid to it.lapses },
            "today" to repo.today(),
            "summary" to repo.deckSummary(deck.id)!!.let {
                listOf(it.total, it.reviewsDue, it.newTotal, it.newToday, it.learned, it.hard, it.levels.toList())
            },
            "cards" to repo.cards(deck.id).associate { it.uuid to it.state() },
            "deck" to listOf(deck.name, deck.uuid, deck.frontLang, deck.backLang),
        )
    }

    private suspend fun exportToFile(repo: Repository): File =
        File(context.cacheDir, "roundtrip.json").apply { writeText(BackupIO.toJson(repo.exportAll())) }

    @Test fun exportThenImportKeepsStreakStatsAndProgress() = runBlocking {
        val goal = Settings.get(context).current.dailyGoal
        val src = open(dbNames[0])
        src.importDecks(listOf(history(goal)))
        // plus a few answers through the live code path, as the games record them
        val live = src.cards(src.allDecks().single().id)
        src.recordAnswer(live[0], Grade.GOOD)
        src.recordAnswer(live[3], Grade.AGAIN)
        src.recordAnswer(live[6], Grade.EASY)

        val before = snapshot(src)
        val stats = src.stats()
        // make sure the fixture really exercises streaks and statistics
        assertEquals(4, stats.streak)
        assertEquals(5, stats.bestStreak)
        assertTrue(stats.todayAnswers >= goal)
        assertTrue(stats.hardest.isNotEmpty())

        // import into an empty database, as on a new phone, through the real file import
        val file = exportToFile(src)
        val dst = open(dbNames[1])
        dst.importDecks(BackupIO.import(context, Uri.fromFile(file)))
        assertEquals(before, snapshot(dst))

        // importing the same file again must not duplicate anything
        dst.importDecks(BackupIO.import(context, Uri.fromFile(file)))
        assertEquals(before, snapshot(dst))
    }
}
