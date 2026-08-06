"""Interface graphique du lanceur Paper/Minecraft AUGUSTIN."""

from __future__ import annotations

import subprocess
import threading
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, scrolledtext, ttk

from augustin_launcher.controller import (
    ServerAlreadyRunningError,
    ServerControlError,
    ServerController,
    ServerState,
)
from augustin_launcher.core import (
    MINECRAFT_VERSION,
    PAPER_PORT,
    LauncherConfigurationError,
    LogTailer,
    check_minecraft_profile,
    minecraft_launcher_command,
    resolve_server_directory,
)


BACKGROUND = "#10151c"
PANEL = "#171f29"
PANEL_ACCENT = "#243142"
TEXT = "#e7edf5"
MUTED_TEXT = "#9eafc4"
ACCENT = "#4cc2ff"
SUCCESS = "#65d18a"
WARNING = "#ffc857"
ERROR = "#ff6b6b"
POLL_INTERVAL_MS = 250
MAX_CONSOLE_LINES = 5_000
STOP_TIMEOUT_SECONDS = 60.0


class LauncherApplication(tk.Tk):
    """Fenêtre principale qui garde le serveur et sa console synchronisés."""

    def __init__(self, server_directory: Path) -> None:
        super().__init__()
        self.server_directory = server_directory
        self.controller = ServerController(server_directory)
        self.log_tailer = LogTailer(server_directory / "logs" / "latest.log")
        self._restart_after_stop = False
        self._close_after_stop = False
        self._stop_waiting = False
        self._stop_timed_out = False
        self._external_notice_displayed = False

        self.title("AUGUSTIN — Paper Launcher")
        self.geometry("1120x760")
        self.minsize(900, 620)
        self.configure(background=BACKGROUND)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        self._configure_style()
        self._build_interface()
        self._append_info(f"Dossier Paper : {self.server_directory}")
        self._append_info(
            f"Serveur attendu : Paper {MINECRAFT_VERSION} — port {PAPER_PORT}."
        )
        self._load_initial_log_tail()
        self._poll()

    def _configure_style(self) -> None:
        """Applique un thème sombre lisible à tous les contrôles ttk."""

        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure(
            "Dark.TFrame",
            background=BACKGROUND,
        )
        style.configure(
            "Panel.TFrame",
            background=PANEL,
        )
        style.configure(
            "Title.TLabel",
            background=BACKGROUND,
            foreground=TEXT,
            font=("Segoe UI", 17, "bold"),
        )
        style.configure(
            "Meta.TLabel",
            background=BACKGROUND,
            foreground=MUTED_TEXT,
            font=("Segoe UI", 9),
        )
        style.configure(
            "Status.TLabel",
            background=PANEL,
            foreground=ACCENT,
            font=("Segoe UI", 11, "bold"),
        )
        style.configure(
            "Dark.TButton",
            padding=(12, 8),
            background=PANEL_ACCENT,
            foreground=TEXT,
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "Dark.TButton",
            background=[("active", "#334962"), ("disabled", "#202934")],
            foreground=[("disabled", "#718096")],
        )
        style.configure(
            "Accent.TButton",
            padding=(12, 8),
            background="#1769aa",
            foreground="#ffffff",
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "Accent.TButton",
            background=[("active", "#2287d1"), ("disabled", "#29435a")],
            foreground=[("disabled", "#9aaabc")],
        )
        style.configure(
            "Dark.TEntry",
            fieldbackground="#0c1117",
            foreground=TEXT,
            insertcolor=TEXT,
            bordercolor=PANEL_ACCENT,
        )

    def _build_interface(self) -> None:
        """Construit la barre d'actions, la console et l'entrée de commandes."""

        root = ttk.Frame(self, style="Dark.TFrame", padding=18)
        root.grid(row=0, column=0, sticky="nsew")
        self.rowconfigure(0, weight=1)
        self.columnconfigure(0, weight=1)
        root.columnconfigure(0, weight=1)
        root.rowconfigure(2, weight=1)

        heading = ttk.Frame(root, style="Dark.TFrame")
        heading.grid(row=0, column=0, sticky="ew", pady=(0, 14))
        heading.columnconfigure(0, weight=1)
        ttk.Label(heading, text="AUGUSTIN — Paper Launcher", style="Title.TLabel").grid(
            row=0, column=0, sticky="w"
        )
        ttk.Label(
            heading,
            text="Console locale • Paper 1.21.4 • Minecraft Java 1.21.4",
            style="Meta.TLabel",
        ).grid(row=1, column=0, sticky="w", pady=(3, 0))

        control_panel = ttk.Frame(root, style="Panel.TFrame", padding=14)
        control_panel.grid(row=1, column=0, sticky="ew", pady=(0, 14))
        control_panel.columnconfigure(0, weight=1)

        self.status_label = ttk.Label(
            control_panel,
            text="Initialisation…",
            style="Status.TLabel",
        )
        self.status_label.grid(row=0, column=0, sticky="w")
        self.status_detail = ttk.Label(
            control_panel,
            text="",
            style="Meta.TLabel",
        )
        self.status_detail.grid(row=1, column=0, sticky="w", pady=(4, 12))

        button_row = ttk.Frame(control_panel, style="Panel.TFrame")
        button_row.grid(row=2, column=0, sticky="w")
        self.start_button = ttk.Button(
            button_row,
            text="Démarrer Serveur",
            style="Accent.TButton",
            command=self._start_server,
        )
        self.start_button.grid(row=0, column=0, padx=(0, 8))
        self.stop_button = ttk.Button(
            button_row,
            text="Arrêter",
            style="Dark.TButton",
            command=self._stop_server,
        )
        self.stop_button.grid(row=0, column=1, padx=(0, 8))
        self.restart_button = ttk.Button(
            button_row,
            text="Redémarrer",
            style="Dark.TButton",
            command=self._restart_server,
        )
        self.restart_button.grid(row=0, column=2, padx=(0, 8))
        self.force_stop_button = ttk.Button(
            button_row,
            text="Forcer l'arrêt",
            style="Dark.TButton",
            command=self._force_stop_server,
        )
        self.force_stop_button.grid(row=0, column=3, padx=(0, 8))
        self.minecraft_button = ttk.Button(
            button_row,
            text="Lancer Minecraft",
            style="Dark.TButton",
            command=self._launch_minecraft,
        )
        self.minecraft_button.grid(row=0, column=4)

        console_panel = ttk.Frame(root, style="Panel.TFrame", padding=1)
        console_panel.grid(row=2, column=0, sticky="nsew")
        console_panel.rowconfigure(0, weight=1)
        console_panel.columnconfigure(0, weight=1)
        self.console = scrolledtext.ScrolledText(
            console_panel,
            background="#0a0f14",
            foreground=TEXT,
            insertbackground=TEXT,
            selectbackground="#1c6aa5",
            borderwidth=0,
            relief="flat",
            font=("Cascadia Mono", 9),
            wrap="word",
            state="disabled",
        )
        self.console.grid(row=0, column=0, sticky="nsew")
        self.console.tag_configure("info", foreground="#b8c7d9")
        self.console.tag_configure("warning", foreground=WARNING)
        self.console.tag_configure("error", foreground=ERROR)
        self.console.tag_configure("success", foreground=SUCCESS)

        command_panel = ttk.Frame(root, style="Dark.TFrame")
        command_panel.grid(row=3, column=0, sticky="ew", pady=(14, 0))
        command_panel.columnconfigure(0, weight=1)
        self.command_entry = ttk.Entry(command_panel, style="Dark.TEntry")
        self.command_entry.grid(row=0, column=0, sticky="ew", padx=(0, 8))
        self.command_entry.bind("<Return>", self._send_command_from_event)
        self.send_button = ttk.Button(
            command_panel,
            text="Envoyer la commande",
            style="Dark.TButton",
            command=self._send_command,
        )
        self.send_button.grid(row=0, column=1)

    def _load_initial_log_tail(self) -> None:
        """Ajoute un historique court afin que la console soit utile immédiatement."""

        lines = self.log_tailer.read_initial_lines()
        if lines:
            self._append_info("--- Fin du journal Paper existant ---")
            self._append_lines(lines)

    def _append_info(self, message: str) -> None:
        """Ajoute une information du launcher à la console."""

        self._append_lines([f"[Launcher] {message}"], "info")

    def _append_error(self, message: str) -> None:
        """Ajoute une erreur visible sans interrompre l'interface graphique."""

        self._append_lines([f"[Launcher] {message}"], "error")

    def _append_lines(self, lines: list[str], tag: str | None = None) -> None:
        """Écrit les logs dans Tkinter tout en bornant la mémoire utilisée."""

        if not lines:
            return
        self.console.configure(state="normal")
        for line in lines:
            line_tag = tag or self._tag_for_line(line)
            self.console.insert("end", f"{line}\n", line_tag)
        self._trim_console()
        self.console.see("end")
        self.console.configure(state="disabled")

    def _trim_console(self) -> None:
        """Conserve uniquement les lignes récentes pour une fenêtre fluide."""

        line_count = int(self.console.index("end-1c").split(".")[0])
        surplus = line_count - MAX_CONSOLE_LINES
        if surplus > 0:
            self.console.delete("1.0", f"{surplus + 1}.0")

    @staticmethod
    def _tag_for_line(line: str) -> str:
        """Colore les messages Paper selon leur niveau habituel."""

        upper_line = line.upper()
        if "ERROR" in upper_line or "EXCEPTION" in upper_line:
            return "error"
        if "WARN" in upper_line or "AVERTISSEMENT" in upper_line:
            return "warning"
        if "DONE (" in upper_line or "EN LIGNE" in upper_line:
            return "success"
        return "info"

    def _poll(self) -> None:
        """Synchronise régulièrement les logs, le processus et les boutons."""

        state = self.controller.refresh()
        self._append_lines(self.controller.drain_output())

        # Ici, je lis latest.log seulement pour ne pas dupliquer les logs de mon enfant.
        if not self.controller.owns_process:
            self._append_lines(self.log_tailer.read_new_lines())

        if state == ServerState.EXTERNAL and not self._external_notice_displayed:
            self._append_info(
                "Serveur externe détecté : lecture des logs uniquement, commandes désactivées."
            )
            self._external_notice_displayed = True
        elif state != ServerState.EXTERNAL:
            self._external_notice_displayed = False

        self._update_status(state)
        self._update_controls(state)
        self.after(POLL_INTERVAL_MS, self._poll)

    def _update_status(self, state: ServerState) -> None:
        """Expose l'état important sans obliger à lire toute la console."""

        colors = {
            ServerState.STOPPED: MUTED_TEXT,
            ServerState.STARTING: ACCENT,
            ServerState.RUNNING: SUCCESS,
            ServerState.STOPPING: WARNING,
            ServerState.EXTERNAL: WARNING,
            ServerState.ERROR: ERROR,
        }
        details = {
            ServerState.STOPPED: "Le port 25565 est libre.",
            ServerState.STARTING: "Paper démarre avec Java 21 et 2 Go de mémoire.",
            ServerState.RUNNING: "Le launcher contrôle ce processus Paper.",
            ServerState.STOPPING: "Sauvegarde et arrêt propre en cours.",
            ServerState.EXTERNAL: "Le port 25565 est déjà occupé par un autre processus.",
            ServerState.ERROR: "Consulte la console pour le détail.",
        }
        self.status_label.configure(text=state.value, foreground=colors[state])
        self.status_detail.configure(text=details[state])

    def _update_controls(self, state: ServerState) -> None:
        """Empêche toute commande risquée vers un processus non possédé."""

        owns_process = self.controller.owns_process
        can_control = owns_process and state in {
            ServerState.STARTING,
            ServerState.RUNNING,
        }
        start_state = "normal" if state == ServerState.STOPPED and not self._stop_waiting else "disabled"
        control_state = "normal" if can_control and not self._stop_waiting else "disabled"
        force_state = "normal" if self._stop_timed_out and owns_process else "disabled"

        self.start_button.configure(state=start_state)
        self.stop_button.configure(state=control_state)
        self.restart_button.configure(state=control_state)
        self.force_stop_button.configure(state=force_state)
        self.command_entry.configure(state=control_state)
        self.send_button.configure(state=control_state)

    def _start_server(self) -> None:
        """Démarre Paper et affiche la commande réellement utilisée."""

        try:
            command = self.controller.start()
        except (LauncherConfigurationError, ServerAlreadyRunningError, ServerControlError) as error:
            self._append_error(str(error))
            messagebox.showerror("Démarrage impossible", str(error), parent=self)
            return

        self._append_info(f"Démarrage : {' '.join(command)}")
        self._stop_timed_out = False

    def _stop_server(self) -> None:
        """Demande la confirmation avant de sauvegarder puis arrêter Paper."""

        if not messagebox.askyesno(
            "Arrêter le serveur",
            "Arrêter Paper proprement ? Les mondes seront sauvegardés avant la fermeture.",
            parent=self,
        ):
            return
        self._begin_clean_stop()

    def _restart_server(self) -> None:
        """Planifie un redémarrage seulement après l'arrêt complet de Paper."""

        if not messagebox.askyesno(
            "Redémarrer le serveur",
            "Redémarrer Paper proprement après la sauvegarde des mondes ?",
            parent=self,
        ):
            return
        self._restart_after_stop = True
        self._begin_clean_stop()

    def _begin_clean_stop(self) -> None:
        """Envoie stop puis attend Paper dans un thread séparé de Tkinter."""

        try:
            self.controller.request_stop()
        except ServerControlError as error:
            self._append_error(str(error))
            messagebox.showerror("Arrêt impossible", str(error), parent=self)
            return

        self._stop_waiting = True
        self._stop_timed_out = False
        self._append_info("Commande stop envoyée ; attente de la sauvegarde Paper.")
        threading.Thread(
            target=self._wait_for_clean_stop,
            name="paper-stop-waiter",
            daemon=True,
        ).start()

    def _wait_for_clean_stop(self) -> None:
        """Attend l'arrêt sans bloquer l'interface, puis revient sur le thread Tk."""

        stopped = self.controller.wait_for_exit(STOP_TIMEOUT_SECONDS)
        self.after(0, lambda: self._finish_clean_stop(stopped))

    def _finish_clean_stop(self, stopped: bool) -> None:
        """Termine le cycle arrêt/redémarrage ou propose la récupération explicite."""

        self._stop_waiting = False
        if not stopped:
            self._stop_timed_out = True
            self._append_error(
                "Paper ne s'est pas arrêté après 60 secondes. L'arrêt forcé reste optionnel."
            )
            return

        self._append_info("Paper est arrêté proprement.")
        self._stop_timed_out = False
        if self._close_after_stop:
            self.destroy()
            return
        if self._restart_after_stop:
            self._restart_after_stop = False
            self._start_server()

    def _force_stop_server(self) -> None:
        """N'autorise la terminaison forcée qu'après double confirmation explicite."""

        if not messagebox.askyesno(
            "Forcer l'arrêt",
            "Paper n'a pas répondu à stop. Forcer la fin peut perdre des données non sauvegardées. Continuer ?",
            icon="warning",
            parent=self,
        ):
            return
        try:
            self.controller.force_stop()
        except ServerControlError as error:
            self._append_error(str(error))
            return

        self._append_info("Arrêt forcé demandé après confirmation explicite.")
        self._stop_timed_out = False
        self._stop_waiting = True
        threading.Thread(
            target=self._wait_for_clean_stop,
            name="paper-force-stop-waiter",
            daemon=True,
        ).start()

    def _send_command_from_event(self, _event: tk.Event[tk.Misc]) -> str:
        """Permet d'envoyer une commande avec la touche Entrée."""

        self._send_command()
        return "break"

    def _send_command(self) -> None:
        """Transmet la commande saisie au stdin du processus Paper possédé."""

        command = self.command_entry.get()
        try:
            self.controller.send_command(command)
        except ServerControlError as error:
            self._append_error(str(error))
            return
        if command.strip():
            self._append_info(f"> {command.strip()}")
            self.command_entry.delete(0, "end")

    def _launch_minecraft(self) -> None:
        """Ouvre le Launcher officiel seulement après validation de MineGus 1.21.4."""

        status = check_minecraft_profile()
        if not status.is_compatible:
            problems: list[str] = []
            if not status.profile_exists:
                problems.append("le profil MineGus est introuvable")
            elif status.profile_version != MINECRAFT_VERSION:
                problems.append(
                    f"le profil MineGus utilise {status.profile_version or 'une version inconnue'}"
                )
            if not status.client_installed:
                problems.append(f"le client {MINECRAFT_VERSION} n'est pas installé")
            message = "; ".join(problems) + "."
            self._append_error(f"Minecraft non lancé : {message}")
            messagebox.showwarning(
                "Minecraft 1.21.4 requis",
                f"Impossible de confirmer la compatibilité : {message}",
                parent=self,
            )
            return

        try:
            subprocess.Popen(
                minecraft_launcher_command(),
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except OSError as error:
            message = f"Impossible d'ouvrir le Launcher Minecraft : {error}."
            self._append_error(message)
            messagebox.showerror("Minecraft", message, parent=self)
            return

        message = (
            "Le Launcher officiel est ouvert. Vérifie le profil MineGus (1.21.4), "
            "puis clique sur Jouer."
        )
        self._append_info(message)
        messagebox.showinfo("Minecraft prêt", message, parent=self)

    def _on_close(self) -> None:
        """Protège les données du monde si la fenêtre possède Paper."""

        if self._stop_waiting:
            return
        if not self.controller.owns_process:
            self.destroy()
            return

        should_stop = messagebox.askyesno(
            "Fermer le launcher",
            "Le serveur est géré par cette fenêtre. L'arrêter proprement avant de fermer ?",
            parent=self,
        )
        if not should_stop:
            return
        self._close_after_stop = True
        self._begin_clean_stop()


def main() -> None:
    """Démarre l'interface ou affiche un message clair si Paper est introuvable."""

    try:
        server_directory = resolve_server_directory()
    except LauncherConfigurationError as error:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Configuration du launcher", str(error), parent=root)
        root.destroy()
        return

    application = LauncherApplication(server_directory)
    application.mainloop()


if __name__ == "__main__":
    main()
