"""Fonctions système et détection pour le lanceur AUGUSTIN.

Ce module ne dépend pas de Tkinter. Les opérations susceptibles de prendre du
temps (détection de Java, lecture des journaux, test du port) sont conçues pour
être exécutées dans un thread de travail par l'interface graphique.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence


MINECRAFT_VERSION = "1.21.4"
PAPER_PORT = 25565
MINIMUM_JAVA_VERSION = 21
DEFAULT_MEMORY_GIB = 2
PORT_PROBE_TIMEOUT_SECONDS = 0.20
JAVA_VERSION_TIMEOUT_SECONDS = 6.0
MAX_LOG_READ_BYTES = 1024 * 1024

MINECRAFT_LAUNCHER_AUMID = "Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"

_JAVA_VERSION_PATTERN = re.compile(
    r"(?:openjdk\s+)?version\s+\"?(?P<major>\d+)", re.IGNORECASE
)


@dataclass(frozen=True)
class JavaRuntime:
    """Décrit une JVM vérifiée utilisable par Paper."""

    executable: Path
    major_version: int


@dataclass(frozen=True)
class MinecraftProfileStatus:
    """Décrit la compatibilité du profil Minecraft local."""

    profile_exists: bool
    profile_version: str | None
    client_installed: bool

    @property
    def is_compatible(self) -> bool:
        return (
            self.profile_exists
            and self.profile_version == MINECRAFT_VERSION
            and self.client_installed
        )


class LauncherConfigurationError(RuntimeError):
    """Signale un prérequis local absent ou invalide."""


def executable_directory() -> Path:
    """Retourne le dossier de l'exécutable, pas le dossier temporaire PyInstaller."""

    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[3]


def is_paper_server_directory(directory: Path) -> bool:
    """Indique si un dossier contient l'installation Paper attendue."""

    return (directory / "paper.jar").is_file()


def resolve_server_directory(
    executable_dir: Path | None = None,
    environment: dict[str, str] | None = None,
) -> Path:
    """Trouve Paper sans dépendre du répertoire courant du processus."""

    environment = environment if environment is not None else os.environ
    candidates: list[Path] = []

    configured_directory = environment.get("AUGUSTIN_SERVER_DIR")
    if configured_directory:
        candidates.append(Path(configured_directory))

    # Le dossier de l'exécutable est le seul repli local : aucun chemin
    # appartenant à une machine de développement ne doit être embarqué.
    candidates.append(executable_dir or executable_directory())

    seen: set[Path] = set()
    for candidate in candidates:
        try:
            resolved = candidate.expanduser().resolve()
        except OSError:
            continue
        if resolved in seen:
            continue
        seen.add(resolved)
        if is_paper_server_directory(resolved):
            return resolved

    raise LauncherConfigurationError(
        "Impossible de trouver paper.jar. Place AugLauncher.exe dans le "
        "dossier PaperServer ou définis la variable AUGUSTIN_SERVER_DIR."
    )


def parse_java_major(version_output: str) -> int | None:
    """Extrait le numéro majeur depuis la sortie de ``java -version``."""

    match = _JAVA_VERSION_PATTERN.search(version_output)
    return int(match.group("major")) if match else None


def _no_window_creation_flags() -> int:
    return int(getattr(subprocess, "CREATE_NO_WINDOW", 0))


