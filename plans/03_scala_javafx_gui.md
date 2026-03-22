# Plan 03: Scala JavaFX GUI

## Goal

Build a desktop GUI using Scala and JavaFX that allows the user to:
1. Select a project directory via file explorer
2. Enter a multi-line bash command as the entry point
3. Execute the Python tracer and view call-stack events streaming in real time
4. View the final persistent result after execution completes
5. Filter call sites by directory path patterns
6. Toggle site-packages exclusion (excluded by default)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  Main.scala                                                  │
│  - JavaFX Application entry point                            │
│  - Initializes primary stage                                 │
└──────────┬──────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────┐
│  AppController.scala                                         │
│  - Orchestrates UI state transitions                         │
│  - Manages execution lifecycle                               │
│  - Handles filter state                                      │
└──────────┬──────────────────────────────────────────────────┘
           │
     ┌─────┴──────┬────────────────┬──────────────────┐
     ▼            ▼                ▼                   ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────────────┐
│ Config   │ │ Execute  │ │ CallStack    │ │ FilterPanel      │
│ Panel    │ │ Runner   │ │ View         │ │                  │
│          │ │          │ │              │ │ - Exclude input  │
│ - Dir    │ │ - Spawns │ │ - TreeView   │ │ - Site-pkg toggle│
│   picker │ │   python │ │   or ListView│ │ - Apply/clear    │
│ - Command│ │   tracer │ │ - Streams in │ │                  │
│   editor │ │ - Reads  │ │ - Persistent │ │                  │
│ - Run btn│ │   JSONL  │ │   after done │ │                  │
└──────────┘ └──────────┘ └──────────────┘ └──────────────────┘
```

---

## Steps

### 3.1 — Main.scala (Application Entry Point)

```scala
package com.callshow.gui

import javafx.application.Application
import javafx.stage.Stage
import javafx.scene.Scene

class CallShowApp extends Application {
  override def start(primaryStage: Stage): Unit = {
    primaryStage.setTitle("CallShow — Python Call Stack Analyzer")

    val controller = new AppController(primaryStage)
    val root = controller.createRoot()

    val scene = new Scene(root, 1200, 800)
    primaryStage.setScene(scene)
    primaryStage.show()
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    Application.launch(classOf[CallShowApp], args: _*)
  }
}
```

### 3.2 — AppController.scala (UI Orchestration)

Manages the overall layout and state:

```
┌─────────────────────────────────────────────────────┐
│  Top: Menu bar (File > Export JSON, Help > About)    │
├──────────────┬──────────────────────────────────────┤
│  Left Panel  │  Center/Right: Call Stack View        │
│              │                                       │
│  Directory:  │  ┌─────────────────────────────────┐ │
│  [__________]│  │ [streaming call events]          │ │
│  [Browse...] │  │                                   │ │
│              │  │ depth | function | file:line      │ │
│  Command:    │  │ 0     | main     | main.py:10    │ │
│  ┌─────────┐│  │ 1     | setup    | main.py:5     │ │
│  │ python  ││  │ 2     | connect  | db.py:22      │ │
│  │ main.py ││  │ ...                               │ │
│  └─────────┘│  │                                   │ │
│              │  └─────────────────────────────────┘ │
│  [▶ Execute] │                                      │
│  [■ Stop]    │  Bottom: Status bar                  │
│              │  "Tracing... 1,234 events captured"  │
├──────────────┤                                      │
│  Filters     │                                      │
│              │                                      │
│  ☑ Exclude   │                                      │
│    site-pkgs │                                      │
│              │                                      │
│  Exclude     │                                      │
│  patterns:   │                                      │
│  [__________]│                                      │
│  [+ Add]     │                                      │
│  - venv/     │                                      │
│  - .tox/     │                                      │
│              │                                      │
└──────────────┴──────────────────────────────────────┘
```

**Responsibilities**:
- Create the SplitPane layout (left config + right results)
- Wire up Execute button to `ExecutionRunner`
- Manage filter state and re-filter displayed events when filters change
- Handle UI state transitions: idle → running → complete

```scala
class AppController(stage: Stage) {
  private val configPanel = new ConfigPanel(stage)
  private val callStackView = new CallStackView()
  private val filterPanel = new FilterPanel()
  private val executionRunner = new ExecutionRunner()
  private val statusBar = new StatusBar()

  // State
  private var allEvents: List[CallEvent] = Nil
  private var isRunning: Boolean = false

  def createRoot(): Parent = {
    // SplitPane with left (config + filters) and right (call stack)
    // Bottom: status bar
    ...
  }

  def onExecute(): Unit = {
    // 1. Clear previous results
    // 2. Get directory and command from configPanel
    // 3. Get exclude patterns from filterPanel
    // 4. Start executionRunner with callbacks
    // 5. Update status to "running"
  }

