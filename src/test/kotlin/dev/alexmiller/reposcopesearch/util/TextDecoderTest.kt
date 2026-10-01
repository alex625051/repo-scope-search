package dev.alexmiller.reposcopesearch.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextDecoderTest {
    @Test fun `utf8 text is decoded`() = assertEquals("Привет", TextDecoder.decode("Привет".toByteArray()))
    @Test fun `binary data is skipped`() = assertNull(TextDecoder.decode(byteArrayOf(1, 0, 2, 3)))
}
