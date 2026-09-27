package failchat.gui

import failchat.ConfigKeys
import javafx.beans.value.ChangeListener
import javafx.collections.ListChangeListener
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.stage.Window
import org.apache.commons.configuration2.Configuration
import java.util.WeakHashMap

/**
 * Runtime JavaFX theme manager.
 *
 * The theme is persisted in the normal user configuration and can be changed
 * without restarting the application. The native JavaFX UI uses one of the
 * bundled stylesheets; the embedded HTML chat skin is intentionally left to
 * its own CSS.
 */
object UiTheme {
    private const val DARK = "dark"
    private const val LIGHT = "light"

    data class ThemeOption(val code: String, private val textKey: String) {
        override fun toString(): String = UiLanguage.text(textKey)
    }

    val options = listOf(
        ThemeOption(LIGHT, "theme.light"),
        ThemeOption(DARK, "theme.dark")
    )

    private const val DARK_STYLESHEET = "/fx/dark.css"
    private const val LIGHT_STYLESHEET = "/fx/light.css"

    private data class ThemeControls(
        val label: Label,
        val selector: ComboBox<ThemeOption>
    )

    private val controls = WeakHashMap<Scene, ThemeControls>()
    private var installed = false
    private var initialized = false
    private var configuration: Configuration? = null
    private var onConfigurationChanged: (() -> Unit)? = null

    @Volatile
    private var currentThemeCode = DARK

    private val windowListener = ListChangeListener<Window> { change ->
        while (change.next()) {
            change.addedSubList.forEach { window ->
                apply(window.scene)
            }
        }
    }

    fun initialize(config: Configuration, configurationChangedCallback: (() -> Unit)? = null) {
        configuration = config
        onConfigurationChanged = configurationChangedCallback
        val configuredTheme = try {
            config.getString(ConfigKeys.theme, DARK)
        } catch (_: Throwable) {
            DARK
        }
        currentThemeCode = normalize(configuredTheme)
        initialized = true

        if (installed) {
            Window.getWindows().forEach { apply(it.scene) }
            refreshSelectors()
        }
    }

    fun install() {
        if (installed) {
            Window.getWindows().forEach { apply(it.scene) }
            return
        }

        if (!initialized) {
            currentThemeCode = DARK
        }

        installed = true
        Window.getWindows().forEach { apply(it.scene) }
        Window.getWindows().addListener(windowListener)
        UiLanguage.addListener(::refreshForLanguageChange)
    }

    fun currentCode(): String = currentThemeCode

    fun currentOption(): ThemeOption = options.first { it.code == currentThemeCode }

    fun optionFor(code: String?): ThemeOption {
        val normalized = normalize(code)
        return options.firstOrNull { it.code == normalized } ?: options.first()
    }

    fun setTheme(requestedCode: String?) {
        val normalized = normalize(requestedCode)
        val changed = normalized != currentThemeCode
        currentThemeCode = normalized
        configuration?.setProperty(ConfigKeys.theme, normalized)
        if (changed) {
            runCatching { onConfigurationChanged?.invoke() }
                .onFailure { /* Persisting the theme must never break live theme switching. */ }
        }

        if (changed || installed) {
            Window.getWindows().forEach { apply(it.scene) }
            refreshSelectors()
        }
    }

    fun apply(scene: Scene?) {
        if (scene == null) return

        val stylesheetUrl = stylesheetUrl()
        scene.stylesheets.removeIf { it == stylesheet(DARK_STYLESHEET) || it == stylesheet(LIGHT_STYLESHEET) }
        if (!scene.stylesheets.contains(stylesheetUrl)) {
            scene.stylesheets.add(stylesheetUrl)
        }

        installThemeSelector(scene)
    }

    private fun refreshForLanguageChange() {
        refreshSelectors()
    }

    private fun refreshSelectors() {
        val snapshot = controls.toMap()
        snapshot.forEach { (scene, themeControls) ->
            if (!scene.root.scene.equals(scene)) return@forEach
            val selectedCode = currentThemeCode
            themeControls.label.text = UiLanguage.text("literal.theme")
            themeControls.selector.items.setAll(options)
            themeControls.selector.value = optionFor(selectedCode)
        }
    }

    private fun installThemeSelector(scene: Scene) {
        if (controls.containsKey(scene)) return

        val languageSelector = findLanguageSelector(scene.root) ?: return
        val footer = languageSelector.parent as? HBox ?: return

        val label = Label("Theme:")
        val selector = ComboBox<ThemeOption>().apply {
            prefWidth = 88.0
            maxWidth = 95.0
            items.setAll(options)
            value = currentOption()
            setOnAction {
                value?.let { setTheme(it.code) }
            }
        }

        val languageIndex = footer.children.indexOf(languageSelector)
        if (languageIndex < 0) return

        footer.children.add(languageIndex + 1, label)
        footer.children.add(languageIndex + 2, selector)
        controls[scene] = ThemeControls(label, selector)

        // A language change can recreate ComboBox cells. Reset the items so the
        // newly translated names are shown immediately.
        val listener = ChangeListener<UiLanguage.LanguageOption> { _, _, _ ->
            selector.items.setAll(options)
            selector.value = optionFor(currentThemeCode)
        }
        languageSelector.valueProperty().addListener(listener)
    }

    private fun findLanguageSelector(node: javafx.scene.Node): ComboBox<*>? {
        if (node is ComboBox<*> && node.items.any { it is UiLanguage.LanguageOption }) {
            return node
        }
        val parent = node as? javafx.scene.Parent ?: return null
        for (child in parent.childrenUnmodifiable) {
            val found = findLanguageSelector(child)
            if (found != null) return found
        }
        return null
    }

    private fun normalize(code: String?): String {
        return if (code?.trim()?.lowercase() == LIGHT) LIGHT else DARK
    }

    private fun stylesheetUrl(): String = stylesheet(
        if (currentThemeCode == LIGHT) LIGHT_STYLESHEET else DARK_STYLESHEET
    )

    private fun stylesheet(resource: String): String {
        return requireNotNull(UiTheme::class.java.getResource(resource)) {
            "Missing theme stylesheet: $resource"
        }.toExternalForm()
    }
}
