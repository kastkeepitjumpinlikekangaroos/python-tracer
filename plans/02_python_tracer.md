# Plan 02: Python Call-Stack Tracer

## Goal

Build a Python program that takes a directory path and a command entry point (multi-line bash), executes the target Python program, and collects the full call stack — streaming call events in real time and writing the final result as JSON to disk.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│  cli.py (entry point)                                │
│  - Parses args: --directory, --command, --output     │
│  - Optionally: --exclude-patterns, --include-all     │
│  - Launches the traced subprocess                    │
└──────────┬───────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────┐
│  tracer.py (core tracing engine)                     │
│  - Injects a tracing bootstrap into the subprocess   │
│  - The bootstrap uses sys.settrace to capture:       │
│    • call events (function entry)                    │
│    • return events (function exit)                   │
│  - Events are written to a pipe/file as JSONL        │
│    (one JSON object per line, streamed in real time)  │
└──────────┬───────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────┐
│  models.py (data model)                              │
│  - CallEvent dataclass:                              │
│    • event_type: "call" | "return"                   │
│    • function_name: str                              │
│    • file_path: str (absolute)                       │
│    • line_number: int                                │
│    • timestamp: float (time.monotonic)               │
│    • depth: int (call stack depth)                   │
│    • caller_function: str | None                     │
│    • caller_file: str | None                         │
│    • caller_line: int | None                         │
│  - TraceResult dataclass:                            │
│    • events: list[CallEvent]                         │
│    • metadata: dict (command, directory, duration)    │
└──────────┬───────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────┐
│  output.py (serialization)                           │
│  - Writes final TraceResult as JSON to disk          │
│  - Supports filtering:                               │
│    • Exclude paths matching patterns (site-packages) │
│    • Include-all mode (no filtering)                 │
└──────────────────────────────────────────────────────┘
```

---

## Steps

### 2.1 — Define data models (`models.py`)

```python
from dataclasses import dataclass, field, asdict
from typing import Optional
import json

@dataclass
class CallEvent:
    event_type: str           # "call" or "return"
    function_name: str
    file_path: str
    line_number: int
    timestamp: float
    depth: int
    caller_function: Optional[str] = None
    caller_file: Optional[str] = None
    caller_line: Optional[int] = None

    def to_dict(self) -> dict:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict())

@dataclass
class TraceResult:
    events: list[CallEvent]
    command: str
    directory: str
    duration_seconds: float
    exit_code: int
    excluded_patterns: list[str] = field(default_factory=list)
```

### 2.2 — Build the tracing engine (`tracer.py`)

**Strategy**: Use a subprocess-based approach where:
1. We create a temporary Python bootstrap script that sets up `sys.settrace`
2. The bootstrap script wraps the user's command and writes JSONL events to a FIFO/file
3. The parent process reads events in real time from the FIFO/file

**Key implementation details**:

```python
import subprocess
import tempfile
import os
import json
import time
from pathlib import Path

TRACE_BOOTSTRAP_TEMPLATE = '''
import sys
import os
import time
import json

_trace_output_path = {output_path!r}
_trace_fd = open(_trace_output_path, 'w', buffering=1)  # line-buffered
_trace_depth = 0
_trace_exclude_patterns = {exclude_patterns!r}
_trace_start = time.monotonic()

def _should_include(filename):
    if not filename:
        return False
    for pattern in _trace_exclude_patterns:
        if pattern in filename:
            return False
    return True

def _trace_function(frame, event, arg):
    global _trace_depth
    if event == 'call':
        filename = frame.f_code.co_filename
        if _should_include(filename):
            caller = frame.f_back
            record = {{
                "event_type": "call",
                "function_name": frame.f_code.co_name,
                "file_path": filename,
                "line_number": frame.f_lineno,
                "timestamp": time.monotonic() - _trace_start,
                "depth": _trace_depth,
                "caller_function": caller.f_code.co_name if caller else None,
                "caller_file": caller.f_code.co_filename if caller else None,
                "caller_line": caller.f_lineno if caller else None,
            }}
            _trace_fd.write(json.dumps(record) + '\\n')
            _trace_fd.flush()
        _trace_depth += 1
        return _trace_function
    elif event == 'return':
        _trace_depth = max(0, _trace_depth - 1)
        filename = frame.f_code.co_filename
        if _should_include(filename):
            record = {{
                "event_type": "return",
                "function_name": frame.f_code.co_name,
                "file_path": filename,
                "line_number": frame.f_lineno,
                "timestamp": time.monotonic() - _trace_start,
                "depth": _trace_depth,
            }}
            _trace_fd.write(json.dumps(record) + '\\n')
            _trace_fd.flush()
    return _trace_function

sys.settrace(_trace_function)
'''

