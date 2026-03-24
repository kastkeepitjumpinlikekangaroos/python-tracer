"""Attach to a running Python process and inject sys.settrace."""

import os
import sys
import json
import time
import shutil
import signal
import tempfile
import subprocess
import platform
from typing import Optional, Callable

from callshow.models import CallEvent, TraceResult
from callshow.tracer import StreamingTraceReader


# The payload script that gets injected into the target process.
# Uses __PLACEHOLDER__ substitution (not .format()) to avoid brace issues.
ATTACH_PAYLOAD = r'''
import sys
import os
import time
import json
import threading
import traceback as _cs_tb

_cs_output_path = __TRACE_OUTPUT_PATH__
_cs_log_path = _cs_output_path + ".log"
_cs_exclude = __TRACE_EXCLUDE_PATTERNS__
_cs_capture_locals = __TRACE_CAPTURE_LOCALS__
_cs_max_repr = 120

# Error log for diagnostics
def _cs_log(msg):
    try:
        with open(_cs_log_path, "a") as f:
            f.write(f"{time.time()}: {msg}\n")
    except Exception:
        pass

try:
    _cs_fd = open(_cs_output_path, "w", buffering=1)
    _cs_log(f"Payload initialized. Writing events to {_cs_output_path}")
except Exception as e:
    _cs_log(f"FATAL: cannot open output file: {e}")
    raise

_cs_depth = 0
_cs_start = time.monotonic()
_cs_lock = threading.Lock()
_cs_error_count = 0

_cs_skip_fns = frozenset({
    "__bool__", "__hash__", "__repr__", "__str__", "__len__",
    "__eq__", "__ne__", "__lt__", "__le__", "__gt__", "__ge__",
    "__contains__", "__iter__", "__next__",
    "__getattr__", "__getattribute__", "__setattr__", "__delattr__",
    "__get__", "__set__", "__delete__",
    "__getitem__", "__setitem__", "__delitem__",
    "__new__", "__del__", "__enter__", "__exit__",
    "__format__", "__sizeof__",
    "<genexpr>", "<listcomp>", "<setcomp>", "<dictcomp>",
})

_cs_skip_locals = frozenset({"self", "cls"})

def _cs_safe_repr(val):
    try:
        r = repr(val)
        return r[:_cs_max_repr - 3] + "..." if len(r) > _cs_max_repr else r
    except Exception:
        return "<repr failed>"

def _cs_capture(frame):
    if not _cs_capture_locals:
        return None
    try:
        result = {}
        for name, val in frame.f_locals.items():
            if name.startswith("_") or name.startswith(".") or name in _cs_skip_locals:
                continue
            result[name] = _cs_safe_repr(val)
        return result or None
    except Exception:
        return None

def _cs_trace(frame, event, arg):
    # CRITICAL: any unhandled exception here causes Python to silently
    # disable sys.settrace. We must catch everything.
    global _cs_depth, _cs_error_count
    try:
        fname = frame.f_code.co_name
        if event == "call":
            fn = frame.f_code.co_filename
            if fname not in _cs_skip_fns and fn and not any(p in fn for p in _cs_exclude):
                caller = frame.f_back
                rec = {
                    "event_type": "call",
                    "function_name": fname,
                    "file_path": fn,
                    "line_number": frame.f_lineno,
                    "timestamp": time.monotonic() - _cs_start,
                    "depth": _cs_depth,
                    "caller_function": caller.f_code.co_name if caller else None,
                    "caller_file": caller.f_code.co_filename if caller else None,
                    "caller_line": caller.f_lineno if caller else None,
                }
                ld = _cs_capture(frame)
                if ld is not None:
                    rec["locals_data"] = ld
                with _cs_lock:
                    _cs_fd.write(json.dumps(rec) + "\n")
                    _cs_fd.flush()
            _cs_depth += 1
            return _cs_trace
        elif event == "return":
            _cs_depth = max(0, _cs_depth - 1)
            if fname not in _cs_skip_fns:
                fn = frame.f_code.co_filename
                if fn and not any(p in fn for p in _cs_exclude):
                    rec = {
                        "event_type": "return",
                        "function_name": fname,
                        "file_path": fn,
                        "line_number": frame.f_lineno,
                        "timestamp": time.monotonic() - _cs_start,
                        "depth": _cs_depth,
                    }
                    if arg is not None:
                        rec["return_value"] = _cs_safe_repr(arg)
                    ld = _cs_capture(frame)
                    if ld is not None:
                        rec["locals_data"] = ld
                    with _cs_lock:
                        _cs_fd.write(json.dumps(rec) + "\n")
                        _cs_fd.flush()
        return _cs_trace
    except Exception as e:
        _cs_error_count += 1
        if _cs_error_count <= 5:
            _cs_log(f"Trace error #{_cs_error_count}: {e}\n{_cs_tb.format_exc()}")
        # MUST return the trace function to keep tracing alive
        return _cs_trace

# Write a marker event so the reader knows the payload is active
_cs_fd.write(json.dumps({
    "event_type": "call",
    "function_name": "__callshow_attached__",
    "file_path": "callshow:attach",
    "line_number": 0,
    "timestamp": 0.0,
    "depth": 0,
}) + "\n")
_cs_fd.flush()
_cs_log("Marker event written. Installing sys.settrace...")

sys.settrace(_cs_trace)
threading.settrace(_cs_trace)
_cs_log("sys.settrace installed successfully")
'''

