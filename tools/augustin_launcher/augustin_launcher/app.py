"""Interface graphique réactive du lanceur Paper/Minecraft AUGUSTIN."""

from __future__ import annotations

import ctypes
import subprocess
import time
import tkinter as tk
import tkinter.font as tkfont
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from tkinter import messagebox, ttk

from augustin_launcher.controller import (
    ServerAlreadyRunningError,
    ServerControlError,
    ServerController,
    ServerSnapshot,
    ServerState,
)
from augustin_launcher.core import (
    DEFAULT_MEMORY_GIB,
    MINECRAFT_VERSION,
    PAPER_PORT,
    LauncherConfigurationError,
    LogTailer,
    check_minecraft_profile,
    minecraft_launcher_command,
    resolve_server_directory,
)


# Palette sombre contrastée, pensée pour rester lisible sur un écran Windows.
BACKGROUND = "#0b1118"
SURFACE = "#111a24"
SURFACE_RAISED = "#162231"
SURFACE_HOVER = "#1d2d40"
BORDER = "#26364a"
BORDER_STRONG = "#36516f"
CONSOLE_BACKGROUND = "#070c12"
TEXT = "#edf4fc"
MUTED_TEXT = "#91a4ba"
DIM_TEXT = "#65788f"
ACCENT = "#3ca6ff"
ACCENT_HOVER = "#63b9ff"
SUCCESS = "#48d597"
WARNING = "#f7c45c"
ERROR = "#ff727c"
DANGER = "#c94e5b"
DANGER_HOVER = "#e05f6d"

UI_POLL_INTERVAL_MS = 90
STARTING_PROBE_INTERVAL_SECONDS = 0.25
RUNNING_PROBE_INTERVAL_SECONDS = 0.75
IDLE_PROBE_INTERVAL_SECONDS = 1.25
EXTERNAL_LOG_INTERVAL_SECONDS = 0.30
OWNED_LOG_SYNC_INTERVAL_SECONDS = 1.0
MAX_CONSOLE_LINES = 8_000
CONSOLE_TRIM_CHUNK = 1_000
MAX_LOG_LINES_PER_TICK = 450
MAX_CONSOLE_LINE_LENGTH = 20_000
STOP_TIMEOUT_SECONDS = 60.0


@dataclass(frozen=True)
class MinecraftLaunchResult:
    """Résultat transférable du thread de travail vers Tkinter."""

    launched: bool
    message: str
    warning: bool = False


class Tooltip:
    """Info-bulle légère, sans dépendance externe."""

    def __init__(self, widget: tk.Misc, text: str) -> None:
        self.widget = widget
        self.text = text
        self._window: tk.Toplevel | None = None
        self._after_id: str | None = None
        widget.bind("<Enter>", self._schedule, add="+")
        widget.bind("<Leave>", self._hide, add="+")
        widget.bind("<ButtonPress>", self._hide, add="+")

    def _schedule(self, _event: tk.Event[tk.Misc] | None = None) -> None:
        self._cancel_schedule()
        self._after_id = self.widget.after(500, self._show)

    def _cancel_schedule(self) -> None:
        if self._after_id is not None:
            try:
                self.widget.after_cancel(self._after_id)
            except tk.TclError:
                pass
            self._after_id = None

    def _show(self) -> None:
        self._after_id = None
        if self._window is not None or not self.widget.winfo_exists():
            return
        x = self.widget.winfo_rootx() + 12
        y = self.widget.winfo_rooty() + self.widget.winfo_height() + 8
        window = tk.Toplevel(self.widget)
        window.wm_overrideredirect(True)
        window.wm_geometry(f"+{x}+{y}")
        label = tk.Label(
            window,
            text=self.text,
            justify="left",
            background="#1b2837",
            foreground=TEXT,
            borderwidth=1,
            relief="solid",
            padx=9,
            pady=6,
            font=("Segoe UI", 9),
        )
        label.pack()
        self._window = window

    def _hide(self, _event: tk.Event[tk.Misc] | None = None) -> None:
        self._cancel_schedule()
        if self._window is not None:
            try:
                self._window.destroy()
            except tk.TclError:
                pass
            self._window = None