def run_traced(directory: str, command: str, output_path: str,
               exclude_patterns: list[str] | None = None,
               on_event=None) -> TraceResult:
    """
    Execute a command with call-stack tracing.

    Args:
        directory: Working directory for the command
        command: Multi-line bash command to execute
        output_path: Path to write final JSON result
        exclude_patterns: File path substrings to exclude (default: site-packages)
        on_event: Optional callback called with each CallEvent as it streams in
    """
    ...
```

**Subprocess execution approach**:
- Create a temporary directory for the trace FIFO
- Write the bootstrap + user command to a temp `.py` file
- For commands that invoke Python directly, inject `sys.settrace` via `sitecustomize.py` or `-c` wrapper
- For general bash commands, wrap with `PYTHONPATH` injection using a custom `sitecustomize.py`
- Read the JSONL output file in a separate thread, calling `on_event` for each line
- When subprocess completes, finalize the TraceResult

**Handling multi-line bash commands**:
The command entry point can be arbitrary bash. Strategy:
1. Write the command to a temp shell script
2. Set `PYTHONSTARTUP` or inject via `sitecustomize.py` placed at the front of `PYTHONPATH`
3. This ensures any Python process spawned by the bash command gets traced

```python
def _create_sitecustomize(trace_output_path, exclude_patterns):
    """Create a sitecustomize.py that installs our tracer."""
    content = TRACE_BOOTSTRAP_TEMPLATE.format(
        output_path=trace_output_path,
        exclude_patterns=exclude_patterns,
    )
    return content
```

### 2.3 — Build the streaming reader

A thread that tails the JSONL output file and invokes callbacks:

```python
import threading

class StreamingTraceReader:
    def __init__(self, trace_file_path: str, on_event):
        self.path = trace_file_path
        self.on_event = on_event
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def _read_loop(self):
        """Tail the JSONL file and emit events."""
        with open(self.path, 'r') as f:
            while not self._stop.is_set():
                line = f.readline()
                if line:
                    event = CallEvent(**json.loads(line))
                    self.on_event(event)
                else:
                    self._stop.wait(0.05)  # poll interval

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)
```

### 2.4 — Build the output serializer (`output.py`)

```python
def write_trace_result(result: TraceResult, output_path: str):
    """Write the full trace result as JSON."""
    data = {
        "metadata": {
            "command": result.command,
            "directory": result.directory,
            "duration_seconds": result.duration_seconds,
            "exit_code": result.exit_code,
            "excluded_patterns": result.excluded_patterns,
            "total_events": len(result.events),
        },
        "events": [e.to_dict() for e in result.events],
    }
    with open(output_path, 'w') as f:
        json.dump(data, f, indent=2)

def filter_events(events: list[CallEvent],
                  exclude_patterns: list[str]) -> list[CallEvent]:
    """Post-hoc filter events by file path patterns."""
    def include(e):
        return not any(p in e.file_path for p in exclude_patterns)
    return [e for e in events if include(e)]
