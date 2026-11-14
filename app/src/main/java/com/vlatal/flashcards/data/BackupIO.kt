package com.vlatal.flashcards.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.vlatal.flashcards.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Import:
 *  - .zip backup of the "Flashcards Maker" app (CardsBackup/<N>/share.db — one deck per folder);
 *  - .json backup of this app.
 * Export: .json with all decks, progress and the answer log.
 */
object BackupIO {

    private const val FORMAT = "kartičky-backup"

    suspend fun import(context: Context, uri: Uri): List<Repository.ImportedDeck> = withContext(Dispatchers.IO) {
        val res = Locales.wrap(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error(res.getString(R.string.err_open_file))
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) importFlashcardsMakerZip(context, res, bytes) else importJson(res, String(bytes, Charsets.UTF_8))
    }

    // ---------- Flashcards Maker ----------

    private fun importFlashcardsMakerZip(context: Context, res: Context, bytes: ByteArray): List<Repository.ImportedDeck> {
        val tmpDir = File(context.cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Flashcards Maker writes zero sizes into the local zip headers, so ZipInputStream
            // fails on its archives — read through the central directory (ZipFile) instead.
            // Only share.db files are extracted, under names we choose (no archive paths are used).
            val zipFile = File(tmpDir, "backup.zip").apply { writeBytes(bytes) }
            val dbFiles = sortedMapOf<String, File>()
            ZipFile(zipFile).use { zip ->
                var idx = 0
                for (e in zip.entries()) {
                    if (!e.isDirectory && e.name.substringAfterLast('/') == "share.db") {
                        val folder = e.name.substringBeforeLast('/', "").substringAfterLast('/')
                        val out = File(tmpDir, "deck_${idx++}.db")
                        zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                        dbFiles[folder.padStart(6, '0') + "_" + idx] = out
                    }
                }
            }
            if (dbFiles.isEmpty()) error(res.getString(R.string.err_no_decks_in_zip))
            return dbFiles.values.mapIndexedNotNull { i, f -> readShareDb(f, i, res.getString(R.string.default_deck_name, i + 1)) }
                .ifEmpty { error(res.getString(R.string.err_empty_decks)) }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun readShareDb(file: File, index: Int, defaultName: String): Repository.ImportedDeck? {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            if ("share_data" !in tables) return null

            var uuid = "fcm-${file.length()}-$index"
            var name = defaultName
            var frontLang = "sk-SK"
            var backLang = "uk-UA"
            if ("share_manage_flashcards" in tables) {
                db.rawQuery("SELECT id, title, front_lang, back_lang FROM share_manage_flashcards LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { uuid = "fcm-$it" }
                        c.getString(1)?.takeIf { it.isNotBlank() }?.let { name = it }
                        c.getString(2)?.takeIf { it.isNotBlank() }?.let { frontLang = it }
                        c.getString(3)?.takeIf { it.isNotBlank() }?.let { backLang = it }
                    }
                }
            }
            val cards = db.rawQuery(
                "SELECT id, front, back, memo, level, next_test_date, view_order FROM share_data ORDER BY view_order", null
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val front = c.getString(1)?.trim().orEmpty()
                        val back = c.getString(2)?.trim().orEmpty()
                        if (front.isEmpty() && back.isEmpty()) continue
                        val level = if (c.isNull(4)) 0 else c.getInt(4)
                        add(Repository.ImportedCard(
                            uuid = c.getString(0) ?: "row-${c.position}",
                            front = front, back = back,
                            memo = c.getString(3)?.trim().orEmpty(),
                            level = level,
                            nextReview = if (level == 0 || c.isNull(5)) 0 else c.getLong(5),
                            position = if (c.isNull(6)) c.position else c.getInt(6),
                        ))
                    }
                }
            }
            return Repository.ImportedDeck(uuid, name, frontLang, backLang, cards)
        }
    }

    // ---------- own format ----------

    private fun importJson(res: Context, text: String): List<Repository.ImportedDeck> {
        val root = try { JSONObject(text) } catch (e: Exception) {
            error(res.getString(R.string.err_unknown_format))
        }
        if (root.optString("format") != FORMAT) error(res.getString(R.string.err_not_our_backup))
        val decks = root.getJSONArray("decks")
        return (0 until decks.length()).map { i ->
            val d = decks.getJSONObject(i)
            val cards = d.getJSONArray("cards")
            Repository.ImportedDeck(
                uuid = d.getString("uuid"), name = d.getString("name"),
                frontLang = d.optString("frontLang", "sk-SK"), backLang = d.optString("backLang", "uk-UA"),
                cards = (0 until cards.length()).map { j ->
                    val c = cards.getJSONObject(j)
                    val level = c.optInt("level")
                    val reviews = c.optJSONArray("reviews")
                    Repository.ImportedCard(
                        uuid = c.getString("uuid"), front = c.getString("front"), back = c.getString("back"),
                        memo = c.optString("memo"), level = level, nextReview = c.optLong("nextReview"),
                        position = c.optInt("position", j),
                        reps = c.optInt("reps", if (level > 0) 1 else 0), lapses = c.optInt("lapses"),
                        tags = c.optString("tags"), gender = c.optString("gender"),
                        reviews = if (reviews == null) emptyList() else (0 until reviews.length()).map { k ->
                            val r = reviews.getJSONArray(k) // [ts, grade, correct, wasNew]
                            Repository.ImportedReview(r.getLong(0), r.getInt(1), r.getInt(2) == 1, r.getInt(3) == 1)
                        },
                    )
                }
            )
        }
    }

    fun toJson(decks: List<Repository.ImportedDeck>): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", 2)
        put("exported", System.currentTimeMillis())
        put("decks", JSONArray().apply {
            decks.forEach { d ->
                put(JSONObject().apply {
                    put("uuid", d.uuid); put("name", d.name)
                    put("frontLang", d.frontLang); put("backLang", d.backLang)
                    put("cards", JSONArray().apply {
                        d.cards.forEach { c ->
                            put(JSONObject().apply {
                                put("uuid", c.uuid); put("front", c.front); put("back", c.back)
                                put("memo", c.memo); put("level", c.level)
                                put("nextReview", c.nextReview); put("position", c.position)
                                put("reps", c.reps); put("lapses", c.lapses)
                                put("tags", c.tags); put("gender", c.gender)
                                put("reviews", JSONArray().apply {
                                    c.reviews.forEach { r ->
                                        put(JSONArray().put(r.ts).put(r.grade).put(if (r.correct) 1 else 0).put(if (r.wasNew) 1 else 0))
                                    }
                                })
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    suspend fun exportJson(context: Context, uri: Uri, decks: List<Repository.ImportedDeck>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(toJson(decks).toByteArray()) }
            ?: error(Locales.wrap(context).getString(R.string.err_write_file))
    }
}