DETACH_PAYLOAD = r'''
import sys
import threading
sys.settrace(None)
threading.settrace(None)
for name in list(globals()):
    if name == "_cs_fd":
        try:
            globals()[name].close()
        except Exception:
            pass
'''


def _find_debugger() -> Optional[str]:
    """Find lldb (macOS) or gdb (Linux)."""
    system = platform.system()
    if system == "Darwin":
        if shutil.which("lldb"):
            return "lldb"
    if shutil.which("gdb"):
        return "gdb"
    return None


def _inject_via_lldb(pid: int, payload_path: str) -> bool:
    """Inject a Python script into a process using lldb."""
    # Write an lldb script file to avoid shell escaping nightmares.
    # This ensures the GIL state variable is properly referenced.
    script_dir = os.path.dirname(payload_path)
    lldb_script = os.path.join(script_dir, "inject.lldb")

    escaped_path = payload_path.replace("\\", "\\\\").replace('"', '\\"')

    with open(lldb_script, "w") as f:
        f.write(f"process attach --pid {pid}\n")
        f.write(f'expr void *$gil = (void *)PyGILState_Ensure()\n')
        f.write(f'expr (int)PyRun_SimpleString("exec(open(\\"{escaped_path}\\").read())")\n')
        f.write(f'expr (void)PyGILState_Release($gil)\n')
        f.write(f'detach\n')
        f.write(f'quit\n')

    cmd = ["lldb", "--batch", "--source", lldb_script]

    print(f"  Running: lldb --batch --source {lldb_script}", file=sys.stderr)
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=30,
        )
    except subprocess.TimeoutExpired:
        print("Error: lldb timed out after 30s", file=sys.stderr)
        return False

    # Always show lldb output for diagnostics
    stdout = result.stdout.strip()
    stderr = result.stderr.strip()

    if stdout:
        # Filter to interesting lines
        for line in stdout.splitlines():
            line_lower = line.strip().lower()
            if any(kw in line_lower for kw in [
                "error", "warning", "pygilstate", "pyrun",
                "process", "attach", "detach", "unable", "failed",
                "(int)", "(void",
            ]):
                print(f"  lldb: {line.strip()}", file=sys.stderr)

    if result.returncode != 0:
        print(f"Error: lldb exited with code {result.returncode}", file=sys.stderr)
        if stderr:
            print(f"  stderr: {stderr}", file=sys.stderr)
        return False

    # Check for fatal errors in output
    combined = (stdout + " " + stderr).lower()
    if "error: attach failed" in combined or "unable to attach" in combined:
        print(
            "Error: lldb could not attach. On macOS, try: sudo <command>",
            file=sys.stderr,
        )
        return False
    if "error:" in combined and "pyrun_simplestring" in combined:
        print("Error: failed to execute Python code in target process", file=sys.stderr)
        return False

    return True


