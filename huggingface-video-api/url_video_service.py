import base64
import binascii
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import imageio_ffmpeg
from PIL import Image, ImageStat

try:
    from yt_dlp import YoutubeDL
except ImportError:  # pragma: no cover
    YoutubeDL = None


class UrlVideoError(Exception):
    pass


@dataclass(frozen=True)
class UrlVideoLimits:
    max_output_mb: int = int(os.getenv("AMZ_URLVIDEO_MAX_OUTPUT_MB", "50"))
    max_seconds: int = int(os.getenv("AMZ_URLVIDEO_MAX_SECONDS", "300"))
    timeout_seconds: int = int(os.getenv("AMZ_URLVIDEO_TIMEOUT_SECONDS", "420"))
    max_width: int = int(os.getenv("AMZ_URLVIDEO_MAX_WIDTH", "720"))
    fps: int = int(os.getenv("AMZ_URLVIDEO_FPS", "24"))

    @property
    def max_output_bytes(self):
        return self.max_output_mb * 1024 * 1024


class UrlVideoService:
    def __init__(self, limits=None):
        self.limits = limits or UrlVideoLimits()
        self.ffmpeg = os.getenv("FFMPEG_BINARY", "").strip() or imageio_ffmpeg.get_ffmpeg_exe()

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

    def analisar_url(self, url: str) -> dict:
        if YoutubeDL is None:
            raise UrlVideoError("Dependencia yt-dlp nao instalada no servidor.")

        url = self._validar_url(url)
        temp_dir = tempfile.mkdtemp(prefix="amz-video-check-")
        cookies_file = self._cookies_file(temp_dir)
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "skip_download": True,
            "socket_timeout": int(self.limits.timeout_seconds),
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        try:
            try:
                with YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=False)
            except Exception as erro:
                detalhe = str(erro).strip().splitlines()[-1][-400:]
                raise UrlVideoError(f"Nao consegui verificar esse link. {detalhe}") from erro

            duracao = info.get("duration") if isinstance(info, dict) else None
            return {
                "url": url,
                "titulo": (info.get("title") if isinstance(info, dict) else None) or "Video",
                "duracao_segundos": int(duracao) if duracao else None,
                "limite_segundos": int(self.limits.max_seconds),
                "permitido": not duracao or int(duracao) <= int(self.limits.max_seconds),
                "origem": (info.get("extractor_key") if isinstance(info, dict) else None) or "",
            }
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)

    def _rotulo_limite_tempo(self):
        segundos = int(self.limits.max_seconds)
        if segundos >= 60 and segundos % 60 == 0:
            minutos = segundos // 60
            return f"{minutos} minuto" if minutos == 1 else f"{minutos} minutos"
        return f"{segundos}s"

    def _notificar_progresso(self, progress_callback, etapa, progresso, mensagem):
        if not progress_callback:
            return

        try:
            progress_callback(etapa, max(0, min(int(progresso), 100)), mensagem)
        except Exception:
            pass

    def _criar_hook_download(self, progress_callback):
        def hook(dados):
            status = dados.get("status")

            if status == "downloading":
                baixado = dados.get("downloaded_bytes") or 0
                total = dados.get("total_bytes") or dados.get("total_bytes_estimate") or 0
                if total:
                    progresso = 12 + int((baixado / total) * 48)
                    mensagem = f"Baixando arquivo... {min(100, int((baixado / total) * 100))}%"
                else:
                    progresso = 30
                    mensagem = "Baixando arquivo..."

                self._notificar_progresso(progress_callback, "baixando", progresso, mensagem)
            elif status == "finished":
                self._notificar_progresso(progress_callback, "convertendo", 66, "Download concluido. Convertendo arquivo...")

        return hook

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
            raise UrlVideoError(f"Nao consegui converter esse arquivo. {detalhes}")

    def _extrair_frame(self, input_path: Path, temp_dir: str, segundos: float):
        output_path = Path(temp_dir) / f"frame-{str(segundos).replace('.', '_')}.jpg"
        comando = [
            self.ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{segundos:.2f}",
            "-i",
            str(input_path),
            "-frames:v",
            "1",
            "-q:v",
            "3",
            str(output_path),
        ]

        resultado = subprocess.run(
            comando,
            capture_output=True,
            text=True,
            timeout=min(self.limits.timeout_seconds, 20),
            check=False,
        )

        if resultado.returncode != 0 or not output_path.exists():
            return None

        return output_path

    def _frame_tem_conteudo(self, frame_path: Path) -> bool:
        with Image.open(frame_path) as imagem:
            imagem = imagem.convert("RGB")
            imagem.thumbnail((96, 96))
            estatistica = ImageStat.Stat(imagem)

        brilho = sum(estatistica.mean) / len(estatistica.mean)
        contraste = max(estatistica.stddev)
        return brilho >= 18 or contraste >= 14

    def _inicio_com_frame_visivel(self, input_path: Path, temp_dir: str) -> float:
        for segundos in (0.0, 0.25, 0.5, 0.8, 1.2, 1.8, 2.5):
            try:
                frame = self._extrair_frame(input_path, temp_dir, segundos)
                if frame and self._frame_tem_conteudo(frame):
                    return segundos
            except Exception:
                continue

        return 0.0

    def _converter_para_mp4(self, input_path: Path, temp_dir: str, max_width=None, progress_callback=None) -> Path:
        output_path = Path(temp_dir) / "amz-video.mp4"
        largura = int(max_width or self.limits.max_width)
        max_width = max(2, (largura // 2) * 2)
        fps = min(max(int(self.limits.fps), 10), 60)
        video_filter = (
            f"fps={fps},"
            f"scale='min({max_width},trunc(iw/2)*2)':-2:flags=lanczos,"
            "setpts=PTS-STARTPTS"
        )
        inicio = self._inicio_com_frame_visivel(input_path, temp_dir)

        args = [
            "-fflags",
            "+genpts",
            "-i",
            str(input_path),
        ]
        if inicio > 0:
            args.extend(["-ss", f"{inicio:.2f}"])

        args.extend([
            "-map",
            "0:v:0",
            "-map",
            "0:a:0?",
            "-sn",
            "-dn",
            "-vf",
            video_filter,
            "-af",
            "aresample=async=1:first_pts=0",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-crf",
            "30",
            "-tune",
            "fastdecode",
            "-profile:v",
            "baseline",
            "-level",
            "3.1",
            "-x264-params",
            "keyint=60:min-keyint=30:scenecut=0",
            "-tag:v",
            "avc1",
            "-pix_fmt",
            "yuv420p",
            "-fps_mode",
            "cfr",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-ar",
            "44100",
            "-ac",
            "2",
            "-video_track_timescale",
            "90000",
            "-avoid_negative_ts",
            "make_zero",
            "-map_metadata",
            "-1",
            "-map_chapters",
            "-1",
            "-movflags",
            "+faststart",
            str(output_path),
        ])
        self._notificar_progresso(progress_callback, "convertendo", 72, "Convertendo para MP4 compativel...")
        self._run_ffmpeg(args)

        return output_path

    def _validar_tamanho_saida(self, path: Path, limite_bytes: int):
        tamanho = path.stat().st_size
        if tamanho <= limite_bytes:
            return

        limite_mb = round(limite_bytes / (1024 * 1024), 2)
        tamanho_mb = round(tamanho / (1024 * 1024), 2)
        raise UrlVideoError(f"Arquivo ficou grande demais ({tamanho_mb} MB). Limite: {limite_mb} MB.")

    def download_video(self, url: str, temp_dir: str, max_bytes=None, max_width=None, progress_callback=None) -> Path:
        if YoutubeDL is None:
            raise UrlVideoError("Dependencia yt-dlp nao instalada no servidor.")

        self._notificar_progresso(progress_callback, "validando", 4, "Validando link...")
        url = self._validar_url(url)

        limite_bytes = int(max_bytes or self.limits.max_output_bytes)
        limite_bytes = min(max(limite_bytes, 1), self.limits.max_output_bytes)

        outtmpl = str(Path(temp_dir) / "video.%(ext)s")
        cookies_file = self._cookies_file(temp_dir)
        self._notificar_progresso(progress_callback, "baixando", 10, "Iniciando download do video...")

        def filtro_por_duracao(info_dict, *, incomplete=False):
            if incomplete:
                return None
            duracao = info_dict.get("duration")
            if duracao and int(duracao) > int(self.limits.max_seconds):
                return f"Video longo demais para este servidor. Limite atual: {self._rotulo_limite_tempo()}."
            return None

        ydl_opts = {
            "outtmpl": outtmpl,
            "format": os.getenv(
                "AMZ_URLVIDEO_FORMAT",
                "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b",
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
            "progress_hooks": [self._criar_hook_download(progress_callback)],
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

        convertido = self._converter_para_mp4(candidato, temp_dir, max_width=max_width, progress_callback=progress_callback)
        self._notificar_progresso(progress_callback, "finalizando", 94, "Validando tamanho final...")
        self._validar_tamanho_saida(convertido, limite_bytes)

        return convertido

    def download_audio(self, url: str, temp_dir: str, max_bytes=None, progress_callback=None) -> Path:
        if YoutubeDL is None:
            raise UrlVideoError("Dependencia yt-dlp nao instalada no servidor.")

        self._notificar_progresso(progress_callback, "validando", 4, "Validando link...")
        url = self._validar_url(url)

        limite_bytes = int(max_bytes or self.limits.max_output_bytes)
        limite_bytes = min(max(limite_bytes, 1), self.limits.max_output_bytes)

        outtmpl = str(Path(temp_dir) / "audio.%(ext)s")
        cookies_file = self._cookies_file(temp_dir)
        self._notificar_progresso(progress_callback, "baixando", 10, "Iniciando download do audio...")

        def filtro_por_duracao(info_dict, *, incomplete=False):
            if incomplete:
                return None
            duracao = info_dict.get("duration")
            if duracao and int(duracao) > int(self.limits.max_seconds):
                return f"Video longo demais para extrair MP3. Limite atual: {self._rotulo_limite_tempo()}."
            return None

        ydl_opts = {
            "outtmpl": outtmpl,
            "format": os.getenv("AMZ_URLAUDIO_FORMAT", "ba/b"),
            "ffmpeg_location": self.ffmpeg,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "retries": 2,
            "fragment_retries": 2,
            "socket_timeout": int(self.limits.timeout_seconds),
            "match_filter": filtro_por_duracao,
            "progress_hooks": [self._criar_hook_download(progress_callback)],
            "overwrites": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        try:
            with YoutubeDL(ydl_opts) as ydl:
                ydl.extract_info(url, download=True)
        except Exception as erro:
            detalhe = str(erro).strip().splitlines()[-1][-400:]
            raise UrlVideoError(f"Nao consegui baixar o audio desse link. {detalhe}")

        arquivos = sorted(
            (item for item in Path(temp_dir).glob("audio.*") if item.is_file() and not item.name.endswith(".part")),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )

        if not arquivos:
            raise UrlVideoError("Nao consegui gerar o arquivo de audio.")

        output_path = Path(temp_dir) / "amz-audio.mp3"
        self._notificar_progresso(progress_callback, "convertendo", 72, "Convertendo para MP3...")
        self._run_ffmpeg([
            "-i",
            str(arquivos[0]),
            "-vn",
            "-b:a",
            "128k",
            "-map",
            "0:a:0?",
            str(output_path),
        ])
        self._notificar_progresso(progress_callback, "finalizando", 94, "Validando tamanho final...")
        self._validar_tamanho_saida(output_path, limite_bytes)

        return output_path
