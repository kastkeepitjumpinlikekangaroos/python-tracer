"""Core tracing engine using sys.settrace injection via sitecustomize."""

import subprocess
import tempfile
import os
import json
import time
import threading
from pathlib import Path
from typing import Callable, Optional

from callshow.models import CallEvent, TraceResult


# Template for the sitecustomize.py that gets injected into the traced process.
# This installs sys.settrace to capture all call/return events.
SITECUSTOMIZE_TEMPLATE = '''
import sys
import os
import time
import json
import threading

# Only activate if our marker env var is set
if os.environ.get("_CALLSHOW_ACTIVE") == "1":
    _trace_output_path = {output_path!r}
    _trace_fd = open(_trace_output_path, "w", buffering=1)  # line-buffered
    _trace_depth = 0
    _trace_exclude_patterns = {exclude_patterns!r}
    _trace_capture_locals = {capture_locals!r}
    _trace_start = time.monotonic()
    _trace_lock = threading.Lock()
    _trace_max_repr = 120

    # Dunder methods and internal names to skip entirely -- these are
    # implementation details (bool coercion, hashing, repr, etc.) that
    # clutter the trace without adding insight.
    _trace_skip_functions = frozenset({{
        "__bool__", "__hash__", "__repr__", "__str__", "__len__",
        "__eq__", "__ne__", "__lt__", "__le__", "__gt__", "__ge__",
        "__contains__", "__iter__", "__next__",
        "__getattr__", "__getattribute__", "__setattr__", "__delattr__",
        "__get__", "__set__", "__delete__",
        "__getitem__", "__setitem__", "__delitem__",
        "__new__", "__del__",
        "__enter__", "__exit__",
        "__format__", "__sizeof__",
        "<genexpr>", "<listcomp>", "<setcomp>", "<dictcomp>",
    }})

    # Local variable names to skip -- these are almost always noisy
    _trace_skip_locals = frozenset({{
        "self", "cls",
    }})

    def _should_include(filename):
        if not filename:
            return False
        for pattern in _trace_exclude_patterns:
            if pattern in filename:
                return False
        return True

    def _safe_repr(val):
        """Safely repr a value, truncating if too long."""
        try:
            r = repr(val)
            if len(r) > _trace_max_repr:
                return r[:_trace_max_repr - 3] + "..."
            return r
        except Exception:
            return "<repr failed>"

    def _capture_locals(frame):
        """Capture frame locals as a dict of name -> repr string."""
        if not _trace_capture_locals:
            return None
        try:
            result = {{}}
            for name, val in frame.f_locals.items():
                # Skip internal, dunder, private, and noisy names
                if name.startswith("_") or name in _trace_skip_locals:
                    continue
                # Skip iterator/generator internal vars like .0
                if name.startswith("."):
                    continue
                result[name] = _safe_repr(val)
            return result if result else None
        except Exception:
            return None

    def _trace_function(frame, event, arg):
        global _trace_depth
        fname = frame.f_code.co_name
        if event == "call":
            filename = frame.f_code.co_filename
            # Skip dunder methods and comprehension internals
            if fname in _trace_skip_functions:
                _trace_depth += 1
                return _trace_function
            if _should_include(filename):
                caller = frame.f_back
                record = {{
                    "event_type": "call",
                    "function_name": fname,
                    "file_path": filename,
                    "line_number": frame.f_lineno,
                    "timestamp": time.monotonic() - _trace_start,
                    "depth": _trace_depth,
                    "caller_function": caller.f_code.co_name if caller else None,
                    "caller_file": caller.f_code.co_filename if caller else None,
                    "caller_line": caller.f_lineno if caller else None,
                }}
                locals_data = _capture_locals(frame)
                if locals_data is not None:
                    record["locals_data"] = locals_data
                with _trace_lock:
                    _trace_fd.write(json.dumps(record) + "\\n")
                    _trace_fd.flush()
            _trace_depth += 1
            return _trace_function
        elif event == "return":
            _trace_depth = max(0, _trace_depth - 1)
            # Skip dunder methods and comprehension internals
            if fname in _trace_skip_functions:
                return _trace_function
            filename = frame.f_code.co_filename
            if _should_include(filename):
                record = {{
                    "event_type": "return",
                    "function_name": fname,
                    "file_path": filename,
                    "line_number": frame.f_lineno,
                    "timestamp": time.monotonic() - _trace_start,
                    "depth": _trace_depth,
                }}
                # Only include return value if it's not None
                if arg is not None:
                    record["return_value"] = _safe_repr(arg)
                locals_data = _capture_locals(frame)
                if locals_data is not None:
                    record["locals_data"] = locals_data
                with _trace_lock:
                    _trace_fd.write(json.dumps(record) + "\\n")
                    _trace_fd.flush()
        return _trace_function

    sys.settrace(_trace_function)
    threading.settrace(_trace_function)
'''


class StreamingTraceReader:
    """Tails a JSONL file and emits CallEvent objects via callback."""

    def __init__(self, trace_file_path: str, on_event: Callable[[CallEvent], None]):
        self.path = trace_file_path
        self.on_event = on_event
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def _read_loop(self):
        # Wait for file to exist
        while not self._stop.is_set() and not os.path.exists(self.path):
            self._stop.wait(0.05)

        if self._stop.is_set():
            return

        with open(self.path, "r") as f:
            while not self._stop.is_set():
                line = f.readline()
                if line:
                    line = line.strip()
                    if line:
                        try:
                            data = json.loads(line)
                            event = CallEvent(**data)
                            self.on_event(event)
                        except (json.JSONDecodeError, TypeError):
                            pass  # skip malformed lines
                else:
                    self._stop.wait(0.05)

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)


