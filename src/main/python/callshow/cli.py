"""CLI entry point for callshow tracer."""

import argparse
import sys
import os

from callshow.tracer import run_traced
from callshow.output import write_trace_result

DEFAULT_EXCLUDES = ["site-packages", "lib/python", "importlib", "<frozen", "<string>"]


def main():
    parser = argparse.ArgumentParser(
        description="CallShow - Trace Python call stacks"
    )
    parser.add_argument(
        "--directory", "-d", required=True,
        help="Working directory for the target program",
    )
    parser.add_argument(
        "--command", "-c", required=True,
        help="Bash command to execute (can be multi-line)",
    )
    parser.add_argument(
        "--output", "-o", default="callshow_trace.json",
        help="Output JSON file path (default: callshow_trace.json)",
    )
    parser.add_argument(
        "--events-file",
        help="Path for streaming JSONL events (default: auto temp file)",
    )
    parser.add_argument(
        "--include-all", action="store_true",
        help="Include all call sites (don't exclude site-packages)",
    )
    parser.add_argument(
        "--exclude", nargs="*", default=None,
        help="Additional path patterns to exclude",
    )
    parser.add_argument(
        "--stream", action="store_true",
        help="Print events to stdout as they stream in",
    )
    parser.add_argument(
        "--stdout-file",
        help="Path to capture subprocess stdout",
    )
    parser.add_argument(
        "--stderr-file",
        help="Path to capture subprocess stderr",
    )
    parser.add_argument(
        "--capture-locals", action="store_true",
        help="Capture local variables at each call/return",
    )

    args = parser.parse_args()

    # Validate directory
    if not os.path.isdir(args.directory):
        print(f"Error: directory does not exist: {args.directory}", file=sys.stderr)
        sys.exit(1)

    # Build exclude patterns
    exclude = [] if args.include_all else list(DEFAULT_EXCLUDES)
    if args.exclude:
        exclude.extend(args.exclude)

    def on_event(event):
        if args.stream:
            indent = "  " * event.depth
            arrow = "\u2192" if event.event_type == "call" else "\u2190"
            print(
                f"{indent}{arrow} {event.function_name} "
                f"({event.file_path}:{event.line_number})"
            )

    result = run_traced(
        directory=args.directory,
        command=args.command,
        output_path=args.output,
        events_file=args.events_file,
        exclude_patterns=exclude,
        on_event=on_event,
        stdout_file=args.stdout_file,
        stderr_file=args.stderr_file,
        capture_locals=args.capture_locals,
    )

    write_trace_result(result, args.output)

    print(f"\nTrace complete: {len(result.events)} events captured", file=sys.stderr)
    print(f"Duration: {result.duration_seconds:.2f}s", file=sys.stderr)
    print(f"Exit code: {result.exit_code}", file=sys.stderr)
    print(f"Output written to: {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
