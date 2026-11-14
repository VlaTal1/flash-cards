package com.vlatal.flashcards

import com.vlatal.flashcards.data.Card
import com.vlatal.flashcards.data.Grade
import com.vlatal.flashcards.data.Schedule
import com.vlatal.flashcards.data.Srs
import com.vlatal.flashcards.data.TextUtil
import com.vlatal.flashcards.data.TextUtil.Verdict.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test fun exactAndVariants() {
        assertEquals(CORRECT, TextUtil.check("жити", "жити, мешкати"))
        assertEquals(CORRECT, TextUtil.check(" Мешкати ", "жити, мешкати"))
        assertEquals(CORRECT, TextUtil.check("bývať", "bývať"))
        assertEquals(CORRECT, TextUtil.check("zo", "z, zo"))
    }

    @Test fun diacriticsAndTypos() {
        assertEquals(ALMOST, TextUtil.check("byvat", "bývať"))
        assertEquals(ALMOST, TextUtil.check("dovidena", "dovidenia, dovi"))
        assertEquals(WRONG, TextUtil.check("písať", "čítať"))
        assertEquals(WRONG, TextUtil.check("", "mať"))
    }

    @Test fun decomposedUnicodeIsCorrect() {
        val nfd = java.text.Normalizer.normalize("bývať", java.text.Normalizer.Form.NFD)
        assertEquals(CORRECT, TextUtil.check(nfd, "bývať"))
    }

    private fun card(level: Int, next: Long, reps: Int = 1) = Card(1, 1, "a", "b", "", level, next, "u", reps = reps)
    private val now = 1_000_000_000_000L
    private val rnd = kotlin.random.Random(1)

    @Test fun srsGood() {
        // a new card → level 1, due in a day
        val s = Srs.schedule(card(0, 0, reps = 0), Grade.GOOD, now, rnd)
        assertEquals(1, s.level); assertEquals(now + Srs.DAY, s.nextReview)
        // an overdue card → level goes up
        assertEquals(4, Srs.schedule(card(3, now - 1), Grade.GOOD, now, rnd).level)
        // not due yet → nothing changes
        assertEquals(Schedule(3, now + 5, false), Srs.schedule(card(3, now + 5), Grade.GOOD, now, rnd))
        // ceiling
        assertEquals(Srs.MAX_LEVEL, Srs.schedule(card(Srs.MAX_LEVEL, 0), Grade.EASY, now, rnd).level)
    }

    @Test fun srsAgainHardEasy() {
        val again = Srs.schedule(card(5, now - 1), Grade.AGAIN, now, rnd)
        assertEquals(3, again.level); assertEquals(now + Srs.RELEARN_DELAY, again.nextReview); assertEquals(true, again.lapsed)
        // a mistake on a new card is not a lapse
        assertEquals(false, Srs.schedule(card(0, 0, reps = 0), Grade.AGAIN, now, rnd).lapsed)
        val hard = Srs.schedule(card(3, now - 1), Grade.HARD, now, rnd)
        assertEquals(3, hard.level); assertEquals(now + 2 * Srs.DAY, hard.nextReview)
        assertEquals(5, Srs.schedule(card(3, now - 1), Grade.EASY, now, rnd).level)
    }

    @Test fun relearnedCardIsDueLater() {
        val c = card(0, now + Srs.RELEARN_DELAY, reps = 3)
        assertEquals(false, c.isDue(now))
        assertEquals(true, c.isDue(now + Srs.RELEARN_DELAY))
    }
}

class DuplicatesTest {
    private fun c(id: Long, f: String, b: String) = com.vlatal.flashcards.data.Card(id, 1, f, b, "", 0, 0, "u$id")
    private val cards = listOf(
        c(1, "bývať", "жити, мешкати"), c(2, "mať sa", "почуватися"), c(3, "káva", "кава"), c(4, "mať", "мати"),
    )
    private fun find(f: String, b: String, except: Long? = null) =
        com.vlatal.flashcards.data.Duplicates.find(f, b, cards, except)

    @Test fun sameWordIgnoresDiacriticsAndCase() {
        assertEquals(listOf(1L), find("Byvat", "").sameWord.map { it.id })
        assertEquals(listOf(3L), find(" káva ", "").sameWord.map { it.id })
    }

    @Test fun editingCardIsNotItsOwnDuplicate() {
        assertEquals(true, find("bývať", "жити", except = 1).isEmpty)
    }

    @Test fun similarWords() {
        assertEquals(listOf(2L), find("mať", "").similarWord.map { it.id })   // part of a phrase
        assertEquals(listOf(1L), find("bývat", "").sameWord.map { it.id })
        assertEquals(listOf(3L), find("kávy", "").similarWord.map { it.id })  // one letter off
    }

    @Test fun sameTranslationByVariant() {
        assertEquals(listOf(1L), find("bývať si", "мешкати").sameTranslation.map { it.id })
        assertEquals(listOf(3L), find("kávička", "Кава").sameTranslation.map { it.id })
        assertEquals(true, find("pivo", "пиво").isEmpty)
    }
}
