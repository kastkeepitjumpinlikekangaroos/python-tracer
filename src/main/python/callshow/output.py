"""JSON serialization and filtering for trace results."""

import json
from callshow.models import CallEvent, TraceResult


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
    with open(output_path, "w") as f:
        json.dump(data, f, indent=2)


def filter_events(
    events: list, exclude_patterns: list
) -> list:
    """Post-hoc filter events by file path patterns."""
    def include(e):
        return not any(p in e.file_path for p in exclude_patterns)
    return [e for e in events if include(e)]
