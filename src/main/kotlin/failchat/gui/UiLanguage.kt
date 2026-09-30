package failchat.gui

import failchat.ConfigKeys
import failchat.failchatHomePath
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Labeled
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableView
import javafx.scene.control.TextInputControl
import javafx.scene.text.Text
import javafx.stage.Window
import org.apache.commons.configuration2.Configuration
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime localization for the native JavaFX UI.
 *
 * A node can be changed by the FXML loader, by a settings listener, or by a
 * previous language pass. The resolver therefore keeps a stable English source
 * text, while also understanding already-translated Russian values. Blank
 * values are never cached, so controls that receive text later still switch
 * language immediately.
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
    private val translationToEnglish = ConcurrentHashMap<String, String>()
    private val englishLiteralValues = ConcurrentHashMap.newKeySet<String>()
    private val normalizedTranslationToEnglish = ConcurrentHashMap<String, String>()
    private var globalWindowListenerInstalled = false

    @Volatile
    private var currentCodeValue = ENGLISH

    @Volatile
    private var currentBundle: ResourceBundle = loadBundleSafely(ENGLISH)

    fun initialize(config: Configuration) {
        val configuredCode = runCatching { config.getString(ConfigKeys.language, ENGLISH) }.getOrDefault(ENGLISH)
        setLanguage(config, configuredCode, notifyListeners = false)
    }

    fun installGlobalLocalization() {
        if (globalWindowListenerInstalled) return
        globalWindowListenerInstalled = true
        rebuildTranslationIndex()

        Window.getWindows().addListener(ListChangeListener { change ->
            while (change.next()) {
                change.addedSubList.forEach(::localizeWindow)
            }
        })

        Window.getWindows().forEach(::localizeWindow)
    }

    fun initializeFromUserConfiguration() {
        val userConfigPath = failchatHomePath.resolve("user.properties")
        if (!Files.isRegularFile(userConfigPath)) return
        try {
            Files.newInputStream(userConfigPath).use { input ->
                val properties = Properties()
                properties.load(input)
                currentCodeValue = optionFor(properties.getProperty(ConfigKeys.language, ENGLISH)).code
                currentBundle = loadBundleSafely(currentCodeValue)
                rebuildTranslationIndex()
            }
        } catch (_: Throwable) {
            currentCodeValue = ENGLISH
            currentBundle = loadBundleSafely(ENGLISH)
            rebuildTranslationIndex()
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
        rebuildTranslationIndex()

        if (!notifyListeners || !changed) return

        // All known settings-specific listeners update dynamic controls first.
        listeners.forEach { listener -> runCatching { listener() } }

        // A second pass catches nodes created/changed by those listeners.
        if (Platform.isFxApplicationThread()) {
            Window.getWindows().forEach(::localizeWindow)
            Platform.runLater { Window.getWindows().forEach(::localizeWindow) }
        } else {
            Platform.runLater { Window.getWindows().forEach(::localizeWindow) }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun text(key: String): String = runCatching { currentBundle.getString(key) }.getOrDefault(key)

    fun localize(root: Parent, ignored: Set<Any> = emptySet()) {
        visit(root, ignored)
    }

    private fun localizeWindow(window: Window) {
        val root = window.scene?.root as? Parent ?: return
        localize(root)
    }

    private fun visit(node: Node, ignored: Set<Any>) {
        if (node !in ignored) {
            when (node) {
                is Labeled -> {
                    node.text = translatedLiteral(node, node.text.orEmpty())
                    node.tooltip?.let { tooltip ->
                        tooltip.text = translatedLiteral(tooltip, tooltip.text.orEmpty())
                    }
                }
                is TextInputControl -> node.promptText = translatedLiteral(node, node.promptText.orEmpty())
                is Text -> node.text = translatedLiteral(node, node.text)
            }
        }

        if (node is TabPane) {
            node.tabs.forEach { tab ->
                if (tab !in ignored) tab.text = translatedLiteral(tab, tab.text.orEmpty())
                tab.content?.let { visit(it, ignored) }
            }
        }

        if (node is TableView<*>) {
            node.columns.forEach { column ->
                val current = column.text
                if (current != null && column !in ignored) {
                    column.text = translatedLiteral(column, current)
                }
            }
        }

        if (node is ScrollPane) {
            node.content?.let { visit(it, ignored) }
        }

        if (node is Parent) {
            node.childrenUnmodifiable.forEach { child -> visit(child, ignored) }
        }
    }

    private fun translatedLiteral(owner: Any, currentValue: String): String {
        if (currentValue.trim().isEmpty()) return currentValue

        val normalizedCurrent = normalizeText(currentValue)
        val reverse = translationToEnglish[currentValue.trim()]
                ?: normalizedTranslationToEnglish[normalizedCurrent]

        val cached = synchronized(originalTexts) { originalTexts[owner] }
        val original = when {
            reverse != null -> reverse
            englishLiteralValues.contains(currentValue.trim()) -> currentValue
            englishLiteralValues.contains(normalizedCurrent) -> currentValue
            cached == null -> currentValue
            normalizeText(cached) == normalizedCurrent -> cached
            else -> currentValue
        }

        synchronized(originalTexts) {
            originalTexts[owner] = original
        }

        return translateLiteral(original)
    }

    private fun translateLiteral(value: String): String {
        if (currentCodeValue == ENGLISH || value.trim().isEmpty()) return value

        val key = "literal.${literalKey(value.trim())}"
        val translated = runCatching { currentBundle.getString(key) }.getOrNull() ?: return value
        val leading = value.takeWhile { it.isWhitespace() }
        val trailing = value.takeLastWhile { it.isWhitespace() }
        return leading + translated + trailing
    }

    private fun literalKey(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun normalizeText(value: String): String = value
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun rebuildTranslationIndex() {
        val english = loadBundleSafely(ENGLISH)
        val russian = loadBundleSafely(RUSSIAN)
        val reverseExact = HashMap<String, String>()
        val reverseNormalized = HashMap<String, String>()
        val englishValues = HashSet<String>()

        fun addEnglish() {
            english.keys.asSequence().filter { it.startsWith("literal.") }.forEach { key ->
                val value = runCatching { english.getString(key) }.getOrNull() ?: return@forEach
                if (value.isNotEmpty()) {
                    val trimmed = value.trim()
                    englishValues.add(trimmed)
                    englishValues.add(normalizeText(trimmed))
                    reverseExact.putIfAbsent(trimmed, value)
                    reverseNormalized.putIfAbsent(normalizeText(trimmed), value)
                }
            }
        }

        fun addRussian() {
            russian.keys.asSequence().filter { it.startsWith("literal.") }.forEach { key ->
                val value = runCatching { russian.getString(key) }.getOrNull() ?: return@forEach
                val englishValue = runCatching { english.getString(key) }.getOrNull() ?: return@forEach
                if (value.isNotEmpty()) {
                    reverseExact.putIfAbsent(value.trim(), englishValue)
                    reverseNormalized.putIfAbsent(normalizeText(value), englishValue)
                }
            }
        }

        addEnglish()
        addRussian()
        translationToEnglish.clear()
        translationToEnglish.putAll(reverseExact)
        normalizedTranslationToEnglish.clear()
        normalizedTranslationToEnglish.putAll(reverseNormalized)
        englishLiteralValues.clear()
        englishLiteralValues.addAll(englishValues)
    }

    private fun loadBundleSafely(code: String): ResourceBundle {
        val locale = Locale.forLanguageTag(code)
        return try {
            ResourceBundle.getBundle("i18n.messages", locale)
        } catch (_: Throwable) {
            EmptyResourceBundle
        }
    }

    private object EmptyResourceBundle : ResourceBundle() {
        override fun handleGetObject(key: String): Any? = null
        override fun getKeys(): Enumeration<String> = Collections.emptyEnumeration()
    }
}
