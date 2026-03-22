package com.callshow.gui

import javafx.application.Application
import javafx.stage.Stage
import javafx.scene.Scene

class CallShowApp extends Application {
  override def start(primaryStage: Stage): Unit = {
    primaryStage.setTitle("CallShow \u2014 Python Call Stack Analyzer")

    val controller = new AppController(primaryStage)
    val root = controller.createRoot()

    val scene = new Scene(root, 1200, 800)

    // Load dark theme
    val css = getClass.getResource("dark-theme.css")
    if (css != null) {
      scene.getStylesheets.add(css.toExternalForm)
    }

    // Register keyboard shortcuts
    controller.registerKeyboardShortcuts(scene)

    primaryStage.setScene(scene)
    primaryStage.show()
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    Application.launch(classOf[CallShowApp], args: _*)
  }
}
