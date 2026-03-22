# Plan 01: Bazel Project Setup (Scala + Python)

## Goal

Set up a Bazel 8+ project using MODULE.bazel that supports both Scala (for the JavaFX GUI) and Python (for the call-stack tracer core). Follow the patterns from `~/grid-games/numba1` for Scala/JavaFX, and add `rules_python` for the Python components.

---

## Steps

### 1.1 — Create project root files

- **WORKSPACE**: Minimal marker file (content: comment pointing to MODULE.bazel)
- **BUILD.bazel**: Empty root marker
- **.bazelrc**: Configure Java version for JavaFX 21 compatibility
  ```
  build --java_language_version=21
  build --java_runtime_version=21
  ```

### 1.2 — Create MODULE.bazel

Define the module and all dependencies:

```bzl
module(name = "python-callshow")

# --- Rules ---
bazel_dep(name = "rules_java", version = "8.14.0")
bazel_dep(name = "rules_jvm_external", version = "6.6")
bazel_dep(name = "rules_scala", version = "7.0.0")
bazel_dep(name = "rules_python", version = "1.4.1")

# --- Maven (Scala/Java dependencies) ---
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        # JavaFX 21 (macOS ARM64)
        "org.openjfx:javafx-base:21.0.1",
        "org.openjfx:javafx-controls:21.0.1",
        "org.openjfx:javafx-fxml:21.0.1",
        "org.openjfx:javafx-graphics:21.0.1",
        # Gson for JSON parsing
        "com.google.code.gson:gson:2.10.1",
    ],
    repositories = ["https://repo1.maven.org/maven2"],
    fetch_sources = True,
    duplicate_version_warning = "none",
)
use_repo(maven, "maven")

# --- Scala toolchain ---
scala_config = use_extension("@rules_scala//scala/extensions:config.bzl", "scala_config")
scala_config.settings(scala_version = "2.13.16")

scala_deps = use_extension("@rules_scala//scala/extensions:deps.bzl", "scala_deps")
scala_deps.scala()

scala_toolchains = use_extension("@rules_scala//scala/extensions:toolchains.bzl", "scala_toolchains")

# --- Python toolchain ---
python = use_extension("@rules_python//python:extensions.bzl", "python")
python.toolchain(python_version = "3.12")

pip = use_extension("@rules_python//python/extensions:pip.bzl", "pip")
pip.parse(
    hub_name = "pip",
    python_version = "3.12",
    requirements_lock = "//:requirements_lock.txt",
)
use_repo(pip, "pip")
```

### 1.3 — Create requirements_lock.txt

Pinned Python dependencies (initially empty or minimal — the tracer uses stdlib only):

```
# Python dependencies for callshow tracer
# The core tracer uses only stdlib (sys.settrace), no external deps needed.
```

### 1.4 — Define directory structure

```
python-callshow/
├── WORKSPACE
├── MODULE.bazel
├── BUILD.bazel                          # root marker
├── .bazelrc
├── requirements_lock.txt
├── plans/                               # these plan files
├── src/
│   ├── main/
│   │   ├── python/callshow/             # Python tracer package
│   │   │   ├── BUILD.bazel
│   │   │   ├── __init__.py
│   │   │   ├── tracer.py                # core sys.settrace logic
│   │   │   ├── models.py                # dataclasses for call events
│   │   │   ├── output.py                # JSON serialization
│   │   │   └── cli.py                   # CLI entry point
│   │   └── scala/com/callshow/
│   │       └── gui/                     # JavaFX GUI
│   │           ├── BUILD.bazel
│   │           ├── Main.scala
│   │           ├── AppController.scala
│   │           ├── ExecutionRunner.scala
│   │           └── CallStackView.scala
│   └── test/
│       ├── python/callshow/
│       │   ├── BUILD.bazel
│       │   └── test_tracer.py
│       └── scala/com/callshow/
│           └── gui/
│               ├── BUILD.bazel
│               └── ... (future tests)
```

### 1.5 — Create BUILD.bazel files

**Python tracer** (`src/main/python/callshow/BUILD.bazel`):
```bzl
load("@rules_python//python:defs.bzl", "py_library", "py_binary")

py_library(
    name = "callshow_lib",
    srcs = glob(["**/*.py"]),
    visibility = ["//visibility:public"],
)

py_binary(
    name = "callshow",
    srcs = ["cli.py"],
    main = "cli.py",
    deps = [":callshow_lib"],
    visibility = ["//visibility:public"],
)
```

**Scala GUI** (`src/main/scala/com/callshow/gui/BUILD.bazel`):
```bzl
load("@rules_scala//scala:scala.bzl", "scala_binary")

scala_binary(
    name = "gui",
    main_class = "com.callshow.gui.Main",
    srcs = glob(["**/*.scala"]),
    deps = [
        "@maven//:org_openjfx_javafx_base",
        "@maven//:org_openjfx_javafx_controls",
        "@maven//:org_openjfx_javafx_fxml",
        "@maven//:org_openjfx_javafx_graphics",
        "@maven//:com_google_code_gson_gson",
    ],
    jvm_flags = [
        "--module-path", "external/maven",
        "--add-modules", "javafx.controls,javafx.fxml",
    ],
)
```

### 1.6 — Verify the build

```bash
bazel build //...
bazel run //src/main/python/callshow:callshow -- --help
bazel run //src/main/scala/com/callshow/gui:gui
```

---

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| rules_python 1.4.1 | Latest stable; hermetic Python toolchain |
| Python 3.12 | Modern, good `sys.settrace` support |
| Scala 2.13.16 | Matches numba1 example for consistency |
| JavaFX 21.0.1 | Same as numba1; well-tested with rules_scala |
| Gson for JSON | Scala GUI needs to parse tracer output; Gson is lightweight and already proven in numba1 |
| No external Python deps initially | `sys.settrace` is stdlib; keeps build simple |

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| rules_python + rules_scala MODULE.bazel conflicts | Keep Python and Scala in separate BUILD packages; test `bazel build //...` early |
| JavaFX module system issues | Use `--add-opens` / `--add-modules` JVM flags as needed |
| Platform-specific JavaFX natives | Start macOS-only; add Windows targets later following numba1 pattern |

---

## Done When

- `bazel build //...` succeeds with both Scala and Python targets
- `bazel run` works for both the Python CLI and Scala GUI (even if they are stubs)
- Directory structure matches the layout above
