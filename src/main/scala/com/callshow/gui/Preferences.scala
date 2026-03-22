package com.callshow.gui

import scala.jdk.CollectionConverters._

/**
 * Simple persistence for user preferences using Java Preferences API.
 */
object AppPreferences {
  private val prefs = java.util.prefs.Preferences.userRoot().node("com/callshow/gui")
  private val MAX_RECENT = 10
  private val SEP = "\u0000"

  // --- Recent directories ---
  def getRecentDirectories: List[String] = {
    val raw = prefs.get("recent_dirs", "")
    if (raw.isEmpty) Nil else raw.split(SEP).filter(_.nonEmpty).toList
  }

  def addRecentDirectory(dir: String): Unit = {
    val current = getRecentDirectories.filterNot(_ == dir)
    val updated = (dir :: current).take(MAX_RECENT)
    prefs.put("recent_dirs", updated.mkString(SEP))
    prefs.flush()
  }

  // --- Last used values ---
  def getLastDirectory: String = prefs.get("last_dir", "")
  def setLastDirectory(dir: String): Unit = {
    prefs.put("last_dir", dir)
    prefs.flush()
  }

  def getLastCommand: String = prefs.get("last_cmd", "")
  def setLastCommand(cmd: String): Unit = {
    prefs.put("last_cmd", cmd)
    prefs.flush()
  }

  // --- Editor ---
  def getEditorCommand: String = {
    val envEditor = System.getenv("CALLSHOW_EDITOR")
    if (envEditor != null && envEditor.nonEmpty) envEditor
    else {
      val env = System.getenv("EDITOR")
      if (env != null && env.nonEmpty) env
      else prefs.get("editor", "vim")
    }
  }

  def setEditorCommand(cmd: String): Unit = {
    prefs.put("editor", cmd)
    prefs.flush()
  }
}
