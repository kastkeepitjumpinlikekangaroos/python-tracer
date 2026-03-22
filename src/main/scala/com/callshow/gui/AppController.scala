package com.callshow.gui

import javafx.geometry.{Insets, Orientation}
import javafx.scene.{Parent, Scene}
import javafx.scene.control._
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.layout._
import javafx.stage.Stage

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

/**
 * Main application controller that orchestrates the UI layout,
 * state transitions, and wiring between components.
 */
class AppController(stage: Stage) {

  private val configPanel = new ConfigPanel(stage)
  private val filterPanel = new FilterPanel()
  private val callStackView = new CallStackView()
  private val statusBar = new StatusBar()
  private val executionRunner = new ExecutionRunner()

  // All events (unfiltered)
  private var allEvents = new java.util.ArrayList[CallEvent]()
  private var currentDirectory: String = ""

  def createRoot(): Parent = {
    // Wire up execute/stop buttons
    configPanel.setOnExecute(() => onExecute())
    configPanel.setOnStop(() => onStop())

    // Wire up filter changes
    filterPanel.setOnFilterChange(() => onFilterChanged())

    // Left panel: config + filters in a scroll pane
    val leftPanel = new VBox(configPanel, filterPanel)
    VBox.setVgrow(filterPanel, Priority.ALWAYS)

    val leftScroll = new ScrollPane(leftPanel)
    leftScroll.setFitToWidth(true)
    leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)
    leftScroll.setPrefWidth(320)
    leftScroll.setMinWidth(280)

    // Main split pane
    val splitPane = new SplitPane()
    splitPane.setOrientation(Orientation.HORIZONTAL)
    splitPane.getItems.addAll(leftScroll, callStackView)
    splitPane.setDividerPositions(0.25)
    SplitPane.setResizableWithParent(leftScroll, false)

    // Root layout
    val root = new BorderPane()
    root.setCenter(splitPane)
    root.setBottom(statusBar)

    root
  }

  def registerKeyboardShortcuts(scene: Scene): Unit = {
    scene.addEventFilter(KeyEvent.KEY_PRESSED, (event: KeyEvent) => {
      // Cmd/Ctrl key combos
      if (event.isMetaDown || event.isControlDown) {
        event.getCode match {
          case KeyCode.R =>
            onExecute()
            event.consume()
          case KeyCode.PERIOD =>
            onStop()
            event.consume()
          case KeyCode.L =>
            configPanel.focusDirectory()
            event.consume()
          case KeyCode.F =>
            callStackView.focusSearch()
            event.consume()
          case KeyCode.DIGIT1 =>
            callStackView.selectTab(0)
            event.consume()
          case KeyCode.DIGIT2 =>
            callStackView.selectTab(1)
            event.consume()
          case KeyCode.DIGIT3 =>
            callStackView.selectTab(2)
            event.consume()
          case _ =>
        }
      }
      // Standalone keys
      if (!event.isConsumed) {
        event.getCode match {
          case KeyCode.ESCAPE =>
            if (executionRunner.isRunning) {
              onStop()
              event.consume()
            }
          case _ =>
        }
      }
    })
  }

  private def onExecute(): Unit = {
    val directory = configPanel.getDirectory
    val command = configPanel.getCommand

    // Validate
    if (directory == null || directory.trim.isEmpty) {
      showAlert("Please select a project directory.")
      return
    }
    if (!new java.io.File(directory).isDirectory) {
      showAlert(s"Directory does not exist: $directory")
      return
    }
    if (command == null || command.trim.isEmpty) {
      showAlert("Please enter a command to execute.")
      return
    }

    // Save to preferences
    AppPreferences.addRecentDirectory(directory)
    AppPreferences.setLastDirectory(directory)
    AppPreferences.setLastCommand(command)

    // Clear previous results
    callStackView.clear()
    allEvents.clear()
    currentDirectory = directory
    callStackView.setBaseDirectory(directory)

    // Set UI to running state
    configPanel.setRunning(true)
    statusBar.setRunning(0)

    // Create temp directory for this trace session
    val tmpDir = Files.createTempDirectory("callshow_gui_")
    val eventsFile = tmpDir.resolve("trace_events.jsonl")
    val resultFile = tmpDir.resolve("trace_result.json")

    // Build tracer command
    val tracerBase = TracerLocator.findTracer()
    val cmd = new java.util.ArrayList[String](tracerBase)
    cmd.add("--directory")
    cmd.add(directory)
    cmd.add("--command")
    cmd.add(command)
    cmd.add("--output")
    cmd.add(resultFile.toString)
    cmd.add("--events-file")
    cmd.add(eventsFile.toString)

    // Add exclude patterns
    val excludes = filterPanel.getExcludePatterns
    for (pattern <- excludes.asScala) {
      cmd.add("--exclude")
      cmd.add(pattern)
    }

    // Capture locals if checked
    if (configPanel.getCaptureLocals) {
      cmd.add("--capture-locals")
    }

    // Execute
    executionRunner.execute(
      tracerCommand = cmd,
      directory = directory,
      onEvent = (event: CallEvent) => onEventReceived(event),
      onStdout = (line: String) => callStackView.appendOutput(line),
      onComplete = (exitCode: Int, duration: Double) => onExecutionComplete(exitCode, duration),
      eventsFilePath = eventsFile
    )
  }

  private def onStop(): Unit = {
    executionRunner.stop()
    configPanel.setRunning(false)
    statusBar.setComplete(allEvents.size, 0.0, -1)
  }

  private def onEventReceived(event: CallEvent): Unit = {
    allEvents.add(event)

    // Check if event passes current filters
    if (passesFilter(event)) {
      callStackView.addEvent(event)
    }

    statusBar.setRunning(allEvents.size)
  }

  private def onExecutionComplete(exitCode: Int, duration: Double): Unit = {
    configPanel.setRunning(false)

    // Rebuild the view with current filters
    applyFilters()

    statusBar.setComplete(callStackView.getEventCount, duration, exitCode)
    statusBar.setFilterInfo(callStackView.getEventCount, allEvents.size)
  }

  private def onFilterChanged(): Unit = {
    applyFilters()
    statusBar.setFilterInfo(callStackView.getEventCount, allEvents.size)
  }

  private def applyFilters(): Unit = {
    val excludes = filterPanel.getExcludePatterns.asScala.toList
    val filtered = allEvents.asScala.filter(e => passesFilter(e, excludes)).asJava
    callStackView.setEvents(filtered)
  }

  private def passesFilter(event: CallEvent): Boolean = {
    passesFilter(event, filterPanel.getExcludePatterns.asScala.toList)
  }

  private def passesFilter(event: CallEvent, excludes: List[String]): Boolean = {
    if (event.file_path == null) return true
    !excludes.exists(pattern => event.file_path.contains(pattern))
  }

  private def showAlert(message: String): Unit = {
    val alert = new Alert(Alert.AlertType.WARNING)
    alert.setTitle("CallShow")
    alert.setHeaderText(null)
    alert.setContentText(message)
    alert.showAndWait()
  }
}
