package dev.alexmiller.reposcopesearch.util

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextDecoder {
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            }
        }
        if (looksBinary(bytes)) return null
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            runCatching { String(bytes, charset("windows-1251")) }.getOrNull()
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(8192)
        if (sample.any { it == 0.toByte() }) return true
        val controls = sample.count { b ->
            val c = b.toInt() and 0xff
            c < 9 || c in 14..31
        }
        return sample.isNotEmpty() && controls.toDouble() / sample.size > 0.05
    }
}
