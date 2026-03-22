# Plan 04: Integration — Python Tracer ↔ Scala GUI

## Goal

Wire the Python tracer and Scala GUI together so that the GUI can invoke the tracer, receive streaming events, and display results. Define the communication protocol, process management, and packaging strategy.

---

## Communication Protocol

### File-Based JSONL Streaming

The Python tracer writes events as newline-delimited JSON (JSONL) to a temporary file. The Scala GUI tails this file to receive events in real time.

```
Python Tracer                     Scala GUI
─────────────                     ─────────
    │                                 │
    │  writes JSONL to temp file      │
    │  ──────────────────────────►    │
    │  (line-buffered, flushed)       │  reads/tails JSONL file
    │                                 │  parses each line as CallEvent
    │                                 │  updates TableView via Platform.runLater
    │                                 │
    │  subprocess exits               │
    │  ──────────────────────────►    │
    │                                 │  reads remaining lines
    │                                 │  reads final JSON output
    │                                 │  transitions to RESULTS state
```

**Why file-based instead of stdout/pipe?**
- The bash command's own stdout/stderr shouldn't be mixed with trace data
- File-based allows the tracer to also capture the subprocess's stdout/stderr separately
- Easier to debug (file is inspectable on disk)

### File Paths Convention

```
/tmp/callshow_<uuid>/
├── trace_events.jsonl       # Streaming JSONL events (written by Python, read by Scala)
├── trace_result.json        # Final structured JSON output (written by Python after completion)
├── subprocess_stdout.log    # Captured stdout of the traced command
├── subprocess_stderr.log    # Captured stderr of the traced command
└── sitecustomize.py         # Injected tracer bootstrap
```

---

## Steps

### 4.1 — Define the invocation contract

The Scala GUI invokes the Python tracer via:

```bash
python -m callshow.cli \
  --directory /path/to/project \
  --command "python main.py --arg1 --arg2" \
  --output /tmp/callshow_<uuid>/trace_result.json \
  --events-file /tmp/callshow_<uuid>/trace_events.jsonl \
  --exclude site-packages lib/python importlib "<frozen" \
  --stdout-file /tmp/callshow_<uuid>/subprocess_stdout.log \
  --stderr-file /tmp/callshow_<uuid>/subprocess_stderr.log
```

Or, when using Bazel-built binary:

```bash
bazel-bin/src/main/python/callshow/callshow \
  --directory ... --command ... --output ... --events-file ...
```

**The GUI must resolve the Python tracer binary path.** Options:
1. **Bazel runfiles**: If both are run via `bazel run`, use runfiles to locate the Python binary
2. **System Python**: Invoke `python -m callshow.cli` assuming callshow is on PYTHONPATH
3. **Bundled binary**: Use `py_binary` output directly

**Decision**: Use option 3 — the Scala GUI will locate the `py_binary` output. During development, the GUI BUILD target will declare a `data` dependency on the Python binary, making it available in runfiles.

### 4.2 — Scala GUI BUILD target update

```bzl
scala_binary(
    name = "gui",
    main_class = "com.callshow.gui.Main",
    srcs = glob(["**/*.scala"]),
    data = [
        "//src/main/python/callshow:callshow",  # Python tracer binary
    ],
    deps = [
        "@maven//:org_openjfx_javafx_base",
        "@maven//:org_openjfx_javafx_controls",
        "@maven//:org_openjfx_javafx_fxml",
        "@maven//:org_openjfx_javafx_graphics",
        "@maven//:com_google_code_gson_gson",
    ],
    jvm_flags = [...],
)
```

### 4.3 — ExecutionRunner implementation details

```scala
class ExecutionRunner {
  private var process: Option[Process] = None
  private var tailThread: Option[Thread] = None

  def execute(
    tracerBinaryPath: String,
    directory: String,
    command: String,
    excludePatterns: List[String],
    onEvent: CallEvent => Unit,
    onStdout: String => Unit,
    onComplete: (Int, Double, String) => Unit  // exitCode, duration, resultJsonPath
  ): Unit = {

    // 1. Create temp directory
    val tmpDir = Files.createTempDirectory("callshow_")
    val eventsFile = tmpDir.resolve("trace_events.jsonl")
    val resultFile = tmpDir.resolve("trace_result.json")
    val stdoutFile = tmpDir.resolve("subprocess_stdout.log")
    val stderrFile = tmpDir.resolve("subprocess_stderr.log")

    // Create events file so tail can start immediately
    Files.createFile(eventsFile)

    // 2. Build command
    val cmd = List(
      tracerBinaryPath,
      "--directory", directory,
      "--command", command,
      "--output", resultFile.toString,
      "--events-file", eventsFile.toString,
      "--stdout-file", stdoutFile.toString,
      "--stderr-file", stderrFile.toString,
    ) ++ excludePatterns.flatMap(p => List("--exclude", p))

    // 3. Start process
    val pb = new ProcessBuilder(cmd.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    process = Some(proc)

    // 4. Start tailing events file
    val tailer = new Thread(() => tailJsonl(eventsFile, onEvent))
    tailer.setDaemon(true)
    tailer.start()
    tailThread = Some(tailer)

    // 5. Optionally read process stdout (for progress/errors)
    val stdoutReader = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(proc.getInputStream))
      var line = reader.readLine()
      while (line != null) {
        Platform.runLater(() => onStdout(line))
        line = reader.readLine()
      }
    })
    stdoutReader.setDaemon(true)
    stdoutReader.start()

    // 6. Wait for completion
    new Thread(() => {
      val exitCode = proc.waitFor()
      Thread.sleep(500)  // let tailer finish reading
      tailer.interrupt()
      tailer.join(2000)

      // Read duration from result file if available
      val duration = readDurationFromResult(resultFile)

      Platform.runLater(() => onComplete(exitCode, duration, resultFile.toString))
    }).start()
  }

  private def tailJsonl(path: Path, onEvent: CallEvent => Unit): Unit = {
    val gson = new Gson()
    var position = 0L
    while (!Thread.currentThread().isInterrupted) {
      val raf = new RandomAccessFile(path.toFile, "r")
      raf.seek(position)
      var line = raf.readLine()
      while (line != null) {
        position = raf.getFilePointer
        try {
          val event = gson.fromJson(line, classOf[CallEvent])
          Platform.runLater(() => onEvent(event))
        } catch {
          case _: Exception => // skip malformed lines
        }
        line = raf.readLine()
      }
      raf.close()
      Thread.sleep(50)  // poll interval
    }
  }

  def stop(): Unit = {
    process.foreach(_.destroyForcibly())
    tailThread.foreach(_.interrupt())
  }
}
```