def run_traced(
    directory: str,
    command: str,
    output_path: str,
    events_file: Optional[str] = None,
    exclude_patterns: Optional[list] = None,
    on_event: Optional[Callable[[CallEvent], None]] = None,
    stdout_file: Optional[str] = None,
    stderr_file: Optional[str] = None,
    capture_locals: bool = False,
) -> TraceResult:
    """
    Execute a command with call-stack tracing.

    Args:
        directory: Working directory for the command
        command: Bash command to execute (can be multi-line)
        output_path: Path to write final JSON result
        events_file: Path for streaming JSONL events (temp file if None)
        exclude_patterns: File path substrings to exclude
        on_event: Optional callback called with each CallEvent as it streams in
        stdout_file: Path to capture subprocess stdout (optional)
        stderr_file: Path to capture subprocess stderr (optional)
        capture_locals: If True, capture frame locals at each call/return

    Returns:
        TraceResult with all captured events and metadata
    """
    if exclude_patterns is None:
        exclude_patterns = []

    # Create temp directory for trace artifacts
    tmp_dir = tempfile.mkdtemp(prefix="callshow_")

    # Set up events file
    if events_file is None:
        events_file = os.path.join(tmp_dir, "trace_events.jsonl")

    # Create the sitecustomize.py that will be injected
    sitecustomize_content = SITECUSTOMIZE_TEMPLATE.format(
        output_path=events_file,
        exclude_patterns=exclude_patterns,
        capture_locals=capture_locals,
    )
    sitecustomize_dir = os.path.join(tmp_dir, "callshow_inject")
    os.makedirs(sitecustomize_dir, exist_ok=True)
    sitecustomize_path = os.path.join(sitecustomize_dir, "sitecustomize.py")
    with open(sitecustomize_path, "w") as f:
        f.write(sitecustomize_content)

    # Write the command to a temp shell script
    script_path = os.path.join(tmp_dir, "run.sh")
    with open(script_path, "w") as f:
        f.write("#!/bin/bash\n")
        f.write(command)
        f.write("\n")
    os.chmod(script_path, 0o755)

    # Build a clean environment for the subprocess.
    # We start from the system environment but remove any Bazel-injected
    # PYTHONPATH so the traced program sees a normal Python environment.
    env = os.environ.copy()
    # Set PYTHONPATH to only our sitecustomize dir -- the traced program's
    # own directory is added by Python automatically (sys.path[0]).
    env["PYTHONPATH"] = sitecustomize_dir
    env["_CALLSHOW_ACTIVE"] = "1"
    # Remove Bazel/build-system vars that might confuse the subprocess
    for key in list(env.keys()):
        if key.startswith("RUNFILES") or key in (
            "TEST_SRCDIR", "PYTHONSAFEPATH",
            "BUILD_WORKING_DIRECTORY", "BUILD_WORKSPACE_DIRECTORY",
        ):
            del env[key]

    # Collect all events
    all_events = []
    events_lock = threading.Lock()

    def collect_event(event: CallEvent):
        with events_lock:
            all_events.append(event)
        if on_event:
            on_event(event)

    # Start streaming reader if we have a callback or need to collect events
    reader = StreamingTraceReader(events_file, collect_event)
    reader.start()

    # Set up stdout/stderr capture.
    # IMPORTANT: we must drain stdout/stderr concurrently to avoid deadlock.
    # If the traced program produces output while the pipe buffer is full,
    # the child blocks on write() and we block on waitpid() — deadlock.
    stdout_fh = None
    stderr_fh = None
    stdout_lines = []
    stderr_lines = []
    try:
        if stdout_file:
            stdout_fh = open(stdout_file, "w")
        if stderr_file:
            stderr_fh = open(stderr_file, "w")

        start_time = time.monotonic()
        proc = subprocess.Popen(
            ["bash", script_path],
            cwd=directory,
            env=env,
            stdout=stdout_fh if stdout_fh else subprocess.PIPE,
            stderr=stderr_fh if stderr_fh else subprocess.PIPE,
        )

        # Drain stdout/stderr in background threads to prevent pipe deadlock
        def drain_pipe(pipe, sink):
            if pipe is None:
                return
            try:
                for line in pipe:
                    sink.append(line)
            except Exception:
                pass

        if proc.stdout:
            t_out = threading.Thread(
                target=drain_pipe, args=(proc.stdout, stdout_lines), daemon=True)
            t_out.start()
        if proc.stderr:
            t_err = threading.Thread(
                target=drain_pipe, args=(proc.stderr, stderr_lines), daemon=True)
            t_err.start()

        proc.wait()
        duration = time.monotonic() - start_time

        # Wait for drain threads to finish
        if proc.stdout:
            t_out.join(timeout=2)
            proc.stdout.close()
        if proc.stderr:
            t_err.join(timeout=2)
            proc.stderr.close()
    finally:
        if stdout_fh:
            stdout_fh.close()
        if stderr_fh:
            stderr_fh.close()

    # Give reader time to finish reading remaining events
    time.sleep(0.2)
    reader.stop()

    # Also do a final read pass on the events file to catch anything missed
    if os.path.exists(events_file):
        seen_count = len(all_events)
        with open(events_file, "r") as f:
            lines = f.readlines()
        for line in lines[seen_count:]:
            line = line.strip()
            if line:
                try:
                    data = json.loads(line)
                    event = CallEvent(**data)
                    all_events.append(event)
                    if on_event:
                        on_event(event)
                except (json.JSONDecodeError, TypeError):
                    pass

    result = TraceResult(
        events=all_events,
        command=command,
        directory=directory,
        duration_seconds=duration,
        exit_code=proc.returncode,
        excluded_patterns=exclude_patterns,
    )

    return result