  def onEventReceived(event: CallEvent): Unit = {
    // Called on JavaFX thread via Platform.runLater
    // 1. Add to allEvents
    // 2. If passes current filters, add to callStackView
    // 3. Update status bar count
  }

  def onExecutionComplete(exitCode: Int, duration: Double): Unit = {
    // 1. Update status to "complete"
    // 2. Mark results as persistent
  }
}
```

### 3.3 — ConfigPanel.scala (Directory + Command Input)

```scala
class ConfigPanel(stage: Stage) extends VBox {
  // Directory selector
  private val directoryField = new TextField()
  private val browseButton = new Button("Browse...")

  // Command editor (multi-line)
  private val commandEditor = new TextArea()

  // Execute / Stop buttons
  private val executeButton = new Button("Execute")
  private val stopButton = new Button("Stop")

  // Browse button opens DirectoryChooser
  browseButton.setOnAction { _ =>
    val chooser = new DirectoryChooser()
    chooser.setTitle("Select Project Directory")
    val dir = chooser.showDialog(stage)
    if (dir != null) directoryField.setText(dir.getAbsolutePath)
  }

  // Public accessors
  def getDirectory: String = directoryField.getText
  def getCommand: String = commandEditor.getText
  def setOnExecute(handler: Runnable): Unit = ...
  def setOnStop(handler: Runnable): Unit = ...
  def setRunning(running: Boolean): Unit = {
    executeButton.setDisable(running)
    stopButton.setDisable(!running)
  }
}
```

### 3.4 — ExecutionRunner.scala (Process Management)

Spawns the Python tracer as a subprocess and reads streaming output:

```scala
class ExecutionRunner {
  private var process: Option[Process] = None
  private var readerThread: Option[Thread] = None

  def execute(
    directory: String,
    command: String,
    excludePatterns: List[String],
    onEvent: CallEvent => Unit,        // called per event (background thread)
    onComplete: (Int, Double) => Unit   // called when done
  ): Unit = {
    val outputFile = Files.createTempFile("callshow_trace_", ".jsonl")

    // Build the command to invoke the Python tracer
    val tracerCmd = buildTracerCommand(directory, command, outputFile, excludePatterns)

    // Start the subprocess
    val pb = new ProcessBuilder("bash", "-c", tracerCmd)
    pb.directory(new File(directory))
    pb.redirectErrorStream(true)
    val proc = pb.start()
    process = Some(proc)

    // Start reader thread for streaming JSONL
    val reader = new Thread(() => {
      // Tail the JSONL output file
      // Parse each line as a CallEvent
      // Call Platform.runLater { onEvent(event) } for each
    })
    reader.setDaemon(true)
    reader.start()
    readerThread = Some(reader)

    // Wait for completion in background
    new Thread(() => {
      val exitCode = proc.waitFor()
      reader.join(2000)
      val duration = ... // from trace metadata
      Platform.runLater(() => onComplete(exitCode, duration))
    }).start()
  }

  def stop(): Unit = {
    process.foreach(_.destroyForcibly())
    process = None
  }

  private def buildTracerCommand(
    directory: String, command: String,
    outputFile: Path, excludePatterns: List[String]
  ): String = {
    // Invoke: python -m callshow.cli --directory <dir> --command <cmd> --output <out>
    // Or invoke the Bazel-built Python binary
    ...
  }
}
```

### 3.5 — CallStackView.scala (Event Display)

A scrollable view showing call events as they stream in:

```scala
class CallStackView extends VBox {
  // Tab pane: "Live Stream" | "Summary"
  private val tabPane = new TabPane()

  // Live stream: TableView with columns
  private val eventsTable = new TableView[CallEvent]()
  // Columns: Depth, Type (call/return), Function, File:Line, Timestamp

  // Summary: TreeView showing call hierarchy
  private val callTree = new TreeView[String]()

  def addEvent(event: CallEvent): Unit = {
    // Add to table (auto-scroll to bottom during streaming)
    eventsTable.getItems.add(event)
    if (autoScroll) {
      eventsTable.scrollTo(eventsTable.getItems.size - 1)
    }
  }

  def clear(): Unit = {
    eventsTable.getItems.clear()
    callTree.setRoot(null)
  }

  def setEvents(events: List[CallEvent]): Unit = {
    // Bulk replace (used when filters change)
    eventsTable.getItems.setAll(events.asJava)
    rebuildTree(events)
  }

  def buildSummaryTree(events: List[CallEvent]): Unit = {
    // Build a tree from call/return pairs
    // Root nodes are depth-0 calls
    // Children are nested calls
    ...
  }
}
```

**TableView columns**:
| Column | Width | Content |
|--------|-------|---------|
| Depth | 60px | Numeric depth, optionally with indentation |
| Type | 60px | "→" for call, "←" for return |
| Function | 200px | Function name |
| Location | flex | file_path:line_number (relative to project dir if possible) |
| Time | 100px | Timestamp in seconds |

### 3.6 — FilterPanel.scala (Exclusion Controls)

```scala
class FilterPanel extends VBox {
  // Checkbox: exclude site-packages (default: checked)
  private val excludeSitePackages = new CheckBox("Exclude site-packages")
  excludeSitePackages.setSelected(true)

