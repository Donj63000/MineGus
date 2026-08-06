"""Contrôle du processus Paper pour le lanceur AUGUSTIN."""

from __future__ import annotations

import queue
import subprocess
import threading
import time
from enum import Enum
from pathlib import Path
from typing import Callable

from .core import (
    PAPER_PORT,
    JavaRuntime,
    LauncherConfigurationError,
    build_server_command,
    find_java_runtime,
    is_port_listening,
)


class ServerState(str, Enum):
    """États affichés dans l'interface du lanceur."""

    STOPPED = "Arrêté"
    STARTING = "Démarrage"
    RUNNING = "En ligne"
    STOPPING = "Arrêt en cours"
    EXTERNAL = "Serveur externe détecté"
    ERROR = "Erreur"


class ServerControlError(RuntimeError):
    """Signale une action impossible sur le processus Paper."""


class ServerAlreadyRunningError(ServerControlError):
    """Empêche l'ouverture d'une seconde instance Paper."""


class ServerController:
    """Démarre et arrête uniquement le serveur enfant créé par le launcher."""

    def __init__(
        self,
        server_directory: Path,
        java_finder: Callable[[], JavaRuntime] = find_java_runtime,
        port_checker: Callable[[], bool] = is_port_listening,
        popen_factory: Callable[..., subprocess.Popen[str]] = subprocess.Popen,
    ) -> None:
        self.server_directory = server_directory
        self._java_finder = java_finder
        self._port_checker = port_checker
        self._popen_factory = popen_factory
        self._process: subprocess.Popen[str] | None = None
        self._reader: threading.Thread | None = None
        self._output: queue.Queue[str] = queue.Queue()
        self._state = ServerState.STOPPED

    @property
    def state(self) -> ServerState:
        """Retourne l'état actuel après avoir vérifié le processus."""

        return self.refresh()

    @property
    def owns_process(self) -> bool:
        """Indique si le launcher peut envoyer des commandes au serveur."""

        return self._process is not None and self._process.poll() is None

    def refresh(self) -> ServerState:
        """Actualise l'état sans jamais prendre le contrôle d'un serveur externe."""

        if self._process is not None:
            if self._process.poll() is None:
                if self._state == ServerState.STOPPING:
                    return self._state
                self._state = (
                    ServerState.RUNNING
                    if self._port_checker()
                    else ServerState.STARTING
                )
                return self._state
            self._process = None
            self._reader = None

        self._state = (
            ServerState.EXTERNAL if self._port_checker() else ServerState.STOPPED
        )
        return self._state

    def start(self) -> tuple[str, ...]:
        """Crée Paper avec ses flux redirigés vers la console de l'interface."""

        if self.refresh() != ServerState.STOPPED:
            raise ServerAlreadyRunningError(
                "Le port Minecraft est déjà utilisé : aucun second serveur ne sera lancé."
            )
        if not (self.server_directory / "paper.jar").is_file():
            raise LauncherConfigurationError(
                f"paper.jar est introuvable dans {self.server_directory}."
            )

        runtime = self._java_finder()
        command = build_server_command(runtime)
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            self._process = self._popen_factory(
                command,
                cwd=str(self.server_directory),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=creationflags,
            )
        except OSError as error:
            self._state = ServerState.ERROR
            raise ServerControlError(
                f"Impossible de démarrer Paper : {error}."
            ) from error

        self._state = ServerState.STARTING
        self._reader = threading.Thread(
            target=self._read_process_output,
            name="paper-log-reader",
            daemon=True,
        )
        self._reader.start()
        return command

    def _read_process_output(self) -> None:
        """Transfère chaque ligne Paper vers une file sûre pour Tkinter."""

        process = self._process
        if process is None or process.stdout is None:
            return

        try:
            for line in iter(process.stdout.readline, ""):
                self._output.put(line.rstrip("\r\n"))
        finally:
            try:
                process.stdout.close()
            except OSError:
                pass

    def drain_output(self) -> list[str]:
        """Retourne les logs disponibles sans bloquer le thread graphique."""

        lines: list[str] = []
        while True:
            try:
                lines.append(self._output.get_nowait())
            except queue.Empty:
                return lines

    def send_command(self, command: str) -> None:
        """Envoie une commande seulement au serveur enfant du launcher."""

        normalized_command = command.strip()
        if not normalized_command:
            return
        if not self.owns_process or self._process is None or self._process.stdin is None:
            raise ServerControlError(
                "Les commandes sont désactivées pour un serveur externe ou arrêté."
            )

        # Ici, je conserve la même saisie que la console Paper native.
        try:
            self._process.stdin.write(f"{normalized_command}\n")
            self._process.stdin.flush()
        except (OSError, ValueError) as error:
            self._state = ServerState.ERROR
            raise ServerControlError(
                "La console Paper est devenue indisponible pendant l'envoi de la commande."
            ) from error

    def request_stop(self) -> None:
        """Demande un arrêt propre à Paper afin de sauvegarder les mondes."""

        if not self.owns_process:
            raise ServerControlError(
                "Je ne peux arrêter proprement que le serveur lancé par ce launcher."
            )
        self.send_command("stop")
        self._state = ServerState.STOPPING

    def wait_for_exit(
        self,
        timeout_seconds: float = 60.0,
        sleep: Callable[[float], None] = time.sleep,
    ) -> bool:
        """Attend la fin de Paper hors du thread graphique."""

        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            if not self.owns_process:
                self.refresh()
                return True
            sleep(0.2)
        return not self.owns_process

    def force_stop(self) -> None:
        """Force l'arrêt uniquement après confirmation explicite dans l'interface."""

        if not self.owns_process or self._process is None:
            raise ServerControlError("Aucun serveur enfant à arrêter de force.")
        try:
            self._process.terminate()
        except OSError as error:
            self._state = ServerState.ERROR
            raise ServerControlError(
                "Impossible de demander l'arrêt forcé du processus Paper."
            ) from error
        self._state = ServerState.STOPPING
