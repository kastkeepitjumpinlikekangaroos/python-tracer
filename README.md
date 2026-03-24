# CallShow

A Python call-stack tracer with a Scala/JavaFX GUI. Trace any Python program's execution, stream call events in real time, capture locals and return values, and open source files directly in your editor.

## Quick Start

```bash
# Build everything
bazel build //...

# Launch the GUI
bazel run //src/main/scala/com/callshow/gui:gui

# Trace a program via CLI
bazel run //src/main/python/callshow:callshow -- \
  --directory /path/to/project \
  --command "python3 main.py" \
  --output trace.json \
  --capture-locals \
  --stream

# Attach to a running Python process (requires sudo on macOS)
sudo PYTHONPATH=src/main/python python3 -m callshow.cli \
  --attach <PID> \
  --output trace.json \
  --capture-locals \
  --stream
```

## How It Works

CallShow injects a `sys.settrace` hook into the target Python process via `sitecustomize.py` on `PYTHONPATH`. Every function call and return is captured as a JSON Lines event, streamed in real time to the GUI or CLI consumer.

```
Target Python Program          CallShow
────────────────────           ────────
                               1. Creates sitecustomize.py with sys.settrace hook
                               2. Sets PYTHONPATH to include it
python3 main.py ──────────►    3. Launches target via bash subprocess
  sys.settrace fires ────►     4. Events written to .jsonl file (line-buffered)
  call: main()                 5. GUI/CLI tails the file in real time
  call: greet("World")         6. On completion, writes structured JSON
  return: 'Hello, World!'
  ...
```

## Components

### Python Tracer (`src/main/python/callshow/`)

The core tracing engine. Captures:

- **Call events**: function name, file path, line number, call depth, caller info
- **Return events**: function name, return value (non-None), final locals
- **Local variables** (opt-in via `--capture-locals`): all non-private, non-dunder locals with safe `repr()` truncated to 120 chars

Automatically filters noise:
- Dunder methods (`__bool__`, `__hash__`, `__repr__`, `__str__`, `__eq__`, etc.)
- Comprehension internals (`<genexpr>`, `<listcomp>`, `<setcomp>`, `<dictcomp>`)
- `self`/`cls` from locals (always present on methods, almost always a huge repr)
- `None` return values (omitted entirely)

Default exclusions: `site-packages`, `lib/python`, `importlib`, `<frozen`, `<string>`

### Scala/JavaFX GUI (`src/main/scala/com/callshow/gui/`)

A dark-themed desktop application with:

- **Config Panel**: Directory picker (with recent directories dropdown), multi-line command editor, "Capture locals()" checkbox
- **Call Stack Tab**: TableView with indented call hierarchy, color-coded rows (green=call, pink=return), return values shown inline in purple, search bar for filtering by function/file name
- **Locals Panel**: Below the call stack table — shows local variables and return value for the selected event
- **Output Tab**: Subprocess stdout/stderr
- **Summary Tab**: TreeView with collapsible call hierarchy, locals shown as expandable child nodes, return values inline
- **Keyboard shortcuts**: `Cmd+R` run, `Cmd+.` stop, `Cmd+F` search, `Cmd+L` focus directory, `Cmd+1/2/3` switch tabs, `Enter` open in editor, `Esc` stop
- **Editor integration**: Double-click or `Enter` on any event opens the file at the exact line number in vim (configurable via `CALLSHOW_EDITOR` or `EDITOR` env var)

## CLI Reference

```
python3 -m callshow.cli (--command CMD | --attach PID) [options]
```

