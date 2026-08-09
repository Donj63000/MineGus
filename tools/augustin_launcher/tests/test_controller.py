"""Tests du contrôle sûr du processus Paper."""

from __future__ import annotations

import io
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from augustin_launcher.controller import (  # noqa: E402
    ServerAlreadyRunningError,
    ServerControlError,
    ServerController,
    ServerState,
)
from augustin_launcher.core import JavaRuntime  # noqa: E402


class _FakeStdin(io.StringIO):
    def __init__(self) -> None:
        super().__init__()
        self.flush_called = False

    def flush(self) -> None:
        self.flush_called = True
        super().flush()


class _BrokenStdin(_FakeStdin):
    def write(self, _value: str) -> int:
        raise BrokenPipeError("stdin fermé")


class _FakeProcess:
    def __init__(self, output: str = "") -> None:
        self.stdin = _FakeStdin()
        self.stdout = io.StringIO(output)
        self.returncode: int | None = None
        self.terminated = False
        self.killed = False
        self.pid = 4242

    def poll(self) -> int | None:
        return self.returncode

    def terminate(self) -> None:
        self.terminated = True
        self.returncode = 1

    def kill(self) -> None:
        self.killed = True
        self.returncode = 1


class ControllerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.server_directory = Path(self.temporary_directory.name)
        (self.server_directory / "paper.jar").touch()
        self.runtime = JavaRuntime(Path(r"C:\Java21\bin\java.exe"), 21)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _controller(
        self,
        process: _FakeProcess,
        port_open: bool = False,
    ) -> ServerController:
        return ServerController(
            self.server_directory,
            java_finder=lambda: self.runtime,
            port_checker=lambda: port_open,
            popen_factory=lambda *_args, **_kwargs: process,
        )

    def test_start_owns_the_child_process_and_accepts_console_commands(self) -> None:
        process = _FakeProcess("Paper est prêt\n")
        controller = self._controller(process)

        command = controller.start()
        controller.send_command("say Bonjour")

        self.assertEqual(command[-3:], ("-jar", "paper.jar", "--nogui"))
        self.assertTrue(controller.owns_process)
        self.assertEqual(process.stdin.getvalue(), "say Bonjour\n")
        self.assertTrue(process.stdin.flush_called)

    def test_clean_stop_sends_the_paper_stop_command(self) -> None:
        process = _FakeProcess()
        controller = self._controller(process)
        controller.start()

        controller.request_stop()

        self.assertEqual(process.stdin.getvalue(), "stop\n")
        self.assertEqual(controller.state, ServerState.STOPPING)

    def test_external_server_cannot_be_started_or_controlled(self) -> None:
        process = _FakeProcess()
        controller = self._controller(process, port_open=True)

        self.assertEqual(controller.state, ServerState.EXTERNAL)
        with self.assertRaises(ServerAlreadyRunningError):
            controller.start()
        with self.assertRaises(ServerControlError):
            controller.send_command("stop")

    def test_force_stop_is_only_available_for_an_owned_process(self) -> None:
        process = _FakeProcess()
        controller = self._controller(process)
        controller.start()

        controller.force_stop()

        self.assertTrue(process.terminated)
        self.assertFalse(controller.owns_process)

    def test_closed_console_is_reported_as_a_control_error(self) -> None:
        process = _FakeProcess()
        process.stdin = _BrokenStdin()
        controller = self._controller(process)
        controller.start()

        with self.assertRaises(ServerControlError):
            controller.send_command("list")

    def test_snapshot_stays_fast_while_java_detection_is_slow(self) -> None:
        process = _FakeProcess()
        finder_entered = threading.Event()
        release_finder = threading.Event()
        errors: list[BaseException] = []

        def slow_java_finder() -> JavaRuntime:
            finder_entered.set()
            if not release_finder.wait(timeout=2.0):
                raise RuntimeError("test timeout")
            return self.runtime

        controller = ServerController(
            self.server_directory,
            java_finder=slow_java_finder,
            port_checker=lambda: False,
            popen_factory=lambda *_args, **_kwargs: process,
        )

        def start_server() -> None:
            try:
                controller.start()
            except BaseException as error:  # pragma: no cover - diagnostic du test
                errors.append(error)

        worker = threading.Thread(target=start_server)
        worker.start()
        self.assertTrue(finder_entered.wait(timeout=1.0))

        started = time.perf_counter()
        snapshot = controller.snapshot()
        elapsed = time.perf_counter() - started

        self.assertLess(elapsed, 0.10)
        self.assertEqual(snapshot.state, ServerState.STARTING)
        self.assertFalse(snapshot.owns_process)

        release_finder.set()
        worker.join(timeout=2.0)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])

    def test_ready_log_marker_moves_the_owned_server_online(self) -> None:
        process = _FakeProcess('[00:00:00] [Server thread/INFO]: Done (1.23s)! For help, type "help"\n')
        controller = self._controller(process)

        controller.start()
        assert controller._reader is not None
        controller._reader.join(timeout=1.0)

        snapshot = controller.snapshot()
        self.assertEqual(snapshot.state, ServerState.RUNNING)
        self.assertTrue(snapshot.owns_process)

    def test_unexpected_process_exit_is_exposed_as_an_error(self) -> None:
        process = _FakeProcess()
        controller = self._controller(process)
        controller.start()

        process.returncode = 7
        snapshot = controller.snapshot()

        self.assertEqual(snapshot.state, ServerState.ERROR)
        self.assertEqual(snapshot.last_exit_code, 7)
        self.assertIn("code 7", snapshot.last_error or "")

    def test_output_queue_is_bounded_and_reports_dropped_lines(self) -> None:
        process = _FakeProcess()
        controller = ServerController(
            self.server_directory,
            java_finder=lambda: self.runtime,
            port_checker=lambda: False,
            popen_factory=lambda *_args, **_kwargs: process,
            output_queue_limit=3,
        )

        for index in range(5):
            controller._enqueue_output(f"ligne {index}")

        lines = controller.drain_output(max_lines=10)
        self.assertIn("2 ligne(s)", lines[0])
        self.assertEqual(lines[1:], ["ligne 2", "ligne 3", "ligne 4"])


if __name__ == "__main__":
    unittest.main()
