package failchat.gui

import failchat.ConfigKeys
import javafx.collections.ListChangeListener
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.Labeled
import javafx.scene.control.ListCell
import javafx.scene.control.TextInputControl
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
    private const val THEME_CONTROL_PROPERTY = "failchat.theme-cell-configured"
    private const val LANGUAGE_CONTROL_PROPERTY = "failchat.language-cell-configured"

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
            val foreground = if (UiTheme.currentCode() == LIGHT) "#202124" else "#f1f3f4"
            textFill = Color.web(foreground)
            style = "-fx-text-fill: $foreground; -fx-fill: $foreground;"
        }
    }

    private class LanguageOptionCell : ListCell<UiLanguage.LanguageOption>() {
        override fun updateItem(item: UiLanguage.LanguageOption?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                text = null
            } else {
                text = item.displayName
            }
            val foreground = if (UiTheme.currentCode() == LIGHT) "#202124" else "#f1f3f4"
            textFill = Color.web(foreground)
            style = "-fx-text-fill: $foreground; -fx-fill: $foreground;"
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

        applyDirectColors(scene.root)
        installThemeSelector(scene)
        controls[scene]?.let { styleThemeControls(it) }
    }

    private fun refreshForLanguageChange() {
        // The text itself changes in UiLanguage first. Reapplying the theme
        // afterwards refreshes all JavaFX Text/Labeled nodes and both selectors.
        Window.getWindows().forEach { apply(it.scene) }
    }

    private fun applyDirectColors(root: Parent) {
        val foreground = if (currentThemeCode == LIGHT) Color.web("#202124") else Color.web("#f1f3f4")
        val secondary = if (currentThemeCode == LIGHT) Color.web("#5f6368") else Color.web("#b8bec5")
        val accent = if (currentThemeCode == LIGHT) Color.web("#4d42b5") else Color.web("#b2a7ff")

        fun walk(node: Node) {
            when (node) {
                is Text -> {
                    node.fill = foreground
                    node.style = appendStyle(node.style, "-fx-fill: ${toHex(foreground)};")
                }
                is Hyperlink -> {
                    node.textFill = accent
                    node.style = appendStyle(node.style, "-fx-text-fill: ${toHex(accent)};")
                }
                is Labeled -> {
                    node.textFill = foreground
                    node.style = appendStyle(node.style, "-fx-text-fill: ${toHex(foreground)};")
                }
                is TextInputControl -> {
                    node.style = appendStyle(node.style, "-fx-text-fill: ${toHex(foreground)}; -fx-prompt-text-fill: ${toHex(secondary)};")
                }
            }

            if (node is ComboBox<*>) {
                styleComboBox(node, foreground)
            }

            if (node is Parent) {
                node.childrenUnmodifiable.forEach(::walk)
            }
        }

        walk(root)
    }

    @Suppress("UNCHECKED_CAST")
    private fun styleComboBox(combo: ComboBox<*>, foreground: Color) {
        combo.style = appendStyle(combo.style, "-fx-text-fill: ${toHex(foreground)};")

        if (combo.items.any { it is UiLanguage.LanguageOption }) {
            val languageCombo = combo as ComboBox<UiLanguage.LanguageOption>
            if (languageCombo.properties[LANGUAGE_CONTROL_PROPERTY] != true) {
                languageCombo.setCellFactory { LanguageOptionCell() }
                languageCombo.buttonCell = LanguageOptionCell()
                languageCombo.properties[LANGUAGE_CONTROL_PROPERTY] = true
            }
            languageCombo.buttonCell?.apply {
                textFill = foreground
                style = "-fx-text-fill: ${toHex(foreground)}; -fx-fill: ${toHex(foreground)};"
            }
        } else {
            combo.buttonCell?.apply {
                textFill = foreground
                style = appendStyle(style, "-fx-text-fill: ${toHex(foreground)}; -fx-fill: ${toHex(foreground)};")
            }
        }
    }

    private fun installThemeSelector(scene: Scene) {
        if (controls.containsKey(scene)) return

        val languageSelector = findLanguageSelector(scene.root) ?: return
        val footer = languageSelector.parent as? javafx.scene.layout.HBox ?: return
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
            setOnAction { value?.let { setTheme(it.code) } }
        }

        footer.children.add(languageIndex + 1, label)
        footer.children.add(languageIndex + 2, selector)

        val themeControls = ThemeControls(label, selector)
        synchronized(controls) {
            controls[scene] = themeControls
        }
        styleThemeControls(themeControls)
    }

    private fun styleThemeControls(themeControls: ThemeControls) {
        val foreground = if (currentThemeCode == LIGHT) Color.web("#202124") else Color.web("#f1f3f4")
        val css = toHex(foreground)
        themeControls.label.textFill = foreground
        themeControls.label.style = appendStyle(themeControls.label.style, "-fx-text-fill: $css;")
        themeControls.selector.style = appendStyle(themeControls.selector.style, "-fx-text-fill: $css;")
        themeControls.selector.buttonCell?.apply {
            textFill = foreground
            style = "-fx-text-fill: $css; -fx-fill: $css;"
        }
        themeControls.selector.items.forEach { /* force ListCell recreation through the items property */ }
        themeControls.selector.requestLayout()
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

    private fun appendStyle(current: String?, addition: String): String {
        val existing = current?.trim().orEmpty()
        return if (existing.isEmpty()) addition else "$existing $addition"
    }

    private fun toHex(color: Color): String {
        fun c(value: Double): String = "%02X".format((value.coerceIn(0.0, 1.0) * 255.0).roundToInt())
        return "#${c(color.red)}${c(color.green)}${c(color.blue)}"
    }

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}
