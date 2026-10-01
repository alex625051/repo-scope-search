package dev.alexananev.reposcopesearch.search

import dev.alexananev.reposcopesearch.model.*
import kotlin.math.max

object ContentMatcher {
    data class Hit(val startLine: Int, val endLine: Int, val snippet: String, val similarity: Double?)

    fun find(text: String, options: SearchOptions): List<Hit> {
        if (options.query.isEmpty()) return emptyList()
        return when {
            options.fuzzy && options.mode == QueryMode.TEXT -> fuzzy(text, options)
            else -> exact(text, options)
        }
    }

    private fun exact(text: String, o: SearchOptions): List<Hit> {
        val pattern = if (o.mode == QueryMode.TEXT) Regex.escape(o.query) else o.query
        val flags = mutableSetOf<RegexOption>()
        if (!o.caseSensitive) flags += RegexOption.IGNORE_CASE
        if (o.multiline) { flags += RegexOption.MULTILINE; flags += RegexOption.DOT_MATCHES_ALL }
        val regex = Regex(pattern, flags)
        return regex.findAll(text).take(1000).map { m ->
            val start = lineAt(text, m.range.first); val end = lineAt(text, m.range.last + 1)
            Hit(start, end, context(text, start, end, o.contextLines), null)
        }.toList()
    }

    private fun fuzzy(text: String, o: SearchOptions): List<Hit> {
        val q = normalize(o.query, o.caseSensitive)
        if (q.isBlank()) return emptyList()
        return text.lineSequence().mapIndexedNotNull { index, line ->
            val candidate = normalize(line, o.caseSensitive)
            val score = bestWindowSimilarity(q, candidate)
            if (score >= o.fuzzyThreshold) Hit(index + 1, index + 1, context(text, index + 1, index + 1, o.contextLines), score) else null
        }.take(1000).toList()
    }

    private fun normalize(s: String, caseSensitive: Boolean): String = (if (caseSensitive) s else s.lowercase()).trim()
    private fun bestWindowSimilarity(q: String, line: String): Double {
        if (line.isEmpty()) return 0.0
        val widths = ((q.length * 0.7).toInt().coerceAtLeast(1)..(q.length * 1.3).toInt().coerceAtMost(line.length)).toList()
        if (widths.isEmpty()) return similarity(q, line)
        var best = 0.0
        for (w in widths) for (i in 0..line.length - w) best = max(best, similarity(q, line.substring(i, i + w)))
        return best
    }
    private fun similarity(a: String, b: String): Double = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length).coerceAtLeast(1)
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1); current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }
    private fun lineAt(text: String, offset: Int) = text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1
    private fun context(text: String, start: Int, end: Int, around: Int): String {
        val lines = text.lines(); val from = (start - around - 1).coerceAtLeast(0); val to = (end + around).coerceAtMost(lines.size)
        return (from until to).joinToString("\n") { i -> "${i + 1}: ${lines[i]}" }
    }
}
