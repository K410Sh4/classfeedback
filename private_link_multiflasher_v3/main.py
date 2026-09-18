import hashlib
import os
import queue
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from serial.tools import list_ports

APP_NAME = "LENDAS ESP MultiFlasher"
APP_VERSION = "3.0.0"

SUPPORTED_CHIPS = {
    "ESP32-S3": {
        "esptool_chip": "esp32s3",
        "label": "ESP32-S3",
        "accent": "#5EEAD4",
    },
    "ESP32-C6": {
        "esptool_chip": "esp32c6",
        "label": "ESP32-C6",
        "accent": "#60A5FA",
    },
}

KNOWN_FIRMWARE = {
    "LENDAS_PrivateLink_S3_v1_4_0_FULL.bin": {
        "chip": "ESP32-S3",
        "kind": "FULL",
        "sha256": "85f630bcd7e5585e42234a11b918c858dddae648c01567dab1605cd775495850",
    },
    "LENDAS_PrivateLink_S3_v1_4_0_OTA.bin": {
        "chip": "ESP32-S3",
        "kind": "APP",
        "sha256": "ffbfc624d8cb98e0c1272265c1f8124572385616d2ea883d4af51ac0e4fe01d6",
    },
    "LENDAS_PrivateLink_C6_v1_0_0_FULL.bin": {
        "chip": "ESP32-C6",
        "kind": "FULL",
        "sha256": "3a5b2bbd8368e4e77ac626d3c1abb4ecc735f6ea855c3ef954a6f19b9505d71e",
    },
    "LENDAS_PrivateLink_C6_v1_0_0_OTA.bin": {
        "chip": "ESP32-C6",
        "kind": "APP",
        "sha256": "df2a9580ca9084479f719a60dfcf4e7fdce8a386b0a3a5bf2b5f875f39372d8b",
    },
}

BG = "#071017"
SURFACE = "#0E1820"
SURFACE_2 = "#14232C"
TEXT = "#E8F0F4"
TEXT_MUTED = "#9FB0BA"
PRIMARY = "#5EEAD4"
SECONDARY = "#60A5FA"
WARNING = "#FBBF24"
ERROR = "#FB7185"
SUCCESS = "#34D399"
OUTLINE = "#2D404B"

PARTITION_MAGIC = b"\xAA\x50"


@dataclass
class PortInfo:
    device: str
    description: str
    hwid: str


@dataclass
class DeviceInfo:
    port: str
    chip: str
    description: str
    flash_text: str
    mac: str
    raw: str


@dataclass
class ImageInfo:
    path: Path
    sha256: str
    size: int
    kind: str
    address: int
    chip_hint: str | None
    catalog_match: bool
    catalog_hash_ok: bool | None
    image_info_output: str


def helper_command(args: list[str]) -> list[str]:
    if getattr(sys, "frozen", False):
        return [sys.executable, "--esptool", *args]
    return [sys.executable, str(Path(__file__).resolve()), "--esptool", *args]


def run_esptool_helper() -> None:
    if len(sys.argv) < 3 or sys.argv[1] != "--esptool":
        return

    try:
        import esptool

        esptool.main(sys.argv[2:])
    except SystemExit as exc:
        raise
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)


if len(sys.argv) >= 2 and sys.argv[1] == "--esptool":
    run_esptool_helper()
    raise SystemExit(0)


