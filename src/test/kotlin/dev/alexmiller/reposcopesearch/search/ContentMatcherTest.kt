package dev.alexananev.reposcopesearch.search
import dev.alexananev.reposcopesearch.model.*
import kotlin.test.*
class ContentMatcherTest {
    private fun options(q: String, mode: QueryMode = QueryMode.TEXT) = SearchOptions(q, mode, SearchBackend.LOCAL, false, false, false, .8, null, true, 1, 1024, 10, 1)
    @Test fun textContext() { val h = ContentMatcher.find("a\nHello World\nc", options("hello")).single(); assertEquals(2, h.startLine); assertTrue("1: a" in h.snippet) }
    @Test fun regex() { assertEquals(1, ContentMatcher.find("abc-123", options("[a-z]+-\\d+", QueryMode.REGEX)).size) }
}