def read_java_major(
    executable: Path,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> int | None:
    """Vérifie la version d'une JVM avec une durée maximale bornée."""

    try:
        result = runner(
            [str(executable), "-version"],
            capture_output=True,
            text=True,
            check=False,
            timeout=JAVA_VERSION_TIMEOUT_SECONDS,
            creationflags=_no_window_creation_flags(),
        )
    except (OSError, subprocess.SubprocessError):
        return None

    output = f"{result.stdout or ''}\n{result.stderr or ''}"
    return parse_java_major(output)


def _common_windows_java_candidates(environment: dict[str, str]) -> Iterable[Path]:
    """Cherche quelques emplacements JDK Windows standards sans récursion lourde."""

    roots: list[Path] = []
    for variable in ("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"):
        value = environment.get(variable)
        if value:
            roots.append(Path(value))

    patterns = (
        "Eclipse Adoptium/jdk-*/bin/java.exe",
        "Microsoft/jdk-*/bin/java.exe",
        "Java/jdk-*/bin/java.exe",
        "BellSoft/LibericaJDK-*/bin/java.exe",
        "Programs/Eclipse Adoptium/jdk-*/bin/java.exe",
    )
    for root in roots:
        for pattern in patterns:
            try:
                matches = sorted(root.glob(pattern), reverse=True)
            except OSError:
                continue
            yield from matches


def java_candidates(
    environment: dict[str, str] | None = None,
    launcher_directory: Path | None = None,
) -> Iterable[Path]:
    """Fournit les JVM candidates dans un ordre stable et explicite."""

    environment = environment if environment is not None else os.environ

    configured_java = environment.get("AUGUSTIN_JAVA")
    if configured_java:
        yield Path(configured_java)

    launcher_root = launcher_directory or executable_directory()
    yield launcher_root / "runtime" / "bin" / "java.exe"
    yield launcher_root / "jdk" / "bin" / "java.exe"

    java_home = environment.get("JAVA_HOME")
    if java_home:
        yield Path(java_home) / "bin" / "java.exe"

    java_on_path = shutil.which("java")
    if java_on_path:
        yield Path(java_on_path)

    yield from _common_windows_java_candidates(environment)


def find_java_runtime(
    candidates: Iterable[Path] | None = None,
    version_reader: Callable[[Path], int | None] = read_java_major,
) -> JavaRuntime:
    """Sélectionne une JVM Java 21 ou plus récente pour Paper 1.21.4."""

    seen: set[Path] = set()
    incompatible_versions: list[str] = []

    for candidate in candidates if candidates is not None else java_candidates():
        try:
            resolved = candidate.expanduser().resolve()
        except OSError:
            continue
        if resolved in seen or not resolved.is_file():
            continue
        seen.add(resolved)

        major_version = version_reader(resolved)
        if major_version is None:
            continue
        if major_version >= MINIMUM_JAVA_VERSION:
            return JavaRuntime(resolved, major_version)
        incompatible_versions.append(f"Java {major_version} ({resolved})")

    detail = ""
    if incompatible_versions:
        detail = " JVM trop anciennes détectées : " + "; ".join(incompatible_versions[:3])
    raise LauncherConfigurationError(
        "Java 21 ou plus récent est requis pour démarrer Paper 1.21.4. "
        "Installe une JDK 21, configure JAVA_HOME ou AUGUSTIN_JAVA." + detail
    )


def build_server_command(
    java_runtime: JavaRuntime,
    minimum_memory_gib: int = DEFAULT_MEMORY_GIB,
    maximum_memory_gib: int = DEFAULT_MEMORY_GIB,
) -> tuple[str, ...]:
    """Construit la commande Paper sans passer par un fichier batch."""

    if minimum_memory_gib <= 0 or maximum_memory_gib < minimum_memory_gib:
        raise ValueError("La mémoire Paper doit être positive et cohérente.")

    return (
        str(java_runtime.executable),
        f"-Xms{minimum_memory_gib}G",
        f"-Xmx{maximum_memory_gib}G",
        "-jar",
        "paper.jar",
        "--nogui",
    )


def is_port_listening(
    host: str = "127.0.0.1",
    port: int = PAPER_PORT,
    connector: Callable[..., socket.socket] = socket.create_connection,
    timeout_seconds: float = PORT_PROBE_TIMEOUT_SECONDS,
) -> bool:
    """Teste le port Minecraft avec un délai court et toujours borné."""

    try:
        connection = connector((host, port), timeout=timeout_seconds)
    except OSError:
        return False

    try:
        return True
    finally:
        connection.close()


def minecraft_directory(appdata_directory: Path | None = None) -> Path:
    """Retourne le dossier Minecraft Java de l'utilisateur Windows."""

    if appdata_directory is None:
        appdata = os.environ.get("APPDATA")
        if not appdata:
            raise LauncherConfigurationError("La variable APPDATA est introuvable.")
        appdata_directory = Path(appdata)
    return appdata_directory / ".minecraft"


def check_minecraft_profile(
    directory: Path | None = None,
    profile_name: str = "MineGus",
) -> MinecraftProfileStatus:
    """Vérifie le profil MineGus et les fichiers du client 1.21.4."""

    minecraft_root = directory or minecraft_directory()
    profile_path = minecraft_root / "launcher_profiles.json"
    profile_version: str | None = None

    try:
        payload = json.loads(profile_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        payload = {}

    profiles = payload.get("profiles", {}) if isinstance(payload, dict) else {}
    if isinstance(profiles, dict):
        for profile in profiles.values():
            if isinstance(profile, dict) and profile.get("name") == profile_name:
                value = profile.get("lastVersionId")
                profile_version = value if isinstance(value, str) else None
                break

    version_directory = minecraft_root / "versions" / MINECRAFT_VERSION
    client_installed = (
        (version_directory / f"{MINECRAFT_VERSION}.json").is_file()
        and (version_directory / f"{MINECRAFT_VERSION}.jar").is_file()
    )
    return MinecraftProfileStatus(
        profile_exists=profile_version is not None,
        profile_version=profile_version,
        client_installed=client_installed,
    )


def minecraft_launcher_command() -> Sequence[str]:
    """Retourne la commande Windows pour ouvrir le Launcher officiel."""

    return (
        "explorer.exe",
        f"shell:AppsFolder\\{MINECRAFT_LAUNCHER_AUMID}",
    )


class LogTailer:
    """Suit un fichier de logs après rotation, troncature ou écriture partielle."""

    def __init__(self, log_path: Path, initial_bytes: int = 96 * 1024) -> None:
        self.log_path = log_path
        self.initial_bytes = initial_bytes
        self._offset = 0
        self._identity: tuple[int, int] | None = None
        self._pending = b""
        self._lock = threading.Lock()

    @staticmethod
    def _identity_for_stat(stat_result: os.stat_result) -> tuple[int, int]:
        return (int(stat_result.st_dev), int(stat_result.st_ino))

    @staticmethod
    def _complete_lines(payload: bytes) -> tuple[list[str], bytes]:
        """Retourne uniquement les lignes terminées, en conservant le fragment final."""

        if not payload:
            return [], b""
        chunks = payload.splitlines(keepends=True)
        pending = b""
        if chunks and not chunks[-1].endswith((b"\n", b"\r")):
            pending = chunks.pop()
        lines = [chunk.rstrip(b"\r\n").decode("utf-8", errors="replace") for chunk in chunks]
        return lines, pending

    def read_initial_lines(self) -> list[str]:
        """Retourne la fin du journal sans charger tout son historique."""

        with self._lock:
            try:
                stat_result = self.log_path.stat()
                size = stat_result.st_size
            except OSError:
                return []

            start = max(0, size - self.initial_bytes)
            try:
                with self.log_path.open("rb") as log_file:
                    log_file.seek(start)
                    payload = log_file.read(self.initial_bytes)
            except OSError:
                return []

            self._identity = self._identity_for_stat(stat_result)
            self._offset = size
            self._pending = b""

            # En partant au milieu du fichier, la première ligne peut être incomplète.
            if start > 0:
                first_break = payload.find(b"\n")
                payload = payload[first_break + 1 :] if first_break >= 0 else b""

            lines, pending = self._complete_lines(payload)
            if pending:
                # Le journal existant peut se terminer sans saut de ligne : on l'affiche.
                lines.append(pending.decode("utf-8", errors="replace"))
            return lines

    def seek_to_end(self) -> None:
        """Synchronise la position sans relire les lignes déjà reçues via stdout."""

        with self._lock:
            try:
                stat_result = self.log_path.stat()
            except OSError:
                self._offset = 0
                self._identity = None
                self._pending = b""
                return
            self._identity = self._identity_for_stat(stat_result)
            self._offset = stat_result.st_size
            self._pending = b""

    def read_new_lines(self, max_bytes: int = MAX_LOG_READ_BYTES) -> list[str]:
        """Lit un volume borné de nouvelles lignes depuis le dernier passage."""

        with self._lock:
            try:
                stat_result = self.log_path.stat()
            except OSError:
                return []

            identity = self._identity_for_stat(stat_result)
            if self._identity is not None and identity != self._identity:
                self._offset = 0
                self._pending = b""
            elif stat_result.st_size < self._offset:
                self._offset = 0
                self._pending = b""
            self._identity = identity

            try:
                with self.log_path.open("rb") as log_file:
                    log_file.seek(self._offset)
                    payload = log_file.read(max_bytes)
                    self._offset = log_file.tell()
            except OSError:
                return []

            lines, self._pending = self._complete_lines(self._pending + payload)
            return lines
