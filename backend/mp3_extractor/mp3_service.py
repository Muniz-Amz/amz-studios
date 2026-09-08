"""Download e conversão de áudio em MP3, sem dependências do bot Discord."""

from __future__ import annotations

import json
import math
import os
import queue
import re
import shutil
import subprocess
import threading
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

# O nome do arquivo nunca é usado como prova de que ele é mídia. A extensão e
# o MIME informado pelo navegador são apenas a primeira barreira; o conteúdo é
# confirmado via ffprobe antes de ocupar uma vaga na fila.
SUPPORTED_UPLOAD_EXTENSIONS = frozenset({
    ".3gp",
    ".aac",
    ".aiff",
    ".avi",
    ".flac",
    ".m4a",
    ".m4v",
    ".mkv",
    ".mov",
    ".mp3",
    ".mp4",
    ".mpeg",
    ".mpg",
    ".oga",
    ".ogg",
    ".opus",
    ".wav",
    ".webm",
    ".wma",
    ".wmv",
})
GENERIC_UPLOAD_MIMETYPES = frozenset({
    "application/octet-stream",
    "application/ogg",
    "application/x-ogg",
})


class Mp3DownloadError(Exception):
    """Erro seguro para exibir a quem solicitou a extração."""


class Mp3UploadTooLargeError(Mp3DownloadError):
    """Upload excedeu o limite de disco antes de chegar ao conversor."""


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))


@dataclass(frozen=True)
class Mp3Limits:
    max_output_mb: int = _env_int("AMZ_MP3_MAX_OUTPUT_MB", 50, 5, 200)
    # O upload e o resultado têm o mesmo teto por padrão: isso mantém duas
    # cópias temporárias dentro de um uso previsível para o plano free.
    max_upload_mb: int = _env_int("AMZ_MP3_MAX_UPLOAD_MB", 50, 5, 75)
    max_seconds: int = _env_int("AMZ_MP3_MAX_SECONDS", 300, 30, 1800)
    timeout_seconds: int = _env_int("AMZ_MP3_TIMEOUT_SECONDS", 360, 60, 900)
    retries: int = _env_int("AMZ_MP3_RETRIES", 2, 1, 5)
    concurrent_fragments: int = _env_int("AMZ_MP3_CONCURRENT_FRAGMENTS", 2, 1, 4)

    @property
    def max_output_bytes(self) -> int:
        return self.max_output_mb * 1024 * 1024

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024


@dataclass(frozen=True)
class UploadMetadata:
    """Metadados seguros do arquivo enviado pelo navegador."""

    source_filename: str
    output_filename: str
    extension: str
    mimetype: str


@dataclass(frozen=True)
class UploadedMediaInfo:
    """Resultado da validação do conteúdo real feita pelo ffprobe."""

    duration_seconds: float
    format_name: str