class EsptoolRunner:
    def __init__(self, emit):
        self.emit = emit
        self.process: subprocess.Popen | None = None
        self._lock = threading.Lock()

    def run(self, args: list[str], timeout: int = 45) -> tuple[int, str]:
        cmd = helper_command(args)
        output_lines: list[str] = []

        startupinfo = None
        creationflags = 0
        if os.name == "nt":
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            creationflags = subprocess.CREATE_NO_WINDOW

        with self._lock:
            self.process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                startupinfo=startupinfo,
                creationflags=creationflags,
            )

        start = time.monotonic()

        try:
            assert self.process.stdout is not None

            while True:
                if time.monotonic() - start > timeout:
                    self.cancel()
                    raise TimeoutError(
                        f"Timeout de {timeout}s executando ferramenta de flash."
                    )

                line = self.process.stdout.readline()

                if line:
                    cleaned = line.rstrip()
                    output_lines.append(cleaned)
                    self.emit(cleaned)

                if self.process.poll() is not None:
                    rest = self.process.stdout.read()
                    if rest:
                        for extra in rest.splitlines():
                            output_lines.append(extra)
                            self.emit(extra)
                    break

                if not line:
                    time.sleep(0.02)

            return self.process.returncode or 0, "\n".join(output_lines)
        finally:
            with self._lock:
                self.process = None

    def cancel(self):
        with self._lock:
            proc = self.process

        if proc is None:
            return

        try:
            proc.terminate()
            proc.wait(timeout=2)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


class MultiFlasherApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(f"{APP_NAME} v{APP_VERSION}")
        self.root.geometry("920x760")
        self.root.minsize(840, 680)
        self.root.configure(bg=BG)

        try:
            self.root.iconname(APP_NAME)
        except Exception:
            pass

        self.events: queue.Queue = queue.Queue()
        self.runner = EsptoolRunner(self._emit_worker_log)
        self.busy = False
        self.cancel_requested = False

        self.port_var = tk.StringVar()
        self.device_var = tk.StringVar(value="Nenhum dispositivo identificado")
        self.flash_var = tk.StringVar(value="-")
        self.mac_var = tk.StringVar(value="-")
        self.image_path_var = tk.StringVar()
        self.image_type_var = tk.StringVar(value="Nenhuma imagem selecionada")
        self.image_hash_var = tk.StringVar(value="-")
        self.address_var = tk.StringVar(value="-")
        self.status_var = tk.StringVar(value="Pronto")
        self.erase_var = tk.BooleanVar(value=True)
        self.verify_var = tk.BooleanVar(value=True)
        self.baud_var = tk.StringVar(value="460800")

        self.detected_device: DeviceInfo | None = None
        self.image_info: ImageInfo | None = None
        self.ports: dict[str, PortInfo] = {}

        self._build_style()
        self._build_ui()
        self.refresh_ports()
        self.root.after(100, self._drain_events)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_style(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass

        style.configure(
            "TCombobox",
            fieldbackground=SURFACE_2,
            background=SURFACE_2,
            foreground=TEXT,
            arrowcolor=TEXT,
            bordercolor=OUTLINE,
            lightcolor=OUTLINE,
            darkcolor=OUTLINE,
            padding=8,
        )

        style.map(
            "TCombobox",
            fieldbackground=[("readonly", SURFACE_2)],
            foreground=[("readonly", TEXT)],
        )

        style.configure(
            "TProgressbar",
            troughcolor=SURFACE_2,
            background=PRIMARY,
            bordercolor=SURFACE_2,
            lightcolor=PRIMARY,
            darkcolor=PRIMARY,
            thickness=12,
        )

    def _card(self, parent, **kwargs):
        return tk.Frame(
            parent,
            bg=SURFACE,
            highlightbackground=OUTLINE,
            highlightthickness=1,
            bd=0,
            **kwargs,
        )

    def _label(self, parent, text="", textvariable=None, size=10, fg=TEXT, bold=False):
        return tk.Label(
            parent,
            text=text,
            textvariable=textvariable,
            bg=parent.cget("bg"),
            fg=fg,
            font=("Segoe UI", size, "bold" if bold else "normal"),
            anchor="w",
            justify="left",
        )

    def _button(self, parent, text, command, primary=False, danger=False, width=None):
        if danger:
            bg = "#3A1820"
            fg = "#FDA4AF"
            active = "#4A2029"
        elif primary:
            bg = PRIMARY
            fg = "#05201C"
            active = "#99F6E4"
        else:
            bg = SURFACE_2
            fg = TEXT
            active = "#1D303A"

        return tk.Button(
            parent,
            text=text,
            command=command,
            bg=bg,
            fg=fg,
            activebackground=active,
            activeforeground=fg,
            relief="flat",
            bd=0,
            padx=14,
            pady=9,
            cursor="hand2",
            font=("Segoe UI", 10, "bold"),
            width=width,
            disabledforeground="#60717B",
        )

    def _build_ui(self):
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=18, pady=16)

        header = tk.Frame(outer, bg=BG)
        header.pack(fill="x")

        self._label(
            header,
            text="LENDAS ESP MultiFlasher",
            size=20,
            bold=True,
        ).pack(side="left")

        self._label(
            header,
            text=f"v{APP_VERSION} • S3 + C6",
            size=10,
            fg=TEXT_MUTED,
        ).pack(side="right", pady=(8, 0))

        self._label(
            outer,
            text="Detecção automática de chip • proteção contra firmware incorreto • SHA-256 • cancelamento seguro",
            size=10,
            fg=TEXT_MUTED,
        ).pack(fill="x", pady=(2, 14))

        top = tk.Frame(outer, bg=BG)
        top.pack(fill="x")

        device_card = self._card(top)
        device_card.pack(side="left", fill="both", expand=True, padx=(0, 7))

        self._label(device_card, text="DISPOSITIVO", size=9, fg=TEXT_MUTED, bold=True).pack(
            fill="x", padx=16, pady=(14, 4)
        )

        port_row = tk.Frame(device_card, bg=SURFACE)
        port_row.pack(fill="x", padx=16)

        self.port_combo = ttk.Combobox(
            port_row,
            textvariable=self.port_var,
            state="readonly",
            width=44,
        )
        self.port_combo.pack(side="left", fill="x", expand=True)
        self.port_combo.bind("<<ComboboxSelected>>", self._on_port_changed)

        self._button(
            port_row,
            "Atualizar",
            self.refresh_ports,
        ).pack(side="left", padx=(8, 0))

        self._button(
            device_card,
            "Detectar automaticamente",
            self.detect_selected_device,
            primary=True,
        ).pack(fill="x", padx=16, pady=12)

        info = tk.Frame(device_card, bg=SURFACE)
        info.pack(fill="x", padx=16, pady=(0, 14))

        for label_text, variable in (
            ("Chip", self.device_var),
            ("Flash", self.flash_var),
            ("MAC", self.mac_var),
        ):
            row = tk.Frame(info, bg=SURFACE)
            row.pack(fill="x", pady=2)
            self._label(row, text=label_text, size=9, fg=TEXT_MUTED).pack(side="left")
            self._label(row, textvariable=variable, size=9, bold=True).pack(side="right")

        image_card = self._card(top)
        image_card.pack(side="left", fill="both", expand=True, padx=(7, 0))

        self._label(image_card, text="FIRMWARE", size=9, fg=TEXT_MUTED, bold=True).pack(
            fill="x", padx=16, pady=(14, 4)
        )

        self._button(
            image_card,
            "Selecionar .BIN",
            self.choose_image,
            primary=True,
        ).pack(fill="x", padx=16, pady=(6, 12))

        path_label = self._label(
            image_card,
            textvariable=self.image_path_var,
            size=9,
            fg=TEXT_MUTED,
        )
        path_label.configure(wraplength=360)
        path_label.pack(fill="x", padx=16)

        image_info_box = tk.Frame(image_card, bg=SURFACE)
        image_info_box.pack(fill="x", padx=16, pady=12)

        for label_text, variable in (
            ("Tipo", self.image_type_var),
            ("Endereço", self.address_var),
            ("SHA-256", self.image_hash_var),
        ):
            row = tk.Frame(image_info_box, bg=SURFACE)
            row.pack(fill="x", pady=2)
            self._label(row, text=label_text, size=9, fg=TEXT_MUTED).pack(side="left")
            value = self._label(row, textvariable=variable, size=9, bold=True)
            value.pack(side="right")
            if label_text == "SHA-256":
                value.configure(font=("Consolas", 8, "bold"))

        options = self._card(outer)
        options.pack(fill="x", pady=(14, 0))

        opt_inner = tk.Frame(options, bg=SURFACE)
        opt_inner.pack(fill="x", padx=16, pady=12)

        self.erase_check = tk.Checkbutton(
            opt_inner,
            text="Apagar flash antes de gravar FULL",
            variable=self.erase_var,
            bg=SURFACE,
            fg=TEXT,
            activebackground=SURFACE,
            activeforeground=TEXT,
            selectcolor=SURFACE_2,
            font=("Segoe UI", 9),
        )
        self.erase_check.pack(side="left")

        self.verify_check = tk.Checkbutton(
            opt_inner,
            text="Verificar flash após gravação",
            variable=self.verify_var,
            bg=SURFACE,
            fg=TEXT,
            activebackground=SURFACE,
            activeforeground=TEXT,
            selectcolor=SURFACE_2,
            font=("Segoe UI", 9),
        )
        self.verify_check.pack(side="left", padx=(20, 0))

        self._label(opt_inner, text="Baud", size=9, fg=TEXT_MUTED).pack(
            side="left", padx=(28, 6)
        )

        self.baud_combo = ttk.Combobox(
            opt_inner,
            textvariable=self.baud_var,
            state="readonly",
            values=("115200", "230400", "460800", "921600"),
            width=9,
        )
        self.baud_combo.pack(side="left")

        action_card = self._card(outer)
        action_card.pack(fill="x", pady=(14, 0))

        action_inner = tk.Frame(action_card, bg=SURFACE)
        action_inner.pack(fill="x", padx=16, pady=14)

        self.flash_button = self._button(
            action_inner,
            "ANALISAR E FLASHAR",
            self.start_flash,
            primary=True,
        )
        self.flash_button.pack(side="left", fill="x", expand=True)

        self.cancel_button = self._button(
            action_inner,
            "Cancelar",
            self.cancel_operation,
            danger=True,
        )
        self.cancel_button.pack(side="left", padx=(10, 0))
        self.cancel_button.configure(state="disabled")

        self.progress = ttk.Progressbar(
            outer,
            mode="determinate",
            maximum=100,
            value=0,
        )
        self.progress.pack(fill="x", pady=(12, 4))

        status_row = tk.Frame(outer, bg=BG)
        status_row.pack(fill="x")

        self.status_dot = tk.Label(
            status_row,
            text="●",
            bg=BG,
            fg=SUCCESS,
            font=("Segoe UI", 10),
        )
        self.status_dot.pack(side="left")

        self._label(
            status_row,
            textvariable=self.status_var,
            size=9,
            fg=TEXT_MUTED,
        ).pack(side="left", padx=(6, 0))

        log_card = self._card(outer)
        log_card.pack(fill="both", expand=True, pady=(12, 0))

        log_header = tk.Frame(log_card, bg=SURFACE)
        log_header.pack(fill="x", padx=12, pady=(10, 4))

        self._label(log_header, text="LOG TÉCNICO", size=9, fg=TEXT_MUTED, bold=True).pack(
            side="left"
        )
        self._button(log_header, "Limpar", self.clear_log).pack(side="right")

        self.log = tk.Text(
            log_card,
            bg="#050A0E",
            fg="#C6D4DB",
            insertbackground=TEXT,
            relief="flat",
            bd=0,
            font=("Consolas", 9),
            wrap="word",
            height=12,
        )
        self.log.pack(fill="both", expand=True, padx=12, pady=(0, 12))

        self._log(f"{APP_NAME} v{APP_VERSION}")
        self._log("Suporte: ESP32-S3 e ESP32-C6.")
        self._log("Selecione a porta e o firmware. O chip será validado antes da gravação.")

    def _on_port_changed(self, _event=None):
        self.detected_device = None
        self.device_var.set("Não identificado")
        self.flash_var.set("-")
        self.mac_var.set("-")

    def refresh_ports(self):
        ports = {}
        labels = []

        for port in list_ports.comports():
            info = PortInfo(
                device=port.device,
                description=port.description or "Dispositivo serial",
                hwid=port.hwid or "",
            )
            ports[port.device] = info
            labels.append(f"{port.device}  •  {info.description}")

        self.ports = ports
        self.port_combo["values"] = labels

        if not labels:
            self.port_var.set("")
            self.status_var.set("Nenhuma porta serial detectada")
            self._set_status_color(WARNING)
            return

        current_device = self._selected_port_device()
        if current_device not in ports:
            self.port_combo.current(0)
        else:
            for i, label in enumerate(labels):
                if label.startswith(current_device + "  •"):
                    self.port_combo.current(i)
                    break

        self.status_var.set(f"{len(labels)} porta(s) encontrada(s)")
        self._set_status_color(SUCCESS)

    def _selected_port_device(self) -> str:
        value = self.port_var.get().strip()
        if not value:
            return ""
        return value.split("  •", 1)[0].strip()

    def detect_selected_device(self):
        if self.busy:
            return

        port = self._selected_port_device()
        if not port:
            messagebox.showwarning(APP_NAME, "Selecione uma porta COM.")
            return

        self._start_worker(
            lambda: self._detect_device_worker(port),
            status="Identificando chip...",
        )

    def _detect_device_worker(self, port: str):
        device = self._probe_device(port)
        self.events.put(("device", device))
        self.events.put(("status", f"{device.chip} identificado em {port}", SUCCESS))

    def _probe_device(self, port: str) -> DeviceInfo:
        self._worker_log(f"Detectando chip em {port}...")

        code, output = self.runner.run(
            [
                "--port",
                port,
                "--baud",
                "115200",
                "chip_id",
            ],
            timeout=25,
        )

        if code != 0:
            raise RuntimeError(
                "Não foi possível identificar o ESP32. "
                "Tente segurar BOOT, apertar RST e soltar BOOT."
            )

        chip = self._parse_chip(output)
        if chip not in SUPPORTED_CHIPS:
            raise RuntimeError(
                f"Chip não suportado pelo MultiFlasher: {chip or 'desconhecido'}."
            )

        mac_match = re.search(
            r"MAC:\s*([0-9A-Fa-f:]{17})",
            output,
        )
        mac = mac_match.group(1).upper() if mac_match else "-"

        self._worker_log("Lendo identificação da flash...")

        flash_code, flash_output = self.runner.run(
            [
                "--port",
                port,
                "--baud",
                "115200",
                "flash_id",
            ],
            timeout=25,
        )

        combined = output + "\n" + flash_output
        flash_text = self._parse_flash_size(combined)

        port_desc = self.ports.get(port)
        description = port_desc.description if port_desc else port

        if flash_code != 0:
            self._worker_log("Aviso: não foi possível ler todos os dados da flash.")

        return DeviceInfo(
            port=port,
            chip=chip,
            description=description,
            flash_text=flash_text,
            mac=mac,
            raw=combined,
        )

    @staticmethod
    def _parse_chip(output: str) -> str:
        upper = output.upper()

        if "ESP32-S3" in upper or "ESP32S3" in upper:
            return "ESP32-S3"

        if "ESP32-C6" in upper or "ESP32C6" in upper:
            return "ESP32-C6"

        match = re.search(r"Chip is\s+([^\r\n(]+)", output, re.IGNORECASE)
        return match.group(1).strip() if match else ""

    @staticmethod
    def _parse_flash_size(output: str) -> str:
        patterns = (
            r"Detected flash size:\s*([^\r\n]+)",
            r"Flash size:\s*([^\r\n]+)",
        )

        for pattern in patterns:
            match = re.search(pattern, output, re.IGNORECASE)
            if match:
                return match.group(1).strip()

        return "detectada, tamanho não reportado"

    def choose_image(self):
        if self.busy:
            return

        path = filedialog.askopenfilename(
            title="Selecionar firmware ESP32",
            filetypes=[
                ("Firmware ESP32", "*.bin"),
                ("Todos os arquivos", "*.*"),
            ],
        )

        if not path:
            return

        try:
            image = self._analyze_image(Path(path))
        except Exception as exc:
            messagebox.showerror(APP_NAME, f"Falha ao analisar firmware:\n{exc}")
            return

        self.image_info = image
        self.image_path_var.set(str(image.path))
        self.image_type_var.set(
            f"{image.kind} • {image.chip_hint or 'chip não identificado'}"
        )
        self.address_var.set(f"0x{image.address:X}")
        self.image_hash_var.set(image.sha256[:16] + "…")

        if image.kind == "APP":
            self.erase_var.set(False)
            self.erase_check.configure(state="disabled")
        else:
            self.erase_check.configure(state="normal")

        if image.catalog_match:
            if image.catalog_hash_ok:
                self._log("Firmware reconhecido no catálogo LENDAS: SHA-256 OK.")
                self._set_status_color(SUCCESS)
                self.status_var.set("Firmware oficial validado")
            else:
                self._log("ALERTA: nome conhecido, mas SHA-256 não corresponde.")
                self._set_status_color(ERROR)
                self.status_var.set("Firmware conhecido com hash divergente")
        else:
            self._log(
                "Firmware externo/não catalogado: será validado pelo tipo de chip e imagem."
            )

    def _analyze_image(self, path: Path) -> ImageInfo:
        if not path.exists():
            raise FileNotFoundError(path)

        if path.suffix.lower() != ".bin":
            raise ValueError("Selecione um arquivo .bin.")

        data = path.read_bytes()
        if len(data) < 32:
            raise ValueError("Arquivo pequeno demais para ser uma imagem ESP válida.")

        sha = hashlib.sha256(data).hexdigest()

        kind = "FULL" if self._looks_like_full_image(data) else "APP"
        address = 0x0 if kind == "FULL" else 0x10000

        image_output = ""
        chip_hint = None

        try:
            code, image_output = self.runner.run(
                ["image_info", str(path)],
                timeout=20,
            )
            if code == 0:
                chip_hint = self._parse_chip(image_output) or None
        except Exception:
            image_output = ""

        catalog = KNOWN_FIRMWARE.get(path.name)
        catalog_match = catalog is not None
        catalog_hash_ok = None

        if catalog:
            catalog_hash_ok = sha.lower() == catalog["sha256"].lower()
            chip_hint = catalog["chip"]
            kind = catalog["kind"]
            address = 0x0 if kind == "FULL" else 0x10000

        if chip_hint is None:
            upper_name = path.name.upper()
            if "C6" in upper_name:
                chip_hint = "ESP32-C6"
            elif "S3" in upper_name:
                chip_hint = "ESP32-S3"

        return ImageInfo(
            path=path,
            sha256=sha,
            size=len(data),
            kind=kind,
            address=address,
            chip_hint=chip_hint,
            catalog_match=catalog_match,
            catalog_hash_ok=catalog_hash_ok,
            image_info_output=image_output,
        )

    @staticmethod
    def _looks_like_full_image(data: bytes) -> bool:
        if len(data) <= 0x8002:
            return False

        return data[0x8000:0x8002] == PARTITION_MAGIC

    def start_flash(self):
        if self.busy:
            return

        port = self._selected_port_device()
        image = self.image_info

        if not port:
            messagebox.showwarning(APP_NAME, "Selecione a porta do ESP32.")
            return

        if image is None:
            messagebox.showwarning(APP_NAME, "Selecione o firmware .bin.")
            return

        if image.catalog_match and image.catalog_hash_ok is False:
            messagebox.showerror(
                APP_NAME,
                "Este arquivo possui nome de firmware oficial, mas o SHA-256 é diferente. "
                "A gravação foi bloqueada.",
            )
            return

        if image.kind == "APP" and self.erase_var.get():
            messagebox.showerror(
                APP_NAME,
                "Não é seguro apagar toda a flash e depois gravar apenas uma imagem APP/OTA. "
                "Use o arquivo FULL para instalação limpa.",
            )
            return

        details = (
            f"Porta: {port}\n"
            f"Arquivo: {image.path.name}\n"
            f"Tipo: {image.kind}\n"
            f"Endereço: 0x{image.address:X}\n"
            f"SHA-256: {image.sha256}\n\n"
            "Continuar?"
        )

        if not messagebox.askyesno("Confirmar gravação", details):
            return

        self._start_worker(
            lambda: self._flash_worker(port, image),
            status="Preparando gravação...",
            flash_mode=True,
        )

    def _flash_worker(self, port: str, image: ImageInfo):
        self.events.put(("progress", 4))
        device = self._probe_device(port)
        self.events.put(("device", device))
        self.events.put(("progress", 14))

        self._validate_target(device, image)

        self._worker_log(
            f"Validação: {device.chip} ↔ {image.chip_hint or 'imagem genérica'} • OK"
        )

        if image.kind == "FULL" and self.erase_var.get():
            self.events.put(("status", "Apagando flash...", WARNING))
            self._worker_log("Executando erase_flash...")

            code, _ = self.runner.run(
                [
                    "--chip",
                    SUPPORTED_CHIPS[device.chip]["esptool_chip"],
                    "--port",
                    port,
                    "--baud",
                    "115200",
                    "erase_flash",
                ],
                timeout=55,
            )

            if code != 0:
                raise RuntimeError("Falha ao apagar a flash.")

            self.events.put(("progress", 28))

        if self.cancel_requested:
            raise RuntimeError("Operação cancelada.")

        self.events.put(("status", "Gravando firmware...", SECONDARY))
        self._worker_log(
            f"Gravando {image.path.name} em 0x{image.address:X}..."
        )

        baud = self.baud_var.get().strip() or "460800"

        code, output = self.runner.run(
            [
                "--chip",
                SUPPORTED_CHIPS[device.chip]["esptool_chip"],
                "--port",
                port,
                "--baud",
                baud,
                "--before",
                "default_reset",
                "--after",
                "hard_reset",
                "write_flash",
                "-z",
                hex(image.address),
                str(image.path),
            ],
            timeout=180,
        )

        if code != 0:
            raise RuntimeError("A gravação falhou. Consulte o log técnico.")

        if "Hash of data verified" in output or "Hash of data verified." in output:
            self._worker_log("Verificação do esptool: hash gravado confirmado.")

        self.events.put(("progress", 82))

        if self.verify_var.get():
            if self.cancel_requested:
                raise RuntimeError("Operação cancelada.")

            self.events.put(("status", "Verificando flash...", SECONDARY))
            self._worker_log("Executando verificação pós-gravação...")

            code, _ = self.runner.run(
                [
                    "--chip",
                    SUPPORTED_CHIPS[device.chip]["esptool_chip"],
                    "--port",
                    port,
                    "--baud",
                    "115200",
                    "verify_flash",
                    hex(image.address),
                    str(image.path),
                ],
                timeout=120,
            )

            if code != 0:
                raise RuntimeError(
                    "A gravação terminou, mas a verificação posterior falhou."
                )

        self.events.put(("progress", 100))
        self.events.put(("status", "Flash concluído e validado", SUCCESS))
        self.events.put(
            (
                "success",
                f"{device.chip} gravado com sucesso.\n\n"
                f"{image.path.name}\n"
                f"SHA-256: {image.sha256}",
            )
        )

    def _validate_target(self, device: DeviceInfo, image: ImageInfo):
        if image.chip_hint and image.chip_hint != device.chip:
            raise RuntimeError(
                f"PROTEÇÃO ATIVADA: firmware para {image.chip_hint}, "
                f"mas a placa conectada é {device.chip}. Gravação bloqueada."
            )

        if image.kind not in ("FULL", "APP"):
            raise RuntimeError("Tipo de imagem não suportado.")

        if image.kind == "FULL" and image.address != 0x0:
            raise RuntimeError("Imagem FULL deve ser gravada em 0x0.")

        if image.kind == "APP" and image.address != 0x10000:
            raise RuntimeError("Imagem APP/OTA deve ser gravada em 0x10000.")

    def cancel_operation(self):
        if not self.busy:
            return

        self.cancel_requested = True
        self.status_var.set("Cancelando operação...")
        self._set_status_color(WARNING)
        self._log("Cancelamento solicitado pelo usuário.")
        self.runner.cancel()

    def _start_worker(self, target, status: str, flash_mode: bool = False):
        if self.busy:
            return

        self.busy = True
        self.cancel_requested = False
        self.flash_button.configure(state="disabled")
        self.cancel_button.configure(state="normal")
        self.port_combo.configure(state="disabled")
        self.progress["value"] = 0
        self.status_var.set(status)
        self._set_status_color(SECONDARY)

        def wrapper():
            try:
                target()
            except Exception as exc:
                if self.cancel_requested:
                    self.events.put(("status", "Operação cancelada", WARNING))
                    self.events.put(("log", f"Cancelado: {exc}"))
                else:
                    self.events.put(("status", "Falha", ERROR))
                    self.events.put(("log", f"ERRO: {exc}"))
                    self.events.put(("error", str(exc)))
            finally:
                self.events.put(("busy", False))

        threading.Thread(target=wrapper, daemon=True).start()

    def _emit_worker_log(self, line: str):
        self.events.put(("log", line))
        match = re.search(r"Writing at 0x[0-9a-fA-F]+.*\((\d+)\s*%\)", line)
        if match:
            percent = int(match.group(1))
            mapped = 28 + int(percent * 0.52)
            self.events.put(("progress", min(80, mapped)))

    def _worker_log(self, text: str):
        self.events.put(("log", text))

    def _drain_events(self):
        try:
            while True:
                event = self.events.get_nowait()
                kind = event[0]

                if kind == "log":
                    self._log(event[1])

                elif kind == "progress":
                    self.progress["value"] = event[1]

                elif kind == "status":
                    self.status_var.set(event[1])
                    self._set_status_color(event[2])

                elif kind == "device":
                    device: DeviceInfo = event[1]
                    self.detected_device = device
                    self.device_var.set(device.chip)
                    self.flash_var.set(device.flash_text)
                    self.mac_var.set(device.mac)
                    self._log(
                        f"Dispositivo: {device.chip} • {device.port} • "
                        f"flash {device.flash_text}"
                    )

                elif kind == "busy":
                    self.busy = event[1]
                    if not self.busy:
                        self.flash_button.configure(state="normal")
                        self.cancel_button.configure(state="disabled")
                        self.port_combo.configure(state="readonly")

                elif kind == "error":
                    messagebox.showerror(APP_NAME, event[1])

                elif kind == "success":
                    messagebox.showinfo(APP_NAME, event[1])

        except queue.Empty:
            pass

        self.root.after(100, self._drain_events)

    def _set_status_color(self, color: str):
        self.status_dot.configure(fg=color)

    def _log(self, text: str):
        timestamp = time.strftime("%H:%M:%S")
        self.log.insert("end", f"[{timestamp}] {text}\n")
        self.log.see("end")

    def clear_log(self):
        self.log.delete("1.0", "end")

    def _on_close(self):
        if self.busy:
            if not messagebox.askyesno(
                APP_NAME,
                "Há uma operação em andamento. Cancelar e fechar?",
            ):
                return
            self.cancel_operation()

        self.root.after(150, self.root.destroy)


def main():
    root = tk.Tk()
    app = MultiFlasherApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
