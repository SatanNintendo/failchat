package failchat.gui

import failchat.ConfigKeys
import javafx.collections.ListChangeListener
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.stage.Window
import org.apache.commons.configuration2.Configuration
import java.util.WeakHashMap

/**
 * Runtime JavaFX theme manager.
 *
 * The theme is persisted in the existing user configuration and can be changed
 * without restarting the application.  The manager also owns the small theme
 * selector next to the existing language selector in the settings footer.
 */
object UiTheme {
    private const val DARK = "dark"
    private const val LIGHT = "light"

    data class ThemeOption(val code: String, private val textKey: String) {
        fun title(): String = UiLanguage.text(textKey)
        override fun toString(): String = title()
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

    private class ThemeOptionCell : ListCell<ThemeOption>() {
        override fun updateItem(item: ThemeOption?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                text = null
            } else {
                text = item.title()
            }
            textFill = Color.web(if (UiTheme.currentCode() == LIGHT) "#202124" else "#f1f3f4")
        }
    }

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
            refreshSelectors()
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

    fun currentOption(): ThemeOption = optionFor(currentThemeCode)

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
                .onFailure { /* A theme change must never break the UI thread. */ }
        }

        // Always apply, even when selecting the already active value. This also
        // repairs controls created by a popup or a dynamically built dialog.
        Window.getWindows().forEach { apply(it.scene) }
        refreshSelectors()
    }

    fun apply(scene: Scene?) {
        if (scene == null) return

        val stylesheetUrl = stylesheetUrl()
        val darkUrl = stylesheet(DARK_STYLESHEET)
        val lightUrl = stylesheet(LIGHT_STYLESHEET)
        scene.stylesheets.removeAll(darkUrl, lightUrl)
        scene.stylesheets.add(stylesheetUrl)

        installThemeSelector(scene)
        controls[scene]?.let { styleThemeControls(it) }
    }

    private fun refreshForLanguageChange() {
        // Re-apply CSS too. The selectors recreate their cells when their items
        // are replaced, and this makes the selected value visible immediately.
        Window.getWindows().forEach { apply(it.scene) }
        refreshSelectors()
    }

    private fun refreshSelectors() {
        val snapshot = synchronized(controls) { controls.toMap() }
        snapshot.forEach { (scene, themeControls) ->
            if (scene.window == null && scene.root.scene == null) return@forEach

            themeControls.label.text = UiLanguage.text("literal.theme")
            themeControls.selector.items.setAll(options)
            themeControls.selector.value = optionFor(currentThemeCode)
            styleThemeControls(themeControls)
            themeControls.selector.requestLayout()
        }
    }

    private fun installThemeSelector(scene: Scene) {
        if (controls.containsKey(scene)) return

        val languageSelector = findLanguageSelector(scene.root) ?: return
        val footer = languageSelector.parent as? HBox ?: return
        val languageIndex = footer.children.indexOf(languageSelector)
        if (languageIndex < 0) return

        val label = Label(UiLanguage.text("literal.theme"))
        val selector = ComboBox<ThemeOption>().apply {
            prefWidth = 88.0
            maxWidth = 95.0
            items.setAll(options)
            value = currentOption()
            setCellFactory { ThemeOptionCell() }
            buttonCell = ThemeOptionCell()
            setOnAction {
                value?.let { setTheme(it.code) }
            }
        }

        footer.children.add(languageIndex + 1, label)
        footer.children.add(languageIndex + 2, selector)

        val themeControls = ThemeControls(label, selector)
        synchronized(controls) {
            controls[scene] = themeControls
        }
        styleThemeControls(themeControls)
    }

    private fun styleThemeControls(controls: ThemeControls) {
        val foreground = if (currentThemeCode == LIGHT) "#202124" else "#f1f3f4"
        controls.label.style = "-fx-text-fill: $foreground;"
        controls.selector.style = "-fx-text-fill: $foreground;"
        controls.selector.buttonCell?.apply {
            textFill = Color.web(foreground)
        }
        controls.selector.requestLayout()
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
