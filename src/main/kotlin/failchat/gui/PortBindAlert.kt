package failchat.gui

import failchat.FailchatServerInfo
import javafx.application.Application
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.stage.Stage

class PortBindAlert : Application() {

    override fun start(primaryStage: Stage) {
        UiLanguage.initializeFromUserConfiguration()

        val alert = Alert(AlertType.ERROR)

        alert.title = UiLanguage.text("dialog.launch-error.title")
        alert.headerText = UiLanguage.text("dialog.launch-error.header")
        alert.contentText = UiLanguage.text("dialog.launch-error.content") + "${FailchatServerInfo.host.hostAddress}:${FailchatServerInfo.port}"

        val stage = alert.dialogPane.scene.window as Stage
        stage.icons.setAll(Images.appIcon)

        alert.showAndWait()
    }
}
