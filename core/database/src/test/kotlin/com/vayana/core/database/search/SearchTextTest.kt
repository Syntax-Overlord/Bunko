package com.vayana.core.database.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchTextTest {
    @Test
    fun firstMatchOffsetAgreesWithFullHighlightRangesIncludingUnicodeAndMissingMatches() {
        val fragments = listOf("Tolkien", "riverbank", "Mountains", "മലയാളം", "Alpha2", "Ωμέγα", "...", "42")
        val queries = listOf(emptyList(), listOf("tolk"), listOf("mount", "riv"), listOf("മല"),
            listOf("α", "ω"), listOf("absent"), listOf("t", "tolkien"), listOf("42", "alpha"))
        val random = kotlin.random.Random(42)
        for (text in listOf("", "...", "RIVER", "padding മലയാളം trailing") + List(200) {
            List(30) { fragments[random.nextInt(fragments.size)] }.joinToString(" ,\n")
        }) {
            for (tokens in queries) {
                assertEquals(wordPrefixMatchRanges(text, tokens).firstOrNull()?.first,
                    firstWordPrefixMatchOffset(text, tokens), "text=$text tokens=$tokens")
            }
        }
    }

    @Test
    fun longPastedQueriesKeepTheEightWordLimitAcrossPhrases() {
        val prefix = "one two three four five six seven eight"
        val trailing = " trailing".repeat(20_000)
        assertEquals(prefix.split(' '), searchTokens(prefix + trailing))
        assertEquals("one* two* three* four* five* six* seven* eight*",
            ftsSearchMatch(prefix + " \"unused phrase\"".repeat(1_000)))
        assertEquals("\"one two three four five six seven eight\"", ftsSearchMatch("\"$prefix$trailing\""))
    }

    @Test
    fun quotesBuildExactPhrasesAlongsideOrdinaryPrefixes() {
        assertEquals("\"river boat\" tolk*", ftsSearchMatch("\"River boat\" Tolk"))
        assertEquals("\"river boat\" \"moon light\"", ftsSearchMatch("\"river boat\" \"moon light\""))
        assertEquals("river* boat*", ftsSearchMatch("river boat"))
    }

    @Test
    fun unmatchedQuotesRemainSearchableWhileTyping() {
        assertEquals("river* boa*", ftsSearchMatch("\"river boa"))
        assertEquals("\"river boat\" moon*", ftsSearchMatch("\"river boat\" \"moon"))
    }

    @Test
    fun phraseSyntaxCannotInjectFtsOperatorsAndKeepsUnicode() {
        assertEquals("\"or near\" and*", ftsSearchMatch("\"OR NEAR*\" AND:"))
        assertEquals("\"രാമായണം 2\"", ftsSearchMatch("\"രാമായണം #2\""))
        assertNull(ftsSearchMatch("\"\" ,.-"))
    }

    @Test
    fun phrasesShareTheExistingEightWordBudget() {
        assertEquals("one* \"two three\" four* five* six* seven* eight*",
            ftsSearchMatch("one \"two three\" four five six seven eight nine"))
    }
    @Test
    fun tokensDropPunctuationAndFtsSyntax() {
        assertEquals(listOf("lord", "of", "the", "rings"), searchTokens("  \"Lord\" -of* the:Rings "))
    }

    @Test
    fun tokensKeepNonLatinLettersAndDigits() {
        assertEquals(listOf("രാമായണം", "2"), searchTokens("രാമായണം #2"))
    }

    @Test
    fun matchIsLowerCasePrefixPerToken() {
        assertEquals("tolk* or*", ftsPrefixMatch(searchTokens("Tolk OR")))
    }

    @Test
    fun blankQueryHasNoMatch() {
        assertNull(ftsPrefixMatch(searchTokens(" ,.- ")))
    }

    @Test
    fun highlightRangesCoverTheMatchedPrefixOfEachWord() {
        assertEquals(listOf(7..10, 17..20), wordPrefixMatchRanges("J.R.R. Tolkien's Rings", listOf("tolk", "ring")))
        assertEquals(listOf(0..6), wordPrefixMatchRanges("Tolkien", listOf("t", "tolkien", "tolkienish")))
        assertEquals(emptyList<IntRange>(), wordPrefixMatchRanges("The Hobbit", emptyList()))
    }

    @Test
    fun fieldMatchesOnWordPrefixOnly() {
        assertTrue("J.R.R. Tolkien".hasWordStartingWithAny(listOf("olkien", "tolk")))
        assertFalse("J.R.R. Tolkien".hasWordStartingWithAny(listOf("olkien")))
        assertFalse(null.hasWordStartingWithAny(listOf("tolk")))
    }
}
