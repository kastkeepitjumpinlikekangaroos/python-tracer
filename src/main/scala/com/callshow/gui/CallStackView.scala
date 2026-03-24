package com.callshow.gui

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.{Insets, Orientation, Pos}
import javafx.scene.control._
import javafx.scene.input.{KeyCode, MouseButton}
import javafx.scene.layout._
import javafx.scene.text.Font

import scala.jdk.CollectionConverters._

/**
 * Main display area showing traced call events.
 * Optimized for large traces (50k+ events).
 */
class CallStackView extends VBox {

  private val tabPane = new TabPane()
  VBox.setVgrow(tabPane, Priority.ALWAYS)

  // ---- Live Stream Tab (TableView + Locals detail) ----
  private val eventsTable = new TableView[CallEvent]()
  private val allItems = FXCollections.observableArrayList[CallEvent]()
  private val filteredItems = new FilteredList[CallEvent](allItems)
  eventsTable.setItems(filteredItems)
  eventsTable.setPlaceholder(new Label("No events yet. Select a directory, enter a command, and click Execute."))

  // Columns: Call Stack (indented function), Location, Time
  private val callStackCol = new TableColumn[CallEvent, String]("Call Stack")
  callStackCol.setPrefWidth(400)
  callStackCol.setMinWidth(200)
  callStackCol.setSortable(false)
  callStackCol.setCellValueFactory(cell =>
    new SimpleStringProperty(cell.getValue.function_name))
  callStackCol.setCellFactory(_ => new CallStackCell())

  private val locationCol = new TableColumn[CallEvent, String]("Location")
  locationCol.setPrefWidth(280)
  locationCol.setMinWidth(150)
  locationCol.setSortable(false)
  locationCol.setCellValueFactory(cell =>
    new SimpleStringProperty(cell.getValue.relativeLocation(baseDirectory)))
  locationCol.setCellFactory(_ => new LocationCell())

  private val timeCol = new TableColumn[CallEvent, String]("Time")
  timeCol.setPrefWidth(80)
  timeCol.setMaxWidth(100)
  timeCol.setMinWidth(60)
  timeCol.setSortable(false)
  timeCol.setCellValueFactory(cell =>
    new SimpleStringProperty(f"${cell.getValue.timestamp}%.4f"))

