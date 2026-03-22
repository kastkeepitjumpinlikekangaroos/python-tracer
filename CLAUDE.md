# CLAUDE.md

## Build & Test

```bash
bazel build //...                                          # build all targets
bazel run //src/main/python/callshow:callshow -- --help    # CLI help
bazel run //src/main/scala/com/callshow/gui:gui            # launch GUI
bazel test //src/test/python/callshow:test_tracer           # run Python tests (7 tests)

# Quick test outside Bazel (faster iteration on Python changes)
PYTHONPATH=src/main/python python3 -m unittest src/test/python/callshow/test_tracer.py -v
```

## Architecture

Two components communicate via file-based JSONL streaming:

- **Python tracer** (`src/main/python/callshow/`) — injects `sys.settrace` via `sitecustomize.py` into a subprocess, writes call/return events as newline-delimited JSON to a temp file
- **Scala/JavaFX GUI** (`src/main/scala/com/callshow/gui/`) — spawns the Python tracer, tails the JSONL file with `RandomAccessFile`, updates the UI via `Platform.runLater()`

The GUI locates the Python binary via `TracerLocator` (checks `RUNFILES_DIR`, `JAVA_RUNFILES`, `BUILD_WORKSPACE_DIRECTORY`, then falls back to `python3 -m callshow.cli`).

## Key Implementation Details

### Python tracer (`tracer.py`)

- `SITECUSTOMIZE_TEMPLATE` is a Python code template injected into the traced process. It uses `.format()` with `{{` / `}}` for literal braces.
- The tracer clears `PYTHONSAFEPATH`, `BUILD_WORKING_DIRECTORY`, and other Bazel env vars from the subprocess environment — without this, the traced program can't find its own modules.
- Dunder methods (`__bool__`, `__hash__`, `__repr__`, etc.), comprehension internals (`<genexpr>`, `<listcomp>`), and `self`/`cls` in locals are skipped to reduce noise.
- `return None` values are omitted entirely.
- Max repr length is 120 chars, truncated with `...`.

### Scala GUI

- All UI updates go through `Platform.runLater()`. Events from the JSONL tailer thread are dispatched to the JavaFX thread.
- `CallEvent.scala` uses Java-style mutable fields (not Scala `Option`) for Gson compatibility.
- JavaFX runs from the classpath (not module path) — the "Unsupported JavaFX configuration" warning at startup is expected and harmless.
- The dark theme CSS is packaged into the JAR via `resources = glob(["**/*.css"])` with `resource_strip_prefix = "src/main/scala"`.
- Locals variable tree nodes use a zero-width space (`\u200b`) prefix on `function_name` to distinguish them from real function call nodes.

## Conventions

- Python source: `src/main/python/callshow/`, tests: `src/test/python/callshow/`
- Scala source: `src/main/scala/com/callshow/gui/`
- Test data: `testdata/sample_project/` (3-file Python project with known call patterns)
- Plans: `plans/` (historical design documents, may be out of date)
- No external Python dependencies — the tracer uses only stdlib (`sys.settrace`, `subprocess`, `json`, `threading`, `tempfile`)
