package failchat.gui

import failchat.ConfigKeys
import failchat.failchatHomePath
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
 * Application-wide runtime localization.
 *
 * FXML contains a lot of plain Text/Labeled values rather than localized keys.
 * We therefore keep the original English value per node and translate it every
 * time the language changes. A reverse translation index is also used for nodes
 * that become visible after a language has already been switched, so their
 * first scan never mistakes a Russian value for the original English value.
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
    private var globalWindowListenerInstalled = false

    @Volatile
    private var currentCodeValue = ENGLISH

    @Volatile
    private var currentBundle: ResourceBundle = loadBundleSafely(ENGLISH)

    fun initialize(config: Configuration) {
        val configuredCode = try {
            config.getString(ConfigKeys.language, ENGLISH)
        } catch (_: Throwable) {
            ENGLISH
        }
        setLanguage(config, configuredCode, notifyListeners = false)
    }

    fun installGlobalLocalization() {
        if (globalWindowListenerInstalled) return
        globalWindowListenerInstalled = true
        rebuildTranslationIndex()

        Window.getWindows().addListener(ListChangeListener { change ->
            while (change.next()) {
                change.addedSubList.forEach { window ->
                    // Window notifications happen on the JavaFX application thread.
                    localizeWindow(window)
                }
            }
        })

        Window.getWindows().forEach(::localizeWindow)
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

        if (notifyListeners && changed) {
            // Dedicated window listeners update dynamic values first (OBS state,
            // buttons, menus, etc.). Then every visible native node is rescanned
            // so static FXML text changes in the same language switch.
            listeners.forEach { listener ->
                runCatching { listener() }
            }

            Window.getWindows().forEach(::localizeWindow)
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
                is Labeled -> node.text = translatedLiteral(node, node.text.orEmpty())
                is TextInputControl -> node.promptText = translatedLiteral(node, node.promptText.orEmpty())
                is Text -> node.text = translatedLiteral(node, node.text)
            }
        }

        if (node is TabPane) {
            node.tabs.forEach { tab ->
                if (tab !in ignored) {
                    tab.text = translatedLiteral(tab, tab.text.orEmpty())
                }
                tab.content?.let { content -> visit(content, ignored) }
            }
        }

        if (node is TableView<*>) {
            node.columns.forEach { column ->
                val current = column.text
                if (current != null && column !in ignored) {
                    // TableColumn is not a Node, so keep its original value in the
                    // same identity map and update it like other static literals.
                    column.text = translatedLiteral(column, current)
                }
            }
        }

        if (node is ScrollPane) {
            node.content?.let { content -> visit(content, ignored) }
        }

        if (node is Parent) {
            node.childrenUnmodifiable.forEach { child -> visit(child, ignored) }
        }
    }

    private fun translatedLiteral(owner: Any, currentValue: String): String {
        val original = synchronized(originalTexts) {
            originalTexts[owner] ?: run {
                val candidate = translationToEnglish[currentValue.trim()]
                val captured = if (candidate != null) englishBundleValue(candidate) else currentValue
                originalTexts[owner] = captured
                captured
            }
        }
        return translateLiteral(original)
    }

    private fun translateLiteral(value: String): String {
        if (currentCodeValue == ENGLISH) return value
        if (value.trim().isEmpty()) return value

        val translated = try {
            currentBundle.getString("literal.${literalKey(value.trim())}")
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

    private fun rebuildTranslationIndex() {
        val english = loadBundleSafely(ENGLISH)
        val russian = loadBundleSafely(RUSSIAN)
        val result = HashMap<String, String>()

        fun add(bundle: ResourceBundle) {
            val keys = bundle.keys
            while (keys.hasMoreElements()) {
                val key = keys.nextElement()
                if (!key.startsWith("literal.")) continue
                val value = runCatching { bundle.getString(key) }.getOrNull() ?: continue
                if (value.isNotEmpty()) result.putIfAbsent(value.trim(), key)
            }
        }

        add(english)
        add(russian)
        translationToEnglish.clear()

        result.forEach { (text, key) ->
            val englishValue = runCatching { english.getString(key) }.getOrNull() ?: return@forEach
            translationToEnglish[text] = englishValue
        }
    }

    private fun englishBundleValue(key: String): String {
        return runCatching { loadBundleSafely(ENGLISH).getString(key) }.getOrDefault(key)
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