```

### 2.5 — Build the CLI (`cli.py`)

```python
import argparse
import sys

DEFAULT_EXCLUDES = ["site-packages", "lib/python", "importlib", "<frozen"]

def main():
    parser = argparse.ArgumentParser(
        description="Trace Python call stacks"
    )
    parser.add_argument("--directory", "-d", required=True,
                        help="Working directory for the target program")
    parser.add_argument("--command", "-c", required=True,
                        help="Bash command to execute (can be multi-line)")
    parser.add_argument("--output", "-o", default="callshow_trace.json",
                        help="Output JSON file path")
    parser.add_argument("--include-all", action="store_true",
                        help="Include all call sites (don't exclude site-packages)")
    parser.add_argument("--exclude", nargs="*", default=None,
                        help="Additional path patterns to exclude")
    parser.add_argument("--stream", action="store_true",
                        help="Print events to stdout as they stream in")

    args = parser.parse_args()

    exclude = [] if args.include_all else DEFAULT_EXCLUDES
    if args.exclude:
        exclude.extend(args.exclude)

    def on_event(event):
        if args.stream:
            print(f"[{event.depth}] {event.event_type}: "
                  f"{event.file_path}:{event.line_number} "
                  f"{event.function_name}")

    result = run_traced(
        directory=args.directory,
        command=args.command,
        output_path=args.output,
        exclude_patterns=exclude,
        on_event=on_event,
    )

    print(f"\nTrace complete: {len(result.events)} events captured")
    print(f"Output written to: {args.output}")

if __name__ == "__main__":
    main()
```

### 2.6 — Write tests (`test_tracer.py`)

Test cases:
1. **Basic tracing**: Trace a simple Python script, verify call/return events captured
2. **Filtering**: Verify site-packages exclusion works
3. **Include-all**: Verify `--include-all` captures everything
4. **Multi-line command**: Verify multi-line bash commands work
5. **Streaming**: Verify `on_event` callback fires during execution
6. **Output format**: Verify JSON output structure matches expected schema
7. **Error handling**: Verify graceful handling when traced program crashes

---

## JSON Output Schema

```json
{
  "metadata": {
    "command": "python main.py",
    "directory": "/path/to/project",
    "duration_seconds": 1.234,
    "exit_code": 0,
    "excluded_patterns": ["site-packages", "lib/python"],
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
      "caller_line": 50
    },
    {
      "event_type": "return",
      "function_name": "main",
      "file_path": "/path/to/project/main.py",
      "line_number": 25,
      "timestamp": 1.200,
      "depth": 0
    }
  ]
}
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `sys.settrace` via sitecustomize injection | Most reliable way to trace any Python process spawned by arbitrary bash |
| JSONL for streaming, JSON for final output | JSONL is trivially streamable (one event per line); final JSON is structured for analysis |
| Line-buffered writes | Ensures events are available to reader immediately |
| Exclude patterns as path substrings | Simple, fast, and covers the main use case (site-packages) |
| `time.monotonic()` for timestamps | Not affected by system clock changes; relative to trace start |
| Depth tracking in tracer | Avoids needing to reconstruct from call/return pairing |

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| `sys.settrace` overhead slows traced program | Exclude patterns reduce event volume; document expected overhead |
| sitecustomize conflicts with target program | Use unique env var to namespace our sitecustomize |
| Large traces (millions of events) | Streaming architecture means GUI doesn't need to hold all in memory; final JSON can be large but written once |
| Traced program uses threading | `sys.settrace` only traces the thread it's installed on; use `threading.settrace` for all threads |
| Command uses virtual environments | Sitecustomize approach works with venvs since it's injected via PYTHONPATH |

---

## Done When

- `callshow --directory /some/project --command "python main.py" --output trace.json` produces valid JSON
- Events stream in real time (verifiable with `--stream` flag)
- site-packages are excluded by default
- `--include-all` includes everything
- Tests pass for all cases listed above
