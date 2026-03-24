---
name: trace
description: Trace a Python program's call stack with locals and return values using CallShow
argument-hint: <directory> <command> OR --attach <PID>
---

Run CallShow to trace a Python program's call stack including locals and return values.

## Arguments

Two modes:
- **Launch mode**: `$ARGUMENTS` is `<directory> <command to run>`
- **Attach mode**: `$ARGUMENTS` starts with `--attach` or `-a` followed by a PID

If only a directory is provided, ask the user what command to run.
If no arguments are provided, ask the user for both directory and command.

## Instructions

### Launch mode (tracing a new program)

1. Parse the arguments. The first argument is the directory path. Everything after is the command to execute.

2. Verify the directory exists using the Bash tool.

3. Run the CallShow tracer CLI with locals capture enabled:

```bash
PYTHONPATH=src/main/python python3 -m callshow.cli \
  --directory "<directory>" \
  --command "<command>" \
  --output /tmp/callshow_trace.json \
  --capture-locals \
  --stream
```

Or via Bazel if preferred:

```bash
bazel run //src/main/python/callshow:callshow -- \
  --directory "<directory>" \
  --command "<command>" \
  --output /tmp/callshow_trace.json \
  --capture-locals \
  --stream
```

### Attach mode (tracing a running process)

1. Parse the PID from the arguments.

2. Run the tracer in attach mode. This requires sudo on macOS:

```bash
cd /Users/owenchristie/python-callshow && \
sudo PYTHONPATH=src/main/python python3 -m callshow.cli \
  --attach <PID> \
  --output /tmp/callshow_trace.json \
  --capture-locals \
  --stream
```

Note: Attach mode requires `sudo` on macOS. The user will need to press Ctrl+C to stop tracing. The tracer will cleanly detach from the process.

### After tracing

4. After the trace completes, read `/tmp/callshow_trace.json` and present a summary:

   - Total events captured and duration
   - Exit code of the traced program (or "attached" for attach mode)
   - The call tree showing function names, files, line numbers, and return values
   - For each function with captured locals, show the variable names and values
   - Highlight any non-zero exit codes or errors

5. If the user asks follow-up questions about specific functions or files, use the trace data to answer them. You can reference specific events by file path and line number.

## Example Usage

```
/trace /Users/me/myproject python3 main.py
/trace ~/myapp "source venv/bin/activate && python run.py --verbose"
/trace . python3 -m pytest tests/test_foo.py
/trace --attach 12345
```

## Notes

- The `--capture-locals` flag is always included to provide maximum debugging insight
- The `--stream` flag prints events as they happen so you can see progress
- Default exclude patterns filter out site-packages, stdlib internals, and Python noise
- Dunder methods, comprehension internals, and `self`/`cls` are automatically skipped
- To include site-packages in the trace, add `--include-all` to the command
- Attach mode requires `sudo` on macOS (SIP restricts process debugging) and uses `lldb`
- Attach mode on Linux requires `gdb` and `ptrace` permissions
