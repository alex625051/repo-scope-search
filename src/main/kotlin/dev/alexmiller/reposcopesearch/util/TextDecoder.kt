package dev.alexananev.reposcopesearch.util

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextDecoder {
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val sample = bytes.take(8192)
        if (sample.count { it == 0.toByte() } > sample.size / 100) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            try { String(bytes, charset("windows-1251")) } catch (_: Exception) { null }
        }
    }
}