  eventsTable.getColumns.addAll(callStackCol, locationCol, timeCol)
  eventsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)

  // Row factory for call/return coloring
  eventsTable.setRowFactory(_ => new TableRow[CallEvent] {
    override def updateItem(item: CallEvent, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      getStyleClass.removeAll("call-row", "return-row")
      if (!empty && item != null) {
        if (item.event_type == "call") {
          getStyleClass.add("call-row")
        } else {
          getStyleClass.add("return-row")
        }
      }
    }
  })

  // Double-click to open in editor
  eventsTable.setOnMouseClicked { event =>
    if (event.getClickCount == 2 && event.getButton == MouseButton.PRIMARY) {
      openSelectedInEditor()
    }
  }
  eventsTable.setOnKeyPressed { event =>
    if (event.getCode == KeyCode.ENTER) {
      openSelectedInEditor()
      event.consume()
    }
  }

  // --- Locals detail panel (below table) ---
  private val localsHeader = new Label("Locals")
  localsHeader.getStyleClass.add("section-header")
  localsHeader.setStyle("-fx-text-fill: #a6adc8; -fx-font-size: 12px; -fx-font-weight: bold;")

  private val localsTable = new TableView[java.util.Map.Entry[String, String]]()
  localsTable.setPlaceholder(new Label("Select an event to view locals"))
  localsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)

  private val varNameCol = new TableColumn[java.util.Map.Entry[String, String], String]("Variable")
  varNameCol.setPrefWidth(160)
  varNameCol.setMinWidth(100)
  varNameCol.setSortable(true)
  varNameCol.setCellValueFactory(cell =>
    new SimpleStringProperty(cell.getValue.getKey))
  varNameCol.setCellFactory(_ => new TableCell[java.util.Map.Entry[String, String], String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) { setText(null); setStyle("") }
      else { setText(item); setStyle("-fx-text-fill: #f9e2af; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;") }
    }
  })

  private val varValueCol = new TableColumn[java.util.Map.Entry[String, String], String]("Value")
  varValueCol.setPrefWidth(400)
  varValueCol.setMinWidth(200)
  varValueCol.setSortable(false)
  varValueCol.setCellValueFactory(cell =>
    new SimpleStringProperty(cell.getValue.getValue))
  varValueCol.setCellFactory(_ => new TableCell[java.util.Map.Entry[String, String], String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) { setText(null); setTooltip(null); setStyle("") }
      else {
        setText(item)
        setStyle("-fx-text-fill: #a6e3a1; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
        setTooltip(if (item.length > 50) new Tooltip(item) else null)
      }
    }
  })

  localsTable.getColumns.addAll(varNameCol, varValueCol)

  private val localsPane = new VBox(4, localsHeader, localsTable)
  localsPane.setPadding(new Insets(4, 0, 0, 0))
  localsPane.getStyleClass.add("locals-panel")

  eventsTable.getSelectionModel.selectedItemProperty().addListener((_, _, newVal) => {
    updateLocalsPanel(newVal, localsHeader, localsTable)
  })

  // --- Table toolbar: auto-scroll + search ---
  private val autoScrollCheck = new CheckBox("Auto-scroll")
  autoScrollCheck.setSelected(true)

  private val searchField = new TextField()
  searchField.getStyleClass.add("search-field")
  searchField.setPromptText("\ud83d\udd0d Search functions...")
  searchField.setPrefWidth(200)

  // Debounced search: only apply predicate after 200ms of no typing
  private var searchTimer: javafx.animation.PauseTransition = _
  searchField.textProperty().addListener((_, _, newVal) => {
    if (searchTimer != null) searchTimer.stop()
    searchTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200))
    searchTimer.setOnFinished(_ => {
      val query = if (newVal == null) "" else newVal.trim.toLowerCase
      if (query.isEmpty) {
        filteredItems.setPredicate(_ => true)
      } else {
        filteredItems.setPredicate(event =>
          (event.function_name != null && event.function_name.toLowerCase.contains(query)) ||
          (event.file_path != null && event.file_path.toLowerCase.contains(query))
        )
      }
    })
    searchTimer.play()
  })

  private val searchSpacer = new Region()
  HBox.setHgrow(searchSpacer, Priority.ALWAYS)

  private val tableToolbar = new HBox(10, autoScrollCheck, searchSpacer, searchField)
  tableToolbar.getStyleClass.add("table-toolbar")
  tableToolbar.setAlignment(Pos.CENTER_LEFT)

  // Split: table on top, locals on bottom
  private val tableSplit = new SplitPane()
  tableSplit.setOrientation(Orientation.VERTICAL)
  tableSplit.getItems.addAll(eventsTable, localsPane)
  tableSplit.setDividerPositions(0.7)

  private val tableContainer = new VBox(tableToolbar, tableSplit)
  VBox.setVgrow(tableSplit, Priority.ALWAYS)

  private val streamTab = new Tab("Call Stack", tableContainer)
  streamTab.setClosable(false)

  // ---- Output Tab (stdout/stderr) ----
  private val outputArea = new TextArea()
  outputArea.setEditable(false)
  outputArea.setFont(Font.font("Menlo", 12))
  outputArea.setWrapText(true)

  private val outputTab = new Tab("Output", outputArea)
  outputTab.setClosable(false)

  // ---- Summary Tab (TreeView) ----
  private val callTree = new TreeView[CallEvent]()
  callTree.setShowRoot(false)
  callTree.setCellFactory(_ => new SummaryTreeCell())

  callTree.setOnMouseClicked { event =>
    if (event.getClickCount == 2 && event.getButton == MouseButton.PRIMARY) {
      openTreeSelectionInEditor()
    }
  }
  callTree.setOnKeyPressed { event =>
    if (event.getCode == KeyCode.ENTER) {
      openTreeSelectionInEditor()
      event.consume()
    }
  }

  // Locals detail panel for tree selection (shared format with table's panel)
  private val treeLocalsHeader = new Label("Locals")
  treeLocalsHeader.getStyleClass.add("section-header")
  treeLocalsHeader.setStyle("-fx-text-fill: #a6adc8; -fx-font-size: 12px; -fx-font-weight: bold;")

  private val treeLocalsTable = new TableView[java.util.Map.Entry[String, String]]()
  treeLocalsTable.setPlaceholder(new Label("Select a function to view locals"))
  treeLocalsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)

  private val treeVarNameCol = new TableColumn[java.util.Map.Entry[String, String], String]("Variable")
  treeVarNameCol.setPrefWidth(160)
  treeVarNameCol.setMinWidth(100)
  treeVarNameCol.setSortable(true)
  treeVarNameCol.setCellValueFactory(cell => new SimpleStringProperty(cell.getValue.getKey))
  treeVarNameCol.setCellFactory(_ => new TableCell[java.util.Map.Entry[String, String], String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) { setText(null); setStyle("") }
      else { setText(item); setStyle("-fx-text-fill: #f9e2af; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;") }
    }
  })

  private val treeVarValueCol = new TableColumn[java.util.Map.Entry[String, String], String]("Value")
  treeVarValueCol.setPrefWidth(400)
  treeVarValueCol.setMinWidth(200)
  treeVarValueCol.setSortable(false)
  treeVarValueCol.setCellValueFactory(cell => new SimpleStringProperty(cell.getValue.getValue))
  treeVarValueCol.setCellFactory(_ => new TableCell[java.util.Map.Entry[String, String], String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) { setText(null); setTooltip(null); setStyle("") }
      else {
        setText(item)
        setStyle("-fx-text-fill: #a6e3a1; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
        setTooltip(if (item.length > 50) new Tooltip(item) else null)
      }
    }
  })

  treeLocalsTable.getColumns.addAll(treeVarNameCol, treeVarValueCol)

  private val treeLocalsPane = new VBox(4, treeLocalsHeader, treeLocalsTable)
  treeLocalsPane.setPadding(new Insets(4, 0, 0, 0))
  treeLocalsPane.getStyleClass.add("locals-panel")

  // Wire tree selection to locals panel
  callTree.getSelectionModel.selectedItemProperty().addListener((_, _, newVal) => {
    if (newVal != null && newVal.getValue != null) {
      updateLocalsPanel(newVal.getValue, treeLocalsHeader, treeLocalsTable)
    } else {
      treeLocalsHeader.setText("Locals")
      treeLocalsTable.getItems.clear()
    }
  })

  private val treeSplit = new SplitPane()
  treeSplit.setOrientation(Orientation.VERTICAL)
  treeSplit.getItems.addAll(callTree, treeLocalsPane)
  treeSplit.setDividerPositions(0.7)

  private val summaryTab = new Tab("Summary", treeSplit)
  summaryTab.setClosable(false)

  tabPane.getTabs.addAll(streamTab, outputTab, summaryTab)
  getChildren.add(tabPane)

  // State
  private var baseDirectory: String = ""
  private var autoScroll: Boolean = true

  autoScrollCheck.setOnAction(_ => autoScroll = autoScrollCheck.isSelected)

  // ---- Event batching for high-throughput streaming ----
  // Instead of updating the UI per-event, batch events and flush periodically.
  private val pendingEvents = new java.util.ArrayList[CallEvent](256)
  private var batchTimer: javafx.animation.AnimationTimer = _
  private var pendingScrollTo = false

  private def startBatchTimer(): Unit = {
    if (batchTimer != null) return
    batchTimer = new javafx.animation.AnimationTimer() {
      private var lastFlush = 0L
      override def handle(now: Long): Unit = {
        // Flush at most every 50ms (20fps)
        if (now - lastFlush >= 50_000_000L) {
          flushPendingEvents()
          lastFlush = now
        }
      }
    }
    batchTimer.start()
  }

  private def stopBatchTimer(): Unit = {
    if (batchTimer != null) {
      batchTimer.stop()
      batchTimer = null
    }
    flushPendingEvents()
  }

  private def flushPendingEvents(): Unit = {
    if (pendingEvents.isEmpty) return
    // Add all pending events to the observable list in one batch
    allItems.addAll(pendingEvents)
    pendingEvents.clear()
    if (pendingScrollTo && autoScroll && !filteredItems.isEmpty) {
      eventsTable.scrollTo(filteredItems.size - 1)
      pendingScrollTo = false
    }
  }

  // ---- Locals panel updates (shared by table and tree selection) ----
  private def updateLocalsPanel(
    event: CallEvent,
    header: Label,
    table: TableView[java.util.Map.Entry[String, String]]
  ): Unit = {
    val items = FXCollections.observableArrayList[java.util.Map.Entry[String, String]]()

    if (event != null && event.hasReturnValue) {
      items.add(java.util.Map.entry("\u21b5 return", event.return_value))
    }

    if (event != null && event.hasLocals) {
      items.addAll(event.locals_data.entrySet())
      val retSuffix = if (event.hasReturnValue) s" \u2192 ${truncate(event.return_value, 40)}" else ""
      header.setText(s"Locals \u2014 ${event.function_name}()$retSuffix")
    } else if (event != null && event.hasReturnValue) {
      header.setText(s"Locals \u2014 ${event.function_name}() \u2192 ${truncate(event.return_value, 60)}")
    } else if (event != null) {
      header.setText("Locals \u2014 not captured (enable 'Capture locals()' and re-run)")
    } else {
      header.setText("Locals")
    }
    table.setItems(items)
  }

  private def truncate(s: String, max: Int): String =
    if (s != null && s.length > max) s.take(max) + "..." else if (s != null) s else ""

  // ---- Custom cells (reuse nodes to avoid GC pressure) ----

  /** Cell that renders indented call/return with arrow glyphs. Reuses child nodes. */
  private class CallStackCell extends TableCell[CallEvent, String] {
    private val spacer = new Region()
    private val arrowLabel = new Label()
    private val nameLabel = new Label()
    private val retLabel = new Label()
    private val localsHint = new Label(" {}")
    localsHint.setStyle("-fx-text-fill: #f9e2af; -fx-font-size: 10px;")
    retLabel.setStyle("-fx-text-fill: #cba6f7; -fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;")
    private val box = new HBox()
    box.setAlignment(Pos.CENTER_LEFT)

    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || getTableRow == null || getTableRow.getItem == null) {
        setText(null)
        setGraphic(null)
      } else {
        val event = getTableRow.getItem
        val indent = event.depth
        val isCall = event.event_type == "call"

        spacer.setMinWidth(indent * 14)
        spacer.setPrefWidth(indent * 14)
        spacer.setMaxWidth(indent * 14)

        arrowLabel.setText(if (isCall) "\u25b6 " else "\u25c0 ")
        arrowLabel.getStyleClass.removeAll("call-arrow", "return-arrow")
        arrowLabel.getStyleClass.add(if (isCall) "call-arrow" else "return-arrow")

        val name = if (event.function_name != null) event.function_name else ""
        nameLabel.setText(name)
        nameLabel.getStyleClass.removeAll("function-name", "function-name-module")
        nameLabel.getStyleClass.add(if (name == "<module>") "function-name-module" else "function-name")

        box.getChildren.clear()
        box.getChildren.addAll(spacer, arrowLabel, nameLabel)

        if (!isCall && event.hasReturnValue) {
          val rv = event.return_value
          retLabel.setText(if (rv.length > 60) " \u2192 " + rv.take(60) + "..." else " \u2192 " + rv)
          retLabel.setTooltip(if (rv.length > 60) new Tooltip(rv) else null)
          box.getChildren.add(retLabel)
        }

        if (event.hasLocals) {
          box.getChildren.add(localsHint)
        }

        setGraphic(box)
        setText(null)
      }
    }
  }

  /** Cell that shows location with tooltip. Reuses nodes. */
  private class LocationCell extends TableCell[CallEvent, String] {
    getStyleClass.add("location-cell")

    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null || getTableRow == null || getTableRow.getItem == null) {
        setText(null)
        setTooltip(null)
      } else {
        val event = getTableRow.getItem
        setText(event.shortLocation)
        setTooltip(new Tooltip(s"${event.relativeLocation(baseDirectory)}\nDouble-click or Enter to open in editor"))
      }
    }
  }

  /** Tree cell for summary view. Reuses child nodes. */
  private class SummaryTreeCell extends TreeCell[CallEvent] {
    private val icon = new Label()
    private val nameLabel = new Label()
    private val locLabel = new Label()
    private val retLabel = new Label()
    private val localsHint = new Label()
    private val box = new HBox(2)
    box.setAlignment(Pos.CENTER_LEFT)

    // For local variable nodes
    private val varNameLabel = new Label()
    private val eqLabel = new Label(" = ")
    eqLabel.setStyle("-fx-text-fill: #6c7086; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
    private val varValLabel = new Label()
    private val varBox = new HBox(varNameLabel, eqLabel, varValLabel)
    varBox.setAlignment(Pos.CENTER_LEFT)
    varNameLabel.setStyle("-fx-text-fill: #f9e2af; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
    varValLabel.setStyle("-fx-text-fill: #a6e3a1; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")

    override def updateItem(item: CallEvent, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) {
        setText(null)
        setGraphic(null)
        setTooltip(null)
      } else {
        val name = if (item.function_name != null) item.function_name else "?"
        val isLocalVar = name.startsWith("\u200b")

        if (isLocalVar) {
          val varName = name.substring(1)
          val varValue = if (item.file_path != null) item.file_path else ""
          varNameLabel.setText(varName)
          varValLabel.setText(varValue)
          setTooltip(if (varValue.length > 80) new Tooltip(varValue) else null)
          setGraphic(varBox)
        } else {
          val isModule = name == "<module>"
          val treeItem = getTreeItem
          val childCount = if (treeItem != null) treeItem.getChildren.size else 0

          icon.setText(if (childCount > 0) "\u25bc " else "\u25c6 ")
          icon.setStyle(if (isModule) "-fx-text-fill: #89b4fa; -fx-font-size: 10px;" else "-fx-text-fill: #a6e3a1; -fx-font-size: 10px;")

          nameLabel.setText(name)
          nameLabel.setStyle(
            if (isModule) "-fx-text-fill: #6c7086; -fx-font-style: italic; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;"
            else "-fx-text-fill: #cdd6f4; -fx-font-weight: bold; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;"
          )

          val loc = item.relativeLocation(baseDirectory)
          locLabel.setText("  " + loc)
          locLabel.setStyle("-fx-text-fill: #585b70; -fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;")

          box.getChildren.clear()
          box.getChildren.addAll(icon, nameLabel, locLabel)

          if (item.hasReturnValue) {
            val rv = item.return_value
            val retText = if (rv.length > 50) rv.take(50) + "..." else rv
            retLabel.setText(s"  \u2192 $retText")
            retLabel.setStyle("-fx-text-fill: #cba6f7; -fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;")
            retLabel.setTooltip(if (rv.length > 50) new Tooltip(rv) else null)
            box.getChildren.add(retLabel)
          }

          if (item.hasLocals) {
            localsHint.setText(s"  {} ${item.locals_data.size} vars")
            localsHint.setStyle("-fx-text-fill: #f9e2af; -fx-font-size: 10px;")
            box.getChildren.add(localsHint)
          }

          setGraphic(box)
          setTooltip(new Tooltip(s"$name\n$loc\nDouble-click or Enter to open in editor"))
        }
        setText(null)
      }
    }
  }

  // ---- Editor integration ----
  private def openInEditor(filePath: String, lineNumber: Int): Unit = {
    if (filePath == null) return
    val editor = AppPreferences.getEditorCommand

    try {
      val os = System.getProperty("os.name").toLowerCase
      if (os.contains("mac")) {
        val escapedPath = filePath.replace("'", "'\\''")
        val script =
          s"""tell application "Terminal"
             |  do script "${editor} +${lineNumber} '${escapedPath}'"
             |  activate
             |end tell""".stripMargin
        new ProcessBuilder("osascript", "-e", script).start()
      } else {
        new ProcessBuilder("sh", "-c", s"""$editor +$lineNumber '$filePath'""").start()
      }
    } catch {
      case e: Exception =>
        System.err.println(s"Failed to open editor: ${e.getMessage}")
    }
  }

  private def openSelectedInEditor(): Unit = {
    val selected = eventsTable.getSelectionModel.getSelectedItem
    if (selected != null) openInEditor(selected.file_path, selected.line_number)
  }

  private def openTreeSelectionInEditor(): Unit = {
    val selected = callTree.getSelectionModel.getSelectedItem
    if (selected != null && selected.getValue != null) {
      val event = selected.getValue
      if (event.function_name != null && !event.function_name.startsWith("\u200b")) {
        openInEditor(event.file_path, event.line_number)
      }
    }
  }

  // ---- Public API ----
  def setBaseDirectory(dir: String): Unit = {
    baseDirectory = if (dir != null) dir else ""
  }

  /** Add a single event during live streaming (batched). */
  def addEvent(event: CallEvent): Unit = {
    pendingEvents.add(event)
    pendingScrollTo = true
    startBatchTimer()
  }

  def clear(): Unit = {
    stopBatchTimer()
    pendingEvents.clear()
    allItems.clear()
    searchField.clear()
    outputArea.clear()
    callTree.setRoot(null)
    localsTable.getItems.clear()
    localsHeader.setText("Locals")
  }

  /** Set all events at once (after trace completion or loading a file). */
  def setEvents(events: java.util.List[CallEvent]): Unit = {
    stopBatchTimer()
    pendingEvents.clear()
    allItems.setAll(events)
    // Build summary tree in a background thread for large traces
    val eventsList = events.asScala.toList
    if (eventsList.size > 5000) {
      // Build tree on background thread, set on FX thread
      val thread = new Thread(() => {
        val root = buildSummaryTreeData(eventsList)
        javafx.application.Platform.runLater(() => callTree.setRoot(root))
      }, "callshow-tree-builder")
      thread.setDaemon(true)
      thread.start()
    } else {
      callTree.setRoot(buildSummaryTreeData(eventsList))
    }
  }

  /** Signal that streaming is complete. Flushes remaining batched events. */
  def streamingComplete(): Unit = {
    stopBatchTimer()
  }

  def appendOutput(text: String): Unit = {
    outputArea.appendText(text + "\n")
  }

  def getEventCount: Int = filteredItems.size

  def selectTab(index: Int): Unit = {
    if (index >= 0 && index < tabPane.getTabs.size) {
      tabPane.getSelectionModel.select(index)
    }
  }

  def focusSearch(): Unit = {
    tabPane.getSelectionModel.select(0)
    searchField.requestFocus()
    searchField.selectAll()
  }

  private def buildSummaryTreeData(events: List[CallEvent]): TreeItem[CallEvent] = {
    val rootEvent = new CallEvent()
    rootEvent.function_name = "Trace"
    rootEvent.file_path = ""
    rootEvent.event_type = "call"
    val root = new TreeItem[CallEvent](rootEvent)
    root.setExpanded(true)

    // For very large traces, limit tree depth and skip locals nodes
    val isLarge = events.size > 10000
    val maxTreeDepth = if (isLarge) 50 else Int.MaxValue

    var stack = List[TreeItem[CallEvent]](root)

    for (event <- events) {
      if (event.event_type == "call") {
        if (stack.size <= maxTreeDepth) {
          val item = new TreeItem[CallEvent](event)
          // Only expand top-level for large traces
          item.setExpanded(event.depth < (if (isLarge) 1 else 3))

          // Skip locals child nodes for large traces
          if (!isLarge && event.hasLocals) {
            item.getChildren.add(createLocalsGroupNode(event))
          }

          stack.head.getChildren.add(item)
          stack = item :: stack
        } else {
          // Beyond max depth — still track depth for returns
          stack = null :: stack
        }
      } else if (event.event_type == "return") {
        if (stack.size > 1) {
          val callItem = stack.head
          if (callItem != null) {
            val callEvent = callItem.getValue
            if (event.hasReturnValue) {
              callEvent.return_value = event.return_value
            }

            if (!isLarge) {
              if (event.hasReturnValue) {
                val retEvent = new CallEvent()
                retEvent.function_name = "\u200breturn"
                retEvent.file_path = event.return_value
                retEvent.event_type = "local_var"
                callItem.getChildren.add(new TreeItem[CallEvent](retEvent))
              }
              if (event.hasLocals) {
                callItem.getChildren.add(createReturnLocalsGroupNode(event))
              }
            }
          }
          stack = stack.tail
        }
      }
    }

    root
  }

  private def createLocalsGroupNode(event: CallEvent): TreeItem[CallEvent] = {
    val groupEvent = new CallEvent()
    groupEvent.function_name = "\u200blocals (entry)"
    groupEvent.file_path = s"${event.locals_data.size} variables"
    groupEvent.event_type = "locals"
    val group = new TreeItem[CallEvent](groupEvent)
    group.setExpanded(false)

    event.locals_data.entrySet().asScala.foreach { entry =>
      val varEvent = new CallEvent()
      varEvent.function_name = "\u200b" + entry.getKey
      varEvent.file_path = entry.getValue
      varEvent.event_type = "local_var"
      group.getChildren.add(new TreeItem[CallEvent](varEvent))
    }
    group
  }

  private def createReturnLocalsGroupNode(event: CallEvent): TreeItem[CallEvent] = {
    val groupEvent = new CallEvent()
    groupEvent.function_name = "\u200blocals (return)"
    groupEvent.file_path = s"${event.locals_data.size} variables"
    groupEvent.event_type = "locals"
    val group = new TreeItem[CallEvent](groupEvent)
    group.setExpanded(false)

    event.locals_data.entrySet().asScala.foreach { entry =>
      val varEvent = new CallEvent()
      varEvent.function_name = "\u200b" + entry.getKey
      varEvent.file_path = entry.getValue
      varEvent.event_type = "local_var"
      group.getChildren.add(new TreeItem[CallEvent](varEvent))
    }
    group
  }
}
