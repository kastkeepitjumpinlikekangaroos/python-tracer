package com.callshow.gui

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control._
import javafx.scene.layout._

import scala.jdk.CollectionConverters._

/**
 * Panel for controlling which call events are displayed.
 * Provides site-packages toggle and custom exclude patterns.
 */
class FilterPanel extends VBox(8) {

  setPadding(new Insets(15))

  private val filterLabel = new Label("Filters")
  filterLabel.getStyleClass.add("section-header")

  // --- Site-packages toggle ---
  private val excludeSitePackages = new CheckBox("Exclude site-packages")
  excludeSitePackages.setSelected(true)
  excludeSitePackages.setTooltip(new Tooltip(
    "Exclude Python site-packages, stdlib internals, importlib, and dynamic code"
  ))

  // --- Custom exclude patterns ---
  private val patternsLabel = new Label("Custom exclude patterns:")
  patternsLabel.getStyleClass.add("section-header")

  private val excludePatterns = FXCollections.observableArrayList[String]()
  private val patternList = new ListView[String](excludePatterns)
  patternList.setPrefHeight(100)
  patternList.setCellFactory(_ => new ListCell[String] {
    override def updateItem(item: String, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      if (empty || item == null) {
        setText(null)
        setGraphic(null)
      } else {
        val label = new Label(item)
        label.setStyle("-fx-text-fill: #cdd6f4;")
        val removeBtn = new Button("\u00d7")
        removeBtn.getStyleClass.add("remove-button")
        removeBtn.setOnAction { _ =>
          excludePatterns.remove(item)
          fireFilterChange()
        }
        val spacer = new Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)
        val row = new HBox(8, label, spacer, removeBtn)
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
        setGraphic(row)
        setText(null)
      }
    }
  })

  private val patternInput = new TextField()
  patternInput.setPromptText("e.g. venv/, .tox/")
  patternInput.setOnAction { _ => addPattern() }

  private val addButton = new Button("+ Add")
  addButton.setOnAction { _ => addPattern() }

  private val addBox = new HBox(5, patternInput, addButton)
  HBox.setHgrow(patternInput, Priority.ALWAYS)

  getChildren.addAll(filterLabel, excludeSitePackages, patternsLabel, patternList, addBox)

  // --- Callback ---
  private var onFilterChange: () => Unit = () => ()

  excludeSitePackages.setOnAction(_ => fireFilterChange())

  private def addPattern(): Unit = {
    val pattern = patternInput.getText.trim
    if (pattern.nonEmpty && !excludePatterns.contains(pattern)) {
      excludePatterns.add(pattern)
      patternInput.clear()
      fireFilterChange()
    }
  }

  private def fireFilterChange(): Unit = onFilterChange()

  // --- Public API ---
  def getExcludePatterns: java.util.List[String] = {
    val patterns = new java.util.ArrayList[String]()
    if (excludeSitePackages.isSelected) {
      patterns.add("site-packages")
      patterns.add("importlib")
      patterns.add("<frozen")
      patterns.add("<string>")
    }
    patterns.addAll(excludePatterns)
    patterns
  }

  def setOnFilterChange(handler: () => Unit): Unit = {
    onFilterChange = handler
  }

  def focusPatternInput(): Unit = {
    patternInput.requestFocus()
  }
}
