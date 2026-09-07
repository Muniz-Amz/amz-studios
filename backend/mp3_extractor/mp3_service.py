"""Download e conversão de áudio em MP3, sem dependências do bot Discord."""

from __future__ import annotations

import base64
import binascii
import os
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import imageio_ffmpeg

try:
    from yt_dlp import YoutubeDL
except ImportError:  # pragma: no cover - tratado também em execução
    YoutubeDL = None


SUPPORTED_DOMAINS = ("youtube.com", "youtu.be", "tiktok.com", "instagram.com")


class Mp3DownloadError(Exception):
    """Erro seguro para exibir a quem solicitou a extração."""


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))


@dataclass(frozen=True)
class Mp3Limits:
    max_output_mb: int = _env_int("AMZ_MP3_MAX_OUTPUT_MB", 50, 5, 200)
    max_seconds: int = _env_int("AMZ_MP3_MAX_SECONDS", 300, 30, 1800)
    timeout_seconds: int = _env_int("AMZ_MP3_TIMEOUT_SECONDS", 360, 60, 900)
    retries: int = _env_int("AMZ_MP3_RETRIES", 2, 1, 5)
    concurrent_fragments: int = _env_int("AMZ_MP3_CONCURRENT_FRAGMENTS", 2, 1, 4)

    @property
    def max_output_bytes(self) -> int:
        return self.max_output_mb * 1024 * 1024


