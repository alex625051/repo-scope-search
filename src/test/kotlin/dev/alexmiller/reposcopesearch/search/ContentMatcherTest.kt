package dev.alexmiller.reposcopesearch.search

import dev.alexmiller.reposcopesearch.model.SearchMode
import dev.alexmiller.reposcopesearch.model.SearchOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentMatcherTest {
    private fun options(
        query: String,
        mode: SearchMode = SearchMode.TEXT,
        caseSensitive: Boolean = false,
        multiline: Boolean = false,
        fuzzy: Boolean = false
    ) = SearchOptions(query, mode, caseSensitive, multiline, fuzzy, 0.80, 1, 10_000, 100)

    @Test
    fun `literal search returns context and line`() {
        val result = ContentMatcher.find("zero\nHello world\ntwo", options("hello")).single()
        assertEquals(2, result.line)
        assertTrue(result.snippet.contains("> 2 | Hello world"))
    }

    @Test
    fun `multiline regexp spans lines`() {
        val result = ContentMatcher.find("begin\nalpha\nbeta\nend", options("alpha.*beta", SearchMode.REGEXP, multiline = true)).single()
        assertEquals(2, result.line)
        assertEquals(3, result.endLine)
    }

    @Test
    fun `fuzzy similarity tolerates typo`() {
        assertTrue(ContentMatcher.similarity("repository", "repositary") >= 0.8)
    }
}
