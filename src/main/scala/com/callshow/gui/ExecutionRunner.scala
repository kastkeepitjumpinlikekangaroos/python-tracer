package com.callshow.gui

import com.google.gson.Gson
import javafx.application.Platform

import java.io.{BufferedReader, File, InputStreamReader, RandomAccessFile}
import java.nio.file.{Files, Path}

/**
 * Manages the execution of the Python tracer as a subprocess.
 * Events arrive via CSEVENT: lines on stdout (reliable) and/or
 * by tailing the JSONL events file (backup).
 *
 * Batches events to avoid flooding the JavaFX thread.
 */
class ExecutionRunner {

  private var process: Option[Process] = None
  private var tailThread: Option[Thread] = None
  private var stdoutThread: Option[Thread] = None

  @volatile private var stopping = false

  private val EVENT_PREFIX = "CSEVENT:"

  def execute(
    tracerCommand: java.util.List[String],
    directory: String,
    onEvent: CallEvent => Unit,
    onStdout: String => Unit,
    onComplete: (Int, Double) => Unit,
    eventsFilePath: Path
  ): Unit = {
    stopping = false

    if (!Files.exists(eventsFilePath)) {
      Files.createFile(eventsFilePath)
    }

    val pb = new ProcessBuilder(tracerCommand)
    pb.directory(new File(directory))
    pb.redirectErrorStream(false)

    val proc = pb.start()
    process = Some(proc)

    val gson = new Gson()

    // Tail the JSONL events file (backup for launch mode)
    val tailer = new Thread(() => tailJsonl(eventsFilePath, onEvent, gson), "callshow-tailer")
    tailer.setDaemon(true)
    tailer.start()
    tailThread = Some(tailer)

    // Read stdout — batch CSEVENT: lines and flush periodically
    val stdReader = new Thread(() => {
      try {
        val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
        val batch = new java.util.ArrayList[CallEvent](64)
        var line = reader.readLine()
        while (line != null && !stopping) {
          if (line.startsWith(EVENT_PREFIX)) {
            try {
              val json = line.substring(EVENT_PREFIX.length)
              val event = gson.fromJson(json, classOf[CallEvent])
              if (event != null && event.event_type != null) {
                batch.add(event)
                // Flush batch when it reaches 64 events or when there's
                // no more data immediately available
                if (batch.size >= 64 || !reader.ready()) {
                  val toFlush = new java.util.ArrayList[CallEvent](batch)
                  batch.clear()
                  Platform.runLater(() => {
                    toFlush.forEach(e => onEvent(e))
                  })
                }
              }
            } catch {
              case _: Exception =>
            }
          } else {
            // Flush any pending events before outputting text
            if (!batch.isEmpty) {
              val toFlush = new java.util.ArrayList[CallEvent](batch)
              batch.clear()
              Platform.runLater(() => toFlush.forEach(e => onEvent(e)))
            }
            val l = line
            Platform.runLater(() => onStdout(l))
          }
          line = reader.readLine()
        }
        // Final flush
        if (!batch.isEmpty) {
          val toFlush = new java.util.ArrayList[CallEvent](batch)
          batch.clear()
          Platform.runLater(() => toFlush.forEach(e => onEvent(e)))
        }
      } catch {
        case _: Exception =>
      }
    }, "callshow-stdout")
    stdReader.setDaemon(true)
    stdReader.start()
    stdoutThread = Some(stdReader)

    // Read stderr
    val errReader = new Thread(() => {
      try {
        val reader = new BufferedReader(new InputStreamReader(proc.getErrorStream))
        var line = reader.readLine()
        while (line != null && !stopping) {
          val l = line
          Platform.runLater(() => onStdout("[stderr] " + l))
          line = reader.readLine()
        }
      } catch {
        case _: Exception =>
      }
    }, "callshow-stderr")
    errReader.setDaemon(true)
    errReader.start()

    // Wait for completion
    val waiter = new Thread(() => {
      val startTime = System.nanoTime()
      val exitCode = proc.waitFor()
      val duration = (System.nanoTime() - startTime) / 1e9

      Thread.sleep(300)
      tailer.interrupt()
      tailer.join(2000)

      Platform.runLater(() => onComplete(exitCode, duration))
    }, "callshow-waiter")
    waiter.setDaemon(true)
    waiter.start()
  }

  def stop(): Unit = {
    stopping = true
    process.foreach(_.destroyForcibly())
    tailThread.foreach(_.interrupt())
    process = None
  }

  def isRunning: Boolean = process.exists(_.isAlive)

  private def tailJsonl(path: Path, onEvent: CallEvent => Unit, gson: Gson): Unit = {
    var position = 0L
    val batch = new java.util.ArrayList[CallEvent](64)

    try {
      while (!Thread.currentThread().isInterrupted) {
        val file = path.toFile
        if (file.exists() && file.length() > position) {
          val raf = new RandomAccessFile(file, "r")
          try {
            raf.seek(position)
            var line = raf.readLine()
            while (line != null) {
              position = raf.getFilePointer
              if (line.trim.nonEmpty) {
                try {
                  val event = gson.fromJson(line, classOf[CallEvent])
                  if (event != null && event.event_type != null) {
                    batch.add(event)
                  }
                } catch {
                  case _: Exception =>
                }
              }
              line = raf.readLine()
            }
          } finally {
            raf.close()
          }
          // Flush batch
          if (!batch.isEmpty) {
            val toFlush = new java.util.ArrayList[CallEvent](batch)
            batch.clear()
            Platform.runLater(() => toFlush.forEach(e => onEvent(e)))
          }
        }
        Thread.sleep(50)
      }
    } catch {
      case _: InterruptedException =>
    }
  }
}
