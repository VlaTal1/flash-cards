package com.vlatal.flashcards.data

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vlatal.flashcards.R
import java.text.Normalizer
import java.util.Locale

object TextUtil {
    private val diacritics = "\\p{Mn}+".toRegex()
    private val junk = "[\\p{Punct}«»„“”‚‘’…–—]".toRegex()
    private val spaces = "\\s+".toRegex()

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase().replace(junk, " ").replace(spaces, " ").trim()

    fun stripDiacritics(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "")

    /** Answer variants: "live, reside" → ["live", "reside", "live reside"]. */
    fun variants(answer: String): List<String> {
        val parts = answer.split(',', ';', '/', '\n').map { normalize(it.replace("\\(.*?\\)".toRegex(), "")) }
        return (parts + normalize(answer) + normalize(answer.replace("\\(.*?\\)".toRegex(), "")))
            .filter { it.isNotEmpty() }.distinct()
    }

    enum class Verdict { CORRECT, ALMOST, WRONG }

    fun check(input: String, answer: String): Verdict {
        val given = normalize(input)
        if (given.isEmpty()) return Verdict.WRONG
        val vs = variants(answer)
        if (vs.any { it == given }) return Verdict.CORRECT
        val g = stripDiacritics(given)
        if (vs.any { stripDiacritics(it) == g }) return Verdict.ALMOST
        // a single typo in a long word counts as "almost"
        if (vs.any { it.length >= 5 && levenshtein(stripDiacritics(it), g) <= 1 }) return Verdict.ALMOST
        return Verdict.WRONG
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

/** Pronunciation via the system TTS engine (works offline when the voice is installed). */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private val warned = mutableSetOf<String>()
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    fun speak(text: String, langTag: String) {
        if (!ready || text.isBlank()) return
        val locale = Locale.forLanguageTag(langTag)
        // no voice for the language: stay silent rather than read Slovak with a foreign accent
        if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            if (warned.add(langTag)) {
                val res = Locales.wrap(appContext)
                android.widget.Toast.makeText(appContext,
                    res.getString(R.string.tts_no_voice, locale.getDisplayLanguage(Locales.current(appContext))),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        tts.language = locale
        tts.setSpeechRate(0.9f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fc")
    }

    /** Whether a voice for the language is installed; null while the engine is still starting. */
    fun hasVoice(langTag: String): Boolean? =
        if (!ready) null else tts.isLanguageAvailable(Locale.forLanguageTag(langTag)) >= TextToSpeech.LANG_AVAILABLE

    fun shutdown() = tts.shutdown()
}
