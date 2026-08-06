"""Fonctions pures de détection pour le lanceur AUGUSTIN."""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence


MINECRAFT_VERSION = "1.21.4"
PAPER_PORT = 25565
MINIMUM_JAVA_VERSION = 21
DEFAULT_MEMORY_GIB = 2
DEFAULT_SERVER_DIRECTORY = Path(
    r"C:\Users\nodig\Desktop\SERVEUR MINECRAFT AUGUSTIN\PaperServer"
)
DEFAULT_JAVA_EXECUTABLE = Path(
    r"C:\Users\nodig\.jdks\ms-21.0.8\bin\java.exe"
)
MINECRAFT_LAUNCHER_AUMID = (
    "Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"
)

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
    """Retourne le dossier de l'exécutable, jamais le dossier temporaire PyInstaller."""

    # Ici, je cible le dossier visible par l'utilisateur après la compilation.
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
    """Trouve le serveur sans dépendre du répertoire courant du processus."""

    environment = environment if environment is not None else os.environ
    candidates: list[Path] = []
    configured_directory = environment.get("AUGUSTIN_SERVER_DIR")
    if configured_directory:
        candidates.append(Path(configured_directory))

    candidates.append(executable_dir or executable_directory())
    candidates.append(DEFAULT_SERVER_DIRECTORY)

    for candidate in candidates:
        resolved = candidate.expanduser().resolve()
        if is_paper_server_directory(resolved):
            return resolved

    raise LauncherConfigurationError(
        "Impossible de trouver paper.jar. Place AUGUSTIN_Launcher.exe dans le "
        "dossier PaperServer ou définis AUGUSTIN_SERVER_DIR."
    )


def parse_java_major(version_output: str) -> int | None:
    """Extrait le numéro majeur depuis la sortie de ``java -version``."""

    match = _JAVA_VERSION_PATTERN.search(version_output)
    return int(match.group("major")) if match else None


def read_java_major(
    executable: Path,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> int | None:
    """Lance une vérification légère de version sans démarrer le serveur."""

    try:
        result = runner(
            [str(executable), "-version"],
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return None

    output = f"{result.stdout or ''}\n{result.stderr or ''}"
    return parse_java_major(output)


def java_candidates(environment: dict[str, str] | None = None) -> Iterable[Path]:
    """Fournit les JVM candidates en privilégiant la JDK 21 configurée."""

    environment = environment if environment is not None else os.environ
    yield DEFAULT_JAVA_EXECUTABLE

    java_home = environment.get("JAVA_HOME")
    if java_home:
        yield Path(java_home) / "bin" / "java.exe"

    java_on_path = shutil.which("java")
    if java_on_path:
        yield Path(java_on_path)


def find_java_runtime(
    candidates: Iterable[Path] | None = None,
    version_reader: Callable[[Path], int | None] = read_java_major,
) -> JavaRuntime:
    """Sélectionne une JVM Java 21 ou plus récente pour Paper 1.21.4."""

    seen: set[Path] = set()
    for candidate in candidates if candidates is not None else java_candidates():
        resolved = candidate.expanduser().resolve()
        if resolved in seen or not resolved.is_file():
            continue
        seen.add(resolved)
        major_version = version_reader(resolved)
        if major_version is not None and major_version >= MINIMUM_JAVA_VERSION:
            return JavaRuntime(resolved, major_version)

    raise LauncherConfigurationError(
        "Java 21 ou plus récent est requis pour démarrer Paper 1.21.4."
    )


def build_server_command(
    java_runtime: JavaRuntime,
    minimum_memory_gib: int = DEFAULT_MEMORY_GIB,
    maximum_memory_gib: int = DEFAULT_MEMORY_GIB,
) -> tuple[str, ...]:
    """Construit la commande Paper sans passer par start.bat."""

    if minimum_memory_gib <= 0 or maximum_memory_gib < minimum_memory_gib:
        raise ValueError("La mémoire Paper doit être positive et cohérente.")

    # Ici, je garde les 2 Go déjà prévus par la configuration du serveur.
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
) -> bool:
    """Teste le port Minecraft afin d'empêcher un second lancement."""

    try:
        connection = connector((host, port), timeout=0.25)
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
                profile_version = profile.get("lastVersionId")
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
    """Retourne la commande Windows stable pour ouvrir le Launcher officiel."""

    return (
        "explorer.exe",
        f"shell:AppsFolder\\{MINECRAFT_LAUNCHER_AUMID}",
    )


class LogTailer:
    """Suit un fichier de logs, y compris après sa rotation ou sa troncature."""

    def __init__(self, log_path: Path, initial_bytes: int = 64 * 1024) -> None:
        self.log_path = log_path
        self.initial_bytes = initial_bytes
        self._offset = 0

    def read_initial_lines(self) -> list[str]:
        """Retourne la fin du journal existant sans charger tout son historique."""

        try:
            size = self.log_path.stat().st_size
        except OSError:
            return []

        start = max(0, size - self.initial_bytes)
        try:
            with self.log_path.open("rb") as log_file:
                log_file.seek(start)
                payload = log_file.read()
        except OSError:
            return []

        self._offset = size
        text = payload.decode("utf-8", errors="replace")
        lines = text.splitlines()
        if start > 0 and lines and not text.startswith(("\n", "\r")):
            return lines[1:]
        return lines

    def read_new_lines(self) -> list[str]:
        """Lit uniquement les lignes ajoutées depuis le dernier passage."""

        try:
            size = self.log_path.stat().st_size
        except OSError:
            return []

        if size < self._offset:
            # Ici, je reprends au début si Paper vient de faire tourner son journal.
            self._offset = 0

        try:
            with self.log_path.open("rb") as log_file:
                log_file.seek(self._offset)
                payload = log_file.read()
                self._offset = log_file.tell()
        except OSError:
            return []

        return payload.decode("utf-8", errors="replace").splitlines()