  // List of custom exclude patterns
  private val excludePatterns = FXCollections.observableArrayList[String]()
  private val patternList = new ListView[String](excludePatterns)
  private val patternInput = new TextField()
  private val addButton = new Button("+ Add")
  // Each list item has a remove button

  // Callback when filters change
  private var onFilterChange: () => Unit = () => ()

  def getExcludePatterns: List[String] = {
    val patterns = excludePatterns.asScala.toList
    if (excludeSitePackages.isSelected) {
      "site-packages" :: "lib/python" :: "importlib" :: "<frozen" :: patterns
    } else {
      patterns
    }
  }

  def setOnFilterChange(handler: () => Unit): Unit = {
    onFilterChange = handler
    // Wire up to checkbox and list changes
  }
}
```

### 3.7 — StatusBar.scala

```scala
class StatusBar extends HBox {
  private val statusLabel = new Label("Ready")
  private val eventCountLabel = new Label("")
  private val durationLabel = new Label("")

  def setRunning(eventCount: Int): Unit = {
    statusLabel.setText("Tracing...")
    eventCountLabel.setText(s"$eventCount events captured")
  }

  def setComplete(eventCount: Int, duration: Double, exitCode: Int): Unit = {
    statusLabel.setText(if (exitCode == 0) "Complete" else s"Exited with code $exitCode")
    eventCountLabel.setText(s"$eventCount events")
    durationLabel.setText(f"${duration}%.2fs")
  }

  def setReady(): Unit = {
    statusLabel.setText("Ready")
    eventCountLabel.setText("")
    durationLabel.setText("")
  }
}
```

### 3.8 — CallEvent.scala (Scala data model)

Mirror the Python CallEvent for Gson deserialization:

```scala
package com.callshow.gui

case class CallEvent(
  event_type: String,
  function_name: String,
  file_path: String,
  line_number: Int,
  timestamp: Double,
  depth: Int,
  caller_function: Option[String],
  caller_file: Option[String],
  caller_line: Option[Int]
)
```

Note: Gson doesn't handle Scala `Option` natively. Either:
- Use a custom TypeAdapter for Option
- Use Java-style nullable fields with null checks
- Use a simple Scala JSON parser

**Decision**: Use nullable Java-style fields for Gson compatibility (simplest approach):

```scala
class CallEvent {
  var event_type: String = _
  var function_name: String = _
  var file_path: String = _
  var line_number: Int = _
  var timestamp: Double = _
  var depth: Int = _
  var caller_function: String = _  // null if absent
  var caller_file: String = _      // null if absent
  var caller_line: Int = _         // 0 if absent
}
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| JavaFX TableView for events | Virtualized scrolling handles large event lists efficiently |
| Two tabs (Live + Summary) | Live stream for real-time monitoring; summary tree for post-hoc analysis |
| Filter changes re-filter in memory | All events stored in `allEvents`; filtering is O(n) re-scan, fast enough for typical traces |
| Platform.runLater for UI updates | Required by JavaFX threading model; batch if events are very rapid |
| Subprocess for Python tracer | Clean separation; GUI just needs to read JSONL output |
| Relative paths in display | Show `src/main.py:10` instead of `/Users/foo/project/src/main.py:10` for readability |

---

## UI State Machine

```
IDLE ──[Execute]──► RUNNING ──[Complete]──► RESULTS
  ▲                    │                      │
  │                    │[Stop]                │[Execute]
  │                    ▼                      │
  └────────────────── IDLE ◄──────────────────┘
```

- **IDLE**: Execute enabled, Stop disabled, view empty or showing previous results
- **RUNNING**: Execute disabled, Stop enabled, events streaming in, status shows count
- **RESULTS**: Execute enabled, Stop disabled, full results displayed, filters active

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Very rapid events overwhelm UI | Batch `Platform.runLater` calls (e.g., every 50ms) instead of per-event |
| Large event lists slow TableView | TableView is virtualized; also offer summary view as alternative |
| JavaFX module system issues at runtime | JVM flags: `--add-modules javafx.controls,javafx.fxml` |
| Finding Python tracer binary at runtime | Either use `bazel run` to set up paths, or embed tracer path as a resource/config |
| File path display too long | Show relative paths; tooltip shows absolute path |

---

## Done When

- GUI launches with all panels visible
- Directory chooser opens native file picker
- Multi-line command editor accepts input
- Execute button spawns tracer and events stream into the table
- Stop button kills the running trace
- Filters update the displayed events in real time
- Status bar shows event count and duration
- Results persist after execution completes
