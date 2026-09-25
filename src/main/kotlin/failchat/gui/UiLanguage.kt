package failchat.gui

import failchat.ConfigKeys
import failchat.failchatHomePath
import org.apache.commons.configuration2.Configuration
import java.util.Collections
import java.util.Enumeration
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.file.Files
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Labeled
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextInputControl
import javafx.scene.text.Text

/**
 * Application UI localization.
 *
 * English is the built-in fallback language. The selected language is stored in the
 * existing user configuration so changing the language does not require a new config file.
 */
object UiLanguage {

    const val ENGLISH = "en"
    const val RUSSIAN = "ru"

    data class LanguageOption(val code: String, val displayName: String) {
        override fun toString(): String = displayName
    }

    val options = listOf(
        LanguageOption(ENGLISH, "English"),
        LanguageOption(RUSSIAN, "Русский")
    )

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val originalTexts = Collections.synchronizedMap(IdentityHashMap<Any, String>())

    @Volatile
    private var currentCodeValue = ENGLISH

    @Volatile
    private var currentBundle: ResourceBundle = loadBundleSafely(ENGLISH)

    fun initialize(config: Configuration) {
        val configuredCode = try {
            config.getString(ConfigKeys.language, ENGLISH)
        } catch (t: Throwable) {
            ENGLISH
        }
        setLanguage(config, configuredCode, notifyListeners = false)
    }

    /** Best-effort initialization for startup error dialogs shown before Dependencies exist. */
    fun initializeFromUserConfiguration() {
        val userConfigPath = failchatHomePath.resolve("user.properties")
        if (!Files.isRegularFile(userConfigPath)) return

        try {
            Files.newInputStream(userConfigPath).use { input ->
                val properties = Properties()
                properties.load(input)
                currentCodeValue = optionFor(properties.getProperty(ConfigKeys.language, ENGLISH)).code
                currentBundle = loadBundle(currentCodeValue)
            }
        } catch (_: Throwable) {
            currentCodeValue = ENGLISH
            currentBundle = loadBundle(ENGLISH)
        }
    }

    fun currentCode(): String = currentCodeValue

    fun currentOption(): LanguageOption = optionFor(currentCodeValue)

    fun optionFor(code: String?): LanguageOption {
        val normalizedCode = code?.trim()?.lowercase(Locale.ROOT)
        return options.firstOrNull { it.code == normalizedCode } ?: options.first()
    }

    fun setLanguage(config: Configuration, requestedCode: String?, notifyListeners: Boolean = true) {
        val normalizedCode = optionFor(requestedCode).code
        val changed = normalizedCode != currentCodeValue

        config.setProperty(ConfigKeys.language, normalizedCode)
        currentCodeValue = normalizedCode
        currentBundle = loadBundleSafely(normalizedCode)

        if (notifyListeners && changed) {
            listeners.forEach { listener ->
                try {
                    listener()
                } catch (_: Throwable) {
                    // A localization listener must never break the configuration change.
                }
            }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun text(key: String): String {
        return try {
            currentBundle.getString(key)
        } catch (_: Exception) {
            key
        }
    }

    /**
     * Localizes static JavaFX text from the original English values in FXML.
     * Dynamic/value nodes can be excluded so a locale switch cannot reset their current value.
     */
    fun localize(root: Parent, ignored: Set<Any> = emptySet()) {
        visit(root, ignored)
    }

    private fun visit(node: Node, ignored: Set<Any>) {
        if (node !in ignored) {
            when (node) {
                is Labeled -> node.text = translatedLiteral(node)
                is TextInputControl -> node.promptText = translatedLiteralPrompt(node)
                is Text -> node.text = translatedLiteral(node)
            }
        }

        if (node is TabPane) {
            node.tabs.forEach { tab ->
                if (tab !in ignored) {
                    tab.text = translatedLiteral(tab)
                }
                tab.content?.let { content -> visit(content, ignored) }
            }
        }

        if (node is ScrollPane) {
            node.content?.let { content -> visit(content, ignored) }
        }

        if (node is Parent) {
            node.childrenUnmodifiable.forEach { child -> visit(child, ignored) }
        }
    }

    private fun translatedLiteral(owner: Any): String {
        val original = originalTexts.getOrPut(owner) {
            when (owner) {
                is Labeled -> owner.text
                is Text -> owner.text
                is Tab -> owner.text
                else -> ""
            }
        }
        return translateLiteral(original)
    }

    private fun translatedLiteralPrompt(owner: TextInputControl): String {
        val original = originalTexts.getOrPut(owner) { owner.promptText }
        return translateLiteral(original)
    }

    private fun translateLiteral(value: String): String {
        if (currentCodeValue == ENGLISH) return value

        val trimmed = value.trim()
        if (trimmed.isEmpty()) return value

        val translated = try {
            currentBundle.getString("literal.${literalKey(trimmed)}")
        } catch (_: Exception) {
            return value
        }

        val leading = value.takeWhile { it.isWhitespace() }
        val trailing = value.takeLastWhile { it.isWhitespace() }
        return leading + translated + trailing
    }

    private fun literalKey(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun loadBundleSafely(code: String): ResourceBundle {
        val locale = Locale.forLanguageTag(code)
        return try {
            ResourceBundle.getBundle("i18n.messages", locale)
        } catch (_: Throwable) {
            EmptyResourceBundle
        }
    }

    /**
     * English fallback used when translation resources are unavailable.
     * A broken/missing optional language resource must never prevent Failchat from starting.
     */
    private object EmptyResourceBundle : ResourceBundle() {
        override fun handleGetObject(key: String): Any? = null

        override fun getKeys(): Enumeration<String> = Collections.emptyEnumeration()
    }
}
