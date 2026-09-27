package failchat.gui

import javafx.collections.ListChangeListener
import javafx.scene.Scene
import javafx.stage.Window

/**
 * Installs the Failchat JavaFX dark theme on every JavaFX window created by the application.
 *
 * Chat skins are rendered by WebView/HTML and are intentionally not modified here; this
 * stylesheet targets the native JavaFX configuration/dialog UI.
 */
object UiTheme {
    private const val STYLESHEET_RESOURCE = "/fx/dark.css"

    private var installed = false
    private val stylesheetUrl: String by lazy {
        requireNotNull(UiTheme::class.java.getResource(STYLESHEET_RESOURCE)) {
            "Missing dark theme stylesheet: $STYLESHEET_RESOURCE"
        }.toExternalForm()
    }

    fun install() {
        if (installed) return
        installed = true

        Window.getWindows().forEach(::applyToWindow)
        Window.getWindows().addListener(ListChangeListener { change ->
            while (change.next()) {
                change.addedSubList.forEach(::applyToWindow)
            }
        })
    }

    fun apply(scene: Scene?) {
        if (scene == null) return
        if (!scene.stylesheets.contains(stylesheetUrl)) {
            scene.stylesheets.add(stylesheetUrl)
        }
    }

    private fun applyToWindow(window: Window) {
        apply(window.scene)
    }
}
