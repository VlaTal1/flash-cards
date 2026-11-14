package com.vlatal.flashcards.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vlatal.flashcards.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Weekly auto-backup to "Downloads/Karticky" (Android 10+, no permissions needed).
 * The last [KEEP] files are kept. Files survive uninstalling the app.
 */
object AutoBackup {
    private const val INTERVAL = 7 * Srs.DAY
    private const val KEEP = 5
    private const val PREFIX = "karticky-auto-"
    val folder = Environment.DIRECTORY_DOWNLOADS + "/Karticky"

    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun runIfDue(context: Context) {
        val settings = Settings.get(context)
        val s = settings.current
        if (!supported || !s.autoBackup) return
        if (System.currentTimeMillis() - s.lastAutoBackup < INTERVAL) return
        runCatching { runNow(context) }
    }

    /** Returns the name of the created file. */
    suspend fun runNow(context: Context): String = withContext(Dispatchers.IO) {
        val res = Locales.wrap(context)
        check(supported) { res.getString(R.string.err_backup_unsupported) }
        val repo = Repository.get(context)
        check(repo.hasCards()) { res.getString(R.string.err_no_cards) }
        val json = BackupIO.toJson(repo.exportAll())
        val name = PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".json"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error(res.getString(R.string.err_create_file))
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error(res.getString(R.string.err_write_file))
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        Settings.get(context).update { copy(lastAutoBackup = System.currentTimeMillis()) }
        cleanup(context)
        name
    }

    /** Deletes old auto-backups (only files created by this app are visible to it). */
    private fun cleanup(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ids = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$PREFIX%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
        ids.drop(KEEP).forEach { runCatching { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) } }
    }
}
