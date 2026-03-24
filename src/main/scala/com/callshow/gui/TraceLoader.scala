package com.callshow.gui

import com.google.gson.{Gson, JsonObject, JsonParser}

import java.io.{File, FileReader}
import java.nio.file.Files

/**
 * Loads and saves trace JSON files.
 */
object TraceLoader {

  case class TraceData(
    events: java.util.List[CallEvent],
    command: String,
    directory: String,
    durationSeconds: Double,
    exitCode: Int,
    totalEvents: Int
  )

  def load(file: File): TraceData = {
    val reader = new FileReader(file)
    val root = JsonParser.parseReader(reader).getAsJsonObject
    reader.close()

    val metadata = root.getAsJsonObject("metadata")
    val command = if (metadata.has("command")) metadata.get("command").getAsString else ""
    val directory = if (metadata.has("directory")) metadata.get("directory").getAsString else ""
    val duration = if (metadata.has("duration_seconds")) metadata.get("duration_seconds").getAsDouble else 0.0
    val exitCode = if (metadata.has("exit_code")) metadata.get("exit_code").getAsInt else 0
    val totalEvents = if (metadata.has("total_events")) metadata.get("total_events").getAsInt else 0

    val gson = new Gson()
    val eventsArray = root.getAsJsonArray("events")
    val events = new java.util.ArrayList[CallEvent]()

    val iter = eventsArray.iterator()
    while (iter.hasNext) {
      try {
        val event = gson.fromJson(iter.next(), classOf[CallEvent])
        if (event != null && event.event_type != null) {
          events.add(event)
        }
      } catch {
        case _: Exception => // skip malformed events
      }
    }

    TraceData(events, command, directory, duration, exitCode, totalEvents)
  }
}
