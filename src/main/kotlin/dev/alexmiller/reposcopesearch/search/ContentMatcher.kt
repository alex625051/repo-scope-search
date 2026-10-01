package dev.alexmiller.reposcopesearch.search

import dev.alexmiller.reposcopesearch.model.RawMatch
import dev.alexmiller.reposcopesearch.model.SearchMode
import dev.alexmiller.reposcopesearch.model.SearchOptions
import kotlin.math.max

object ContentMatcher {
    fun validate(options: SearchOptions): String? {
        if (options.query.isEmpty()) return "Введите строку или регулярное выражение"
        if (options.fuzzy && options.mode == SearchMode.REGEXP) return "Неточный поиск применяется только в режиме «Текст»"
        if (options.mode == SearchMode.REGEXP) {
            return runCatching { compileRegex(options) }.exceptionOrNull()?.message
        }
        return null
    }

    fun find(text: String, options: SearchOptions): Sequence<RawMatch> = sequence {
        if (options.fuzzy) {
            yieldAll(findFuzzy(text, options))
            return@sequence
        }
        val regex = when (options.mode) {
            SearchMode.TEXT -> literalRegex(options)
            SearchMode.REGEXP -> compileRegex(options)
        }
        for (match in regex.findAll(text)) {
            val start = match.range.first.coerceAtLeast(0)
            val endExclusive = (match.range.last + 1).coerceAtLeast(start)
            yield(toRawMatch(text, start, endExclusive, match.value, options.contextLines))
        }
    }

    private fun literalRegex(options: SearchOptions): Regex {
        val flags = mutableSetOf<RegexOption>()
        if (!options.caseSensitive) flags += RegexOption.IGNORE_CASE
        if (options.multiline) flags += RegexOption.DOT_MATCHES_ALL
        return Regex(Regex.escape(options.query), flags)
    }

    private fun compileRegex(options: SearchOptions): Regex {
        val flags = mutableSetOf<RegexOption>()
        if (!options.caseSensitive) flags += RegexOption.IGNORE_CASE
        if (options.multiline) {
            flags += RegexOption.MULTILINE
            flags += RegexOption.DOT_MATCHES_ALL
        }
        return Regex(options.query, flags)
    }

    private fun findFuzzy(text: String, options: SearchOptions): Sequence<RawMatch> = sequence {
        val query = normalize(options.query, options.caseSensitive)
        if (query.isBlank()) return@sequence
        val lines = text.lines()
        val queryLineCount = options.query.lines().size.coerceAtLeast(1)
        val minWindow = max(1, queryLineCount - 1)
        val maxWindow = queryLineCount + 1
        val offsets = lineOffsets(text)
        for (startLine in lines.indices) {
            var bestScore = 0.0
            var bestText = ""
            var bestCount = 1
            for (count in minWindow..maxWindow) {
                if (startLine + count > lines.size) continue
                val candidate = lines.subList(startLine, startLine + count).joinToString("\n")
                val normalized = normalize(candidate, options.caseSensitive)
                if (normalized.length > query.length * 2 + 200 || query.length > normalized.length * 2 + 200) continue
                val score = similarity(query, normalized)
                if (score > bestScore) {
                    bestScore = score
                    bestText = candidate
                    bestCount = count
                }
            }
            if (bestScore >= options.fuzzyThreshold) {
                val start = offsets.getOrElse(startLine) { 0 }
                val endLine = (startLine + bestCount - 1).coerceAtMost(lines.lastIndex)
                val end = start + bestText.length
                yield(toRawMatch(text, start, end, bestText, options.contextLines, bestScore))
                repeat(bestCount - 1) { /* one best match per starting line */ }
            }
        }
    }

    private fun normalize(value: String, caseSensitive: Boolean): String {
        val whitespace = value.trim().replace(Regex("\\s+"), " ")
        return if (caseSensitive) whitespace else whitespace.lowercase()
    }

    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.length > 20_000 || b.length > 20_000) return max(a.length, b.length)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun toRawMatch(
        text: String,
        start: Int,
        endExclusive: Int,
        value: String,
        contextLines: Int,
        similarity: Double? = null
    ): RawMatch {
        val lineStarts = lineOffsets(text)
        val startLineIndex = lineIndex(lineStarts, start)
        val endLineIndex = lineIndex(lineStarts, (endExclusive - 1).coerceAtLeast(start))
        val allLines = text.lines()
        val from = (startLineIndex - contextLines).coerceAtLeast(0)
        val to = (endLineIndex + contextLines).coerceAtMost(allLines.lastIndex)
        val width = (to + 1).toString().length
        val snippet = (from..to).joinToString("\n") { index ->
            val marker = if (index in startLineIndex..endLineIndex) ">" else " "
            "$marker ${(index + 1).toString().padStart(width)} | ${allLines[index]}"
        }
        return RawMatch(startLineIndex + 1, endLineIndex + 1, value, snippet, similarity)
    }

    private fun lineOffsets(text: String): IntArray {
        val result = ArrayList<Int>()
        result += 0
        text.forEachIndexed { index, char -> if (char == '\n' && index + 1 <= text.length) result += index + 1 }
        return result.toIntArray()
    }

    private fun lineIndex(offsets: IntArray, position: Int): Int {
        val found = offsets.binarySearch(position)
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }
}
