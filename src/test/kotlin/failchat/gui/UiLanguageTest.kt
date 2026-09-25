package failchat.gui

import failchat.loadDefaultConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class UiLanguageTest {

    @Test
    fun russianLanguageUsesRussianTranslations() {
        val config = loadDefaultConfig()

        try {
            UiLanguage.initialize(config)
            UiLanguage.setLanguage(config, UiLanguage.RUSSIAN, notifyListeners = false)

            assertEquals("ru", UiLanguage.currentCode())
            assertEquals("Основные настройки", UiLanguage.text("literal.main-settings"))
            assertEquals("Громкость:", UiLanguage.text("literal.volume"))
            assertEquals("Очистить чат", UiLanguage.text("chat.menu.clear-chat"))
        } finally {
            UiLanguage.setLanguage(config, UiLanguage.ENGLISH, notifyListeners = false)
        }
    }

    @Test
    fun unsupportedLanguageFallsBackToEnglish() {
        val config = loadDefaultConfig()

        try {
            UiLanguage.initialize(config)
            UiLanguage.setLanguage(config, "de", notifyListeners = false)

            assertEquals(UiLanguage.ENGLISH, UiLanguage.currentCode())
            assertEquals("Main settings", UiLanguage.text("literal.main-settings"))
        } finally {
            UiLanguage.setLanguage(config, UiLanguage.ENGLISH, notifyListeners = false)
        }
    }
}
