"""Data models for call-stack tracing."""

from dataclasses import dataclass, field, asdict
from typing import Optional, Dict
import json


@dataclass
class CallEvent:
    """A single call or return event in the trace."""
    event_type: str           # "call" or "return"
    function_name: str
    file_path: str
    line_number: int
    timestamp: float          # seconds since trace start (monotonic)
    depth: int                # call stack depth
    caller_function: Optional[str] = None
    caller_file: Optional[str] = None
    caller_line: Optional[int] = None
    locals_data: Optional[Dict[str, str]] = None  # variable name -> repr string
    return_value: Optional[str] = None             # repr of return value

    def to_dict(self) -> dict:
        d = asdict(self)
        # Omit optional fields when None to keep output compact
        if d.get("locals_data") is None:
            del d["locals_data"]
        if d.get("return_value") is None:
            del d["return_value"]
        return d

    def to_json(self) -> str:
        return json.dumps(self.to_dict())


@dataclass
class TraceResult:
    """Complete result of a trace execution."""
    events: list
    command: str
    directory: str
    duration_seconds: float
    exit_code: int
    excluded_patterns: list = field(default_factory=list)
