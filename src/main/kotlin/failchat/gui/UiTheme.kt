package failchat.gui

import failchat.ConfigKeys
import javafx.collections.ListChangeListener
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Labeled
import javafx.scene.control.ListCell
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Window
import org.apache.commons.configuration2.Configuration
import java.util.WeakHashMap

/**
 * Application-wide JavaFX theme manager.
 *
 * The original settings.fxml uses many javafx.scene.text.Text nodes instead of
 * Labels. Those nodes do not reliably follow -fx-text-fill, so the theme is
 * applied both through the stylesheet and directly to the live scene graph.
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

    /** Colors come from the stylesheet; an inline color here would go stale after a theme switch. */
    private class ThemeOptionCell : ListCell<ThemeOption>() {
        override fun updateItem(item: ThemeOption?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.title()
        }
    }

    private val controls = WeakHashMap<Scene, ThemeControls>()
    private var installed = false
    private var refreshingSelectors = false
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
        val configuredTheme = runCatching {
            config.getString(ConfigKeys.theme, DARK)
        }.getOrDefault(DARK)
        currentThemeCode = normalize(configuredTheme)
        initialized = true

        if (installed) {
            Window.getWindows().forEach { apply(it.scene) }
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

        // Applying immediately is important: the settings window must change
        // before the click handler returns, without requiring a restart.
        Window.getWindows().forEach { apply(it.scene) }
        if (changed) {
            runCatching { onConfigurationChanged?.invoke() }
        }
    }

    fun apply(scene: Scene?) {
        if (scene == null) return

        val stylesheetUrl = stylesheetUrl()
        val darkUrl = stylesheet(DARK_STYLESHEET)
        val lightUrl = stylesheet(LIGHT_STYLESHEET)
        scene.stylesheets.removeAll(darkUrl, lightUrl)
        if (!scene.stylesheets.contains(stylesheetUrl)) {
            scene.stylesheets.add(stylesheetUrl)
        }

        installThemeSelector(scene)
        applyDirectColors(scene.root)
        controls[scene]?.let { refreshThemeControls(it) }
    }

    private fun refreshForLanguageChange() {
        // The text itself changes in UiLanguage first. Reapplying the theme afterwards
        // refreshes the theme selector (its option titles and its label).
        Window.getWindows().toList().forEach { runCatching { apply(it.scene) } }
    }

    /**
     * Most colors come from the stylesheet. Only plain javafx.scene.text.Text nodes from the FXML
     * are colored directly (they are not always restyled when the stylesheet is swapped at runtime).
     *
     * Never touch nodes that belong to a control skin: LabeledText (inside every Button/CheckBox/Label)
     * has its fill and text bound to the owner, and setting a bound property throws. An exception here
     * used to abort the whole pass, leaving the nodes after it (the footer with "Language:"/"Theme:")
     * with the colors of the previous theme.
     */
    private fun applyDirectColors(root: Parent) {
        val foreground = if (currentThemeCode == LIGHT) Color.web("#202124") else Color.web("#f1f3f4")

        fun walk(node: Node) {
            if (node is Text && node.parent !is Labeled && !node.fillProperty().isBound) {
                runCatching { node.fill = foreground }
            }
            if (node is Parent) {
                node.childrenUnmodifiable.toList().forEach(::walk)
            }
        }

        walk(root)
    }

    private fun installThemeSelector(scene: Scene) {
        if (controls.containsKey(scene)) return

        val languageSelector = findLanguageSelector(scene.root) ?: return
        val footer = languageSelector.parent as? javafx.scene.layout.HBox ?: return
        val languageIndex = footer.children.indexOf(languageSelector)
        if (languageIndex < 0) return

        val label = Label(UiLanguage.text("literal.theme"))
        val selector = ComboBox<ThemeOption>().apply {
            prefWidth = 108.0
            minWidth = Region.USE_PREF_SIZE
            items.setAll(options)
            value = currentOption()
            setCellFactory { ThemeOptionCell() }
            buttonCell = ThemeOptionCell()
            setOnAction { if (!refreshingSelectors) value?.let { setTheme(it.code) } }
        }

        footer.children.add(languageIndex + 1, label)
        footer.children.add(languageIndex + 2, selector)

        val themeControls = ThemeControls(label, selector)
        synchronized(controls) {
            controls[scene] = themeControls
        }
        refreshThemeControls(themeControls)
    }

    private fun refreshThemeControls(themeControls: ThemeControls) {
        refreshingSelectors = true
        try {
            themeControls.label.text = UiLanguage.text("literal.theme")
            themeControls.selector.buttonCell = ThemeOptionCell()
            themeControls.selector.setCellFactory { ThemeOptionCell() }
            themeControls.selector.items.setAll(options)
            themeControls.selector.value = currentOption()
        } finally {
            refreshingSelectors = false
        }
    }

    private fun findLanguageSelector(node: Node): ComboBox<*>? {
        if (node is ComboBox<*> && node.items.any { it is UiLanguage.LanguageOption }) {
            return node
        }
        val parent = node as? Parent ?: return null
        for (child in parent.childrenUnmodifiable) {
            findLanguageSelector(child)?.let { return it }
        }
        return null
    }

    private fun normalize(code: String?): String {
        return if (code?.trim()?.lowercase() == LIGHT) LIGHT else DARK
    }

    private fun stylesheetUrl(): String = stylesheet(
            if (currentThemeCode == LIGHT) LIGHT_STYLESHEET else DARK_STYLESHEET
    )

    private fun stylesheet(resource: String): String = requireNotNull(UiTheme::class.java.getResource(resource)) {
        "Missing theme stylesheet: $resource"
    }.toExternalForm()
}
