"""Contrôle thread-safe du processus Paper pour le lanceur AUGUSTIN."""

from __future__ import annotations

import queue
import subprocess
import threading
import time
from dataclasses import dataclass
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
    """États fonctionnels affichés par le launcher."""

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


@dataclass(frozen=True)
class ServerSnapshot:
    """Vue instantanée et non bloquante de l'état du contrôleur."""

    state: ServerState
    owns_process: bool
    pid: int | None
    port_open: bool | None
    started_at: float | None
    ready_at: float | None
    java_runtime: JavaRuntime | None
    last_exit_code: int | None
    last_error: str | None

    @property
    def uptime_seconds(self) -> float | None:
        if not self.owns_process or self.started_at is None:
            return None
        return max(0.0, time.monotonic() - self.started_at)


class ServerController:
    """Démarre et arrête uniquement le serveur enfant créé par le launcher.

    Les méthodes ``start`` et ``probe`` peuvent être lentes et doivent être
    appelées depuis un thread de travail. ``snapshot`` et ``drain_output`` sont
    conçues pour le thread Tkinter : elles n'effectuent aucun appel réseau ni
    aucune détection de Java.
    """

    READY_MARKERS = (
        "DONE (",
        "FOR HELP, TYPE \"HELP\"",
    )

    def __init__(
        self,
        server_directory: Path,
        java_finder: Callable[[], JavaRuntime] = find_java_runtime,
        port_checker: Callable[[], bool] = is_port_listening,
        popen_factory: Callable[..., subprocess.Popen[str]] = subprocess.Popen,
        output_queue_limit: int = 20_000,
    ) -> None:
        self.server_directory = server_directory
        self._java_finder = java_finder
        self._port_checker = port_checker
        self._popen_factory = popen_factory

        self._lock = threading.RLock()
        self._operation_lock = threading.Lock()
        self._stdin_lock = threading.Lock()
        self._process: subprocess.Popen[str] | None = None
        self._reader: threading.Thread | None = None
        self._output: queue.Queue[str] = queue.Queue(maxsize=output_queue_limit)
        self._dropped_output_lines = 0

        self._state = ServerState.STOPPED
        self._port_open: bool | None = None
        self._start_in_progress = False
        self._started_at: float | None = None
        self._ready_at: float | None = None
        self._java_runtime: JavaRuntime | None = None
        self._last_exit_code: int | None = None
        self._last_error: str | None = None

    @property
    def state(self) -> ServerState:
        """Retourne l'état après un test de port synchrone.

        Cette propriété reste utile dans les tests ou les scripts. L'interface
        graphique utilise ``snapshot`` puis lance ``probe`` en arrière-plan.
        """

        return self.refresh()

    @property
    def owns_process(self) -> bool:
        """Indique rapidement si le processus enfant Paper est encore vivant."""

        with self._lock:
            self._refresh_process_only_locked()
            return self._process is not None

    def snapshot(self) -> ServerSnapshot:
        """Retourne une vue cohérente sans opération réseau ou sous-processus."""

        with self._lock:
            self._refresh_process_only_locked()
            process = self._process
            pid_value = getattr(process, "pid", None) if process is not None else None
            pid = int(pid_value) if isinstance(pid_value, int) else None
            return ServerSnapshot(
                state=self._state,
                owns_process=process is not None,
                pid=pid,
                port_open=self._port_open,
                started_at=self._started_at,
                ready_at=self._ready_at,
                java_runtime=self._java_runtime,
                last_exit_code=self._last_exit_code,
                last_error=self._last_error,
            )

    def refresh(self) -> ServerState:
        """Compatibilité : effectue un test de port synchrone puis actualise l'état."""

        return self.probe()

    def probe(self) -> ServerState:
        """Teste le port puis actualise l'état ; à exécuter hors de Tkinter."""

        try:
            port_open = bool(self._port_checker())
        except OSError:
            port_open = False

        with self._lock:
            self._port_open = port_open
            self._refresh_process_only_locked()

            if self._process is not None:
                if self._state != ServerState.STOPPING:
                    self._state = (
                        ServerState.RUNNING
                        if port_open or self._ready_at is not None
                        else ServerState.STARTING
                    )
                return self._state

            if self._start_in_progress:
                self._state = ServerState.STARTING
            elif port_open:
                self._state = ServerState.EXTERNAL
            elif self._state != ServerState.ERROR:
                self._state = ServerState.STOPPED
            return self._state

    def _refresh_process_only_locked(self) -> None:
        process = self._process
        if process is None:
            return

        try:
            return_code = process.poll()
        except OSError as error:
            return_code = -1
            self._last_error = f"Impossible de vérifier le processus Paper : {error}."

        if return_code is None:
            if self._state != ServerState.STOPPING:
                self._state = (
                    ServerState.RUNNING
                    if self._port_open is True or self._ready_at is not None
                    else ServerState.STARTING
                )
            return

        expected_stop = self._state == ServerState.STOPPING
        self._last_exit_code = int(return_code)
        self._process = None
        self._reader = None
        self._started_at = None
        self._ready_at = None

        if expected_stop or return_code == 0:
            self._last_error = None
            self._state = (
                ServerState.EXTERNAL if self._port_open is True else ServerState.STOPPED
            )
        else:
            self._state = ServerState.ERROR
            if not self._last_error:
                self._last_error = (
                    f"Paper s'est fermé de façon inattendue (code {return_code}). "
                    "Consulte les dernières lignes de la console."
                )

    def _set_start_failure(self, message: str, state: ServerState = ServerState.ERROR) -> None:
        with self._lock:
            self._start_in_progress = False
            self._state = state
            self._last_error = message

    def start(self) -> tuple[str, ...]:
        """Vérifie les prérequis et crée Paper avec ses flux redirigés."""

        if not self._operation_lock.acquire(blocking=False):
            raise ServerControlError("Une autre opération serveur est déjà en cours.")

        try:
            with self._lock:
                self._refresh_process_only_locked()
                if self._process is not None or self._start_in_progress:
                    raise ServerAlreadyRunningError("Paper est déjà en cours de lancement.")
                if self._state == ServerState.STOPPING:
                    raise ServerControlError("Attends la fin de l'arrêt avant de redémarrer.")
                self._start_in_progress = True
                self._state = ServerState.STARTING
                self._last_error = None
                self._last_exit_code = None
                self._ready_at = None

            if self._port_checker():
                message = (
                    f"Le port Minecraft {PAPER_PORT} est déjà utilisé : aucun second "
                    "serveur ne sera lancé."
                )
                with self._lock:
                    self._port_open = True
                self._set_start_failure(message, ServerState.EXTERNAL)
                raise ServerAlreadyRunningError(message)

            with self._lock:
                self._port_open = False

            paper_path = self.server_directory / "paper.jar"
            if not paper_path.is_file():
                message = f"paper.jar est introuvable dans {self.server_directory}."
                self._set_start_failure(message)
                raise LauncherConfigurationError(message)

            runtime = self._java_runtime
            if runtime is None or not runtime.executable.is_file():
                try:
                    runtime = self._java_finder()
                except LauncherConfigurationError as error:
                    self._set_start_failure(str(error))
                    raise

            command = build_server_command(runtime)
            creationflags = int(getattr(subprocess, "CREATE_NO_WINDOW", 0))
            try:
                process = self._popen_factory(
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
                message = f"Impossible de démarrer Paper : {error}."
                self._set_start_failure(message)
                raise ServerControlError(message) from error

            with self._lock:
                self._process = process
                self._java_runtime = runtime
                self._state = ServerState.STARTING
                self._start_in_progress = False
                self._started_at = time.monotonic()
                self._ready_at = None
                self._last_error = None

            reader = threading.Thread(
                target=self._read_process_output,
                args=(process,),
                name="paper-log-reader",
                daemon=True,
            )
            with self._lock:
                self._reader = reader
            reader.start()
            return command
        except (LauncherConfigurationError, ServerControlError):
            with self._lock:
                self._start_in_progress = False
            raise
        except Exception as error:
            message = f"Erreur inattendue pendant le démarrage de Paper : {error}."
            self._set_start_failure(message)
            raise ServerControlError(message) from error
        finally:
            self._operation_lock.release()

    def _read_process_output(self, process: subprocess.Popen[str]) -> None:
        """Transfère chaque ligne Paper vers une file bornée et thread-safe."""

        stream = process.stdout
        if stream is None:
            return

        try:
            for line in iter(stream.readline, ""):
                normalized = line.rstrip("\r\n")
                self._observe_output_line(normalized)
                self._enqueue_output(normalized)
        finally:
            try:
                stream.close()
            except OSError:
                pass
            with self._lock:
                self._refresh_process_only_locked()

    def _observe_output_line(self, line: str) -> None:
        upper_line = line.upper()
        if any(marker in upper_line for marker in self.READY_MARKERS):
            with self._lock:
                if self._process is not None and self._state != ServerState.STOPPING:
                    self._ready_at = time.monotonic()
                    self._state = ServerState.RUNNING

    def _enqueue_output(self, line: str) -> None:
        try:
            self._output.put_nowait(line)
            return
        except queue.Full:
            pass

        # On sacrifie la ligne la plus ancienne plutôt que de laisser les logs
        # consommer toute la mémoire ou bloquer le lecteur stdout de Paper.
        try:
            self._output.get_nowait()
        except queue.Empty:
            pass
        try:
            self._output.put_nowait(line)
        except queue.Full:
            pass
        with self._lock:
            self._dropped_output_lines += 1

    def drain_output(self, max_lines: int = 400) -> list[str]:
        """Retourne un lot borné de logs, sans monopoliser le thread graphique."""

        if max_lines <= 0:
            return []

        lines: list[str] = []
        with self._lock:
            dropped = self._dropped_output_lines
            self._dropped_output_lines = 0
        if dropped:
            lines.append(
                f"[Launcher/WARN] {dropped} ligne(s) ancienne(s) ont été ignorées "
                "pour préserver la fluidité de l'interface."
            )

        while len(lines) < max_lines:
            try:
                lines.append(self._output.get_nowait())
            except queue.Empty:
                break
        return lines

    def has_pending_output(self) -> bool:
        return not self._output.empty()

    def send_command(self, command: str) -> None:
        """Envoie une commande uniquement au serveur enfant du launcher."""

        normalized_command = command.strip()
        if not normalized_command:
            return

        with self._lock:
            self._refresh_process_only_locked()
            process = self._process
            stream = process.stdin if process is not None else None
        if process is None or stream is None:
            raise ServerControlError(
                "Les commandes sont désactivées pour un serveur externe ou arrêté."
            )

        try:
            with self._stdin_lock:
                stream.write(f"{normalized_command}\n")
                stream.flush()
        except (OSError, ValueError) as error:
            with self._lock:
                self._state = ServerState.ERROR
                self._last_error = (
                    "La console Paper est devenue indisponible pendant l'envoi "
                    "de la commande."
                )
            raise ServerControlError(self._last_error) from error

    def request_stop(self) -> None:
        """Demande un arrêt propre afin que Paper sauvegarde les mondes."""

        if not self.owns_process:
            raise ServerControlError(
                "Je ne peux arrêter proprement que le serveur lancé par ce launcher."
            )
        self.send_command("stop")
        with self._lock:
            self._state = ServerState.STOPPING
            self._last_error = None

    def wait_for_exit(
        self,
        timeout_seconds: float = 60.0,
        sleep: Callable[[float], None] = time.sleep,
    ) -> bool:
        """Attend la fin du processus ciblé ; à exécuter hors de Tkinter."""

        with self._lock:
            target_process = self._process
        if target_process is None:
            return True

        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            try:
                return_code = target_process.poll()
            except OSError:
                return_code = -1
            if return_code is not None:
                with self._lock:
                    self._refresh_process_only_locked()
                return True
            sleep(0.2)

        try:
            stopped = target_process.poll() is not None
        except OSError:
            stopped = True
        if stopped:
            with self._lock:
                self._refresh_process_only_locked()
        return stopped

    def force_stop(self) -> None:
        """Envoie terminate uniquement au processus Paper possédé."""

        with self._lock:
            self._refresh_process_only_locked()
            process = self._process
        if process is None:
            raise ServerControlError("Aucun serveur enfant à arrêter de force.")

        try:
            process.terminate()
        except OSError as error:
            message = "Impossible de demander l'arrêt forcé du processus Paper."
            with self._lock:
                self._state = ServerState.ERROR
                self._last_error = message
            raise ServerControlError(message) from error
        with self._lock:
            self._state = ServerState.STOPPING

    def kill(self) -> None:
        """Dernier recours : tue le processus après l'échec de terminate."""

        with self._lock:
            self._refresh_process_only_locked()
            process = self._process
        if process is None:
            return

        killer = getattr(process, "kill", None)
        try:
            if callable(killer):
                killer()
            else:
                process.terminate()
        except OSError as error:
            message = "Impossible de terminer le processus Paper."
            with self._lock:
                self._state = ServerState.ERROR
                self._last_error = message
            raise ServerControlError(message) from error
        with self._lock:
            self._state = ServerState.STOPPING

    def force_stop_and_wait(self, terminate_timeout_seconds: float = 5.0) -> bool:
        """Termine Paper puis utilise kill s'il résiste au délai de grâce."""

        self.force_stop()
        if self.wait_for_exit(terminate_timeout_seconds):
            return True
        self.kill()
        return self.wait_for_exit(terminate_timeout_seconds)
