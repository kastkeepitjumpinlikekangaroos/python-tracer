package com.callshow.gui

import javafx.geometry.{Insets, Pos}
import javafx.scene.control.Label
import javafx.scene.layout.{HBox, Priority, Region}
import javafx.scene.paint.Color
import javafx.scene.shape.Circle

/**
 * Status bar displayed at the bottom of the application window.
 * Shows state indicator, event count, and duration.
 */
class StatusBar extends HBox(12) {

  getStyleClass.add("status-bar")
  setPadding(new Insets(5, 15, 5, 15))
  setAlignment(Pos.CENTER_LEFT)

  private val indicator = new Circle(5)
  indicator.getStyleClass.add("status-indicator")
  indicator.setFill(Color.web("#6c7086"))

  private val statusLabel = new Label("Ready")
  statusLabel.setStyle("-fx-font-weight: bold;")

  private val eventCountLabel = new Label("")
  private val filterInfoLabel = new Label("")
  filterInfoLabel.setStyle("-fx-text-fill: #6c7086;")

  private val shortcutsHint = new Label("\u2318R run  \u2318. stop  Enter open in editor")
  shortcutsHint.getStyleClass.add("shortcut-hint")

  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)

  private val durationLabel = new Label("")

  getChildren.addAll(indicator, statusLabel, eventCountLabel, filterInfoLabel, spacer, shortcutsHint, durationLabel)

  def setRunning(eventCount: Int): Unit = {
    indicator.setFill(Color.web("#a6e3a1"))
    statusLabel.setText("Tracing...")
    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #a6e3a1;")
    eventCountLabel.setText(f"$eventCount%,d events captured")
    durationLabel.setText("")
    filterInfoLabel.setText("")
  }

  def setComplete(eventCount: Int, duration: Double, exitCode: Int): Unit = {
    if (exitCode == 0) {
      indicator.setFill(Color.web("#89b4fa"))
      statusLabel.setText("Complete")
      statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #89b4fa;")
    } else {
      indicator.setFill(Color.web("#f38ba8"))
      statusLabel.setText(s"Exited ($exitCode)")
      statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #f38ba8;")
    }
    eventCountLabel.setText(f"$eventCount%,d events")
    durationLabel.setText(f"$duration%.2fs")
  }

  def setReady(): Unit = {
    indicator.setFill(Color.web("#6c7086"))
    statusLabel.setText("Ready")
    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #cdd6f4;")
    eventCountLabel.setText("")
    durationLabel.setText("")
    filterInfoLabel.setText("")
  }

  def setFilterInfo(shown: Int, total: Int): Unit = {
    if (shown < total) {
      filterInfoLabel.setText(f"(showing $shown%,d of $total%,d)")
    } else {
      filterInfoLabel.setText("")
    }
  }
}