### 4.4 — Locating the Python binary

```scala
object TracerLocator {
  /**
   * Find the Python tracer binary.
   * Priority:
   * 1. Bazel runfiles (when run via `bazel run`)
   * 2. Sibling directory (when packaged)
   * 3. System PATH (fallback)
   */
  def findTracer(): String = {
    // Check Bazel runfiles
    val runfilesPath = System.getenv("RUNFILES_DIR")
    if (runfilesPath != null) {
      val tracerPath = Paths.get(runfilesPath,
        "python-callshow", "src", "main", "python", "callshow", "callshow")
      if (Files.exists(tracerPath)) return tracerPath.toString
    }

    // Check relative to jar location
    val jarDir = Paths.get(getClass.getProtectionDomain
      .getCodeSource.getLocation.toURI).getParent
    val relativePath = jarDir.resolve("../python/callshow/callshow")
    if (Files.exists(relativePath)) return relativePath.toAbsolutePath.toString

    // Fallback: assume on PATH or use python -m
    "python3 -m callshow.cli"
  }
}
```

### 4.5 — Filter integration

When the user changes filter settings in the GUI:

```scala
// In AppController
def onFilterChanged(): Unit = {
  val patterns = filterPanel.getExcludePatterns
  val filtered = allEvents.filter { event =>
    !patterns.exists(p => event.file_path.contains(p))
  }
  callStackView.setEvents(filtered)
  statusBar.updateFilteredCount(filtered.size, allEvents.size)
}
```

Filters apply to:
- **Live stream**: New events are checked against current filters before display
- **Stored events**: Re-filtering applies to `allEvents` and refreshes the view
- **Both call and return events**: If a call is filtered out, its corresponding return is too

### 4.6 — Error handling

| Scenario | Handling |
|----------|----------|
| Python tracer not found | Show error dialog with instructions to build via Bazel |
| Traced command fails (non-zero exit) | Show exit code in status bar; display any events captured before failure |
| Traced command hangs | Stop button kills process; timeout option in future |
| Invalid directory | Validate before execution; show error if directory doesn't exist |
| Empty command | Validate before execution; disable Execute button if empty |
| JSONL parse error | Skip malformed line; log warning |
| Disk full / permission error | Catch IOException; show error dialog |

### 4.7 — Subprocess stdout/stderr display

Add an "Output" tab alongside the call stack view:

```
┌────────────────────────────────────────────┐
│  [Call Stack]  [Output]  [Summary]          │
├────────────────────────────────────────────┤
│  stdout/stderr from the traced command     │
│  displayed here in a monospace TextArea     │
│  (read-only, auto-scrolling)               │
└────────────────────────────────────────────┘
```

This lets the user see what the program is printing while also viewing the call stack.

---

## End-to-End Flow

```
1. User launches GUI:     bazel run //src/main/scala/com/callshow/gui:gui
2. User picks directory:  /Users/alice/my-project
3. User types command:    python main.py --verbose
4. User clicks Execute

5. GUI creates:           /tmp/callshow_abc123/
6. GUI invokes:           <tracer_binary> --directory ... --command ... --events-file ...

7. Python tracer:
   a. Creates sitecustomize.py with sys.settrace hook
   b. Runs: bash -c "PYTHONPATH=/tmp/callshow_abc123:$PYTHONPATH python main.py --verbose"
   c. Events stream to trace_events.jsonl (line-buffered)
   d. On completion, writes trace_result.json

8. GUI (concurrently):
   a. Tails trace_events.jsonl, parses JSONL, adds to TableView
   b. Reads subprocess stdout/stderr, shows in Output tab
   c. Updates status bar with event count

9. Traced command completes:
   a. GUI reads final trace_result.json
   b. Transitions to RESULTS state
   c. Builds summary tree view
   d. Shows "Complete — 5,432 events in 2.3s"

10. User adjusts filters:
    a. Unchecks "Exclude site-packages"
    b. GUI re-filters allEvents and refreshes TableView
    c. Status bar: "Showing 12,847 of 12,847 events"
```

---

## Testing Strategy

### Integration tests:
1. **Round-trip test**: Python tracer produces JSONL → Scala reads it → events match
2. **Streaming test**: Start tracer on a slow program → verify events appear in GUI before completion
3. **Filter test**: Load a trace with mixed site-packages and user code → verify filter toggle works
4. **Error test**: Trace a program that crashes → verify partial results displayed
5. **Stop test**: Start a long trace → click Stop → verify process killed and partial results shown

### Manual test script:
Create a sample Python project under `testdata/` with known call patterns for predictable testing.

---

## Done When

- `bazel run //src/main/scala/com/callshow/gui:gui` launches the full application
- User can select directory, enter command, click Execute
- Events stream into the table in real time
- Filters toggle works (site-packages on/off)
- Results persist after execution
- Stop button works
- Output tab shows subprocess stdout/stderr
