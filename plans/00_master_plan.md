# Master Plan: python-callshow

## Project Overview

**CallShow** is a tool that analyzes Python programs by tracing their full call stack during execution. It consists of:

1. **Python tracer core** — executes a target program with `sys.settrace` instrumentation, streaming call events as JSONL and writing a final JSON result
2. **Scala/JavaFX GUI** — desktop application for configuring, executing, and visualizing traced call stacks
3. **Bazel build system** — unified build for both Python and Scala components

---

## Implementation Order

The plans are designed to be executed in sequence, with each phase building on the previous:

```
Phase 1: Bazel Project Setup (Plan 01)
    │
    ▼
Phase 2: Python Tracer Core (Plan 02)
    │
    ▼
Phase 3: Scala JavaFX GUI (Plan 03)
    │
    ▼
Phase 4: Integration (Plan 04)
    │
    ▼
Phase 5: Polish & Testing
```

---

## Phase 1: Bazel Project Setup
**Plan**: [01_bazel_project_setup.md](./01_bazel_project_setup.md)
**Estimated complexity**: Low

### What to do:
1. Create `MODULE.bazel` with rules_scala, rules_python, rules_jvm_external
2. Create directory structure for Python and Scala source trees
3. Create `BUILD.bazel` files for all packages
4. Create `.bazelrc` with Java 21 settings
5. Create `requirements_lock.txt` (empty initially)
6. Create minimal stub files so `bazel build //...` passes

### Verification:
```bash
bazel build //...                                              # all targets compile
bazel run //src/main/python/callshow:callshow -- --help        # Python stub runs
bazel run //src/main/scala/com/callshow/gui:gui                # Scala stub launches
```

### Key files created:
- `WORKSPACE`, `MODULE.bazel`, `.bazelrc`, `BUILD.bazel`
- `requirements_lock.txt`
- `src/main/python/callshow/BUILD.bazel` + stub `.py` files
- `src/main/scala/com/callshow/gui/BUILD.bazel` + stub `.scala` files

---

## Phase 2: Python Tracer Core
**Plan**: [02_python_tracer.md](./02_python_tracer.md)
**Estimated complexity**: Medium-High

### What to do:
1. Implement `models.py` — `CallEvent` and `TraceResult` dataclasses
2. Implement `tracer.py` — `sys.settrace` injection via `sitecustomize.py`, subprocess management, JSONL streaming
3. Implement `output.py` — JSON serialization and filtering
4. Implement `cli.py` — argparse CLI with `--directory`, `--command`, `--output`, `--events-file`, `--exclude`, `--include-all`, `--stream`
5. Write tests in `test_tracer.py`
6. Create a test fixture Python project under `testdata/`

### Verification:
```bash
# Trace a simple Python program
bazel run //src/main/python/callshow:callshow -- \
  --directory /tmp/test_project \
  --command "python hello.py" \
  --output /tmp/trace.json \
  --stream

# Verify JSON output
cat /tmp/trace.json | python -m json.tool

# Run tests
bazel test //src/test/python/callshow:test_tracer
```

### Key files created/modified:
- `src/main/python/callshow/models.py`
- `src/main/python/callshow/tracer.py`
- `src/main/python/callshow/output.py`
- `src/main/python/callshow/cli.py`
- `src/test/python/callshow/test_tracer.py`
- `testdata/sample_project/` (test fixture)

### Critical implementation notes:
- The `sitecustomize.py` injection must work with arbitrary bash commands, not just `python <script>`
- JSONL must be line-buffered and flushed immediately for real-time streaming
- Default exclude patterns: `["site-packages", "lib/python", "importlib", "<frozen"]`
- `time.monotonic()` for timestamps (relative to trace start)
- Track call depth explicitly in the tracer

---

## Phase 3: Scala JavaFX GUI
**Plan**: [03_scala_javafx_gui.md](./03_scala_javafx_gui.md)
**Estimated complexity**: Medium-High

### What to do:
1. Implement `Main.scala` — JavaFX Application entry point
2. Implement `CallEvent.scala` — Java-style mutable data class for Gson deserialization
3. Implement `ConfigPanel.scala` — directory picker, command editor, execute/stop buttons
4. Implement `CallStackView.scala` — TableView for streaming events + TreeView for summary
5. Implement `FilterPanel.scala` — site-packages toggle, custom exclude patterns
6. Implement `StatusBar.scala` — event count, duration, state indicator
7. Implement `AppController.scala` — layout, state machine, wiring

### Verification:
```bash
bazel run //src/main/scala/com/callshow/gui:gui
# Manually verify:
# - Window opens at 1200x800
# - Directory picker works
# - Command editor accepts multi-line input
# - All panels render correctly
```

### Key files created/modified:
- `src/main/scala/com/callshow/gui/Main.scala`
- `src/main/scala/com/callshow/gui/CallEvent.scala`
- `src/main/scala/com/callshow/gui/ConfigPanel.scala`
- `src/main/scala/com/callshow/gui/CallStackView.scala`
- `src/main/scala/com/callshow/gui/FilterPanel.scala`
- `src/main/scala/com/callshow/gui/StatusBar.scala`
- `src/main/scala/com/callshow/gui/AppController.scala`

### Critical implementation notes:
- All UI updates must go through `Platform.runLater()`
- Batch rapid events (every 50ms) to avoid overwhelming JavaFX
- TableView is virtualized — handles large event lists efficiently
- Use nullable Java fields in CallEvent for Gson compatibility (not Scala Option)

