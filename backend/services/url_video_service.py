import binascii
import base64
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import imageio_ffmpeg

try:
    from yt_dlp import YoutubeDL
except ImportError:  # pragma: no cover
    YoutubeDL = None


class UrlVideoError(Exception):
    pass


@dataclass(frozen=True)
class UrlVideoLimits:
    max_output_mb: int = int(os.getenv("AMZ_URLVIDEO_MAX_OUTPUT_MB", os.getenv("AMZ_MEDIA_MAX_OUTPUT_MB", "8")))
    max_seconds: int = int(os.getenv("AMZ_URLVIDEO_MAX_SECONDS", "120"))
    timeout_seconds: int = int(os.getenv("AMZ_URLVIDEO_TIMEOUT_SECONDS", "80"))
    max_width: int = int(os.getenv("AMZ_URLVIDEO_MAX_WIDTH", "720"))

    @property
    def max_output_bytes(self):
        return self.max_output_mb * 1024 * 1024


class UrlVideoService:
    def __init__(self, limits=None):
        self.limits = limits or UrlVideoLimits()
        self.ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

    def _cookies_file(self, temp_dir: str):
        cookies_path = os.getenv("AMZ_YTDLP_COOKIES_PATH", "").strip()
        if cookies_path:
            path = Path(cookies_path)
            return path if path.exists() else None

        cookies_b64 = os.getenv("AMZ_YTDLP_COOKIES_B64", "").strip()
        if not cookies_b64:
            return None

        try:
            conteudo = base64.b64decode(cookies_b64.encode("utf-8"), validate=True)
        except (ValueError, binascii.Error):
            raise UrlVideoError("Cookies invalidos em AMZ_YTDLP_COOKIES_B64.")

        destino = Path(temp_dir) / "cookies.txt"
        destino.write_bytes(conteudo)
        return destino

    def _validar_url(self, url: str):
        parsed = urlparse(str(url or "").strip())
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise UrlVideoError("Envie um link valido (http/https).")
        return parsed.geturl()

    def _run_ffmpeg(self, args):
        comando = [self.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *args]
        try:
            resultado = subprocess.run(
                comando,
                capture_output=True,
                text=True,
                timeout=self.limits.timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as erro:
            raise UrlVideoError("Conversao demorou demais e foi cancelada.") from erro

        if resultado.returncode != 0:
            detalhes = (resultado.stderr or "ffmpeg falhou").strip()[-400:]
            raise UrlVideoError(f"Nao consegui deixar o video compativel com o Discord. {detalhes}")

    def _converter_para_discord(self, input_path: Path, temp_dir: str) -> Path:
        output_path = Path(temp_dir) / "amz-video.mp4"
        scale = f"scale='min({self.limits.max_width},iw)':-2:flags=lanczos"

        self._run_ffmpeg([
            "-i",
            str(input_path),
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-vf",
            scale,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "28",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(output_path),
        ])

        return output_path

    def download_video(self, url: str, temp_dir: str, max_bytes=None) -> Path:
        if YoutubeDL is None:
            raise UrlVideoError("Dependencia `yt-dlp` nao instalada no servidor.")

        url = self._validar_url(url)

        limite_bytes = int(max_bytes or self.limits.max_output_bytes)
        limite_bytes = min(max(limite_bytes, 1), self.limits.max_output_bytes)

        outtmpl = str(Path(temp_dir) / "video.%(ext)s")
        cookies_file = self._cookies_file(temp_dir)

        def filtro_por_duracao(info_dict, *, incomplete=False):
            if incomplete:
                return None
            duracao = info_dict.get("duration")
            if duracao and int(duracao) > int(self.limits.max_seconds):
                return "Video longo demais para este comando."
            return None

        ydl_opts = {
            "outtmpl": outtmpl,
            "format": os.getenv(
                "AMZ_URLVIDEO_FORMAT",
                "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/b[vcodec^=avc1][ext=mp4]/bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
            ),
            "merge_output_format": "mp4",
            "ffmpeg_location": self.ffmpeg,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "retries": 2,
            "fragment_retries": 2,
            "socket_timeout": int(self.limits.timeout_seconds),
            "max_filesize": limite_bytes,
            "match_filter": filtro_por_duracao,
            "overwrites": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        try:
            with YoutubeDL(ydl_opts) as ydl:
                ydl.extract_info(url, download=True)
        except Exception as erro:
            detalhe = str(erro).strip().splitlines()[-1][-400:]
            raise UrlVideoError(f"Nao consegui baixar esse video. {detalhe}")

        candidato = Path(temp_dir) / "video.mp4"
        if not candidato.exists():
            arquivos = sorted(
                (item for item in Path(temp_dir).glob("video.*") if item.is_file() and not item.name.endswith(".part")),
                key=lambda item: item.stat().st_mtime,
                reverse=True,
            )
            if arquivos:
                candidato = arquivos[0]

        if not candidato.exists():
            raise UrlVideoError("Nao consegui gerar o arquivo final do video.")

        convertido = self._converter_para_discord(candidato, temp_dir)
        tamanho = convertido.stat().st_size
        if tamanho > limite_bytes:
            limite_mb = round(limite_bytes / (1024 * 1024), 2)
            tamanho_mb = round(tamanho / (1024 * 1024), 2)
            raise UrlVideoError(f"Arquivo ficou grande demais ({tamanho_mb} MB). Limite: {limite_mb} MB.")

        return convertido