class LauncherApplication(tk.Tk):
    """Fenêtre principale, sans opération lente dans le thread Tkinter."""

    def __init__(self, server_directory: Path) -> None:
        super().__init__()
        self.server_directory = server_directory
        self.controller = ServerController(server_directory)
        self.log_tailer = LogTailer(server_directory / "logs" / "latest.log")
        self._executor = ThreadPoolExecutor(
            max_workers=4,
            thread_name_prefix="augustin-worker",
        )

        self._start_future: Future[tuple[str, ...]] | None = None
        self._stop_future: Future[bool] | None = None
        self._probe_future: Future[ServerState] | None = None
        self._log_future: Future[list[str] | None] | None = None
        self._log_future_mode: str | None = None
        self._minecraft_future: Future[MinecraftLaunchResult] | None = None

        self._next_probe_at = 0.0
        self._next_log_at = 0.0
        self._restart_after_stop = False
        self._close_after_stop = False
        self._close_after_start = False
        self._stop_timed_out = False
        self._stop_mode: str | None = None
        self._destroying = False
        self._progress_running = False
        self._external_notice_displayed = False
        self._last_snapshot = self.controller.snapshot()
        self._last_state = self._last_snapshot.state
        self._last_owned_process = self._last_snapshot.owns_process
        self._console_line_count = 0
        self._command_history: list[str] = []
        self._history_index = 0
        self._last_probe_error_at = 0.0

        self.title("AUGUSTIN — Paper Launcher")
        self.geometry("1240x820")
        self.minsize(980, 680)
        self.configure(background=BACKGROUND)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        self._configure_style()
        self._build_interface()
        self._center_window()
        self._apply_windows_dark_title_bar()
        self._append_info(f"Dossier Paper : {self.server_directory}")
        self._append_info(
            f"Configuration : Paper {MINECRAFT_VERSION}, port {PAPER_PORT}, "
            f"mémoire {DEFAULT_MEMORY_GIB} Go."
        )
        self._start_initial_log_read()
        self.after(30, self._poll)

    # ------------------------------------------------------------------
    # Construction visuelle
    # ------------------------------------------------------------------

    def _configure_style(self) -> None:
        style = ttk.Style(self)
        style.theme_use("clam")

        style.configure("Root.TFrame", background=BACKGROUND)
        style.configure("Surface.TFrame", background=SURFACE)
        style.configure("Raised.TFrame", background=SURFACE_RAISED)
        style.configure("Console.TFrame", background=CONSOLE_BACKGROUND)

        style.configure(
            "Primary.TButton",
            background=ACCENT,
            foreground="#06111b",
            bordercolor=ACCENT,
            lightcolor=ACCENT,
            darkcolor=ACCENT,
            padding=(16, 10),
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "Primary.TButton",
            background=[("active", ACCENT_HOVER), ("disabled", "#24435c")],
            foreground=[("disabled", "#7890a5")],
            bordercolor=[("active", ACCENT_HOVER), ("disabled", "#24435c")],
        )

        style.configure(
            "Secondary.TButton",
            background=SURFACE_RAISED,
            foreground=TEXT,
            bordercolor=BORDER_STRONG,
            lightcolor=SURFACE_RAISED,
            darkcolor=SURFACE_RAISED,
            padding=(14, 9),
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "Secondary.TButton",
            background=[("active", SURFACE_HOVER), ("disabled", "#131c26")],
            foreground=[("disabled", "#5f7185")],
            bordercolor=[("active", ACCENT), ("disabled", "#202d3b")],
        )

        style.configure(
            "Danger.TButton",
            background="#3a2028",
            foreground="#ffb8bd",
            bordercolor="#713b46",
            lightcolor="#3a2028",
            darkcolor="#3a2028",
            padding=(14, 9),
            font=("Segoe UI", 10, "bold"),
        )
        style.map(
            "Danger.TButton",
            background=[("active", DANGER_HOVER), ("disabled", "#1c171c")],
            foreground=[("active", "#ffffff"), ("disabled", "#604f57")],
            bordercolor=[("active", DANGER_HOVER), ("disabled", "#2a2228")],
        )

        style.configure(
            "Ghost.TButton",
            background=SURFACE,
            foreground=MUTED_TEXT,
            bordercolor=BORDER,
            lightcolor=SURFACE,
            darkcolor=SURFACE,
            padding=(10, 6),
            font=("Segoe UI", 9),
        )
        style.map(
            "Ghost.TButton",
            background=[("active", SURFACE_HOVER), ("disabled", SURFACE)],
            foreground=[("active", TEXT), ("disabled", DIM_TEXT)],
            bordercolor=[("active", BORDER_STRONG)],
        )

        style.configure(
            "Command.TEntry",
            fieldbackground="#0a1119",
            foreground=TEXT,
            insertcolor=TEXT,
            bordercolor=BORDER_STRONG,
            lightcolor=BORDER_STRONG,
            darkcolor=BORDER_STRONG,
            padding=(10, 9),
            font=("Consolas", 10),
        )
        style.map(
            "Command.TEntry",
            bordercolor=[("focus", ACCENT), ("disabled", BORDER)],
            fieldbackground=[("disabled", "#0c1219")],
            foreground=[("disabled", DIM_TEXT)],
        )

        style.configure(
            "Auto.TCheckbutton",
            background=SURFACE,
            foreground=MUTED_TEXT,
            indicatorbackground=SURFACE_RAISED,
            indicatorforeground=ACCENT,
            font=("Segoe UI", 9),
        )
        style.map(
            "Auto.TCheckbutton",
            background=[("active", SURFACE)],
            foreground=[("active", TEXT)],
            indicatorbackground=[("selected", ACCENT), ("active", SURFACE_HOVER)],
        )

        style.configure(
            "Loading.Horizontal.TProgressbar",
            troughcolor="#0d151e",
            background=ACCENT,
            bordercolor="#0d151e",
            lightcolor=ACCENT,
            darkcolor=ACCENT,
            thickness=5,
        )

        for orientation in ("Vertical", "Horizontal"):
            scrollbar_style = f"Dark.{orientation}.TScrollbar"
            style.configure(
                scrollbar_style,
                background=SURFACE_RAISED,
                troughcolor=CONSOLE_BACKGROUND,
                bordercolor=BORDER,
                arrowcolor=MUTED_TEXT,
                lightcolor=SURFACE_RAISED,
                darkcolor=SURFACE_RAISED,
                gripcount=0,
            )
            style.map(
                scrollbar_style,
                background=[("active", SURFACE_HOVER), ("pressed", BORDER_STRONG)],
                arrowcolor=[("active", TEXT)],
            )

    def _build_interface(self) -> None:
        root = ttk.Frame(self, style="Root.TFrame", padding=(22, 18, 22, 18))
        root.grid(row=0, column=0, sticky="nsew")
        self.rowconfigure(0, weight=1)
        self.columnconfigure(0, weight=1)
        root.columnconfigure(0, weight=1)
        root.rowconfigure(3, weight=1)

        self._build_header(root)
        self._build_status_card(root)
        self._build_action_bar(root)
        self._build_console(root)
        self._build_command_bar(root)

    def _build_header(self, parent: ttk.Frame) -> None:
        header = ttk.Frame(parent, style="Root.TFrame")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 14))
        header.columnconfigure(0, weight=1)

        brand = ttk.Frame(header, style="Root.TFrame")
        brand.grid(row=0, column=0, sticky="w")
        tk.Label(
            brand,
            text="AUGUSTIN",
            background=BACKGROUND,
            foreground=TEXT,
            font=("Segoe UI", 23, "bold"),
        ).grid(row=0, column=0, sticky="w")
        tk.Label(
            brand,
            text="PAPER SERVER CONTROL",
            background=BACKGROUND,
            foreground=ACCENT,
            font=("Segoe UI", 9, "bold"),
        ).grid(row=1, column=0, sticky="w", pady=(1, 0))
        tk.Label(
            brand,
            text=f"Console locale sécurisée • Minecraft Java {MINECRAFT_VERSION}",
            background=BACKGROUND,
            foreground=MUTED_TEXT,
            font=("Segoe UI", 9),
        ).grid(row=2, column=0, sticky="w", pady=(4, 0))

        self.header_status = tk.Frame(
            header,
            background=SURFACE,
            highlightbackground=BORDER,
            highlightthickness=1,
            padx=12,
            pady=8,
        )
        self.header_status.grid(row=0, column=1, sticky="e")
        self.header_dot = tk.Label(
            self.header_status,
            text="●",
            background=SURFACE,
            foreground=DIM_TEXT,
            font=("Segoe UI Symbol", 11),
        )
        self.header_dot.grid(row=0, column=0, padx=(0, 7))
        self.header_status_text = tk.Label(
            self.header_status,
            text="Initialisation",
            background=SURFACE,
            foreground=TEXT,
            font=("Segoe UI", 9, "bold"),
        )
        self.header_status_text.grid(row=0, column=1)

    def _build_status_card(self, parent: ttk.Frame) -> None:
        card = tk.Frame(
            parent,
            background=SURFACE,
            highlightbackground=BORDER,
            highlightthickness=1,
            padx=18,
            pady=15,
        )
        card.grid(row=1, column=0, sticky="ew", pady=(0, 12))
        card.columnconfigure(0, weight=1)

        state_area = tk.Frame(card, background=SURFACE)
        state_area.grid(row=0, column=0, sticky="w")
        self.status_dot = tk.Label(
            state_area,
            text="●",
            background=SURFACE,
            foreground=DIM_TEXT,
            font=("Segoe UI Symbol", 17),
        )
        self.status_dot.grid(row=0, column=0, rowspan=2, padx=(0, 11), sticky="n")
        self.status_label = tk.Label(
            state_area,
            text="Initialisation…",
            background=SURFACE,
            foreground=TEXT,
            font=("Segoe UI", 15, "bold"),
        )
        self.status_label.grid(row=0, column=1, sticky="w")
        self.status_detail = tk.Label(
            state_area,
            text="Vérification du serveur local.",
            background=SURFACE,
            foreground=MUTED_TEXT,
            font=("Segoe UI", 9),
            anchor="w",
        )
        self.status_detail.grid(row=1, column=1, sticky="w", pady=(3, 0))

        metrics = tk.Frame(card, background=SURFACE)
        metrics.grid(row=0, column=1, rowspan=2, sticky="e")
        self.metric_port = self._create_metric(metrics, 0, "PORT", f"{PAPER_PORT}")
        self.metric_java = self._create_metric(metrics, 1, "JAVA", "À détecter")
        self.metric_process = self._create_metric(metrics, 2, "PROCESSUS", "—")
        self.metric_uptime = self._create_metric(metrics, 3, "ACTIVITÉ", "Hors ligne")

        self.progress_frame = tk.Frame(card, background=SURFACE)
        self.progress_frame.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(13, 0))
        self.progress_frame.columnconfigure(0, weight=1)
        self.progress_bar = ttk.Progressbar(
            self.progress_frame,
            style="Loading.Horizontal.TProgressbar",
            mode="indeterminate",
        )
        self.progress_bar.grid(row=0, column=0, sticky="ew")
        self.progress_text = tk.Label(
            self.progress_frame,
            text="",
            background=SURFACE,
            foreground=MUTED_TEXT,
            font=("Segoe UI", 8),
        )
        self.progress_text.grid(row=1, column=0, sticky="w", pady=(5, 0))
        self.progress_frame.grid_remove()

    @staticmethod
    def _create_metric(parent: tk.Frame, column: int, title: str, value: str) -> tk.Label:
        grid_column = column * 2
        if column > 0:
            separator = tk.Frame(parent, background=BORDER, width=1, height=35)
            separator.grid(row=0, column=grid_column - 1, sticky="ns", padx=(2, 2))
        frame = tk.Frame(parent, background=SURFACE, padx=12)
        frame.grid(row=0, column=grid_column, sticky="n")
        tk.Label(
            frame,
            text=title,
            background=SURFACE,
            foreground=DIM_TEXT,
            font=("Segoe UI", 7, "bold"),
        ).pack(anchor="w")
        value_label = tk.Label(
            frame,
            text=value,
            background=SURFACE,
            foreground=TEXT,
            font=("Segoe UI", 9, "bold"),
        )
        value_label.pack(anchor="w", pady=(3, 0))
        return value_label

    def _build_action_bar(self, parent: ttk.Frame) -> None:
        bar = ttk.Frame(parent, style="Root.TFrame")
        bar.grid(row=2, column=0, sticky="ew", pady=(0, 12))
        bar.columnconfigure(5, weight=1)

        self.start_button = ttk.Button(
            bar,
            text="▶  Démarrer",
            style="Primary.TButton",
            command=self._start_server,
        )
        self.start_button.grid(row=0, column=0, padx=(0, 8))
        Tooltip(self.start_button, "Vérifie Java et lance Paper sans bloquer la fenêtre.")

        self.stop_button = ttk.Button(
            bar,
            text="■  Arrêter",
            style="Secondary.TButton",
            command=self._stop_server,
        )
        self.stop_button.grid(row=0, column=1, padx=(0, 8))
        Tooltip(self.stop_button, "Envoie la commande stop et attend la sauvegarde des mondes.")

        self.restart_button = ttk.Button(
            bar,
            text="↻  Redémarrer",
            style="Secondary.TButton",
            command=self._restart_server,
        )
        self.restart_button.grid(row=0, column=2, padx=(0, 8))
        Tooltip(self.restart_button, "Arrêt propre, puis nouveau démarrage automatique.")

        self.force_stop_button = ttk.Button(
            bar,
            text="⚠  Forcer l'arrêt",
            style="Danger.TButton",
            command=self._force_stop_server,
        )
        self.force_stop_button.grid(row=0, column=3)
        Tooltip(
            self.force_stop_button,
            "Disponible uniquement si Paper ne répond pas à l'arrêt propre.",
        )

        self.minecraft_button = ttk.Button(
            bar,
            text="Lancer Minecraft  →",
            style="Secondary.TButton",
            command=self._launch_minecraft,
        )
        self.minecraft_button.grid(row=0, column=6, sticky="e")
        Tooltip(
            self.minecraft_button,
            "Vérifie le profil MineGus 1.21.4 puis ouvre le Launcher officiel.",
        )

    def _build_console(self, parent: ttk.Frame) -> None:
        panel = tk.Frame(
            parent,
            background=SURFACE,
            highlightbackground=BORDER,
            highlightthickness=1,
        )
        panel.grid(row=3, column=0, sticky="nsew")
        panel.rowconfigure(1, weight=1)
        panel.columnconfigure(0, weight=1)

        toolbar = tk.Frame(panel, background=SURFACE, padx=13, pady=9)
        toolbar.grid(row=0, column=0, columnspan=2, sticky="ew")
        toolbar.columnconfigure(1, weight=1)
        tk.Label(
            toolbar,
            text="Console serveur",
            background=SURFACE,
            foreground=TEXT,
            font=("Segoe UI", 10, "bold"),
        ).grid(row=0, column=0, sticky="w")

        self.live_label = tk.Label(
            toolbar,
            text="●  EN ATTENTE",
            background=SURFACE,
            foreground=DIM_TEXT,
            font=("Segoe UI", 8, "bold"),
        )
        self.live_label.grid(row=0, column=1, sticky="w", padx=(12, 0))

        self.auto_scroll_var = tk.BooleanVar(value=True)
        self.auto_scroll_check = ttk.Checkbutton(
            toolbar,
            text="Défilement automatique",
            variable=self.auto_scroll_var,
            style="Auto.TCheckbutton",
        )
        self.auto_scroll_check.grid(row=0, column=2, padx=(8, 8))

        clear_button = ttk.Button(
            toolbar,
            text="Effacer",
            style="Ghost.TButton",
            command=self._clear_console,
        )
        clear_button.grid(row=0, column=3)
        Tooltip(clear_button, "Efface uniquement l'affichage, jamais latest.log.")

        console_container = tk.Frame(panel, background=CONSOLE_BACKGROUND)
        console_container.grid(row=1, column=0, columnspan=2, sticky="nsew")
        console_container.rowconfigure(0, weight=1)
        console_container.columnconfigure(0, weight=1)

        console_font = self._choose_console_font()
        self.console = tk.Text(
            console_container,
            background=CONSOLE_BACKGROUND,
            foreground="#d8e4ef",
            insertbackground=TEXT,
            selectbackground="#245f8d",
            selectforeground="#ffffff",
            borderwidth=0,
            relief="flat",
            font=(console_font, 9),
            wrap="none",
            state="disabled",
            padx=12,
            pady=10,
            spacing1=1,
            undo=False,
        )
        vertical_scroll = ttk.Scrollbar(
            console_container,
            orient="vertical",
            command=self.console.yview,
            style="Dark.Vertical.TScrollbar",
        )
        horizontal_scroll = ttk.Scrollbar(
            console_container,
            orient="horizontal",
            command=self.console.xview,
            style="Dark.Horizontal.TScrollbar",
        )
        self.console.configure(
            yscrollcommand=vertical_scroll.set,
            xscrollcommand=horizontal_scroll.set,
        )
        self.console.grid(row=0, column=0, sticky="nsew")
        vertical_scroll.grid(row=0, column=1, sticky="ns")
        horizontal_scroll.grid(row=1, column=0, sticky="ew")

        self.console.tag_configure("launcher", foreground="#9fc6e8")
        self.console.tag_configure("info", foreground="#d1deea")
        self.console.tag_configure("muted", foreground="#7e92a8")
        self.console.tag_configure("warning", foreground=WARNING)
        self.console.tag_configure("error", foreground=ERROR)
        self.console.tag_configure("success", foreground=SUCCESS)
        self.console.bind("<Button-3>", self._show_console_menu)

        self.console_menu = tk.Menu(
            self,
            tearoff=False,
            background=SURFACE_RAISED,
            foreground=TEXT,
            activebackground=SURFACE_HOVER,
            activeforeground=TEXT,
            borderwidth=0,
        )
        self.console_menu.add_command(label="Copier", command=self._copy_console_selection)
        self.console_menu.add_command(label="Tout sélectionner", command=self._select_all_console)
        self.console_menu.add_separator()
        self.console_menu.add_command(label="Effacer l'affichage", command=self._clear_console)

    def _build_command_bar(self, parent: ttk.Frame) -> None:
        frame = ttk.Frame(parent, style="Root.TFrame")
        frame.grid(row=4, column=0, sticky="ew", pady=(12, 0))
        frame.columnconfigure(1, weight=1)

        prompt = tk.Label(
            frame,
            text=">",
            background=BACKGROUND,
            foreground=ACCENT,
            font=("Consolas", 13, "bold"),
        )
        prompt.grid(row=0, column=0, padx=(2, 8))

        self.command_entry = ttk.Entry(frame, style="Command.TEntry")
        self.command_entry.grid(row=0, column=1, sticky="ew", padx=(0, 9))
        self.command_entry.bind("<Return>", self._send_command_from_event)
        self.command_entry.bind("<Up>", self._history_previous)
        self.command_entry.bind("<Down>", self._history_next)

        self.send_button = ttk.Button(
            frame,
            text="Envoyer",
            style="Primary.TButton",
            command=self._send_command,
        )
        self.send_button.grid(row=0, column=2)

        self.command_hint = tk.Label(
            frame,
            text="Commande Paper sans « / » • Entrée pour envoyer • ↑/↓ historique",
            background=BACKGROUND,
            foreground=DIM_TEXT,
            font=("Segoe UI", 8),
        )
        self.command_hint.grid(row=1, column=1, sticky="w", pady=(5, 0))

        full_path = str(self.server_directory)
        path_text = full_path if len(full_path) <= 58 else "…" + full_path[-57:]
        self.path_label = tk.Label(
            frame,
            text=path_text,
            background=BACKGROUND,
            foreground=DIM_TEXT,
            font=("Segoe UI", 8),
            anchor="e",
        )
        self.path_label.grid(row=1, column=2, sticky="e", pady=(5, 0))
        Tooltip(self.path_label, full_path)

    def _choose_console_font(self) -> str:
        try:
            available = set(tkfont.families(self))
        except tk.TclError:
            available = set()
        for candidate in ("Cascadia Mono", "Consolas", "Courier New"):
            if candidate in available:
                return candidate
        return "TkFixedFont"

    def _center_window(self) -> None:
        self.update_idletasks()
        width = max(980, self.winfo_width())
        height = max(680, self.winfo_height())
        x = max(0, (self.winfo_screenwidth() - width) // 2)
        y = max(0, (self.winfo_screenheight() - height) // 2)
        self.geometry(f"{width}x{height}+{x}+{y}")


    def _apply_windows_dark_title_bar(self) -> None:
        """Demande une barre de titre sombre à Windows 10/11, si disponible."""

        try:
            self.update_idletasks()
            hwnd = ctypes.windll.user32.GetParent(self.winfo_id())  # type: ignore[attr-defined]
            enabled = ctypes.c_int(1)
            for attribute in (20, 19):
                result = ctypes.windll.dwmapi.DwmSetWindowAttribute(  # type: ignore[attr-defined]
                    hwnd,
                    attribute,
                    ctypes.byref(enabled),
                    ctypes.sizeof(enabled),
                )
                if result == 0:
                    break
        except (AttributeError, OSError, tk.TclError):
            pass

    # ------------------------------------------------------------------
    # Boucle non bloquante et tâches de fond
    # ------------------------------------------------------------------

    def _start_initial_log_read(self) -> None:
        self._log_future_mode = "initial"
        self._log_future = self._executor.submit(self.log_tailer.read_initial_lines)

    def _poll(self) -> None:
        if self._destroying:
            return

        self._consume_start_future()
        self._consume_stop_future()
        self._consume_probe_future()
        self._consume_log_future()
        self._consume_minecraft_future()

        lines = self.controller.drain_output(MAX_LOG_LINES_PER_TICK)
        if lines:
            self._append_lines(lines)

        snapshot = self.controller.snapshot()
        self._handle_state_transition(snapshot)
        self._schedule_probe(snapshot)
        self._schedule_log_io(snapshot)
        self._update_interface(snapshot)
        self._last_snapshot = snapshot

        interval = 35 if self.controller.has_pending_output() else UI_POLL_INTERVAL_MS
        self.after(interval, self._poll)

    def _consume_start_future(self) -> None:
        future = self._start_future
        if future is None or not future.done():
            return
        self._start_future = None

        try:
            command = future.result()
        except (LauncherConfigurationError, ServerAlreadyRunningError, ServerControlError) as error:
            self._append_error(str(error))
            if self._close_after_start:
                self._destroy_application()
                return
            messagebox.showerror("Démarrage impossible", str(error), parent=self)
            return
        except Exception as error:  # filet de sécurité pour ne jamais tuer la boucle Tk
            message = f"Erreur inattendue pendant le démarrage : {error}."
            self._append_error(message)
            if self._close_after_start:
                self._destroy_application()
                return
            messagebox.showerror("Démarrage impossible", message, parent=self)
            return

        self._append_info(f"Processus Paper créé : {self._format_command(command)}")
        self._stop_timed_out = False
        self._next_probe_at = 0.0
        if self._close_after_start:
            self._close_after_start = False
            self._close_after_stop = True
            self._begin_clean_stop()

    def _consume_stop_future(self) -> None:
        future = self._stop_future
        if future is None or not future.done():
            return
        stop_mode = self._stop_mode
        self._stop_future = None
        self._stop_mode = None

        try:
            stopped = future.result()
        except ServerControlError as error:
            self._append_error(str(error))
            self._stop_timed_out = False
            if self._close_after_stop and not self.controller.owns_process:
                self._destroy_application()
                return
            messagebox.showerror("Arrêt impossible", str(error), parent=self)
            return
        except Exception as error:
            message = f"Erreur inattendue pendant l'arrêt : {error}."
            self._append_error(message)
            self._stop_timed_out = False
            messagebox.showerror("Arrêt impossible", message, parent=self)
            return

        if not stopped:
            self._stop_timed_out = True
            if stop_mode == "force":
                self._append_error(
                    "Le processus Paper résiste encore après terminate puis kill. "
                    "Vérifie le Gestionnaire des tâches Windows."
                )
            else:
                self._append_error(
                    "Paper ne s'est pas arrêté après 60 secondes. Vérifie la console, "
                    "puis utilise « Forcer l'arrêt » uniquement si nécessaire."
                )
            return

        self._stop_timed_out = False
        self._append_info("Paper est arrêté ; les mondes ont eu le temps d'être sauvegardés.")
        self._next_probe_at = 0.0
        self._after_server_stopped()

    def _consume_probe_future(self) -> None:
        future = self._probe_future
        if future is None or not future.done():
            return
        self._probe_future = None
        try:
            future.result()
        except Exception as error:
            now = time.monotonic()
            if now - self._last_probe_error_at >= 10.0:
                self._append_warning(f"Test du port temporairement impossible : {error}.")
                self._last_probe_error_at = now

    def _consume_log_future(self) -> None:
        future = self._log_future
        if future is None or not future.done():
            return
        mode = self._log_future_mode
        self._log_future = None
        self._log_future_mode = None

        try:
            lines = future.result() or []
        except Exception as error:
            self._append_warning(f"Lecture de latest.log impossible : {error}.")
            return

        if mode == "initial" and lines:
            self._append_info("Dernières lignes du journal Paper précédent :")
            self._append_lines(lines)
        elif mode == "external" and lines:
            self._append_lines(lines)

    def _consume_minecraft_future(self) -> None:
        future = self._minecraft_future
        if future is None or not future.done():
            return
        self._minecraft_future = None

        try:
            result = future.result()
        except Exception as error:
            result = MinecraftLaunchResult(
                False,
                f"Impossible d'ouvrir le Launcher Minecraft : {error}.",
            )

        if result.launched:
            self._append_info(result.message)
            messagebox.showinfo("Minecraft prêt", result.message, parent=self)
        elif result.warning:
            self._append_warning(result.message)
            messagebox.showwarning("Minecraft 1.21.4 requis", result.message, parent=self)
        else:
            self._append_error(result.message)
            messagebox.showerror("Minecraft", result.message, parent=self)

    def _schedule_probe(self, snapshot: ServerSnapshot) -> None:
        if self._probe_future is not None or self._start_future is not None:
            return
        now = time.monotonic()
        if now < self._next_probe_at:
            return

        if snapshot.state == ServerState.STARTING:
            interval = STARTING_PROBE_INTERVAL_SECONDS
        elif snapshot.owns_process:
            interval = RUNNING_PROBE_INTERVAL_SECONDS
        else:
            interval = IDLE_PROBE_INTERVAL_SECONDS
        self._next_probe_at = now + interval
        self._probe_future = self._executor.submit(self.controller.probe)

    def _schedule_log_io(self, snapshot: ServerSnapshot) -> None:
        if self._log_future is not None:
            return
        now = time.monotonic()
        if now < self._next_log_at:
            return

        if snapshot.owns_process:
            self._next_log_at = now + OWNED_LOG_SYNC_INTERVAL_SECONDS
            self._log_future_mode = "sync"
            self._log_future = self._executor.submit(self._sync_log_tailer)
        else:
            self._next_log_at = now + EXTERNAL_LOG_INTERVAL_SECONDS
            self._log_future_mode = "external"
            self._log_future = self._executor.submit(self.log_tailer.read_new_lines)

    def _sync_log_tailer(self) -> None:
        self.log_tailer.seek_to_end()
        return None

    # ------------------------------------------------------------------
    # État et contrôles
    # ------------------------------------------------------------------

    def _handle_state_transition(self, snapshot: ServerSnapshot) -> None:
        state = snapshot.state
        previous_state = self._last_state

        if state == ServerState.EXTERNAL and not self._external_notice_displayed:
            self._append_warning(
                "Un serveur écoute déjà sur le port 25565. Le launcher reste en "
                "lecture seule et n'enverra aucune commande à ce processus."
            )
            self._external_notice_displayed = True
        elif state != ServerState.EXTERNAL:
            self._external_notice_displayed = False

        if state != previous_state:
            if state == ServerState.RUNNING:
                self._append_success("Paper est prêt et accepte les connexions.")
                self._stop_timed_out = False
            elif state == ServerState.ERROR and snapshot.last_error:
                self._append_error(snapshot.last_error)
            elif state == ServerState.STOPPED and previous_state == ServerState.STOPPING:
                self._after_server_stopped()

        if self._last_owned_process and not snapshot.owns_process:
            self._next_log_at = 0.0

        self._last_state = state
        self._last_owned_process = snapshot.owns_process

    def _after_server_stopped(self) -> None:
        if self.controller.owns_process:
            return
        if self._close_after_stop:
            self._destroy_application()
            return
        if self._restart_after_stop:
            self._restart_after_stop = False
            self.after(150, self._start_server)

    def _update_interface(self, snapshot: ServerSnapshot) -> None:
        display_state = snapshot.state
        state_text = display_state.value
        detail = self._status_detail_for(snapshot)
        color = self._state_color(display_state)

        if self._start_future is not None and not snapshot.owns_process:
            state_text = "Préparation du démarrage"
            detail = "Recherche de Java 21 et validation des fichiers Paper…"
            color = ACCENT
        elif self._minecraft_future is not None:
            detail = "Vérification du profil MineGus et ouverture du Launcher officiel…"

        self.status_dot.configure(foreground=color)
        self.status_label.configure(text=state_text, foreground=color)
        self.status_detail.configure(text=detail)
        self.header_dot.configure(foreground=color)
        self.header_status_text.configure(text=state_text)

        port_value = self._port_metric(snapshot)
        self.metric_port.configure(text=port_value)
        java_value = (
            f"Java {snapshot.java_runtime.major_version}"
            if snapshot.java_runtime is not None
            else "À détecter"
        )
        self.metric_java.configure(text=java_value)
        self.metric_process.configure(text=f"PID {snapshot.pid}" if snapshot.pid else "—")
        self.metric_uptime.configure(text=self._activity_metric(snapshot))

        live = snapshot.owns_process or snapshot.state == ServerState.EXTERNAL
        self.live_label.configure(
            text="●  JOURNAL EN DIRECT" if live else "●  EN ATTENTE",
            foreground=SUCCESS if live else DIM_TEXT,
        )

        self._update_progress(snapshot)
        self._update_controls(snapshot)

    def _status_detail_for(self, snapshot: ServerSnapshot) -> str:
        if snapshot.state == ServerState.STOPPED:
            return f"Le port {PAPER_PORT} est libre. Le serveur peut être démarré."
        if snapshot.state == ServerState.STARTING:
            elapsed = self._format_duration(snapshot.uptime_seconds)
            suffix = f" • {elapsed}" if elapsed else ""
            return f"Paper charge les plugins et les mondes{suffix}. La fenêtre reste utilisable."
        if snapshot.state == ServerState.RUNNING:
            elapsed = self._format_duration(snapshot.uptime_seconds)
            suffix = f" depuis {elapsed}" if elapsed else ""
            return f"Serveur prêt sur 127.0.0.1:{PAPER_PORT}{suffix}."
        if snapshot.state == ServerState.STOPPING:
            return "Commande stop envoyée : sauvegarde des mondes et fermeture en cours."
        if snapshot.state == ServerState.EXTERNAL:
            return "Port occupé par un processus non créé par cette fenêtre : contrôle désactivé."
        if snapshot.last_error:
            return snapshot.last_error
        return "Une erreur a été détectée. Consulte les dernières lignes de la console."

    @staticmethod
    def _state_color(state: ServerState) -> str:
        return {
            ServerState.STOPPED: MUTED_TEXT,
            ServerState.STARTING: ACCENT,
            ServerState.RUNNING: SUCCESS,
            ServerState.STOPPING: WARNING,
            ServerState.EXTERNAL: WARNING,
            ServerState.ERROR: ERROR,
        }[state]

    @staticmethod
    def _port_metric(snapshot: ServerSnapshot) -> str:
        if snapshot.port_open is True:
            return f"{PAPER_PORT} ouvert"
        if snapshot.port_open is False:
            return f"{PAPER_PORT} libre"
        return f"{PAPER_PORT} …"

    def _activity_metric(self, snapshot: ServerSnapshot) -> str:
        if snapshot.uptime_seconds is not None:
            return self._format_duration(snapshot.uptime_seconds) or "0 s"
        if snapshot.state == ServerState.EXTERNAL:
            return "Externe"
        if snapshot.state == ServerState.ERROR:
            return "À vérifier"
        return "Hors ligne"

    def _update_progress(self, snapshot: ServerSnapshot) -> None:
        visible = False
        text = ""
        if self._start_future is not None:
            visible = True
            text = "Préparation de l'environnement Java…"
        elif snapshot.state == ServerState.STARTING:
            visible = True
            text = "Démarrage de Paper, chargement des chunks et des plugins…"
        elif self._stop_future is not None or snapshot.state == ServerState.STOPPING:
            visible = True
            text = "Arrêt propre : Paper sauvegarde les mondes…"
        elif self._minecraft_future is not None:
            visible = True
            text = "Ouverture du Launcher Minecraft…"

        if visible:
            self.progress_text.configure(text=text)
            self.progress_frame.grid()
            if not self._progress_running:
                self.progress_bar.start(12)
                self._progress_running = True
        else:
            if self._progress_running:
                self.progress_bar.stop()
                self._progress_running = False
            self.progress_frame.grid_remove()

    def _update_controls(self, snapshot: ServerSnapshot) -> None:
        start_busy = self._start_future is not None
        stop_busy = self._stop_future is not None
        operation_busy = start_busy or stop_busy or self._close_after_start
        controllable = snapshot.owns_process and snapshot.state in {
            ServerState.STARTING,
            ServerState.RUNNING,
        }
        can_start = (
            snapshot.state in {ServerState.STOPPED, ServerState.ERROR}
            and not operation_busy
            and not snapshot.owns_process
        )
        can_control = controllable and not operation_busy
        can_force = self._stop_timed_out and snapshot.owns_process and not stop_busy
        can_command = controllable and not stop_busy and not self._close_after_stop

        self.start_button.configure(
            state="normal" if can_start else "disabled",
            text="Démarrage…" if start_busy else ("↻  Réessayer" if snapshot.state == ServerState.ERROR else "▶  Démarrer"),
        )
        self.stop_button.configure(state="normal" if can_control else "disabled")
        self.restart_button.configure(state="normal" if can_control else "disabled")
        self.force_stop_button.configure(state="normal" if can_force else "disabled")
        self.minecraft_button.configure(
            state="normal" if self._minecraft_future is None else "disabled",
            text="Ouverture…" if self._minecraft_future is not None else "Lancer Minecraft  →",
        )
        self.command_entry.configure(state="normal" if can_command else "disabled")
        self.send_button.configure(state="normal" if can_command else "disabled")

    # ------------------------------------------------------------------
    # Actions utilisateur
    # ------------------------------------------------------------------

    def _start_server(self) -> None:
        if self._start_future is not None or self._stop_future is not None:
            return
        snapshot = self.controller.snapshot()
        if snapshot.owns_process:
            return

        self._stop_timed_out = False
        self._append_info("Préparation du démarrage : vérification du port, de Java et de paper.jar.")
        self._start_future = self._executor.submit(self.controller.start)

    def _stop_server(self) -> None:
        if not messagebox.askyesno(
            "Arrêter le serveur",
            "Arrêter Paper proprement ? Les mondes seront sauvegardés avant la fermeture.",
            parent=self,
        ):
            return
        self._restart_after_stop = False
        self._begin_clean_stop()

    def _restart_server(self) -> None:
        if not messagebox.askyesno(
            "Redémarrer le serveur",
            "Sauvegarder les mondes, arrêter Paper, puis le redémarrer automatiquement ?",
            parent=self,
        ):
            return
        self._restart_after_stop = True
        self._begin_clean_stop()

    def _begin_clean_stop(self) -> None:
        if self._stop_future is not None:
            return
        self._stop_timed_out = False
        self._stop_mode = "clean"
        self._append_info("Commande stop planifiée ; attente de la sauvegarde Paper.")
        self._stop_future = self._executor.submit(self._clean_stop_worker)

    def _clean_stop_worker(self) -> bool:
        self.controller.request_stop()
        return self.controller.wait_for_exit(STOP_TIMEOUT_SECONDS)

    def _force_stop_server(self) -> None:
        if not messagebox.askyesno(
            "Forcer l'arrêt",
            "Paper n'a pas répondu à la commande stop. Forcer la fin peut perdre "
            "des données non sauvegardées. Continuer ?",
            icon="warning",
            parent=self,
        ):
            return
        self._stop_timed_out = False
        self._stop_mode = "force"
        self._append_warning("Arrêt forcé confirmé : terminate, puis kill si nécessaire.")
        self._stop_future = self._executor.submit(self.controller.force_stop_and_wait)

    def _send_command_from_event(self, _event: tk.Event[tk.Misc]) -> str:
        self._send_command()
        return "break"

    def _send_command(self) -> None:
        command = self.command_entry.get().strip()
        if not command:
            return
        if command.startswith("/"):
            command = command[1:].lstrip()
        if not command:
            return

        try:
            self.controller.send_command(command)
        except ServerControlError as error:
            self._append_error(str(error))
            return

        self._append_info(f"> {command}")
        self._remember_command(command)
        self.command_entry.delete(0, "end")

    def _remember_command(self, command: str) -> None:
        if not self._command_history or self._command_history[-1] != command:
            self._command_history.append(command)
            if len(self._command_history) > 100:
                del self._command_history[:20]
        self._history_index = len(self._command_history)

    def _history_previous(self, _event: tk.Event[tk.Misc]) -> str:
        if not self._command_history:
            return "break"
        self._history_index = max(0, self._history_index - 1)
        self._set_command_entry(self._command_history[self._history_index])
        return "break"

    def _history_next(self, _event: tk.Event[tk.Misc]) -> str:
        if not self._command_history:
            return "break"
        self._history_index = min(len(self._command_history), self._history_index + 1)
        value = "" if self._history_index == len(self._command_history) else self._command_history[self._history_index]
        self._set_command_entry(value)
        return "break"

    def _set_command_entry(self, value: str) -> None:
        self.command_entry.delete(0, "end")
        self.command_entry.insert(0, value)
        self.command_entry.icursor("end")

    def _launch_minecraft(self) -> None:
        if self._minecraft_future is not None:
            return
        self._append_info("Vérification du profil Minecraft MineGus 1.21.4.")
        self._minecraft_future = self._executor.submit(self._launch_minecraft_worker)

    @staticmethod
    def _launch_minecraft_worker() -> MinecraftLaunchResult:
        try:
            status = check_minecraft_profile()
        except LauncherConfigurationError as error:
            return MinecraftLaunchResult(False, str(error), warning=True)

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
            return MinecraftLaunchResult(
                False,
                "Impossible de confirmer la compatibilité : " + "; ".join(problems) + ".",
                warning=True,
            )

        try:
            subprocess.Popen(
                minecraft_launcher_command(),
                creationflags=int(getattr(subprocess, "CREATE_NO_WINDOW", 0)),
            )
        except OSError as error:
            return MinecraftLaunchResult(
                False,
                f"Impossible d'ouvrir le Launcher Minecraft : {error}.",
            )

        return MinecraftLaunchResult(
            True,
            "Le Launcher officiel est ouvert. Vérifie le profil MineGus (1.21.4), "
            "puis clique sur Jouer.",
        )

    # ------------------------------------------------------------------
    # Console
    # ------------------------------------------------------------------

    def _append_info(self, message: str) -> None:
        self._append_lines([f"[Launcher] {message}"], "launcher")

    def _append_warning(self, message: str) -> None:
        self._append_lines([f"[Launcher/WARN] {message}"], "warning")

    def _append_error(self, message: str) -> None:
        self._append_lines([f"[Launcher/ERROR] {message}"], "error")

    def _append_success(self, message: str) -> None:
        self._append_lines([f"[Launcher] {message}"], "success")

    def _append_lines(self, lines: list[str], forced_tag: str | None = None) -> None:
        if not lines or self._destroying:
            return

        normalized_lines = [
            line[:MAX_CONSOLE_LINE_LENGTH] + ("…" if len(line) > MAX_CONSOLE_LINE_LENGTH else "")
            for line in lines
        ]
        try:
            at_bottom = float(self.console.yview()[1]) >= 0.995
        except (tk.TclError, ValueError, IndexError):
            at_bottom = True

        self.console.configure(state="normal")
        current_tag: str | None = None
        buffer: list[str] = []

        def flush_buffer() -> None:
            nonlocal buffer, current_tag
            if buffer:
                self.console.insert("end", "\n".join(buffer) + "\n", current_tag)
                buffer = []

        for line in normalized_lines:
            tag = forced_tag or self._tag_for_line(line)
            if tag != current_tag:
                flush_buffer()
                current_tag = tag
            buffer.append(line)
        flush_buffer()

        self._console_line_count += len(normalized_lines)
        if self._console_line_count > MAX_CONSOLE_LINES + CONSOLE_TRIM_CHUNK:
            self.console.delete("1.0", f"{CONSOLE_TRIM_CHUNK + 1}.0")
            self._console_line_count -= CONSOLE_TRIM_CHUNK

        if self.auto_scroll_var.get() and at_bottom:
            self.console.see("end")
        self.console.configure(state="disabled")

    @staticmethod
    def _tag_for_line(line: str) -> str:
        upper_line = line.upper()
        if line.startswith("[Launcher/ERROR]"):
            return "error"
        if line.startswith("[Launcher/WARN]"):
            return "warning"
        if line.startswith("[Launcher]"):
            return "launcher"
        if any(token in upper_line for token in ("ERROR", "EXCEPTION", "SEVERE", "FATAL")):
            return "error"
        if any(token in upper_line for token in ("WARN", "AVERTISSEMENT")):
            return "warning"
        if any(token in upper_line for token in ("DONE (", "EN LIGNE", "JOINED THE GAME")):
            return "success"
        if "DEBUG" in upper_line or "TRACE" in upper_line:
            return "muted"
        return "info"

    def _clear_console(self) -> None:
        self.console.configure(state="normal")
        self.console.delete("1.0", "end")
        self.console.configure(state="disabled")
        self._console_line_count = 0
        self._append_info("Affichage de la console effacé. Le fichier latest.log est intact.")

    def _show_console_menu(self, event: tk.Event[tk.Misc]) -> None:
        try:
            self.console_menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.console_menu.grab_release()

    def _copy_console_selection(self) -> None:
        try:
            selection = self.console.get("sel.first", "sel.last")
        except tk.TclError:
            return
        self.clipboard_clear()
        self.clipboard_append(selection)

    def _select_all_console(self) -> None:
        self.console.configure(state="normal")
        self.console.tag_add("sel", "1.0", "end-1c")
        self.console.mark_set("insert", "1.0")
        self.console.see("insert")
        self.console.configure(state="disabled")

    # ------------------------------------------------------------------
    # Fermeture et utilitaires
    # ------------------------------------------------------------------

    def _on_close(self) -> None:
        if self._destroying:
            return

        if self._stop_future is not None:
            self._close_after_stop = True
            self._append_info("La fenêtre se fermera dès que Paper sera complètement arrêté.")
            return

        if self._start_future is not None:
            should_close = messagebox.askyesno(
                "Démarrage en cours",
                "Le démarrage est déjà lancé. Fermer automatiquement dès que Paper "
                "aura été arrêté proprement ?",
                parent=self,
            )
            if should_close:
                self._close_after_start = True
                self._append_info(
                    "Fermeture demandée : le launcher terminera le démarrage puis "
                    "arrêtera Paper proprement."
                )
            return

        snapshot = self.controller.snapshot()
        if not snapshot.owns_process:
            self._destroy_application()
            return

        should_stop = messagebox.askyesno(
            "Fermer le launcher",
            "Le serveur est géré par cette fenêtre. L'arrêter proprement avant de fermer ?",
            parent=self,
        )
        if not should_stop:
            return
        self._close_after_stop = True
        self._restart_after_stop = False
        self._begin_clean_stop()

    def _destroy_application(self) -> None:
        if self._destroying:
            return
        self._destroying = True
        try:
            if self._progress_running:
                self.progress_bar.stop()
        except tk.TclError:
            pass
        self._executor.shutdown(wait=False, cancel_futures=True)
        try:
            self.destroy()
        except tk.TclError:
            pass

    @staticmethod
    def _format_command(command: tuple[str, ...]) -> str:
        return " ".join(f'"{part}"' if " " in part else part for part in command)

    @staticmethod
    def _format_duration(seconds: float | None) -> str:
        if seconds is None:
            return ""
        total = max(0, int(seconds))
        hours, remainder = divmod(total, 3600)
        minutes, secs = divmod(remainder, 60)
        if hours:
            return f"{hours} h {minutes:02d} min"
        if minutes:
            return f"{minutes} min {secs:02d} s"
        return f"{secs} s"


def _enable_windows_dpi_awareness() -> None:
    """Évite une interface floue sur les écrans Windows à mise à l'échelle élevée."""

    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # type: ignore[attr-defined]
    except (AttributeError, OSError):
        try:
            ctypes.windll.user32.SetProcessDPIAware()  # type: ignore[attr-defined]
        except (AttributeError, OSError):
            pass


def main() -> None:
    """Démarre l'interface ou affiche un diagnostic de configuration."""

    _enable_windows_dpi_awareness()
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