def _inject_via_gdb(pid: int, payload_path: str) -> bool:
    """Inject a Python script into a process using gdb."""
    escaped_path = payload_path.replace("\\", "\\\\").replace('"', '\\"')

    # Use a gdb script file for reliability
    script_dir = os.path.dirname(payload_path)
    gdb_script = os.path.join(script_dir, "inject.gdb")

    with open(gdb_script, "w") as f:
        f.write(f"attach {pid}\n")
        f.write(f'call (void*)PyGILState_Ensure()\n')
        f.write(f'call (int)PyRun_SimpleString("exec(open(\\"{escaped_path}\\").read())")\n')
        f.write(f'call (void)PyGILState_Release($1)\n')
        f.write(f'detach\n')
        f.write(f'quit\n')

    cmd = ["gdb", "--batch", "--command", gdb_script]

    print(f"  Running: gdb --batch --command {gdb_script}", file=sys.stderr)
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=30,
        )
    except subprocess.TimeoutExpired:
        print("Error: gdb timed out after 30s", file=sys.stderr)
        return False

    if result.returncode != 0:
        print(f"Error: gdb exited with code {result.returncode}", file=sys.stderr)
        if result.stderr:
            print(f"  stderr: {result.stderr.strip()}", file=sys.stderr)
        return False

    return True


def inject_payload(pid: int, payload_path: str) -> bool:
    """Inject a Python payload into a running process."""
    debugger = _find_debugger()
    if debugger is None:
        print(
            "Error: no debugger found. Install lldb (macOS) or gdb (Linux).",
            file=sys.stderr,
        )
        return False

    print(f"Injecting via {debugger} into PID {pid}...", file=sys.stderr)
    if debugger == "lldb":
        return _inject_via_lldb(pid, payload_path)
    else:
        return _inject_via_gdb(pid, payload_path)


