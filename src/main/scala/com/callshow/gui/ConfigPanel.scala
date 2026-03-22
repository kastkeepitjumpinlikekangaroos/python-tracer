package com.callshow.gui

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.{DirectoryChooser, Stage}
import java.io.File

import scala.jdk.CollectionConverters._

/**
 * Left-side configuration panel with directory picker, command editor,
 * and execute/stop buttons.
 */
class ConfigPanel(stage: Stage) extends VBox(10) {

  getStyleClass.add("config-panel")
  setPadding(new Insets(15))
  setPrefWidth(350)
  setMinWidth(300)

  // --- Directory selector ---
  private val dirLabel = new Label("Project Directory")
  dirLabel.getStyleClass.add("section-header")

  private val directoryCombo = new ComboBox[String]()
  directoryCombo.setEditable(true)
  directoryCombo.setMaxWidth(Double.MaxValue)
  directoryCombo.setPromptText("Select a directory...")

  // Load recent directories
  private val recentDirs = AppPreferences.getRecentDirectories
  directoryCombo.getItems.addAll(recentDirs.asJava)
  val lastDir = AppPreferences.getLastDirectory
  if (lastDir.nonEmpty) directoryCombo.getEditor.setText(lastDir)

  private val browseButton = new Button("Browse...")
  browseButton.setMaxWidth(Double.MaxValue)
  browseButton.setOnAction { _ =>
    val chooser = new DirectoryChooser()
    chooser.setTitle("Select Project Directory")
    val currentText = directoryCombo.getEditor.getText
    if (currentText != null && currentText.nonEmpty) {
      val current = new File(currentText)
      if (current.isDirectory) chooser.setInitialDirectory(current)
    }
    val dir = chooser.showDialog(stage)
    if (dir != null) directoryCombo.getEditor.setText(dir.getAbsolutePath)
  }

  private val dirBox = new VBox(5, dirLabel, directoryCombo, browseButton)

  // --- Command editor ---
  private val cmdLabel = new Label("Command Entry Point")
  cmdLabel.getStyleClass.add("section-header")

  private val commandEditor = new TextArea()
  commandEditor.setPromptText("python3 main.py\n# or multi-line bash commands")
  commandEditor.setPrefRowCount(4)
  commandEditor.setFont(javafx.scene.text.Font.font("Menlo", 13))

  // Load last command
  val lastCmd = AppPreferences.getLastCommand
  if (lastCmd.nonEmpty) commandEditor.setText(lastCmd)

  private val cmdBox = new VBox(5, cmdLabel, commandEditor)

  // --- Execute / Stop buttons ---
  private val executeButton = new Button("\u25b6  Execute")
  executeButton.setMaxWidth(Double.MaxValue)
  executeButton.getStyleClass.add("execute-button")

  private val stopButton = new Button("\u25a0  Stop")
  stopButton.setMaxWidth(Double.MaxValue)
  stopButton.setDisable(true)
  stopButton.getStyleClass.add("stop-button")

  private val buttonBox = new HBox(10, executeButton, stopButton)
  HBox.setHgrow(executeButton, Priority.ALWAYS)
  HBox.setHgrow(stopButton, Priority.ALWAYS)

  // --- Capture locals checkbox ---
  private val captureLocalsCheck = new CheckBox("Capture locals()")
  captureLocalsCheck.setTooltip(new Tooltip(
    "Capture local variables at each call/return. Adds overhead but enables debugging."
  ))

  // --- Separator ---
  private val sep = new Separator()

  getChildren.addAll(dirBox, cmdBox, captureLocalsCheck, buttonBox, sep)

  // --- Public API ---
  def getDirectory: String = directoryCombo.getEditor.getText
  def getCommand: String = commandEditor.getText

  def getCaptureLocals: Boolean = captureLocalsCheck.isSelected

  def setDirectory(dir: String): Unit = directoryCombo.getEditor.setText(dir)
  def setCommand(cmd: String): Unit = commandEditor.setText(cmd)

  def setOnExecute(handler: Runnable): Unit =
    executeButton.setOnAction(_ => handler.run())

  def setOnStop(handler: Runnable): Unit =
    stopButton.setOnAction(_ => handler.run())

  def setRunning(running: Boolean): Unit = {
    executeButton.setDisable(running)
    stopButton.setDisable(!running)
    directoryCombo.setDisable(running)
    browseButton.setDisable(running)
    commandEditor.setDisable(running)
  }

  def focusDirectory(): Unit = {
    directoryCombo.requestFocus()
    directoryCombo.getEditor.selectAll()
  }
}
