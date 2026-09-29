package hk.eduhk.typeduck.ime.text

import android.graphics.Rect

/**
 * Data class describing a computed candidate item.
 *
 * @property geometry The geometry of the computed candidate, used to position and size the item correctly when
 *  being drawn on a canvas.
 */
sealed class ComputedCandidate(var geometry: Rect) {
    /**
     * Computed word candidate, used for suggestions provided by the librime backend.
     *
     * @property word The word this computed candidate item represents. Used in the callback to provide which word
     *  should be filled out.
     */
    class Word(
        val word: String,
        val comment: String,
        geometry: Rect = Rect()
    ) : ComputedCandidate(geometry) {

        val isReverseLookup: Boolean
        val note: String
        val entries: List<CandidateEntry>

        val entry: CandidateEntry?
        val hasDictionaryEntry: Boolean
        val romanization: String
        val definition: String?

        init {
            val comment = Comment(comment)
            // I don't know why, but Kotlin only supports \r, \n, \t and \b
            isReverseLookup = comment.consume('\u000b' /* \v */)
            note = comment.consumeUntil('\u000c' /* \f */)
            entries = if (comment.isNotEmpty()) {
                if (comment.consume('\r'))
                    comment.toString().split('\r').map { CandidateEntry(/* csv: */ it) }
                else
                    comment.toString().split('\u000c').map { CandidateEntry(honzi = word, jyutping = it.removeSuffix("; ")) }
            } else
                listOf()

            val matchedEntry = entries.firstOrNull { it.matchInputBuffer == "1" }
            val dictionaryEntries = entries.filter { it.isDictionaryEntry }
            val exactDictionaryEntry =
                dictionaryEntries.firstOrNull { it.honzi == word && it.matchInputBuffer == "1" }
                    ?: dictionaryEntries.firstOrNull { it.honzi == word }
            val matchedDictionaryEntry =
                dictionaryEntries.firstOrNull { it.matchInputBuffer == "1" }

            // The dictionary lookup filter emits a synthetic matched "composition" row for
            // sentence candidates, followed by the real dictionary rows for their components.
            // Prefer real dictionary metadata for display instead of letting that synthetic row
            // hide the English definition.
            entry = exactDictionaryEntry
                ?: matchedDictionaryEntry
                ?: dictionaryEntries.firstOrNull()
                ?: matchedEntry
                ?: entries.firstOrNull()
            hasDictionaryEntry = dictionaryEntries.isNotEmpty()
            romanization = matchedEntry?.jyutping
                ?: entry?.jyutping
                ?: (if (isReverseLookup) "" else note)

            val exactDefinition = exactDictionaryEntry?.englishDefinition
                ?: exactDictionaryEntry?.mainLanguageOrLabel
            if (!exactDefinition.isNullOrEmpty()) {
                definition = exactDefinition
            } else if (dictionaryEntries.isNotEmpty()) {
                // For Rime-composed phrases that do not have one CEDICT row, show a concise
                // component gloss rather than the meaningless "(composition)" marker.
                definition = dictionaryEntries
                    .groupBy { it.honzi.orEmpty() }
                    .values
                    .mapNotNull { group ->
                        group.firstOrNull { it.matchInputBuffer == "1" && !it.englishDefinition.isNullOrEmpty() }
                            ?.englishDefinition
                            ?: group.firstNotNullOfOrNull { it.englishDefinition ?: it.mainLanguageOrLabel }
                    }
                    .distinct()
                    .take(2)
                    .joinToString(" · ")
                    .ifEmpty { null }
            } else {
                definition = entry?.mainLanguageOrLabel
            }
        }

        override fun toString(): String {
            return "Word { word=\"$word\", comment=\"$comment\", geometry=$geometry }"
        }
    }

    /**
     * Computed word candidate, used for clipboard paste suggestions.
     *
     * @property arrow The page button text this computed candidate item represents. Used in the callback to
     *  provide which page button should be filled out.
     */
    class Symbol(
        val arrow: String,
        geometry: Rect
    ) : ComputedCandidate(geometry) {
        override fun toString(): String {
            return "Symbol { arrow=$arrow, geometry=$geometry }"
        }
    }
}

private class Comment(private val comment: String) {
    private val length = comment.length
    private var i = 0
    fun isNotEmpty() = i < length
    fun consume(char: Char) = (isNotEmpty() && comment[i] == char).also { if (it) i++ }
    fun consumeUntil(char: Char): String {
        val start = i
        while (isNotEmpty())
            if (comment[i] == char) return comment.substring(start, i++)
            else i++
        return comment.substring(start, i)
    }
    override fun toString() = comment.substring(i)
}