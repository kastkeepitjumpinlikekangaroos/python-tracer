---
name: trace
description: Trace a Python program's call stack with locals and return values using CallShow
argument-hint: <directory> <command>
---

Run CallShow to trace a Python program's call stack including locals and return values.

## Arguments

- `$ARGUMENTS` should be: `<directory> <command to run>`
- If only a directory is provided, ask the user what command to run
- If no arguments are provided, ask the user for both directory and command

## Instructions

1. Parse the arguments. The first argument is the directory path. Everything after is the command to execute.

2. Verify the directory exists using the Bash tool.

3. Run the CallShow tracer CLI with locals capture enabled:

```bash
bazel run //src/main/python/callshow:callshow -- \
  --directory "<directory>" \
  --command "<command>" \
  --output /tmp/callshow_trace.json \
  --capture-locals \
  --stream
```

If `bazel run` is too slow or the project hasn't been built yet, fall back to running directly:

```bash
PYTHONPATH=src/main/python python3 -m callshow.cli \
  --directory "<directory>" \
  --command "<command>" \
  --output /tmp/callshow_trace.json \
  --capture-locals \
  --stream
```

4. After the trace completes, read `/tmp/callshow_trace.json` and present a summary:

   - Total events captured and duration
   - Exit code of the traced program
   - The call tree showing function names, files, line numbers, and return values
   - For each function with captured locals, show the variable names and values
   - Highlight any non-zero exit codes or errors

5. If the user asks follow-up questions about specific functions or files, use the trace data to answer them. You can reference specific events by file path and line number.

## Example Usage

```
/trace /Users/me/myproject python3 main.py
/trace ~/myapp "source venv/bin/activate && python run.py --verbose"
/trace . python3 -m pytest tests/test_foo.py
```

## Notes

- The `--capture-locals` flag is always included to provide maximum debugging insight
- The `--stream` flag prints events as they happen so you can see progress
- Default exclude patterns filter out site-packages, stdlib internals, and Python noise
- Dunder methods, comprehension internals, and `self`/`cls` are automatically skipped
- To include site-packages in the trace, add `--include-all` to the command