def attach_traced(
    pid: int,
    output_path: str,
    events_file: Optional[str] = None,
    exclude_patterns: Optional[list] = None,
    on_event: Optional[Callable[[CallEvent], None]] = None,
    capture_locals: bool = False,
) -> TraceResult:
    """
    Attach to a running Python process and trace its call stack.

    Args:
        pid: Process ID of the target Python process
        output_path: Path to write final JSON result
        events_file: Path for streaming JSONL events
        exclude_patterns: File path substrings to exclude
        on_event: Optional callback for each event
        capture_locals: Capture local variables

    Returns:
        TraceResult with captured events
    """
    if exclude_patterns is None:
        exclude_patterns = []

    tmp_dir = tempfile.mkdtemp(prefix="callshow_attach_")

    if events_file is None:
        events_file = os.path.join(tmp_dir, "trace_events.jsonl")

    # Build the attach payload with substituted values
    payload = ATTACH_PAYLOAD
    payload = payload.replace("__TRACE_OUTPUT_PATH__", repr(events_file))
    payload = payload.replace("__TRACE_EXCLUDE_PATTERNS__", repr(exclude_patterns))
    payload = payload.replace("__TRACE_CAPTURE_LOCALS__", repr(capture_locals))

    payload_path = os.path.join(tmp_dir, "attach_payload.py")
    with open(payload_path, "w") as f:
        f.write(payload)

    detach_path = os.path.join(tmp_dir, "detach_payload.py")
    with open(detach_path, "w") as f:
        f.write(DETACH_PAYLOAD)

    # Verify the process exists
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        print(f"Error: no process with PID {pid}", file=sys.stderr)
        sys.exit(1)
    except PermissionError:
        print(
            f"Error: no permission to signal PID {pid}. Try running with sudo.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Collect events
    all_events = []

    def collect_event(event: CallEvent):
        all_events.append(event)
        if on_event:
            on_event(event)

    # Inject the tracing payload
    start_time = time.monotonic()
    if not inject_payload(pid, payload_path):
        print("Failed to attach to process.", file=sys.stderr)
        sys.exit(1)

    # Wait for the marker event to confirm payload is running
    log_file = events_file + ".log"
    print(f"Waiting for trace events at {events_file}...", file=sys.stderr)

    marker_found = False
    for _ in range(60):  # up to 3 seconds
        if os.path.exists(events_file) and os.path.getsize(events_file) > 0:
            marker_found = True
            break
        time.sleep(0.05)

    if not marker_found:
        print(
            "Warning: no events received from target process.\n"
            f"  Events file: {events_file}\n"
            f"  Payload: {payload_path}\n"
            f"  Diagnostics log: {log_file}",
            file=sys.stderr,
        )
        # Show log file contents if available
        if os.path.exists(log_file):
            print(f"  --- Payload log ---", file=sys.stderr)
            with open(log_file) as f:
                for line in f:
                    print(f"  {line.rstrip()}", file=sys.stderr)
            print(f"  ---", file=sys.stderr)
        else:
            print(
                "  No log file found. The payload may not have executed.\n"
                "  Ensure the target is a CPython process and you have permission.",
                file=sys.stderr,
            )
    else:
        print("Payload active in target process.", file=sys.stderr)

    # Start reading events
    reader = StreamingTraceReader(events_file, collect_event)
    reader.start()

    print(
        f"Attached to PID {pid}. Tracing... (press Ctrl+C to stop and detach)",
        file=sys.stderr,
    )

    # Wait for Ctrl+C or SIGTERM
    stop = False

    def handle_signal(signum, frame):
        nonlocal stop
        stop = True

    old_int_handler = signal.signal(signal.SIGINT, handle_signal)
    old_term_handler = signal.signal(signal.SIGTERM, handle_signal)

    try:
        while not stop:
            # Check if the target process is still alive
            try:
                os.kill(pid, 0)
            except (ProcessLookupError, PermissionError):
                print("\nTarget process exited.", file=sys.stderr)
                break

            # Periodic status
            if len(all_events) > 0 and len(all_events) % 500 == 0:
                elapsed = time.monotonic() - start_time
                print(
                    f"\r  {len(all_events)} events captured ({elapsed:.1f}s)...",
                    end="", file=sys.stderr,
                )

            time.sleep(0.1)
    finally:
        signal.signal(signal.SIGINT, old_int_handler)
        signal.signal(signal.SIGTERM, old_term_handler)

    duration = time.monotonic() - start_time

    # Detach: remove the trace hook
    print("\nDetaching tracer...", file=sys.stderr)
    try:
        os.kill(pid, 0)  # check if still alive before detaching
        inject_payload(pid, detach_path)
    except (ProcessLookupError, PermissionError):
        print("  (process already exited, skipping detach)", file=sys.stderr)

    # Give reader time to finish
    time.sleep(0.3)
    reader.stop()

    # Final read pass
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

    # Show any errors from the payload log
    if os.path.exists(log_file):
        with open(log_file) as f:
            log_contents = f.read().strip()
        if "error" in log_contents.lower() or "FATAL" in log_contents:
            print(f"  --- Payload diagnostics ---", file=sys.stderr)
            print(f"  {log_contents}", file=sys.stderr)
            print(f"  ---", file=sys.stderr)

    return TraceResult(
        events=all_events,
        command=f"attach:{pid}",
        directory="",
        duration_seconds=duration,
        exit_code=0,
        excluded_patterns=exclude_patterns,
    )