---

## Phase 4: Integration
**Plan**: [04_integration.md](./04_integration.md)
**Estimated complexity**: Medium

### What to do:
1. Implement `ExecutionRunner.scala` — subprocess management, JSONL tailing
2. Implement `TracerLocator.scala` — find the Python binary (runfiles, relative, PATH)
3. Wire `AppController` → `ExecutionRunner` → `CallStackView`
4. Add `data` dependency in GUI BUILD target for the Python binary
5. Implement filter integration (live + retroactive filtering)
6. Add "Output" tab for subprocess stdout/stderr
7. Add error handling for all failure modes
8. Create integration test fixtures

### Verification:
```bash
bazel run //src/main/scala/com/callshow/gui:gui
# End-to-end:
# 1. Browse to testdata/sample_project
# 2. Enter: python main.py
# 3. Click Execute
# 4. Verify events stream in
# 5. Toggle site-packages filter
# 6. Click Execute again on a different program
# 7. Click Stop during execution
```

### Key files created/modified:
- `src/main/scala/com/callshow/gui/ExecutionRunner.scala`
- `src/main/scala/com/callshow/gui/TracerLocator.scala`
- `src/main/scala/com/callshow/gui/AppController.scala` (updated)
- `src/main/scala/com/callshow/gui/BUILD.bazel` (updated with data dep)
- `testdata/sample_project/` (integration test fixture)

---

## Phase 5: Polish & Testing
**Estimated complexity**: Low-Medium

### What to do:
1. Add keyboard shortcuts (Ctrl+Enter to execute, Escape to stop)
2. Add menu bar (File > Export JSON, Edit > Preferences, Help > About)
3. Add relative path display in the call stack view (relative to project directory)
4. Add event batching for rapid traces (50ms batching window)
5. Add tooltips (full absolute path on hover in call stack)
6. Persist last-used directory and command (via Java Preferences API)
7. Style the UI (consistent fonts, colors, spacing)
8. Write comprehensive integration tests
9. Create a `testdata/` directory with sample Python projects for manual testing

### Verification:
- All `bazel test //...` pass
- Manual walkthrough of the full workflow
- Test with a real-world Python project (e.g., a Flask app, a pytest suite)

---

## Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| File-based JSONL for streaming | Clean separation; no stdout mixing; debuggable on disk |
| `sys.settrace` via `sitecustomize.py` | Works with arbitrary bash commands, not just `python script.py` |
| Gson for JSON in Scala | Already proven in numba1; simple and lightweight |
| Subprocess isolation | Tracer runs in separate process; GUI won't crash if tracer fails |
| Default exclude site-packages | Most users want to see their own code, not library internals |
| Bazel `data` dep for Python binary | Works with `bazel run`; no manual path management |

---

## Directory Structure (Final)

```
python-callshow/
├── WORKSPACE
├── MODULE.bazel
├── MODULE.bazel.lock           # generated by Bazel
├── BUILD.bazel
├── .bazelrc
├── requirements_lock.txt
├── plans/
│   ├── 00_master_plan.md       # this file
│   ├── 01_bazel_project_setup.md
│   ├── 02_python_tracer.md
│   ├── 03_scala_javafx_gui.md
│   └── 04_integration.md
├── src/
│   ├── main/
│   │   ├── python/callshow/
│   │   │   ├── BUILD.bazel
│   │   │   ├── __init__.py
│   │   │   ├── tracer.py
│   │   │   ├── models.py
│   │   │   ├── output.py
│   │   │   └── cli.py
│   │   └── scala/com/callshow/gui/
│   │       ├── BUILD.bazel
│   │       ├── Main.scala
│   │       ├── AppController.scala
│   │       ├── ConfigPanel.scala
│   │       ├── CallStackView.scala
│   │       ├── FilterPanel.scala
│   │       ├── StatusBar.scala
│   │       ├── ExecutionRunner.scala
│   │       ├── TracerLocator.scala
│   │       └── CallEvent.scala
│   └── test/
│       ├── python/callshow/
│       │   ├── BUILD.bazel
│       │   └── test_tracer.py
│       └── scala/com/callshow/gui/
│           ├── BUILD.bazel
│           └── ...
└── testdata/
    └── sample_project/
        ├── main.py
        ├── utils.py
        └── lib/
            └── helper.py
```

---

## Risk Summary

| Risk | Impact | Mitigation |
|------|--------|------------|
| rules_python + rules_scala coexistence in MODULE.bazel | Build failures | Test early in Phase 1; keep separate BUILD packages |
| `sys.settrace` performance overhead | Traced programs run slower | Filtering reduces event volume; document expected overhead |
| JavaFX module system on modern JDK | Runtime crashes | Use `--add-modules` / `--add-opens` JVM flags |
| Very large traces (>1M events) | GUI lag, memory pressure | Batched UI updates; virtual TableView; offer summary-only mode |
| Platform-specific JavaFX natives | Build fails on non-macOS | Start macOS-only; add Windows targets in future iteration |

---

## Success Criteria

The project is complete when:
1. `bazel build //...` succeeds
2. `bazel run //src/main/scala/com/callshow/gui:gui` launches the GUI
3. A user can select a directory, enter a command, and execute it
4. Call stack events stream into the GUI in real time during execution
5. Results persist after execution completes
6. Site-packages can be toggled on/off (off by default)
7. Custom exclude patterns can be added/removed
8. The traced program's stdout/stderr is visible in the GUI
