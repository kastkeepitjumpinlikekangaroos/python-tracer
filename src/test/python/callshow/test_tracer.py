"""Tests for the callshow tracer."""

import json
import os
import sys
import tempfile
import unittest

from callshow.models import CallEvent, TraceResult
from callshow.tracer import run_traced
from callshow.output import write_trace_result, filter_events


# Path to testdata relative to workspace root
def _testdata_dir():
    # Walk up from this file to find the workspace root
    d = os.path.dirname(os.path.abspath(__file__))
    while d != "/":
        if os.path.exists(os.path.join(d, "MODULE.bazel")):
            return os.path.join(d, "testdata", "sample_project")
        d = os.path.dirname(d)
    # Fallback: try relative to cwd
    return os.path.join(os.getcwd(), "testdata", "sample_project")


class TestCallEvent(unittest.TestCase):
    def test_to_dict(self):
        event = CallEvent(
            event_type="call",
            function_name="foo",
            file_path="/test/foo.py",
            line_number=10,
            timestamp=0.001,
            depth=0,
        )
        d = event.to_dict()
        self.assertEqual(d["event_type"], "call")
        self.assertEqual(d["function_name"], "foo")
        self.assertEqual(d["line_number"], 10)
        self.assertIsNone(d["caller_function"])

    def test_to_json(self):
        event = CallEvent(
            event_type="return",
            function_name="bar",
            file_path="/test/bar.py",
            line_number=20,
            timestamp=0.5,
            depth=1,
        )
        j = event.to_json()
        parsed = json.loads(j)
        self.assertEqual(parsed["event_type"], "return")
        self.assertEqual(parsed["depth"], 1)


class TestTracer(unittest.TestCase):
    def test_basic_trace(self):
        """Trace a simple Python script and verify events are captured."""
        sample_dir = _testdata_dir()
        if not os.path.isdir(sample_dir):
            self.skipTest(f"testdata not found at {sample_dir}")

        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
            output_path = f.name

        try:
            result = run_traced(
                directory=sample_dir,
                command="python3 main.py",
                output_path=output_path,
                exclude_patterns=["site-packages", "importlib", "<frozen"],
            )
            self.assertIsInstance(result, TraceResult)
            self.assertEqual(result.exit_code, 0)
            self.assertGreater(len(result.events), 0)
            self.assertGreater(result.duration_seconds, 0)

            # Check that we captured call events
            call_events = [e for e in result.events if e.event_type == "call"]
            return_events = [e for e in result.events if e.event_type == "return"]
            self.assertGreater(len(call_events), 0)
            self.assertGreater(len(return_events), 0)

            # Check that known functions are in the trace
            function_names = {e.function_name for e in result.events}
            self.assertIn("main", function_names)
            self.assertIn("greet", function_names)
            self.assertIn("compute", function_names)
            self.assertIn("add_numbers", function_names)
        finally:
            if os.path.exists(output_path):
                os.unlink(output_path)

    def test_include_all(self):
        """Verify include-all mode captures more events."""
        sample_dir = _testdata_dir()
        if not os.path.isdir(sample_dir):
            self.skipTest(f"testdata not found at {sample_dir}")

        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
            output_filtered = f.name
        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
            output_all = f.name

        try:
            result_filtered = run_traced(
                directory=sample_dir,
                command="python3 main.py",
                output_path=output_filtered,
                exclude_patterns=["site-packages", "importlib", "<frozen"],
            )
            result_all = run_traced(
                directory=sample_dir,
                command="python3 main.py",
                output_path=output_all,
                exclude_patterns=[],
            )
            # Include-all should capture at least as many events
            self.assertGreaterEqual(len(result_all.events), len(result_filtered.events))
        finally:
            for p in [output_filtered, output_all]:
                if os.path.exists(p):
                    os.unlink(p)

    def test_streaming_callback(self):
        """Verify on_event callback fires during execution."""
        sample_dir = _testdata_dir()
        if not os.path.isdir(sample_dir):
            self.skipTest(f"testdata not found at {sample_dir}")

        streamed_events = []

        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
            output_path = f.name

        try:
            result = run_traced(
                directory=sample_dir,
                command="python3 main.py",
                output_path=output_path,
                exclude_patterns=["site-packages", "importlib", "<frozen"],
                on_event=lambda e: streamed_events.append(e),
            )
            # Streaming should have captured events
            self.assertGreater(len(streamed_events), 0)
        finally:
            if os.path.exists(output_path):
                os.unlink(output_path)


class TestOutput(unittest.TestCase):
    def test_write_trace_result(self):
        """Verify JSON output structure."""
        events = [
            CallEvent("call", "foo", "/test.py", 1, 0.001, 0),
            CallEvent("return", "foo", "/test.py", 5, 0.002, 0),
        ]
        result = TraceResult(
            events=events,
            command="python test.py",
            directory="/test",
            duration_seconds=0.5,
            exit_code=0,
        )

        with tempfile.NamedTemporaryFile(suffix=".json", delete=False, mode="w") as f:
            output_path = f.name

        try:
            write_trace_result(result, output_path)
            with open(output_path) as f:
                data = json.load(f)

            self.assertIn("metadata", data)
            self.assertIn("events", data)
            self.assertEqual(data["metadata"]["total_events"], 2)
            self.assertEqual(data["metadata"]["exit_code"], 0)
            self.assertEqual(len(data["events"]), 2)
            self.assertEqual(data["events"][0]["function_name"], "foo")
        finally:
            if os.path.exists(output_path):
                os.unlink(output_path)

    def test_filter_events(self):
        """Verify filtering by path patterns."""
        events = [
            CallEvent("call", "foo", "/project/main.py", 1, 0.001, 0),
            CallEvent("call", "bar", "/usr/lib/python3.12/site-packages/pkg/mod.py", 10, 0.002, 1),
            CallEvent("call", "baz", "/project/utils.py", 5, 0.003, 1),
        ]
        filtered = filter_events(events, ["site-packages"])
        self.assertEqual(len(filtered), 2)
        names = [e.function_name for e in filtered]
        self.assertIn("foo", names)
        self.assertIn("baz", names)
        self.assertNotIn("bar", names)


if __name__ == "__main__":
    unittest.main()
