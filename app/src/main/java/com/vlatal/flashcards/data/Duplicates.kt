package com.vlatal.flashcards.data

/**
 * Finds existing cards while a card is being added or edited.
 * Comparison ignores case, punctuation, extra spaces and diacritics
 * ("byvat" finds "bývať").
 */
object Duplicates {

    data class Result(
        val sameWord: List<Card>,        // the same Slovak word
        val similarWord: List<Card>,     // one letter off, or part of a phrase ("mať" / "mať sa")
        val sameTranslation: List<Card>, // another word has the same translation variant
    ) {
        val isEmpty get() = sameWord.isEmpty() && similarWord.isEmpty() && sameTranslation.isEmpty()
    }

    private const val MAX_SIMILAR = 3

    fun key(s: String): String = TextUtil.stripDiacritics(TextUtil.normalize(s))

    private fun translationKeys(s: String): Set<String> =
        TextUtil.variants(s).map { TextUtil.stripDiacritics(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * @param exceptId the card being edited (it is not a duplicate of itself).
     */
    fun find(front: String, back: String, cards: List<Card>, exceptId: Long? = null): Result {
        val others = cards.filter { it.id != exceptId }
        val f = key(front)
        val same = if (f.isEmpty()) emptyList() else others.filter { key(it.front) == f }
        val similar = if (f.length < 3) emptyList() else others.asSequence()
            .filter { it !in same }
            .filter { isSimilar(f, key(it.front)) }
            .take(MAX_SIMILAR).toList()

        val b = translationKeys(back)
        val sameTr = if (b.isEmpty()) emptyList() else others
            .filter { it !in same }
            .filter { c -> translationKeys(c.back).any { it in b } }
        return Result(same, similar, sameTr)
    }

    private fun isSimilar(a: String, b: String): Boolean {
        if (a == b || b.isEmpty()) return false
        // a single typo in a long enough word
        if (minOf(a.length, b.length) >= 4 && TextUtil.levenshtein(a, b) <= 1) return true
        // one is part of the other as whole words: "mať" ⊂ "mať sa"
        val wa = a.split(' ').toSet(); val wb = b.split(' ').toSet()
        return (wa.size != wb.size) && (wb.containsAll(wa) || wa.containsAll(wb))
    }
}
