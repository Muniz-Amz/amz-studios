import os
import subprocess
from dataclasses import dataclass
from pathlib import Path

import imageio_ffmpeg


class MediaError(Exception):
    pass


@dataclass(frozen=True)
class MediaLimits:
    max_input_mb: int = int(os.getenv("AMZ_MEDIA_MAX_INPUT_MB", "8"))
    max_output_mb: int = int(os.getenv("AMZ_MEDIA_MAX_OUTPUT_MB", "8"))
    max_audio_seconds: int = int(os.getenv("AMZ_MEDIA_MAX_AUDIO_SECONDS", "300"))
    ffmpeg_timeout: int = int(os.getenv("AMZ_MEDIA_FFMPEG_TIMEOUT_SECONDS", "180"))

    @property
    def max_input_bytes(self):
        return self.max_input_mb * 1024 * 1024

    @property
    def max_output_bytes(self):
        return self.max_output_mb * 1024 * 1024


VIDEO_EXTENSIONS = {".mp4", ".mov", ".webm", ".mkv", ".avi"}


def nome_seguro(nome):
    return "".join(char if char.isalnum() or char in "._-" else "_" for char in nome)[:80] or "arquivo"


def tipo_anexo(attachment):
    content_type = (attachment.content_type or "").lower()
    extension = Path(attachment.filename or "").suffix.lower()

    if content_type.startswith("video/") or extension in VIDEO_EXTENSIONS:
        return "video"

    return "unknown"


def validar_tamanho_entrada(attachment, limits):
    if attachment.size and attachment.size > limits.max_input_bytes:
        raise MediaError(f"Arquivo grande demais. Limite: {limits.max_input_mb} MB.")


def validar_saida(path, limits):
    tamanho = Path(path).stat().st_size

    if tamanho > limits.max_output_bytes:
        raise MediaError(f"Resultado ficou grande demais. Limite: {limits.max_output_mb} MB.")


class MediaService:
    def __init__(self, limits=None):
        self.limits = limits or MediaLimits()
        self.ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

    def _run_ffmpeg(self, args):
        comando = [self.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *args]
        try:
            resultado = subprocess.run(
                comando,
                capture_output=True,
                text=True,
                timeout=self.limits.ffmpeg_timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as erro:
            raise MediaError("Conversao demorou demais e foi cancelada para proteger o Render.") from erro

        if resultado.returncode != 0:
            detalhes = (resultado.stderr or "ffmpeg falhou").strip()[-500:]
            raise MediaError(f"Nao consegui converter esse arquivo. {detalhes}")

    def video_para_audio(self, input_path, output_path):
        self._run_ffmpeg([
            "-t",
            str(self.limits.max_audio_seconds),
            "-i",
            str(input_path),
            "-vn",
            "-b:a",
            "128k",
            "-map",
            "a:0",
            str(output_path),
        ])
        validar_saida(output_path, self.limits)