| Flag | Description |
|------|-------------|
| `--command, -c` | Bash command to execute, can be multi-line |
| `--attach, -a` | Attach to a running Python process by PID |
| `--directory, -d` | Working directory for the target program (default: `.`) |
| `--output, -o` | Output JSON file path (default: `callshow_trace.json`) |
| `--events-file` | Path for streaming JSONL events (default: auto temp file) |
| `--capture-locals` | Capture local variables at each call/return |
| `--include-all` | Include all call sites (don't exclude site-packages) |
| `--exclude` | Additional path patterns to exclude |
| `--stream` | Print events to stdout as they stream in |
| `--stdout-file` | Path to capture subprocess stdout |
| `--stderr-file` | Path to capture subprocess stderr |

### Attach Mode

Attach to an already-running Python process and inject tracing:

```bash
# Find your Python process
ps aux | grep python

# Attach (requires sudo on macOS due to SIP)
# From the python-callshow project root:
sudo PYTHONPATH=src/main/python python3 -m callshow.cli \
  --attach 12345 --capture-locals --stream --output trace.json
# Press Ctrl+C to stop tracing and detach
```

Requirements:
- **macOS**: `sudo` required (SIP restricts `task_for_pid`). Uses `lldb` to inject.
- **Linux**: Same-user or `CAP_SYS_PTRACE` capability. Uses `gdb` to inject.
- Target must be a **CPython** process (not PyPy, GraalPy, etc.)

The tracer injects `sys.settrace` into the running process via the debugger calling `PyRun_SimpleString()`. On `Ctrl+C`, a second injection removes the trace hook and detaches cleanly.

## JSON Output Format

```json
{
  "metadata": {
    "command": "python3 main.py",
    "directory": "/path/to/project",
    "duration_seconds": 1.234,
    "exit_code": 0,
    "excluded_patterns": ["site-packages", "lib/python", "importlib", "<frozen", "<string>"],
    "total_events": 42
  },
  "events": [
    {
      "event_type": "call",
      "function_name": "main",
      "file_path": "/path/to/project/main.py",
      "line_number": 10,
      "timestamp": 0.001,
      "depth": 0,
      "caller_function": "<module>",
      "caller_file": "/path/to/project/main.py",
      "caller_line": 50,
      "locals_data": {"arg1": "'hello'", "count": "5"}
    },
    {
      "event_type": "return",
      "function_name": "main",
      "file_path": "/path/to/project/main.py",
      "line_number": 25,
      "timestamp": 1.200,
      "depth": 0,
      "return_value": "42",
      "locals_data": {"result": "42"}
    }
  ]
}
```

## Editor Integration

Double-click or press `Enter` on any event in the Call Stack or Summary tab to open it in your editor. Configurable via (in priority order):

1. `CALLSHOW_EDITOR` environment variable
2. `EDITOR` environment variable
3. Falls back to `vim`

On macOS, opens a new Terminal.app tab. The editor is invoked as `<editor> +<line> <file>`.

## Build System

Built with Bazel 8+ using `MODULE.bazel` (bzlmod):

| Dependency | Version |
|-----------|---------|
| rules_scala | 7.0.0 |
| rules_python | 1.4.1 |
| rules_java | 8.14.0 |
| rules_jvm_external | 6.6 |
| Scala | 2.13.16 |
| Python | 3.12 |
| JavaFX | 21.0.1 |
| Gson | 2.10.1 |

### Build Targets

```bash
bazel build //...                                        # build everything
bazel run //src/main/python/callshow:callshow             # run CLI
bazel run //src/main/scala/com/callshow/gui:gui           # run GUI
bazel test //src/test/python/callshow:test_tracer          # run tests
```

## Project Structure

```
python-callshow/
├── MODULE.bazel                     # Bazel deps: rules_scala, rules_python, JavaFX, Gson
├── .bazelrc                         # Java 21 for JavaFX compatibility
├── src/main/python/callshow/
│   ├── cli.py                       # CLI entry point (--directory, --command, --output, etc.)
│   ├── tracer.py                    # sys.settrace injection, subprocess management, JSONL streaming
│   ├── models.py                    # CallEvent and TraceResult dataclasses
│   └── output.py                    # JSON serialization and filtering
├── src/main/scala/com/callshow/gui/
│   ├── Main.scala                   # JavaFX Application entry, loads dark theme
│   ├── AppController.scala          # Layout, state machine, keyboard shortcuts, preferences
│   ├── CallStackView.scala          # TableView, TreeView, locals panel, editor integration
│   ├── ConfigPanel.scala            # Directory picker, command editor, capture-locals checkbox
│   ├── FilterPanel.scala            # Site-packages toggle, custom exclude patterns
│   ├── ExecutionRunner.scala        # Subprocess management, JSONL file tailing
│   ├── TracerLocator.scala          # Finds Python binary via runfiles/workspace/PATH
│   ├── StatusBar.scala              # State indicator, event count, duration, shortcut hints
│   ├── CallEvent.scala              # Gson-compatible data class mirroring Python CallEvent
│   ├── Preferences.scala            # Recent directories, last command, editor preference
│   └── dark-theme.css               # Catppuccin Mocha dark theme
├── src/test/python/callshow/
│   └── test_tracer.py               # 7 test cases: tracing, filtering, streaming, output format
└── testdata/sample_project/         # Test fixture: main.py -> utils.py -> lib/helper.py
```
