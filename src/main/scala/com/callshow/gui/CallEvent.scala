package com.callshow.gui

/**
 * Java-style mutable data class for Gson deserialization of trace events.
 * Mirrors the Python CallEvent dataclass.
 */
class CallEvent {
  var event_type: String = _
  var function_name: String = _
  var file_path: String = _
  var line_number: Int = 0
  var timestamp: Double = 0.0
  var depth: Int = 0
  var caller_function: String = _  // null if absent
  var caller_file: String = _      // null if absent
  var caller_line: Int = 0         // 0 if absent
  var locals_data: java.util.Map[String, String] = _ // null if not captured
  var return_value: String = _                       // null if not a return event

  /** Whether this event has captured locals. */
  def hasLocals: Boolean =
    locals_data != null && !locals_data.isEmpty

  /** Whether this event has a return value. */
  def hasReturnValue: Boolean =
    return_value != null && return_value != "None"

  /** Get the file name (last path component) for display. */
  def fileName: String = {
    if (file_path == null) return ""
    val idx = file_path.lastIndexOf('/')
    if (idx >= 0) file_path.substring(idx + 1) else file_path
  }

  /** Short location: just filename:line. */
  def shortLocation: String = {
    if (file_path == null) return ""
    s"${fileName}:$line_number"
  }

  /** Get the location string relative to a base directory. */
  def relativeLocation(baseDir: String): String = {
    if (file_path == null) return ""
    val path = if (baseDir != null && file_path.startsWith(baseDir)) {
      val rel = file_path.substring(baseDir.length)
      if (rel.startsWith("/")) rel.substring(1) else rel
    } else {
      file_path
    }
    s"$path:$line_number"
  }

  override def toString: String =
    s"CallEvent($event_type, $function_name, $file_path:$line_number, depth=$depth)"
}