class Mp3DownloadService:
    def __init__(self, limits: Mp3Limits | None = None):
        self.limits = limits or Mp3Limits()
        self.ffmpeg = os.getenv("FFMPEG_BINARY", "").strip() or shutil.which("ffmpeg") or imageio_ffmpeg.get_ffmpeg_exe()
        ffmpeg_path = Path(self.ffmpeg)
        self.ffprobe = (
            os.getenv("FFPROBE_BINARY", "").strip()
            or shutil.which("ffprobe")
            or str(ffmpeg_path.with_name("ffprobe" + ffmpeg_path.suffix))
        )

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

    def validate_upload_metadata(self, filename: str, mimetype: str | None) -> UploadMetadata:
        """Valida os metadados enviados pelo navegador antes de gravar o arquivo.

        Esta etapa evita receber tipos obviamente incorretos, mas não confia no
        nome ou no cabeçalho do cliente: ``probe_uploaded_media`` confirma o
        conteúdo real antes de o job entrar na fila.
        """

        raw_name = str(filename or "").replace("\\", "/").rsplit("/", 1)[-1].strip()
        raw_name = "".join(char for char in raw_name if char.isprintable())[:180]
        if not raw_name:
            raise Mp3DownloadError("Selecione um arquivo de áudio ou vídeo válido.")

        extension = Path(raw_name).suffix.lower()
        if extension not in SUPPORTED_UPLOAD_EXTENSIONS:
            raise Mp3DownloadError(
                "Formato não aceito. Envie áudio ou vídeo em MP3, M4A, WAV, MP4, MOV, WebM ou formato parecido."
            )

        normalized_mime = str(mimetype or "").split(";", 1)[0].strip().lower()
        if normalized_mime and not (
            normalized_mime.startswith("audio/")
            or normalized_mime.startswith("video/")
            or normalized_mime in GENERIC_UPLOAD_MIMETYPES
        ):
            raise Mp3DownloadError("Esse arquivo não foi identificado como áudio ou vídeo.")

        stem = Path(raw_name).stem.strip()
        safe_stem = re.sub(r"[^\w .()\-]+", "_", stem, flags=re.UNICODE).strip(" ._")[:80] or "amz-audio"
        return UploadMetadata(
            source_filename=raw_name,
            output_filename=f"{safe_stem}.mp3",
            extension=extension,
            mimetype=normalized_mime,
        )

    def probe_uploaded_media(self, input_path: str | Path) -> UploadedMediaInfo:
        """Confirma que o arquivo local contém uma faixa de áudio permitida."""

        source = Path(input_path)
        if not source.is_file():
            raise Mp3DownloadError("O arquivo enviado não foi encontrado para conversão.")
        if not self.ffprobe or not Path(self.ffprobe).is_file() and not shutil.which(self.ffprobe):
            raise Mp3DownloadError("O servidor não está pronto para validar arquivos enviados.")

        command = [
            self.ffprobe,
            "-v",
            "error",
            "-probesize",
            "5M",
            "-analyzeduration",
            "5M",
            "-show_entries",
            "format=format_name,duration:stream=codec_type,duration",
            "-of",
            "json",
            str(source),
        ]
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=min(45, self.limits.timeout_seconds),
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            print(f"[MP3] ffprobe falhou: {type(error).__name__}: {str(error)[-300:]}")
            raise Mp3DownloadError("Não consegui validar esse arquivo de mídia.") from error

        if completed.returncode != 0:
            print(f"[MP3] ffprobe recusou upload: {completed.stderr[-500:]}")
            raise Mp3DownloadError("Esse arquivo não contém um áudio ou vídeo compatível.")

        try:
            probe = json.loads(completed.stdout or "{}")
        except json.JSONDecodeError as error:
            raise Mp3DownloadError("Não consegui identificar o conteúdo desse arquivo.") from error

        streams = probe.get("streams") if isinstance(probe, dict) else None
        audio_streams = [item for item in (streams or []) if isinstance(item, dict) and item.get("codec_type") == "audio"]
        if not audio_streams:
            raise Mp3DownloadError("Esse vídeo não possui uma faixa de áudio para converter.")

        duration_candidates = []
        format_data = probe.get("format") if isinstance(probe, dict) else None
        if isinstance(format_data, dict):
            duration_candidates.append(format_data.get("duration"))
        duration_candidates.extend(stream.get("duration") for stream in audio_streams)

        duration = 0.0
        for value in duration_candidates:
            try:
                candidate = float(value)
            except (TypeError, ValueError):
                continue
            if math.isfinite(candidate) and candidate > duration:
                duration = candidate

        if duration <= 0:
            raise Mp3DownloadError("Não consegui identificar a duração desse arquivo. Envie um arquivo de mídia completo.")
        if duration > self.limits.max_seconds:
            minutes = max(1, round(self.limits.max_seconds / 60))
            raise Mp3DownloadError(f"O arquivo é longo demais. Envie uma mídia de até {minutes} minuto(s).")

        format_name = ""
        if isinstance(format_data, dict):
            format_name = str(format_data.get("format_name") or "")[:120]
        return UploadedMediaInfo(duration_seconds=duration, format_name=format_name)

    def convert_uploaded_media(
        self,
        input_path: str | Path,
        temp_dir: str | Path,
        duration_seconds: float,
        progress_callback=None,
    ) -> Path:
        """Converte uma mídia já validada para MP3 sem chamar plataformas externas."""

        workspace = Path(temp_dir).resolve()
        source = Path(input_path).resolve()
        try:
            source.relative_to(workspace)
        except ValueError as error:
            raise Mp3DownloadError("O arquivo enviado não está em uma área temporária segura.") from error

        if not source.is_file():
            raise Mp3DownloadError("O arquivo enviado não foi encontrado para conversão.")
        if source.stat().st_size > self.limits.max_upload_bytes:
            raise Mp3DownloadError("O arquivo enviado ultrapassa o limite permitido.")

        try:
            duration = float(duration_seconds)
        except (TypeError, ValueError) as error:
            raise Mp3DownloadError("Não consegui identificar a duração desse arquivo.") from error
        if not math.isfinite(duration) or duration <= 0 or duration > self.limits.max_seconds:
            raise Mp3DownloadError("A duração desse arquivo não é permitida para conversão.")

        output = workspace / "audio.mp3"
        bitrate = os.getenv("AMZ_MP3_BITRATE", "192").strip()
        if not bitrate.isdigit() or not 32 <= int(bitrate) <= 320:
            bitrate = "192"

        command = [
            self.ffmpeg,
            "-hide_banner",
            "-nostdin",
            "-y",
            "-i",
            str(source),
            # Defesa adicional caso um arquivo malformado informe duração
            # incorreta durante a sondagem.
            "-t",
            str(self.limits.max_seconds),
            "-map",
            "0:a:0",
            "-vn",
            "-map_metadata",
            "-1",
            "-c:a",
            "libmp3lame",
            "-b:a",
            f"{bitrate}k",
            "-progress",
            "pipe:1",
            "-nostats",
            "-loglevel",
            "error",
            str(output),
        ]

        if progress_callback:
            progress_callback("convertendo", 12, "Convertendo seu arquivo para MP3…")

        try:
            process = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
        except OSError as error:
            print(f"[MP3] ffmpeg não iniciou: {type(error).__name__}: {str(error)[-300:]}")
            raise Mp3DownloadError("O conversor de MP3 não está disponível no servidor.") from error

        # O ffmpeg costuma emitir poucas linhas de progresso, mas a fila é
        # limitada para um arquivo malformado nunca acumular texto na memória.
        lines: queue.Queue[str | None] = queue.Queue(maxsize=128)

        def read_output() -> None:
            try:
                if process.stdout:
                    for line in iter(process.stdout.readline, ""):
                        try:
                            lines.put(line, timeout=0.5)
                        except queue.Full:
                            continue
            finally:
                try:
                    lines.put_nowait(None)
                except queue.Full:
                    pass

        reader = threading.Thread(target=read_output, name="amz-mp3-ffmpeg", daemon=True)
        reader.start()
        deadline = time.monotonic() + self.limits.timeout_seconds
        last_progress = 12
        output_tail: list[str] = []

        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    process.kill()
                    raise Mp3DownloadError("A conversão demorou demais. Tente um arquivo menor.")

                try:
                    line = lines.get(timeout=min(1.0, remaining))
                except queue.Empty:
                    if process.poll() is not None and not reader.is_alive():
                        break
                    continue

                if line is None:
                    break

                normalized = line.strip()
                if normalized:
                    output_tail.append(normalized)
                    if len(output_tail) > 20:
                        output_tail.pop(0)

                if not normalized.startswith("out_time_us="):
                    continue
                try:
                    processed_seconds = float(normalized.split("=", 1)[1]) / 1_000_000
                except (TypeError, ValueError):
                    continue

                progress = min(94, max(12, 12 + int((processed_seconds / duration) * 82)))
                if progress > last_progress:
                    last_progress = progress
                    if progress_callback:
                        progress_callback("convertendo", progress, f"Convertendo seu arquivo para MP3… {min(99, round((processed_seconds / duration) * 100))}%")

            return_code = process.wait(timeout=5)
        except Mp3DownloadError:
            if process.poll() is None:
                process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            raise
        except Exception as error:
            if process.poll() is None:
                process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            print(f"[MP3] ffmpeg falhou: {type(error).__name__}: {str(error)[-300:]}")
            raise Mp3DownloadError("Não consegui converter esse arquivo para MP3.") from error
        finally:
            reader.join(timeout=1)
            if process.stdout:
                process.stdout.close()

        if return_code != 0 or not output.is_file():
            detail = " | ".join(output_tail)[-700:]
            print(f"[MP3] conversão de upload falhou (código {return_code}): {detail}")
            output.unlink(missing_ok=True)
            raise Mp3DownloadError("Não consegui converter esse arquivo para MP3.")

        if output.stat().st_size <= 0:
            output.unlink(missing_ok=True)
            raise Mp3DownloadError("O MP3 gerado ficou vazio. Tente outro arquivo.")
        if output.stat().st_size > self.limits.max_output_bytes:
            output.unlink(missing_ok=True)
            raise Mp3DownloadError("O MP3 ficou grande demais para este servidor.")

        if progress_callback:
            progress_callback("finalizando", 94, "Validando o MP3 final…")
        return output

    @staticmethod
    def _public_error(error: Exception) -> str:
        text = " ".join(line.strip() for line in str(error).splitlines() if line.strip())
        lower = text.lower()

        if "video longo demais" in lower or "vídeo longo demais" in lower or "duration" in lower and "long" in lower:
            return "O conteúdo é longo demais para este servidor. Tente um vídeo de até 5 minutos."
        if "sign in to confirm" in lower or "confirm you're not a bot" in lower or "not a bot" in lower:
            return (
                "O YouTube exigiu verificação de conta para este vídeo. "
                "Esse bloqueio é da própria plataforma; tente outro conteúdo público."
            )
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
        # Uma exigência de login/anti-bot não melhora com nova tentativa e não
        # deve consumir fila, CPU ou novas requisições ao YouTube.
        if any(term in text for term in (
            "sign in to confirm",
            "confirm you're not a bot",
            "not a bot",
        )):
            return False
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
            # Um alvo específico pode ser configurado no Render quando ele for
            # compatível com a versão instalada de curl-cffi/yt-dlp.
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
            # Falhas de extração de metadados tendem a ser determinísticas
            # (conteúdo privado, bloqueio ou verificação). A repetição de rede
            # continua coberta por `retries` e pelo retry externo controlado.
            "extractor_retries": 1,
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

        # Detalhe fica somente no log privado do Render; a resposta pública é
        # normalizada abaixo para não expor URLs temporárias ou dados internos.
        print(f"[MP3] yt-dlp falhou: {type(last_error).__name__}: {str(last_error)[-900:]}")
        raise Mp3DownloadError(self._public_error(last_error or RuntimeError("erro desconhecido")))

    def download_audio(self, raw_url: str, temp_dir: str, progress_callback=None) -> Path:
        if YoutubeDL is None:
            raise Mp3DownloadError("A dependência yt-dlp não está instalada no servidor.")

        url = self.validate_url(raw_url)
        if progress_callback:
            progress_callback("validando", 4, "Validando link público…")

        output_template = str(Path(temp_dir) / "audio.%(ext)s")

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
