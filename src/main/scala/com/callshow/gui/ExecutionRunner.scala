package com.callshow.gui

import com.google.gson.Gson
import javafx.application.Platform

import java.io.{BufferedReader, File, InputStreamReader, RandomAccessFile}
import java.nio.file.{Files, Path}

/**
 * Manages the execution of the Python tracer as a subprocess
 * and reads streaming JSONL events.
 */
class ExecutionRunner {

  private var process: Option[Process] = None
  private var tailThread: Option[Thread] = None
  private var stdoutThread: Option[Thread] = None

  @volatile private var stopping = false

  def execute(
    tracerCommand: java.util.List[String],
    directory: String,
    onEvent: CallEvent => Unit,
    onStdout: String => Unit,
    onComplete: (Int, Double) => Unit,
    eventsFilePath: Path
  ): Unit = {
    stopping = false

    // Create the events file so we can start tailing immediately
    if (!Files.exists(eventsFilePath)) {
      Files.createFile(eventsFilePath)
    }

    // Start the process
    val pb = new ProcessBuilder(tracerCommand)
    pb.directory(new File(directory))
    pb.redirectErrorStream(false)

    val proc = pb.start()
    process = Some(proc)

    // Tail the JSONL events file
    val tailer = new Thread(() => tailJsonl(eventsFilePath, onEvent), "callshow-tailer")
    tailer.setDaemon(true)
    tailer.start()
    tailThread = Some(tailer)

    // Read process stdout (tracer status messages)
    val stdReader = new Thread(() => {
      try {
        val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
        var line = reader.readLine()
        while (line != null && !stopping) {
          val l = line
          Platform.runLater(() => onStdout(l))
          line = reader.readLine()
        }
      } catch {
        case _: Exception => // process ended
      }
    }, "callshow-stdout")
    stdReader.setDaemon(true)
    stdReader.start()
    stdoutThread = Some(stdReader)

    // Read stderr in background
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

    // Wait for completion in background thread
    val waiter = new Thread(() => {
      val startTime = System.nanoTime()
      val exitCode = proc.waitFor()
      val duration = (System.nanoTime() - startTime) / 1e9

      // Give tailer time to finish reading remaining events
      Thread.sleep(500)
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

  private def tailJsonl(path: Path, onEvent: CallEvent => Unit): Unit = {
    val gson = new Gson()
    var position = 0L

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
                    val e = event
                    Platform.runLater(() => onEvent(e))
                  }
                } catch {
                  case _: Exception => // skip malformed lines
                }
              }
              line = raf.readLine()
            }
          } finally {
            raf.close()
          }
        }
        Thread.sleep(50)
      }
    } catch {
      case _: InterruptedException => // normal exit
    }
  }
}
