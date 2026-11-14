package com.vlatal.flashcards.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

private class DbHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE decks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                front_lang TEXT NOT NULL DEFAULT 'sk-SK',
                back_lang TEXT NOT NULL DEFAULT 'uk-UA',
                created INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE cards(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE,
                front TEXT NOT NULL,
                back TEXT NOT NULL,
                memo TEXT NOT NULL DEFAULT '',
                level INTEGER NOT NULL DEFAULT 0,
                next_review INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_cards_deck ON cards(deck_id)")
        db.execSQL("CREATE UNIQUE INDEX idx_cards_uuid ON cards(deck_id, uuid)")
        upgradeTo2(db)
    }

    private fun upgradeTo2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE cards ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cards ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cards ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE cards ADD COLUMN gender TEXT NOT NULL DEFAULT ''")
        // cards learned before are not new
        db.execSQL("UPDATE cards SET reps = 1 WHERE level > 0")
        db.execSQL(
            """CREATE TABLE reviews(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                card_id INTEGER NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
                ts INTEGER NOT NULL,
                day TEXT NOT NULL,
                grade INTEGER NOT NULL,
                correct INTEGER NOT NULL,
                was_new INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_reviews_day ON reviews(day)")
        db.execSQL("CREATE INDEX idx_reviews_card ON reviews(card_id)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeTo2(db)
    }
}

data class DayStat(val day: LocalDate, val answers: Int, val correct: Int)

data class Stats(
    val streak: Int,
    val bestStreak: Int,
    val todayAnswers: Int,
    val goal: Int,
    val newToday: Int,
    val newPerDay: Int,
    val last30: List<DayStat>,
    val forecast: List<Int>, // index 0 = today (including overdue), then one entry per day
    val accuracy7: Int?,
    val accuracy30: Int?,
    val total: Int,
    val learned: Int,
    val mastered: Int,
    val hardest: List<Card>,
)

class Repository private constructor(context: Context, dbName: String = DB_NAME) {
    private val helper = DbHelper(context.applicationContext, dbName)
    private val settings = Settings.get(context)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Incremented on every data change so that screens reload. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun changed() { _version.value++ }

    /** Makes screens reload (e.g. when returning to the app — the day may have changed). */
    fun refresh() = changed()

    // ---------- decks ----------

    suspend fun deckSummaries(): List<DeckSummary> = io {
        val now = System.currentTimeMillis()
        val quota = newQuotaLeft()
        decks().map { summaryFor(it, now, quota) }
    }

    suspend fun deckSummary(deckId: Long): DeckSummary? = io {
        decks().firstOrNull { it.id == deckId }?.let { summaryFor(it, System.currentTimeMillis(), newQuotaLeft()) }
    }

    private fun summaryFor(deck: Deck, now: Long, quota: Int): DeckSummary {
        val levels = IntArray(Srs.MAX_LEVEL + 1)
        var due = 0; var newCount = 0; var hard = 0
        db.rawQuery("SELECT level, next_review, reps, lapses FROM cards WHERE deck_id=?", arrayOf(deck.id.toString())).use { c ->
            while (c.moveToNext()) {
                val lvl = c.getInt(0).coerceIn(0, Srs.MAX_LEVEL)
                levels[lvl]++
                if (c.getInt(2) == 0) newCount++ else if (c.getLong(1) <= now) due++
                if (c.getInt(3) >= Srs.HARD_LAPSES) hard++
            }
        }
        val total = levels.sum()
        return DeckSummary(deck, total, due, newCount, minOf(newCount, quota), total - levels[0], hard, levels)
    }

    private fun decks(): List<Deck> =
        db.rawQuery("SELECT id, name, front_lang, back_lang, uuid FROM decks ORDER BY created", null).use { c ->
            buildList {
                while (c.moveToNext()) add(Deck(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
            }
        }

    suspend fun allDecks(): List<Deck> = io { decks() }

    suspend fun createDeck(name: String, uuid: String = UUID.randomUUID().toString(),
                           frontLang: String = "sk-SK", backLang: String = "uk-UA"): Long = io {
        val id = insertDeck(name, uuid, frontLang, backLang)
        changed(); id
    }

    private fun insertDeck(name: String, uuid: String, frontLang: String, backLang: String): Long =
        db.insertOrThrow("decks", null, ContentValues().apply {
            put("uuid", uuid); put("name", name)
            put("front_lang", frontLang); put("back_lang", backLang)
            put("created", System.currentTimeMillis())
        })

    suspend fun renameDeck(id: Long, name: String) = io {
        db.update("decks", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    suspend fun deleteDeck(id: Long) = io {
        db.delete("decks", "id=?", arrayOf(id.toString()))
        changed()
    }

    suspend fun resetProgress(deckId: Long) = io {
        db.update("cards", ContentValues().apply {
            put("level", 0); put("next_review", 0); put("reps", 0); put("lapses", 0)
        }, "deck_id=?", arrayOf(deckId.toString()))
        changed()
    }

    // ---------- cards ----------

    private val cardCols = "id, deck_id, front, back, memo, level, next_review, uuid, reps, lapses, tags, gender"

    suspend fun cards(deckId: Long): List<Card> = io {
        db.rawQuery("SELECT $cardCols FROM cards WHERE deck_id=? ORDER BY position, id", arrayOf(deckId.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.toCard()) } }
    }

    private fun Cursor.toCard() = Card(
        getLong(0), getLong(1), getString(2), getString(3), getString(4) ?: "",
        getInt(5), getLong(6), getString(7), getInt(8), getInt(9), getString(10) ?: "", getString(11) ?: "",
    )

    suspend fun addCard(deckId: Long, front: String, back: String, memo: String, tags: String, gender: String) = io {
        val pos = db.rawQuery("SELECT COALESCE(MAX(position),0)+1 FROM cards WHERE deck_id=?", arrayOf(deckId.toString()))
            .use { it.moveToFirst(); it.getInt(0) }
        insertCard(deckId, ImportedCard(UUID.randomUUID().toString(), front, back, memo, 0, 0, pos,
            tags = normalizeTags(tags), gender = gender))
        changed()
    }

    private fun insertCard(deckId: Long, c: ImportedCard): Long =
        db.insertOrThrow("cards", null, ContentValues().apply {
            put("uuid", c.uuid); put("deck_id", deckId)
            put("front", c.front); put("back", c.back); put("memo", c.memo)
            put("level", c.level.coerceIn(0, Srs.MAX_LEVEL)); put("next_review", c.nextReview)
            put("position", c.position); put("created", System.currentTimeMillis())
            put("reps", c.reps); put("lapses", c.lapses); put("tags", c.tags); put("gender", c.gender)
        })

    suspend fun updateCard(card: Card, front: String, back: String, memo: String, tags: String, gender: String) = io {
        db.update("cards", ContentValues().apply {
            put("front", front); put("back", back); put("memo", memo)
            put("tags", normalizeTags(tags)); put("gender", gender)
            // a rephrased "hard" card gets a fresh start
            if (front != card.front || back != card.back) put("lapses", 0)
        }, "id=?", arrayOf(card.id.toString()))
        changed()
    }

    suspend fun deleteCards(ids: Collection<Long>) = io {
        db.beginTransaction()
        try {
            ids.forEach { db.delete("cards", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    suspend fun addTag(cards: Collection<Card>, tag: String) = io {
        db.beginTransaction()
        try {
            cards.forEach { c ->
                db.update("cards", ContentValues().apply { put("tags", normalizeTags(c.tags + "," + tag)) },
                    "id=?", arrayOf(c.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    suspend fun removeTag(cards: Collection<Card>, tag: String) = io {
        db.beginTransaction()
        try {
            cards.forEach { c ->
                db.update("cards", ContentValues().apply {
                    put("tags", normalizeTags(c.tagList.filter { !it.equals(tag, true) }.joinToString(",")))
                }, "id=?", arrayOf(c.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    private fun normalizeTags(s: String) =
        s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    // ---------- answers ----------

    /** Records an answer: new level, next review date and a row in the answer log. */
    suspend fun recordAnswer(card: Card, grade: Grade): Card = io {
        val updated = applyAnswer(card, grade)
        changed()
        updated
    }

    /** "I was right": undoes the last answer for the card and records it as correct. */
    suspend fun overrideAnswer(original: Card, grade: Grade = Grade.GOOD) = io {
        db.execSQL("DELETE FROM reviews WHERE id = (SELECT MAX(id) FROM reviews WHERE card_id=?)", arrayOf(original.id))
        applyAnswer(original, grade)
        changed()
    }

    private fun applyAnswer(card: Card, grade: Grade): Card {
        val now = System.currentTimeMillis()
        val s = Srs.schedule(card, grade, now)
        val updated = card.copy(level = s.level, nextReview = s.nextReview, reps = card.reps + 1,
            lapses = card.lapses + if (s.lapsed) 1 else 0)
        db.beginTransaction()
        try {
            db.update("cards", ContentValues().apply {
                put("level", updated.level); put("next_review", updated.nextReview)
                put("reps", updated.reps); put("lapses", updated.lapses)
            }, "id=?", arrayOf(card.id.toString()))
            db.insertOrThrow("reviews", null, ContentValues().apply {
                put("card_id", card.id); put("ts", now); put("day", Day.of(now))
                put("grade", grade.ordinal); put("correct", if (grade == Grade.AGAIN) 0 else 1)
                put("was_new", if (card.isNew) 1 else 0)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return updated
    }

    /** How many new cards can still be started today (the limit is shared by all decks). */
    suspend fun newQuota(): Int = io { newQuotaLeft() }

    private fun newQuotaLeft(): Int {
        val started = db.rawQuery("SELECT COUNT(*) FROM reviews WHERE day=? AND was_new=1", arrayOf(Day.today()))
            .use { it.moveToFirst(); it.getInt(0) }
        return (settings.current.newPerDay - started).coerceAtLeast(0)
    }

    // ---------- statistics ----------

    data class Today(val answers: Int, val goal: Int, val streak: Int, val reviewsDue: Int, val newAvailable: Int)

    suspend fun today(): Today = io {
        val goal = settings.current.dailyGoal
        val answers = answersOn(Day.today())
        val (streak, _) = streaks(goal)
        val now = System.currentTimeMillis()
        val due = db.rawQuery("SELECT COUNT(*) FROM cards WHERE reps>0 AND next_review<=?", arrayOf(now.toString()))
            .use { it.moveToFirst(); it.getInt(0) }
        val newCount = db.rawQuery("SELECT COUNT(*) FROM cards WHERE reps=0", null).use { it.moveToFirst(); it.getInt(0) }
        Today(answers, goal, streak, due, minOf(newCount, newQuotaLeft()))
    }

    private fun answersOn(day: String) =
        db.rawQuery("SELECT COUNT(*) FROM reviews WHERE day=?", arrayOf(day)).use { it.moveToFirst(); it.getInt(0) }

    /** Current and best streak of days on which the daily goal was reached. */
    private fun streaks(goal: Int): Pair<Int, Int> {
        // rawQuery binds arguments as text, and in SQLite an integer is always less than text,
        // so the bound goal must be cast back to an integer for the comparison to work
        val days = db.rawQuery("SELECT day FROM reviews GROUP BY day HAVING COUNT(*) >= CAST(? AS INTEGER)", arrayOf(goal.toString()))
            .use { c -> buildSet { while (c.moveToNext()) add(LocalDate.parse(c.getString(0))) } }
        val today = LocalDate.now()
        // if today's goal isn't reached yet, the streak stays alive until the day ends
        var d = if (today in days) today else today.minusDays(1)
        var current = 0
        while (d in days) { current++; d = d.minusDays(1) }
        var best = 0; var run = 0; var prev: LocalDate? = null
        for (day in days.sorted()) {
            run = if (prev != null && prev.plusDays(1) == day) run + 1 else 1
            best = maxOf(best, run); prev = day
        }
        return current to best
    }

    suspend fun stats(): Stats = io {
        val s = settings.current
        val today = LocalDate.now()
        val from = today.minusDays(29)
        val byDay = db.rawQuery(
            "SELECT day, COUNT(*), SUM(correct) FROM reviews WHERE day >= ? GROUP BY day", arrayOf(from.toString())
        ).use { c -> buildMap { while (c.moveToNext()) put(LocalDate.parse(c.getString(0)), c.getInt(1) to c.getInt(2)) } }
        val last30 = (0..29).map { i ->
            val d = from.plusDays(i.toLong()); val (a, ok) = byDay[d] ?: (0 to 0); DayStat(d, a, ok)
        }
        fun acc(days: Int): Int? {
            val slice = last30.takeLast(days); val a = slice.sumOf { it.answers }
            return if (a == 0) null else slice.sumOf { it.correct } * 100 / a
        }
        val forecast = IntArray(7)
        val zone = java.time.ZoneId.systemDefault()
        db.rawQuery("SELECT next_review FROM cards WHERE reps > 0", null).use { c ->
            while (c.moveToNext()) {
                val d = java.time.Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate()
                val idx = java.time.temporal.ChronoUnit.DAYS.between(today, d).coerceAtLeast(0)
                if (idx < 7) forecast[idx.toInt()]++
            }
        }
        val (total, learned, mastered) = db.rawQuery(
            "SELECT COUNT(*), SUM(level > 0), SUM(level >= 5) FROM cards", null
        ).use { it.moveToFirst(); Triple(it.getInt(0), it.getInt(1), it.getInt(2)) }
        val hardest = db.rawQuery("SELECT $cardCols FROM cards WHERE lapses > 0 ORDER BY lapses DESC, level LIMIT 15", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toCard()) } }
        val (streak, best) = streaks(s.dailyGoal)
        Stats(
            streak, best, answersOn(today.toString()), s.dailyGoal,
            s.newPerDay - newQuotaLeft(), s.newPerDay,
            last30, forecast.toList(), acc(7), acc(30), total, learned, mastered, hardest,
        )
    }

    // ---------- import / export ----------

    data class ImportedReview(val ts: Long, val grade: Int, val correct: Boolean, val wasNew: Boolean)

    data class ImportedCard(
        val uuid: String, val front: String, val back: String, val memo: String,
        val level: Int, val nextReview: Long, val position: Int,
        val reps: Int = if (level > 0) 1 else 0, val lapses: Int = 0,
        val tags: String = "", val gender: String = "",
        val reviews: List<ImportedReview> = emptyList(),
    )

    data class ImportedDeck(val uuid: String, val name: String, val frontLang: String, val backLang: String,
                            val cards: List<ImportedCard>)

    data class ImportResult(val decksCreated: Int, val decksMerged: Int, val cardsAdded: Int, val cardsUpdated: Int)

    /**
     * Merging import: decks and cards are matched by uuid, so importing the same
     * backup again creates no duplicates. Existing cards get the imported text, and
     * the higher of the two progress values is kept (so progress made here isn't lost).
     */
    suspend fun importDecks(decks: List<ImportedDeck>): ImportResult = io {
        var created = 0; var merged = 0; var added = 0; var updated = 0
        db.beginTransaction()
        try {
            for (d in decks) {
                val existingId = db.rawQuery("SELECT id FROM decks WHERE uuid=?", arrayOf(d.uuid))
                    .use { if (it.moveToFirst()) it.getLong(0) else null }
                val deckId = existingId ?: insertDeck(d.name, d.uuid, d.frontLang, d.backLang)
                if (existingId == null) created++ else merged++
                for (c in d.cards) {
                    val local = db.rawQuery("SELECT $cardCols FROM cards WHERE deck_id=? AND uuid=?",
                        arrayOf(deckId.toString(), c.uuid)).use { if (it.moveToFirst()) it.toCard() else null }
                    val cardId = if (local == null) {
                        added++
                        insertCard(deckId, c)
                    } else {
                        val keepLocal = local.level > c.level || (local.level == c.level && local.reps >= c.reps)
                        db.update("cards", ContentValues().apply {
                            put("front", c.front); put("back", c.back); put("memo", c.memo)
                            if (c.tags.isNotEmpty()) put("tags", normalizeTags(local.tags + "," + c.tags))
                            if (c.gender.isNotEmpty()) put("gender", c.gender)
                            if (!keepLocal) {
                                put("level", c.level.coerceIn(0, Srs.MAX_LEVEL)); put("next_review", c.nextReview)
                            }
                            put("reps", maxOf(local.reps, c.reps)); put("lapses", maxOf(local.lapses, c.lapses))
                        }, "id=?", arrayOf(local.id.toString()))
                        updated++
                        local.id
                    }
                    for (r in c.reviews) {
                        val exists = db.rawQuery("SELECT 1 FROM reviews WHERE card_id=? AND ts=?",
                            arrayOf(cardId.toString(), r.ts.toString())).use { it.moveToFirst() }
                        if (!exists) db.insertOrThrow("reviews", null, ContentValues().apply {
                            put("card_id", cardId); put("ts", r.ts); put("day", Day.of(r.ts))
                            put("grade", r.grade); put("correct", if (r.correct) 1 else 0); put("was_new", if (r.wasNew) 1 else 0)
                        })
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        ImportResult(created, merged, added, updated)
    }

    suspend fun exportAll(): List<ImportedDeck> = io {
        val reviews = mutableMapOf<Long, MutableList<ImportedReview>>()
        db.rawQuery("SELECT card_id, ts, grade, correct, was_new FROM reviews ORDER BY ts", null).use { c ->
            while (c.moveToNext()) reviews.getOrPut(c.getLong(0)) { mutableListOf() }
                .add(ImportedReview(c.getLong(1), c.getInt(2), c.getInt(3) == 1, c.getInt(4) == 1))
        }
        decks().map { d ->
            val cards = db.rawQuery(
                "SELECT $cardCols, position FROM cards WHERE deck_id=? ORDER BY position, id", arrayOf(d.id.toString())
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val card = c.toCard()
                        add(ImportedCard(card.uuid, card.front, card.back, card.memo, card.level, card.nextReview,
                            c.getInt(12), card.reps, card.lapses, card.tags, card.gender, reviews[card.id].orEmpty()))
                    }
                }
            }
            ImportedDeck(d.uuid, d.name, d.frontLang, d.backLang, cards)
        }
    }

    suspend fun hasCards(): Boolean = io {
        db.rawQuery("SELECT EXISTS(SELECT 1 FROM cards)", null).use { it.moveToFirst(); it.getInt(0) == 1 }
    }

    /** Closes the database (used by tests that open their own repositories). */
    fun close() = helper.close()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val DB_NAME = "flashcards.db"
        @Volatile private var instance: Repository? = null

        /** A separate repository on its own database file — for tests, never touches the app's data. */
        @androidx.annotation.VisibleForTesting
        fun openSeparate(context: Context, dbName: String): Repository {
            require(dbName != DB_NAME) { "Tests must not use the app database" }
            return Repository(context, dbName)
        }

        fun get(context: Context): Repository =
            instance ?: synchronized(this) { instance ?: Repository(context).also { instance = it } }
    }
}
