package com.callshow.gui

import javafx.geometry.{Insets, Orientation}
import javafx.scene.{Parent, Scene}
import javafx.scene.control._
import javafx.scene.input.{KeyCode, KeyCombination, KeyEvent}
import javafx.scene.layout._
import javafx.stage.{FileChooser, Stage}

import java.io.File
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
  private var lastSavePath: File = _

  // Cached exclude patterns (rebuilt when filters change, not per-event)
  @volatile private var cachedExcludes: List[String] = Nil

  def createRoot(): Parent = {
    // Wire up execute/stop buttons
    configPanel.setOnExecute(() => onExecute())
    configPanel.setOnStop(() => onStop())

    // Wire up filter changes
    filterPanel.setOnFilterChange(() => onFilterChanged())

    // --- Menu bar ---
    val menuBar = createMenuBar()

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
    root.setTop(menuBar)
    root.setCenter(splitPane)
    root.setBottom(statusBar)

    root
  }

  private def createMenuBar(): MenuBar = {
    val menuBar = new MenuBar()
    menuBar.setUseSystemMenuBar(true) // native macOS menu bar

    // --- File menu ---
    val fileMenu = new Menu("File")

    val openItem = new MenuItem("Open Trace...")
    openItem.setAccelerator(KeyCombination.keyCombination("Meta+O"))
    openItem.setOnAction(_ => onOpenTrace())

    val saveItem = new MenuItem("Save Trace As...")
    saveItem.setAccelerator(KeyCombination.keyCombination("Meta+S"))
    saveItem.setOnAction(_ => onSaveTrace())

    fileMenu.getItems.addAll(openItem, saveItem)

    menuBar.getMenus.add(fileMenu)
    menuBar
  }

  def registerKeyboardShortcuts(scene: Scene): Unit = {
    scene.addEventFilter(KeyEvent.KEY_PRESSED, (event: KeyEvent) => {
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

  // --- Open / Save ---

  private def onOpenTrace(): Unit = {
    val chooser = new FileChooser()
    chooser.setTitle("Open Trace File")
    chooser.getExtensionFilters.addAll(
      new FileChooser.ExtensionFilter("CallShow Traces", "*.json"),
      new FileChooser.ExtensionFilter("All Files", "*.*")
    )

    // Start in last-used directory if available
    val lastDir = AppPreferences.getLastDirectory
    if (lastDir != null && lastDir.nonEmpty) {
      val dir = new File(lastDir)
      if (dir.isDirectory) chooser.setInitialDirectory(dir)
    }

    val file = chooser.showOpenDialog(stage)
    if (file == null) return

    loadTraceFile(file)
  }

  private def loadTraceFile(file: File): Unit = {
    try {
      val data = TraceLoader.load(file)

      // Clear and populate
      callStackView.clear()
      allEvents.clear()
      allEvents.addAll(data.events)

      currentDirectory = data.directory
      callStackView.setBaseDirectory(data.directory)
      lastSavePath = file

      // Apply current filters
      applyFilters()

      // Update status
      statusBar.setComplete(callStackView.getEventCount, data.durationSeconds, data.exitCode)
      statusBar.setFilterInfo(callStackView.getEventCount, allEvents.size)

      // Update title
      stage.setTitle(s"CallShow \u2014 ${file.getName}")

      // Show metadata in output tab
      callStackView.appendOutput(s"Loaded trace: ${file.getAbsolutePath}")
      callStackView.appendOutput(s"Command: ${data.command}")
      callStackView.appendOutput(s"Directory: ${data.directory}")
      callStackView.appendOutput(f"Duration: ${data.durationSeconds}%.2fs")
      callStackView.appendOutput(s"Total events: ${data.totalEvents}")
      callStackView.appendOutput(s"Events after filtering: ${callStackView.getEventCount}")

    } catch {
      case e: Exception =>
        showAlert(s"Failed to load trace file:\n${e.getMessage}")
    }
  }

  private def onSaveTrace(): Unit = {
    if (allEvents.isEmpty) {
      showAlert("No trace data to save. Run a trace first.")
      return
    }

    val chooser = new FileChooser()
    chooser.setTitle("Save Trace As")
    chooser.getExtensionFilters.add(
      new FileChooser.ExtensionFilter("CallShow Traces", "*.json")
    )
    chooser.setInitialFileName("callshow_trace.json")

    if (lastSavePath != null) {
      chooser.setInitialDirectory(lastSavePath.getParentFile)
      chooser.setInitialFileName(lastSavePath.getName)
    }

    val file = chooser.showSaveDialog(stage)
    if (file == null) return

    try {
      // Build the JSON structure matching Python's output format
      val gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create()

      val metadata = new com.google.gson.JsonObject()
      metadata.addProperty("command", if (currentDirectory.nonEmpty) configPanel.getCommand else "loaded from file")
      metadata.addProperty("directory", currentDirectory)
      metadata.addProperty("duration_seconds", 0.0)
      metadata.addProperty("exit_code", 0)
      metadata.addProperty("total_events", allEvents.size)

      val eventsArray = new com.google.gson.JsonArray()
      allEvents.forEach { event =>
        eventsArray.add(gson.toJsonTree(event))
      }

      val root = new com.google.gson.JsonObject()
      root.add("metadata", metadata)
      root.add("events", eventsArray)

      val writer = new java.io.FileWriter(file)
      gson.toJson(root, writer)
      writer.close()

      lastSavePath = file
      stage.setTitle(s"CallShow \u2014 ${file.getName}")
      callStackView.appendOutput(s"Trace saved to: ${file.getAbsolutePath}")

    } catch {
      case e: Exception =>
        showAlert(s"Failed to save trace:\n${e.getMessage}")
    }
  }

  // --- Execute ---

  private def onExecute(): Unit = {
    if (configPanel.isAttachMode) {
      onExecuteAttach()
    } else {
      onExecuteLaunch()
    }
  }

  private def onExecuteLaunch(): Unit = {
    val directory = configPanel.getDirectory
    val command = configPanel.getCommand

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

    AppPreferences.addRecentDirectory(directory)
    AppPreferences.setLastDirectory(directory)
    AppPreferences.setLastCommand(command)

    prepareForExecution(directory)
    stage.setTitle("CallShow \u2014 Python Call Stack Analyzer")

    val tmpDir = Files.createTempDirectory("callshow_gui_")
    val eventsFile = tmpDir.resolve("trace_events.jsonl")
    val resultFile = tmpDir.resolve("trace_result.json")

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
    cmd.add("--json-stream")

    appendExcludesAndLocals(cmd)

    executionRunner.execute(
      tracerCommand = cmd,
      directory = directory,
      onEvent = (event: CallEvent) => onEventReceived(event),
      onStdout = (line: String) => callStackView.appendOutput(line),
      onComplete = (exitCode: Int, duration: Double) => onExecutionComplete(exitCode, duration),
      eventsFilePath = eventsFile
    )
  }

  private def onExecuteAttach(): Unit = {
    val pidText = configPanel.getAttachPid

    if (pidText.isEmpty) {
      showAlert("Please enter a process ID (PID).")
      return
    }
    val pid = try {
      pidText.toInt
    } catch {
      case _: NumberFormatException =>
        showAlert(s"Invalid PID: $pidText")
        return
    }

    prepareForExecution("")
    stage.setTitle(s"CallShow \u2014 Attached to PID $pid")

    val tmpDir = Files.createTempDirectory("callshow_gui_")
    val eventsFile = tmpDir.resolve("trace_events.jsonl")
    val resultFile = tmpDir.resolve("trace_result.json")

    val tracerBase = TracerLocator.findTracer()
    val cmd = new java.util.ArrayList[String](tracerBase)
    cmd.add("--attach")
    cmd.add(pid.toString)
    cmd.add("--output")
    cmd.add(resultFile.toString)
    cmd.add("--events-file")
    cmd.add(eventsFile.toString)
    cmd.add("--json-stream")

    appendExcludesAndLocals(cmd)

    callStackView.appendOutput(s"Attaching to PID $pid...")

    executionRunner.execute(
      tracerCommand = cmd,
      directory = System.getProperty("user.dir"),
      onEvent = (event: CallEvent) => onEventReceived(event),
      onStdout = (line: String) => callStackView.appendOutput(line),
      onComplete = (exitCode: Int, duration: Double) => onExecutionComplete(exitCode, duration),
      eventsFilePath = eventsFile
    )
  }

  private def prepareForExecution(directory: String): Unit = {
    callStackView.clear()
    allEvents.clear()
    currentDirectory = directory
    callStackView.setBaseDirectory(directory)
    cachedExcludes = filterPanel.getExcludePatterns.asScala.toList
    configPanel.setRunning(true)
    statusBar.setRunning(0)
  }

  private def appendExcludesAndLocals(cmd: java.util.ArrayList[String]): Unit = {
    val excludes = filterPanel.getExcludePatterns
    for (pattern <- excludes.asScala) {
      cmd.add("--exclude")
      cmd.add(pattern)
    }
    if (configPanel.getCaptureLocals) {
      cmd.add("--capture-locals")
    }
  }

  private def onStop(): Unit = {
    executionRunner.stop()
    configPanel.setRunning(false)
    statusBar.setComplete(allEvents.size, 0.0, -1)
  }

  private def onEventReceived(event: CallEvent): Unit = {
    allEvents.add(event)

    if (passesFilter(event)) {
      callStackView.addEvent(event)
    }

    // Only update status bar every 100 events to reduce UI work
    val size = allEvents.size
    if (size % 100 == 0 || size < 100) {
      statusBar.setRunning(size)
    }
  }

  private def onExecutionComplete(exitCode: Int, duration: Double): Unit = {
    configPanel.setRunning(false)
    callStackView.streamingComplete()

    applyFilters()

    statusBar.setComplete(callStackView.getEventCount, duration, exitCode)
    statusBar.setFilterInfo(callStackView.getEventCount, allEvents.size)
  }

  private def onFilterChanged(): Unit = {
    cachedExcludes = filterPanel.getExcludePatterns.asScala.toList
    applyFilters()
    statusBar.setFilterInfo(callStackView.getEventCount, allEvents.size)
  }

  private def applyFilters(): Unit = {
    cachedExcludes = filterPanel.getExcludePatterns.asScala.toList
    val filtered = allEvents.asScala.filter(e => passesFilter(e)).asJava
    callStackView.setEvents(filtered)
  }

  private def passesFilter(event: CallEvent): Boolean = {
    if (event.file_path == null) return true
    val fp = event.file_path
    !cachedExcludes.exists(pattern => fp.contains(pattern))
  }

  private def showAlert(message: String): Unit = {
    val alert = new Alert(Alert.AlertType.WARNING)
    alert.setTitle("CallShow")
    alert.setHeaderText(null)
    alert.setContentText(message)
    alert.showAndWait()
  }
}