class Mp3DownloadService:
    def __init__(self, limits: Mp3Limits | None = None):
        self.limits = limits or Mp3Limits()
        self.ffmpeg = os.getenv("FFMPEG_BINARY", "").strip() or shutil.which("ffmpeg") or imageio_ffmpeg.get_ffmpeg_exe()

    def ytdlp_version(self) -> str:
        if YoutubeDL is None:
            return "indisponível"
        try:
            from yt_dlp.version import __version__
            return __version__
        except Exception:
            return "desconhecida"

    @staticmethod
    def _host_is_supported(host: str) -> bool:
        normalized = host.lower().strip(".")
        return any(normalized == domain or normalized.endswith(f".{domain}") for domain in SUPPORTED_DOMAINS)

    def validate_url(self, value: str) -> str:
        url = str(value or "").strip()
        if not url or len(url) > 2048:
            raise Mp3DownloadError("Envie um link público válido.")

        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise Mp3DownloadError("Envie um link válido começando com http ou https.")

        try:
            port = parsed.port
        except ValueError as error:
            raise Mp3DownloadError("Esse link usa uma porta inválida.") from error

        if port not in {None, 80, 443}:
            raise Mp3DownloadError("Esse link usa uma porta não permitida.")

        if not self._host_is_supported(parsed.hostname):
            raise Mp3DownloadError("Use um link público do YouTube, TikTok ou Instagram.")

        return parsed.geturl()

    def _cookies_file(self, temp_dir: str) -> Path | None:
        path_value = os.getenv("AMZ_MP3_YTDLP_COOKIES_PATH", "").strip()
        if path_value:
            path = Path(path_value)
            if not path.exists():
                raise Mp3DownloadError("Os cookies configurados para o MP3 não foram encontrados no servidor.")
            return path

        encoded = os.getenv("AMZ_MP3_YTDLP_COOKIES_B64", "").strip()
        if not encoded:
            return None

        try:
            content = base64.b64decode(encoded.encode("utf-8"), validate=True)
        except (ValueError, binascii.Error) as error:
            raise Mp3DownloadError("Os cookies configurados para o MP3 são inválidos.") from error

        destination = Path(temp_dir) / "cookies.txt"
        destination.write_bytes(content)
        return destination

    @staticmethod
    def _public_error(error: Exception) -> str:
        text = " ".join(line.strip() for line in str(error).splitlines() if line.strip())
        lower = text.lower()

        if "video longo demais" in lower or "vídeo longo demais" in lower or "duration" in lower and "long" in lower:
            return "O conteúdo é longo demais para este servidor. Tente um vídeo de até 5 minutos."
        if "sign in to confirm" in lower or "confirm you're not a bot" in lower or "not a bot" in lower:
            return "A plataforma pediu uma verificação. Tente outro conteúdo público."
        if "private video" in lower or "this video is private" in lower or "login required" in lower:
            return "Esse conteúdo é privado ou exige login. Use um link público."
        if "unsupported url" in lower:
            return "Esse tipo de link ainda não é suportado."
        if "requested format is not available" in lower:
            return "A plataforma não liberou um formato de áudio compatível para esse conteúdo."
        if "file is larger than max-filesize" in lower or "larger than max" in lower:
            return "O arquivo ficou grande demais para este servidor."
        if any(term in lower for term in (
            "unexpected_eof_while_reading",
            "eof occurred in violation of protocol",
            "connection reset",
            "connection aborted",
            "read timed out",
            "timed out",
            "remote end closed connection",
            "ssl:",
        )):
            return "A conexão com a plataforma falhou temporariamente. Tente novamente em alguns segundos."
        if "http error 429" in lower or "too many requests" in lower:
            return "A plataforma limitou o servidor por alguns instantes. Tente novamente mais tarde."
        if "yt-dlp -u" in lower or "latest version" in lower:
            return "O servidor precisa atualizar o yt-dlp. Aguarde o próximo deploy e tente novamente."

        return "Não consegui extrair o áudio desse link agora. Tente outro conteúdo público."

    @staticmethod
    def _is_temporary_error(error: Exception) -> bool:
        text = " ".join(line.strip() for line in str(error).splitlines() if line.strip()).lower()
        return any(term in text for term in (
            "unexpected_eof_while_reading",
            "eof occurred in violation of protocol",
            "connection reset",
            "connection aborted",
            "read timed out",
            "timed out",
            "temporarily unavailable",
            "remote end closed connection",
            "ssl:",
            "http error 429",
        ))

    @staticmethod
    def _platform_options(url: str) -> dict:
        host = (urlparse(url).hostname or "").lower()
        options: dict = {}

        if host == "tiktok.com" or host.endswith(".tiktok.com"):
            options["http_headers"] = {
                "User-Agent": (
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
                ),
                "Referer": "https://www.tiktok.com/",
            }
            impersonate = os.getenv("AMZ_MP3_TIKTOK_IMPERSONATE", "").strip()
        elif host == "youtu.be" or host.endswith(".youtube.com"):
            options["http_headers"] = {
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
                ),
                "Referer": "https://www.youtube.com/",
            }
            impersonate = os.getenv("AMZ_MP3_YOUTUBE_IMPERSONATE", "").strip()
        else:
            impersonate = os.getenv("AMZ_MP3_INSTAGRAM_IMPERSONATE", "").strip()

        global_impersonate = os.getenv("AMZ_MP3_YTDLP_IMPERSONATE", "").strip()
        if global_impersonate or impersonate:
            options["impersonate"] = global_impersonate or impersonate

        return options

    @staticmethod
    def _progress_hook(callback):
        def hook(data: dict) -> None:
            if not callback:
                return

            status = data.get("status")
            if status == "downloading":
                downloaded = data.get("downloaded_bytes") or 0
                total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
                if total:
                    percent = min(100, int((downloaded / total) * 100))
                    callback("baixando", 12 + int(percent * 0.52), f"Baixando áudio… {percent}%")
                else:
                    callback("baixando", 32, "Baixando áudio…")
            elif status == "finished":
                callback("convertendo", 68, "Download concluído. Convertendo para MP3…")

        return hook

    def _network_options(self) -> dict:
        options = {
            "socket_timeout": self.limits.timeout_seconds,
            "retries": self.limits.retries,
            "fragment_retries": self.limits.retries,
            "extractor_retries": self.limits.retries,
            "file_access_retries": self.limits.retries,
        }
        if shutil.which("node"):
            options["js_runtimes"] = {"node": {}}
        if os.getenv("AMZ_MP3_FORCE_IPV4", "").strip().lower() in {"1", "true", "yes"}:
            options["force_ipv4"] = True
        return options

    def _run_ytdlp(self, options: dict, url: str, callback) -> None:
        last_error: Exception | None = None
        for attempt in range(1, self.limits.retries + 1):
            try:
                with YoutubeDL(options) as downloader:
                    downloader.extract_info(url, download=True)
                return
            except Exception as error:
                last_error = error
                if attempt < self.limits.retries and self._is_temporary_error(error):
                    if callback:
                        callback("tentando novamente", 10, f"Conexão instável. Tentando novamente ({attempt + 1}/{self.limits.retries})…")
                    time.sleep(min(attempt * 2, 5))

        raise Mp3DownloadError(self._public_error(last_error or RuntimeError("erro desconhecido")))

    def download_audio(self, raw_url: str, temp_dir: str, progress_callback=None) -> Path:
        if YoutubeDL is None:
            raise Mp3DownloadError("A dependência yt-dlp não está instalada no servidor.")

        url = self.validate_url(raw_url)
        if progress_callback:
            progress_callback("validando", 4, "Validando link público…")

        output_template = str(Path(temp_dir) / "audio.%(ext)s")
        cookies_file = self._cookies_file(temp_dir)

        def filter_duration(info_dict, *, incomplete=False):
            if incomplete:
                return None
            duration = info_dict.get("duration")
            if duration and int(duration) > self.limits.max_seconds:
                return "Vídeo longo demais para extrair MP3."
            return None

        options = {
            **self._network_options(),
            **self._platform_options(url),
            "outtmpl": output_template,
            "format": os.getenv("AMZ_MP3_FORMAT", "bestaudio/best"),
            "ffmpeg_location": self.ffmpeg,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "max_filesize": self.limits.max_output_bytes,
            "match_filter": filter_duration,
            "progress_hooks": [self._progress_hook(progress_callback)],
            "concurrent_fragment_downloads": self.limits.concurrent_fragments,
            "cachedir": False,
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": os.getenv("AMZ_MP3_BITRATE", "192"),
            }],
            "overwrites": True,
        }
        if cookies_file:
            options["cookiefile"] = str(cookies_file)

        if progress_callback:
            progress_callback("baixando", 10, "Iniciando download do áudio…")
        self._run_ytdlp(options, url, progress_callback)

        candidates = sorted(
            (item for item in Path(temp_dir).glob("audio.*") if item.is_file() and not item.name.endswith((".part", ".ytdl", ".tmp"))),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )
        output = next((item for item in candidates if item.suffix.lower() == ".mp3"), None)
        if not output:
            raise Mp3DownloadError("Não consegui converter o áudio para MP3.")

        if output.stat().st_size > self.limits.max_output_bytes:
            raise Mp3DownloadError("O arquivo ficou grande demais para este servidor.")

        if progress_callback:
            progress_callback("finalizando", 94, "Validando o MP3 final…")
        return output
