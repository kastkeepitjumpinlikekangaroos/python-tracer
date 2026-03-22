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
 * Features: indented call hierarchy, color-coded rows, search,
 * locals detail panel, and double-click to open in editor.
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
      if (empty || item == null) {
        setText(null)
        setStyle("")
      } else {
        setText(item)
        setStyle("-fx-text-fill: #f9e2af; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
      }
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
      if (empty || item == null) {
        setText(null)
        setTooltip(null)
        setStyle("")
      } else {
        setText(item)
        setStyle("-fx-text-fill: #a6e3a1; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
        if (item.length > 50) {
          setTooltip(new Tooltip(item))
        }
      }
    }
  })

  localsTable.getColumns.addAll(varNameCol, varValueCol)

  private val localsPane = new VBox(4, localsHeader, localsTable)
  localsPane.setPadding(new Insets(4, 0, 0, 0))
  localsPane.getStyleClass.add("locals-panel")

  // Update locals when selection changes
  eventsTable.getSelectionModel.selectedItemProperty().addListener((_, _, newVal) => {
    updateLocalsPanel(newVal)
  })

  // --- Table toolbar: auto-scroll + search ---
  private val autoScrollCheck = new CheckBox("Auto-scroll")
  autoScrollCheck.setSelected(true)

  private val searchField = new TextField()
  searchField.getStyleClass.add("search-field")
  searchField.setPromptText("\ud83d\udd0d Search functions...")
  searchField.setPrefWidth(200)
  searchField.textProperty().addListener((_, _, newVal) => {
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

  // Double-click and Enter on tree nodes
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

  private val summaryTab = new Tab("Summary", callTree)
  summaryTab.setClosable(false)

  tabPane.getTabs.addAll(streamTab, outputTab, summaryTab)
  getChildren.add(tabPane)

  // State
  private var baseDirectory: String = ""
  private var autoScroll: Boolean = true

  autoScrollCheck.setOnAction(_ => autoScroll = autoScrollCheck.isSelected)

  // ---- Locals panel updates ----
  private def updateLocalsPanel(event: CallEvent): Unit = {
    val items = FXCollections.observableArrayList[java.util.Map.Entry[String, String]]()

    if (event != null && event.hasReturnValue) {
      // Add return value as first entry
      items.add(java.util.Map.entry("\u21b5 return", event.return_value))
    }

    if (event != null && event.hasLocals) {
      items.addAll(event.locals_data.entrySet())
      val retSuffix = if (event.hasReturnValue) s" \u2192 ${truncate(event.return_value, 40)}" else ""
      localsHeader.setText(s"Locals \u2014 ${event.function_name}()$retSuffix")
    } else if (event != null && event.hasReturnValue) {
      localsHeader.setText(s"Locals \u2014 ${event.function_name}() \u2192 ${truncate(event.return_value, 60)}")
    } else if (event != null) {
      localsHeader.setText("Locals \u2014 not captured (enable 'Capture locals()' and re-run)")
    } else {
      localsHeader.setText("Locals")
    }
    localsTable.setItems(items)
  }

  private def truncate(s: String, max: Int): String =
    if (s != null && s.length > max) s.take(max) + "..." else if (s != null) s else ""

  // ---- Custom cells ----

  /** Cell that renders indented call/return with arrow glyphs. */
  private class CallStackCell extends TableCell[CallEvent, String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || getTableRow == null || getTableRow.getItem == null) {
        setText(null)
        setGraphic(null)
      } else {
        val event = getTableRow.getItem
        val indent = event.depth
        val isCall = event.event_type == "call"
        val arrow = if (isCall) "\u25b6 " else "\u25c0 "
        val name = if (event.function_name != null) event.function_name else ""
        val isModule = name == "<module>"

        val spacer = new Region()
        spacer.setMinWidth(indent * 14)
        spacer.setPrefWidth(indent * 14)
        spacer.setMaxWidth(indent * 14)

        val arrowLabel = new Label(arrow)
        arrowLabel.getStyleClass.add(if (isCall) "call-arrow" else "return-arrow")

        val nameLabel = new Label(name)
        nameLabel.getStyleClass.add(if (isModule) "function-name-module" else "function-name")

        val box = new HBox(spacer, arrowLabel, nameLabel)

        // Show return value inline for return events
        if (!isCall && event.hasReturnValue) {
          val retLabel = new Label(" \u2192 " + event.return_value)
          retLabel.setStyle("-fx-text-fill: #cba6f7; -fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;")
          if (event.return_value.length > 60) {
            retLabel.setText(" \u2192 " + event.return_value.take(60) + "...")
            retLabel.setTooltip(new Tooltip(event.return_value))
          }
          box.getChildren.add(retLabel)
        }

        // Small indicator if locals are captured
        if (event.hasLocals) {
          val localsHint = new Label(" {}")
          localsHint.setStyle("-fx-text-fill: #f9e2af; -fx-font-size: 10px;")
          box.getChildren.add(localsHint)
        }

        box.setAlignment(Pos.CENTER_LEFT)
        setGraphic(box)
        setText(null)
      }
    }
  }

  /** Cell that shows location with tooltip. Clickable appearance. */
  private class LocationCell extends TableCell[CallEvent, String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null || getTableRow == null || getTableRow.getItem == null) {
        setText(null)
        setTooltip(null)
        getStyleClass.remove("location-cell")
      } else {
        val event = getTableRow.getItem
        setText(event.shortLocation)
        setTooltip(new Tooltip(s"${event.relativeLocation(baseDirectory)}\nDouble-click or Enter to open in editor"))
        if (!getStyleClass.contains("location-cell")) {
          getStyleClass.add("location-cell")
        }
      }
    }
  }

  /** Tree cell that renders function name + location + locals as children. */
  private class SummaryTreeCell extends TreeCell[CallEvent] {
    override def updateItem(item: CallEvent, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) {
        setText(null)
        setGraphic(null)
        setTooltip(null)
      } else {
        val name = if (item.function_name != null) item.function_name else "?"
        val isModule = name == "<module>"
        val isLocalVar = name.startsWith("\u200b")  // zero-width space marks locals nodes
        val treeItem = getTreeItem
        val childCount = if (treeItem != null) treeItem.getChildren.size else 0

        if (isLocalVar) {
          // This is a locals variable child node
          val varName = name.substring(1)  // strip marker
          val varValue = if (item.file_path != null) item.file_path else ""

          val nameLabel = new Label(varName)
          nameLabel.setStyle("-fx-text-fill: #f9e2af; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
          val eqLabel = new Label(" = ")
          eqLabel.setStyle("-fx-text-fill: #6c7086; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
          val valLabel = new Label(varValue)
          valLabel.setStyle("-fx-text-fill: #a6e3a1; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;")
          if (varValue.length > 80) {
            setTooltip(new Tooltip(varValue))
          }

          val box = new HBox(nameLabel, eqLabel, valLabel)
          box.setAlignment(Pos.CENTER_LEFT)
          setGraphic(box)
        } else {
          // Normal function call node
          val icon = new Label(if (childCount > 0) "\u25bc " else "\u25c6 ")
          icon.setStyle(
            if (isModule) "-fx-text-fill: #89b4fa; -fx-font-size: 10px;"
            else "-fx-text-fill: #a6e3a1; -fx-font-size: 10px;"
          )

          val nameLabel = new Label(name)
          nameLabel.setStyle(
            if (isModule) "-fx-text-fill: #6c7086; -fx-font-style: italic; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;"
            else "-fx-text-fill: #cdd6f4; -fx-font-weight: bold; -fx-font-family: 'Menlo', monospace; -fx-font-size: 12px;"
          )

          val loc = item.relativeLocation(baseDirectory)
          val locLabel = new Label("  " + loc)
          locLabel.setStyle("-fx-text-fill: #585b70; -fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;")

          val parts = new java.util.ArrayList[javafx.scene.Node]()
          parts.add(icon)
          parts.add(nameLabel)
          parts.add(locLabel)

          // Show return value inline
          if (item.hasReturnValue) {
            val retText = if (item.return_value.length > 50) item.return_value.take(50) + "..." else item.return_value
            val retLabel = new Label(s"  \u2192 $retText")
            retLabel.setStyle("-fx-text-fill: #cba6f7; -fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;")
            if (item.return_value.length > 50) {
              retLabel.setTooltip(new Tooltip(item.return_value))
            }
            parts.add(retLabel)
          }

          if (item.hasLocals) {
            val localsHint = new Label(s"  {} ${item.locals_data.size} vars")
            localsHint.setStyle("-fx-text-fill: #f9e2af; -fx-font-size: 10px;")
            parts.add(localsHint)
          }

          val box = new HBox(2)
          box.getChildren.addAll(parts)
          box.setAlignment(Pos.CENTER_LEFT)
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
      // Don't try to open locals variable nodes
      if (event.function_name != null && !event.function_name.startsWith("\u200b")) {
        openInEditor(event.file_path, event.line_number)
      }
    }
  }

  // ---- Public API ----
  def setBaseDirectory(dir: String): Unit = {
    baseDirectory = if (dir != null) dir else ""
  }

  def addEvent(event: CallEvent): Unit = {
    allItems.add(event)
    if (autoScroll && !filteredItems.isEmpty) {
      eventsTable.scrollTo(filteredItems.size - 1)
    }
  }

  def clear(): Unit = {
    allItems.clear()
    searchField.clear()
    outputArea.clear()
    callTree.setRoot(null)
    localsTable.getItems.clear()
    localsHeader.setText("Locals")
  }

  def setEvents(events: java.util.List[CallEvent]): Unit = {
    allItems.setAll(events)
    buildSummaryTree(events.asScala.toList)
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

  def buildSummaryTree(events: List[CallEvent]): Unit = {
    val rootEvent = new CallEvent()
    rootEvent.function_name = "Trace"
    rootEvent.file_path = ""
    rootEvent.event_type = "call"
    val root = new TreeItem[CallEvent](rootEvent)
    root.setExpanded(true)

    var stack = List[TreeItem[CallEvent]](root)

    for (event <- events) {
      if (event.event_type == "call") {
        val item = new TreeItem[CallEvent](event)
        item.setExpanded(event.depth < 3)

        // Add locals as child nodes if present
        if (event.hasLocals) {
          val localsParent = createLocalsGroupNode(event)
          item.getChildren.add(localsParent)
        }

        stack.head.getChildren.add(item)
        stack = item :: stack
      } else if (event.event_type == "return") {
        if (stack.size > 1) {
          val callItem = stack.head
          // Copy return value onto the call event so the tree cell can display it
          val callEvent = callItem.getValue
          if (event.hasReturnValue) {
            callEvent.return_value = event.return_value
          }

          // Add return value as a child node
          if (event.hasReturnValue) {
            val retEvent = new CallEvent()
            retEvent.function_name = "\u200breturn"
            retEvent.file_path = event.return_value
            retEvent.event_type = "local_var"
            callItem.getChildren.add(new TreeItem[CallEvent](retEvent))
          }

          // Add return locals to the current stack item if present
          if (event.hasLocals) {
            val returnLocals = createReturnLocalsGroupNode(event)
            callItem.getChildren.add(returnLocals)
          }

          stack = stack.tail
        }
      }
    }

    callTree.setRoot(root)
  }

  /** Create a "locals (entry)" group node containing variable children. */
  private def createLocalsGroupNode(event: CallEvent): TreeItem[CallEvent] = {
    val groupEvent = new CallEvent()
    groupEvent.function_name = "\u200blocals (entry)"  // zero-width space as marker
    groupEvent.file_path = s"${event.locals_data.size} variables"
    groupEvent.event_type = "locals"
    val group = new TreeItem[CallEvent](groupEvent)
    group.setExpanded(false)

    event.locals_data.entrySet().asScala.foreach { entry =>
      val varEvent = new CallEvent()
      varEvent.function_name = "\u200b" + entry.getKey  // marker + var name
      varEvent.file_path = entry.getValue               // repurpose file_path for value display
      varEvent.event_type = "local_var"
      group.getChildren.add(new TreeItem[CallEvent](varEvent))
    }
    group
  }

  /** Create a "locals (return)" group node containing variable children. */
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
