package failchat.gui

import failchat.ConfigKeys
import failchat.FailchatServerInfo
import failchat.skin.Skin
import failchat.util.invertBoolean
import failchat.util.urlPattern
import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.ContextMenu
import javafx.scene.control.CustomMenuItem
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import mu.KotlinLogging
import org.apache.commons.configuration2.Configuration
import java.net.MalformedURLException

class ChatFrame(
        private val app: Application,
        private val config: Configuration,
        private val skins: List<Skin>,
        private val guiEventHandler: Lazy<GuiEventHandler>,
        private val ctConfigurator: ClickTransparencyConfigurator?
) {

    private companion object {
        val logger = KotlinLogging.logger {}
    }
    private val decoratedStage: Stage = buildChatStage(StageStyle.DECORATED)
    private val transparentStage: Stage = buildChatStage(StageStyle.TRANSPARENT)
    private val webView: WebView = WebView()
    private val webEngine: WebEngine = webView.engine
    private val chatScene: Scene = buildChatScene()
    // context menu
    private val switchDecorationsItem: CheckMenuItem = CheckMenuItem()
    private val onTopItem: CheckMenuItem = CheckMenuItem()
    private val clickTransparencyItem: CheckMenuItem = CheckMenuItem()
    private val viewersItem: CheckMenuItem = CheckMenuItem()
    private val zoomLabelText = Text()
    private val zoomValueText = Text("???")
    private val zoomValues = listOf(25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300, 400, 500)
    private val showHiddenMessages: CheckMenuItem = CheckMenuItem()
    private val clearChatItem = MenuItem()
    private val closeChatItem = MenuItem()
    // hot keys
    private val switchDecorationsKey = KeyCode.F
    private val onTopKey = KeyCode.O
    private val clickTransparencyKey = KeyCode.T
    private val viewersKey = KeyCode.V
    private val showHiddenMessagesKey = KeyCode.H
    private val clearChatKey = KeyCode.C
    private val closeChatKey = KeyCode.ESCAPE

    private var currentStage: Stage = decoratedStage
    private var lastOpenedSkinUrl: String? = null
    init {
        if (skins.isEmpty()) throw IllegalArgumentException("Empty skins")
        UiLanguage.addListener(::updateLocalization)
        buildContextMenu()
        updateLocalization()
    }

    fun show() {
        if (config.getBoolean(ConfigKeys.frame)) {
            currentStage = decoratedStage
            chatScene.fill = Color.BLACK
        } else {
            currentStage = transparentStage
            chatScene.fill = Color.TRANSPARENT
        }
        configureChatStage(currentStage)
        updateContextMenu()
        loadSkin()
        showChatStage(currentStage)
    }

    private fun loadSkin() {
        val skinName = config.getString(ConfigKeys.skin)
        try {
            val skin = skins.find { it.name == skinName } ?: skins.first()
            val optionalPortParam = if (FailchatServerInfo.port != FailchatServerInfo.defaultPort) {
                "?port=${FailchatServerInfo.port}"
            } else {
                ""
            }
            val url = "http://${FailchatServerInfo.host.hostAddress}:${FailchatServerInfo.port}/chat/${skin.name}" + optionalPortParam
            lastOpenedSkinUrl = url
            webEngine.load(url)
        } catch (e: MalformedURLException) {
            logger.error("Failed to load skin '{}'", skinName, e)
        }
    }

    fun hide() {
        saveChatPosition(currentStage)
        hideChatStage(currentStage)
        clearWebContent()
    }

    fun clearWebContent() {
        webEngine.loadContent("")
    }

    private fun buildChatStage(style: StageStyle): Stage {
        val stage = Stage()
        stage.title = "failchat"
        stage.initStyle(style)
        stage.setOnCloseRequest {
            saveChatPosition(stage)
            guiEventHandler.value.handleShutDown()
        }
        stage.icons.setAll(Images.appIcon)
        return stage
    }

    private fun buildContextMenu() {
        if (ctConfigurator == null) {
            clickTransparencyItem.isDisable = true
        }

        fun Button.configureZoomButton(): Button = this.apply {
            minHeight = 20.0
            maxHeight = 20.0
            minWidth = 20.0
            maxWidth = 20.0
            padding = Insets.EMPTY
        }

        val minusButton = Button("-").configureZoomButton()
        val plusButton = Button("+").configureZoomButton()
        val zoomBox = HBox(zoomLabelText, minusButton, zoomValueText, Text("%"), plusButton).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 0.0, 0.0, 15.0)
        }
        val zoomItem = CustomMenuItem(zoomBox, false)

        switchDecorationsItem.accelerator = KeyCombination.valueOf(switchDecorationsKey.name)
        onTopItem.accelerator = KeyCombination.valueOf(onTopKey.name)
        clickTransparencyItem.accelerator = KeyCombination.valueOf(clickTransparencyKey.name)
        viewersItem.accelerator = KeyCombination.valueOf(viewersKey.name)
        clearChatItem.accelerator = KeyCombination.valueOf(clearChatKey.name)
        showHiddenMessages.accelerator = KeyCombination.valueOf(showHiddenMessagesKey.name)
        closeChatItem.accelerator = KeyCombination.valueOf(closeChatKey.name)

        val contextMenu = ContextMenu(
                switchDecorationsItem, onTopItem, clickTransparencyItem, viewersItem, zoomItem, SeparatorMenuItem(),
                clearChatItem, showHiddenMessages, SeparatorMenuItem(),
                closeChatItem
        )
        contextMenu.sceneProperty().addListener { _, _, popupScene ->
            UiTheme.apply(popupScene)
        }

        chatScene.setOnMouseClicked { mouseEvent ->
            if (mouseEvent.button == MouseButton.SECONDARY) {
                contextMenu.show(chatScene.root, mouseEvent.screenX, mouseEvent.screenY)
            } else if (contextMenu.isShowing && mouseEvent.eventType == MouseEvent.MOUSE_CLICKED) {
                contextMenu.hide()
            }
        }

        switchDecorationsItem.setOnAction { switchDecorations() }
        onTopItem.setOnAction { toggleOnTop() }
        clickTransparencyItem.setOnAction {
            ctConfigurator?.let { ctf -> toggleClickTransparency(ctf) }
        }
        viewersItem.setOnAction { toggleShowViewersBar() }
        clearChatItem.setOnAction { guiEventHandler.value.handleClearChat() }
        showHiddenMessages.setOnAction { toggleShowHiddenMessages() }
        closeChatItem.setOnAction { guiEventHandler.value.handleStopChat() }

        fun Button.configureZoomButtonCallback(elementNumberToGet: Int, filter: (Int, List<Int>) -> Boolean) = this.setOnAction {
            val oldValue = config.getInt(ConfigKeys.zoomPercent)
            val newValue = zoomValues.asSequence().windowed(2, 1)
                    .find { filter.invoke(oldValue, it) }
                    ?.get(elementNumberToGet)
                    ?: kotlin.run {
                        if (oldValue <= zoomValues.first()) zoomValues.first()
                        else zoomValues.last()
                    }
            config.setProperty(ConfigKeys.zoomPercent, newValue)
            guiEventHandler.value.handleConfigurationChange()
            zoomValueText.text = newValue.toString()
        }
        minusButton.configureZoomButtonCallback(0) { oldValue, range -> oldValue in (range[0] + 1)..range[1] }
        plusButton.configureZoomButtonCallback(1) { oldValue, range -> oldValue in range[0]..(range[1] - 1) }
    }

    private fun buildChatScene(): Scene {
        webEngine.userAgent = webEngine.userAgent + "/failchat"
        val scene = Scene(webView)
        webView.style = "-fx-background-color: transparent;"
        webView.isContextMenuEnabled = false

        webEngine.loadWorker.stateProperty().addListener { _, _, _ ->
            runCatching {
                val window = webEngine.executeScript("window") as netscape.javascript.JSObject
                window.setMember("javaLogger", WebViewLogger)
                webEngine.executeScript("""
                    console.log = function (message) { javaLogger.log(String(message)); };
                    console.error = function (message) { javaLogger.error(String(message)); };
                """.trimIndent())
            }.onFailure { logger.debug("Failed to install WebView logger", it) }
        }

        webEngine.loadWorker.stateProperty().addListener { _, _, newValue ->
            if (newValue == Worker.State.SUCCEEDED) {
                installWebStatusLocalization()
                updateWebStatusLocalization()
            }
        }

        scene.setOnKeyReleased { key ->
            if (key.isControlDown || key.isAltDown || key.isShiftDown || key.isMetaDown) return@setOnKeyReleased
            when (key.code) {
                switchDecorationsKey -> switchDecorations()
                onTopKey -> onTopItem.isSelected = toggleOnTop()
                clickTransparencyKey -> ctConfigurator?.let { clickTransparencyItem.isSelected = toggleClickTransparency(it) }
                viewersKey -> viewersItem.isSelected = toggleShowViewersBar()
                clearChatKey -> guiEventHandler.value.handleClearChat()
                showHiddenMessagesKey -> showHiddenMessages.isSelected = toggleShowHiddenMessages()
                closeChatKey -> guiEventHandler.value.handleStopChat()
                else -> {}
            }
        }

        webEngine.loadWorker.stateProperty().addListener { _, _, newValue ->
            if (newValue == Worker.State.SCHEDULED) {
                val location = webEngine.location
                val matcher = urlPattern.matcher(location)
                if (matcher.find() && location != lastOpenedSkinUrl) {
                    Platform.runLater { webEngine.loadWorker.cancel() }
                    logger.debug("Opening url in default browser: '{}'", location)
                    app.hostServices.showDocument(location)
                } else {
                    logger.debug("Opening url in web engine: '{}'", location)
                }
            }
        }
        return scene
    }

    private fun installWebStatusLocalization() {
        val script = """
            (function() {
                window.__failchatStatusLabels = window.__failchatStatusLabels || {
                    connected: "Connected",
                    disconnected: "Disconnected"
                };

                function detectStatus(node) {
                    var value = String(node.nodeValue || "");
                    var exact = value.replace(/^\s+|\s+$/g, "").toLowerCase();
                    if (exact === "connected" || exact === "disconnected") {
                        node.__failchatStatusKey = exact;
                        node.__failchatStatusPrefix = value.match(/^\s*/)[0];
                        node.__failchatStatusSuffix = value.match(/\s*$/)[0];
                        return true;
                    }

                    var suffixMatch = value.match(/^(.*?)(\s+)(connected|disconnected)(\s*)$/i);
                    if (!suffixMatch) return false;
                    node.__failchatStatusKey = suffixMatch[3].toLowerCase();
                    node.__failchatStatusPrefix = suffixMatch[1] + suffixMatch[2];
                    node.__failchatStatusSuffix = suffixMatch[4];
                    return true;
                }

                function replaceStatusTextNode(node) {
                    if (!node || node.nodeType !== 3) return;
                    if (!node.__failchatStatusKey && !detectStatus(node)) return;
                    var key = node.__failchatStatusKey;
                    var translated = window.__failchatStatusLabels[key];
                    if (typeof translated !== "string") return;
                    node.nodeValue = (node.__failchatStatusPrefix || "") + translated + (node.__failchatStatusSuffix || "");
                }

                function scan(root) {
                    if (!root) return;
                    if (root.nodeType === 3) {
                        replaceStatusTextNode(root);
                        return;
                    }
                    var children = root.childNodes;
                    if (!children) return;
                    for (var i = 0; i < children.length; i++) scan(children[i]);
                }

                window.failchatSetStatusLabels = function(connected, disconnected) {
                    window.__failchatStatusLabels = {
                        connected: String(connected),
                        disconnected: String(disconnected)
                    };
                    scan(document.body || document.documentElement);
                };

                if (!window.__failchatStatusLocalizationInstalled) {
                    window.__failchatStatusLocalizationInstalled = true;
                    var observer = new MutationObserver(function(mutations) {
                        for (var i = 0; i < mutations.length; i++) {
                            var mutation = mutations[i];
                            if (mutation.type === "characterData") {
                                replaceStatusTextNode(mutation.target);
                            } else {
                                for (var j = 0; j < mutation.addedNodes.length; j++) {
                                    scan(mutation.addedNodes[j]);
                                }
                            }
                        }
                    });
                    observer.observe(document.documentElement || document, {
                        subtree: true,
                        childList: true,
                        characterData: true
                    });
                    window.__failchatStatusObserver = observer;
                }

                scan(document.body || document.documentElement);
            })();
        """.trimIndent()

        runCatching { webEngine.executeScript(script) }
                .onFailure { logger.debug("Failed to install WebView status localization bridge", it) }
    }

    private fun updateWebStatusLocalization() {
        fun escapeJavaScript(value: String): String = value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")

        val connected = escapeJavaScript(UiLanguage.text("chat.status.connected"))
        val disconnected = escapeJavaScript(UiLanguage.text("chat.status.disconnected"))
        runCatching {
            webEngine.executeScript("if (window.failchatSetStatusLabels) window.failchatSetStatusLabels('$connected', '$disconnected');")
        }.onFailure {
            logger.debug("Web chat status localization bridge is not ready", it)
        }
    }

    private fun switchDecorations() {
        val fromStage = currentStage
        val toStage = if (fromStage === decoratedStage) {
            config.setProperty(ConfigKeys.frame, false)
            switchDecorationsItem.isSelected = false
            chatScene.fill = Color.TRANSPARENT
            transparentStage
        } else {
            config.setProperty(ConfigKeys.frame, true)
            switchDecorationsItem.isSelected = true
            chatScene.fill = Color.BLACK
            decoratedStage
        }
        saveChatPosition(fromStage)
        hideChatStage(fromStage)
        configureChatStage(toStage)
        showChatStage(toStage)
        currentStage = toStage
        logger.debug("Chat stage was switched from {} to {}", fromStage.style, toStage.style)
    }

    private fun configureChatStage(stage: Stage) {
        stage.opacity = config.getDouble(ConfigKeys.opacity) / 100
        stage.isAlwaysOnTop = config.getBoolean(ConfigKeys.onTop)
        if (config.getBoolean(ConfigKeys.clickTransparency)) stage.isAlwaysOnTop = true
        stage.width = config.getDouble("chat.width")
        stage.height = config.getDouble("chat.height")
        val x = config.getDouble("chat.x")
        val y = config.getDouble("chat.y")
        if (x != -1.0 && y != -1.0) {
            stage.x = x
            stage.y = y
        }
    }

    private fun hideChatStage(stage: Stage) {
        ctConfigurator?.removeClickTransparency(stage)
        stage.hide()
    }

    private fun showChatStage(stage: Stage) {
        stage.scene = chatScene
        UiTheme.apply(stage.scene)
        stage.show()
        ctConfigurator?.configureClickTransparency(stage)
    }

    private fun saveChatPosition(stage: Stage) {
        config.setProperty("chat.width", stage.width.toInt())
        config.setProperty("chat.height", stage.height.toInt())
        if (stage.x >= -10000 && stage.y >= -10000) {
            config.setProperty("chat.x", stage.x.toInt())
            config.setProperty("chat.y", stage.y.toInt())
        }
    }

    private fun updateLocalization() {
        switchDecorationsItem.text = UiLanguage.text("chat.menu.show-frame")
        onTopItem.text = UiLanguage.text("chat.menu.on-top")
        clickTransparencyItem.text = UiLanguage.text("chat.menu.click-through")
        viewersItem.text = UiLanguage.text("chat.menu.show-viewers")
        showHiddenMessages.text = UiLanguage.text("chat.menu.show-hidden-messages")
        zoomLabelText.text = UiLanguage.text("chat.menu.zoom")
        clearChatItem.text = UiLanguage.text("chat.menu.clear-chat")
        closeChatItem.text = UiLanguage.text("chat.menu.close-chat")
        updateWebStatusLocalization()
    }

    private fun updateContextMenu() {
        switchDecorationsItem.isSelected = config.getBoolean(ConfigKeys.frame)
        onTopItem.isSelected = config.getBoolean(ConfigKeys.onTop)
        viewersItem.isSelected = config.getBoolean(ConfigKeys.showViewers)
        zoomValueText.text = config.getString(ConfigKeys.zoomPercent)
        showHiddenMessages.isSelected = config.getBoolean(ConfigKeys.showHiddenMessages)
    }

    private fun toggleOnTop(): Boolean {
        val newValue = config.invertBoolean(ConfigKeys.onTop)
        currentStage.isAlwaysOnTop = newValue
        return newValue
    }

    private fun toggleClickTransparency(ctf: ClickTransparencyConfigurator): Boolean {
        val clickTransparencyEnabled = config.invertBoolean(ConfigKeys.clickTransparency)
        if (clickTransparencyEnabled) {
            // don't override configuration value for onTop option
            currentStage.isAlwaysOnTop = true
            onTopItem.isDisable = true
            ctf.configureClickTransparency(currentStage)
        } else {
            ctf.removeClickTransparency(currentStage)
            currentStage.isAlwaysOnTop = config.getBoolean(ConfigKeys.onTop)
            onTopItem.isDisable = false
        }
        guiEventHandler.value.handleConfigurationChange()
        return clickTransparencyEnabled
    }

    private fun toggleShowViewersBar(): Boolean {
        val newValue = config.invertBoolean(ConfigKeys.showViewers)
        guiEventHandler.value.handleConfigurationChange()
        return newValue
    }

    private fun toggleShowHiddenMessages(): Boolean {
        val newValue = config.invertBoolean(ConfigKeys.showHiddenMessages)
        guiEventHandler.value.handleConfigurationChange()
        return newValue
    }
}
