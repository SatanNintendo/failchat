package failchat.tts

import org.junit.Test
import kotlin.test.assertEquals

class TtsTextSanitizerTest {

    @Test
    fun normalizesWhitespace() {
        assertEquals(
            "Hello world test",
            TtsTextSanitizer.sanitize("  Hello\nworld\t  test  ", 100)
        )
    }

    @Test
    fun truncatesWithEllipsis() {
        assertEquals(
            "Hello…",
            TtsTextSanitizer.sanitize("Hello world", 6)
        )
    }

    @Test
    fun doesNotSplitSurrogatePair() {
        assertEquals(
            "😀a…",
            TtsTextSanitizer.sanitize("😀abcdef", 3)
        )
    }

    @Test
    fun zeroOrNegativeLimitProducesEmptyText() {
        assertEquals("", TtsTextSanitizer.sanitize("Hello", 0))
        assertEquals("", TtsTextSanitizer.sanitize("Hello", -1))
    }
}
