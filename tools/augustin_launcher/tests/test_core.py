"""Tests des fonctions sans interface graphique du lanceur."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from augustin_launcher.core import (  # noqa: E402
    DEFAULT_MEMORY_GIB,
    MINECRAFT_VERSION,
    JavaRuntime,
    LogTailer,
    build_server_command,
    check_minecraft_profile,
    is_port_listening,
    parse_java_major,
    resolve_server_directory,
)


class _FakeConnection:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


class CoreTests(unittest.TestCase):
    def test_parse_java_major_supports_oracle_and_openjdk_outputs(self) -> None:
        self.assertEqual(parse_java_major('java version "21.0.8"'), 21)
        self.assertEqual(parse_java_major('openjdk version "24"'), 24)
        self.assertIsNone(parse_java_major("version inconnue"))

    def test_build_server_command_uses_the_expected_paper_arguments(self) -> None:
        runtime = JavaRuntime(Path(r"C:\Java\bin\java.exe"), 21)

        command = build_server_command(runtime)

        self.assertEqual(
            command,
            (
                r"C:\Java\bin\java.exe",
                f"-Xms{DEFAULT_MEMORY_GIB}G",
                f"-Xmx{DEFAULT_MEMORY_GIB}G",
                "-jar",
                "paper.jar",
                "--nogui",
            ),
        )

    def test_resolve_server_directory_prefers_the_executable_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            server_directory = Path(temporary_directory)
            (server_directory / "paper.jar").touch()

            resolved = resolve_server_directory(
                executable_dir=server_directory,
                environment={},
            )

        self.assertEqual(resolved, server_directory.resolve())

    def test_port_check_closes_a_successful_connection(self) -> None:
        connection = _FakeConnection()

        listening = is_port_listening(
            connector=lambda _address, timeout: connection,
        )

        self.assertTrue(listening)
        self.assertTrue(connection.closed)

    def test_port_check_handles_a_closed_port(self) -> None:
        def failing_connector(_address: object, timeout: float) -> _FakeConnection:
            raise OSError("port fermé")

        self.assertFalse(is_port_listening(connector=failing_connector))

    def test_minecraft_profile_is_found_by_its_name_and_not_its_json_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            minecraft_directory = Path(temporary_directory)
            (minecraft_directory / "launcher_profiles.json").write_text(
                json.dumps(
                    {
                        "profiles": {
                            "random-profile-id": {
                                "name": "MineGus",
                                "lastVersionId": MINECRAFT_VERSION,
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )
            version_directory = minecraft_directory / "versions" / MINECRAFT_VERSION
            version_directory.mkdir(parents=True)
            (version_directory / f"{MINECRAFT_VERSION}.json").touch()
            (version_directory / f"{MINECRAFT_VERSION}.jar").touch()

            status = check_minecraft_profile(minecraft_directory)

        self.assertTrue(status.profile_exists)
        self.assertEqual(status.profile_version, MINECRAFT_VERSION)
        self.assertTrue(status.client_installed)
        self.assertTrue(status.is_compatible)

    def test_minecraft_profile_is_rejected_when_the_client_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            minecraft_directory = Path(temporary_directory)
            (minecraft_directory / "launcher_profiles.json").write_text(
                json.dumps(
                    {
                        "profiles": {
                            "profile": {
                                "name": "MineGus",
                                "lastVersionId": MINECRAFT_VERSION,
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )

            status = check_minecraft_profile(minecraft_directory)

        self.assertFalse(status.client_installed)
        self.assertFalse(status.is_compatible)

    def test_log_tailer_reads_appended_lines_and_recovers_after_truncation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            log_path = Path(temporary_directory) / "latest.log"
            log_path.write_text("première ligne\ndeuxième ligne\n", encoding="utf-8")
            tailer = LogTailer(log_path)

            self.assertEqual(
                tailer.read_initial_lines(),
                ["première ligne", "deuxième ligne"],
            )

            with log_path.open("a", encoding="utf-8") as log_file:
                log_file.write("troisième ligne\n")
            self.assertEqual(tailer.read_new_lines(), ["troisième ligne"])

            log_path.write_text("journal remplacé\n", encoding="utf-8")
            self.assertEqual(tailer.read_new_lines(), ["journal remplacé"])


if __name__ == "__main__":
    unittest.main()
