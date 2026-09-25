package failchat.tts

internal object TtsTextSanitizer {

    fun sanitize(text: String, maxCharacters: Int): String {
        if (maxCharacters <= 0) return ""

        val normalized = text
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount <= maxCharacters) {
            return normalized
        }

        if (maxCharacters == 1) {
            return "…"
        }

        val endIndex = normalized.offsetByCodePoints(0, maxCharacters - 1)
        return normalized.substring(0, endIndex).trimEnd() + "…"
    }
}
