import binascii
import base64
import os
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import imageio_ffmpeg
from PIL import Image, ImageStat

try:
    from yt_dlp import YoutubeDL
except ImportError:  # pragma: no cover
    YoutubeDL = None

try:
    from yt_dlp.networking.impersonate import ImpersonateTarget
except ImportError:  # pragma: no cover
    ImpersonateTarget = None


class UrlVideoError(Exception):
    pass


@dataclass(frozen=True)
class UrlVideoLimits:
    max_output_mb: int = int(os.getenv("AMZ_URLVIDEO_MAX_OUTPUT_MB", os.getenv("AMZ_MEDIA_MAX_OUTPUT_MB", "8")))
    max_seconds: int = int(os.getenv("AMZ_URLVIDEO_MAX_SECONDS", "90"))
    timeout_seconds: int = int(os.getenv("AMZ_URLVIDEO_TIMEOUT_SECONDS", "150"))
    retries: int = int(os.getenv("AMZ_URLVIDEO_RETRIES", "2"))
    max_width: int = int(os.getenv("AMZ_URLVIDEO_MAX_WIDTH", "540"))
    fps: int = int(os.getenv("AMZ_URLVIDEO_FPS", "24"))

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
            if not path.exists():
                raise UrlVideoError("Arquivo de cookies configurado em AMZ_YTDLP_COOKIES_PATH nao foi encontrado.")
            return path

        cookies_b64 = os.getenv("AMZ_YTDLP_COOKIES_B64", "").strip()
        cookies_text = os.getenv("AMZ_YTDLP_COOKIES_TEXT", "").strip()

        if cookies_b64:
            try:
                conteudo = base64.b64decode(cookies_b64.encode("utf-8"), validate=True)
            except (ValueError, binascii.Error):
                raise UrlVideoError("Cookies invalidos em AMZ_YTDLP_COOKIES_B64.")
        elif cookies_text:
            conteudo = cookies_text.replace("\\n", "\n").encode("utf-8")
        else:
            return None

        destino = Path(temp_dir) / "cookies.txt"
        destino.write_bytes(conteudo)
        return destino

    def _formatar_erro_download(self, erro, tipo="video", cookies_file=None):
        detalhe = str(erro).strip()
        detalhe_curto = detalhe.splitlines()[-1][-400:] if detalhe else "Erro desconhecido."
        detalhe_lower = detalhe.lower()
        usando_cookies = bool(cookies_file)

        if "sign in to confirm" in detalhe_lower or "not a bot" in detalhe_lower or "cookies" in detalhe_lower:
            if usando_cookies:
                return "O YouTube bloqueou esse link mesmo com cookies configurados. Atualize os cookies do servidor ou tente outro link."

            return (
                "O YouTube bloqueou o servidor e pediu confirmacao de conta. "
                "Configure cookies do yt-dlp no servidor (`AMZ_YTDLP_COOKIES_B64` ou `AMZ_YTDLP_COOKIES_TEXT`) "
                "ou tente um link de TikTok/Instagram."
            )

        if "private video" in detalhe_lower or "this video is private" in detalhe_lower:
            return "Esse video esta privado ou sem permissao de acesso."

        if "video unavailable" in detalhe_lower or "this video is unavailable" in detalhe_lower:
            return "Esse video esta indisponivel para download."

        if "video not available" in detalhe_lower or "status code 0" in detalhe_lower:
            return "A plataforma nao disponibilizou esse video agora. Tente outro link publico."

        if "unsupported url" in detalhe_lower:
            return "Esse link ainda nao e suportado pelo downloader."

        if "video longo demais" in detalhe_lower:
            return detalhe_curto

        prefixo = "Nao consegui baixar o audio desse link." if tipo == "audio" else "Nao consegui baixar esse video."
        return f"{prefixo} {detalhe_curto}"

    def _validar_url(self, url: str):
        parsed = urlparse(str(url or "").strip())
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise UrlVideoError("Envie um link valido (http/https).")
        return parsed.geturl()

    @staticmethod
    def _alvo_impersonacao(valor):
        """Converte um alvo textual para o tipo aceito pelo yt-dlp atual."""
        texto = str(valor or "").strip()
        if not texto:
            return None
        if ImpersonateTarget is None:
            raise UrlVideoError("A biblioteca yt-dlp nao suporta a configuracao de impersonacao do servidor.")

        try:
            return ImpersonateTarget.from_str(texto)
        except (AssertionError, TypeError, ValueError) as erro:
            raise UrlVideoError("Alvo de impersonacao invalido no servidor.") from erro

    def _opcoes_plataforma(self, url: str):
        host = (urlparse(url).hostname or "").lower()
        global_impersonate = os.getenv("AMZ_YTDLP_IMPERSONATE", "").strip()
        if host == "tiktok.com" or host.endswith(".tiktok.com"):
            opcoes = {
                "http_headers": {
                    "User-Agent": (
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
                    ),
                    "Referer": "https://www.tiktok.com/",
                }
            }
            impersonate = (
                global_impersonate
                or os.getenv("AMZ_URLVIDEO_TIKTOK_IMPERSONATE", "").strip()
                or os.getenv("AMZ_YTDLP_TIKTOK_IMPERSONATE", "").strip()
            )
            if impersonate:
                opcoes["impersonate"] = self._alvo_impersonacao(impersonate)
            return opcoes

        if host in {"youtube.com", "youtu.be"} or host.endswith(".youtube.com"):
            opcoes = {
                "http_headers": {
                    "User-Agent": (
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
                    ),
                    "Referer": "https://www.youtube.com/",
                }
            }
            impersonate = (
                global_impersonate
                or os.getenv("AMZ_URLVIDEO_YOUTUBE_IMPERSONATE", "").strip()
                or os.getenv("AMZ_YTDLP_YOUTUBE_IMPERSONATE", "").strip()
            )
            if impersonate:
                opcoes["impersonate"] = self._alvo_impersonacao(impersonate)
            return opcoes

        return {}

    def _opcoes_rede_ytdlp(self):
        """Opcoes comuns de rede e o runtime EJS quando Node estiver disponivel."""
        opcoes = {
            "retries": max(1, min(int(self.limits.retries), 5)),
            "fragment_retries": max(1, min(int(self.limits.retries), 5)),
            "extractor_retries": 1,
            "file_access_retries": max(1, min(int(self.limits.retries), 5)),
            "socket_timeout": int(self.limits.timeout_seconds),
        }
        # O yt-dlp atual precisa de um runtime JS externo para resolver
        # desafios do YouTube. O Render do bot pode nao ter Node instalado;
        # nesse caso omitimos a opcao para nao quebrar plataformas que nao
        # dependem dela.
        if shutil.which("node"):
            opcoes["js_runtimes"] = {"node": {}}
        if os.getenv("AMZ_URLVIDEO_FORCE_IPV4", "").strip().lower() in {"1", "true", "yes"}:
            opcoes["force_ipv4"] = True
        return opcoes

    @staticmethod
    def _texto_erro_ytdlp(erro):
        return " ".join(linha.strip() for linha in str(erro).splitlines() if linha.strip())

    @classmethod
    def _erro_temporario_ytdlp(cls, erro):
        texto = cls._texto_erro_ytdlp(erro).lower()
        # Bloqueios de conta e conteudo privado sao determinísticos; repetir
        # so consome a janela de requisicao e deixa o comando mais lento.
        if any(termo in texto for termo in (
            "sign in to confirm",
            "confirm you're not a bot",
            "not a bot",
            "private video",
            "this video is private",
            "video unavailable",
            "this video is unavailable",
            "requested format is not available",
        )):
            return False
        return any(termo in texto for termo in (
            "unexpected_eof_while_reading",
            "eof occurred in violation of protocol",
            "unable to download api page",
            "connection reset",
            "connection aborted",
            "read timed out",
            "timed out",
            "temporarily unavailable",
            "remote end closed connection",
            "ssl:",
            "http error 429",
            "too many requests",
        ))

    @staticmethod
    def _limpar_arquivos_parciais(ydl_opts):
        outtmpl = ydl_opts.get("outtmpl")
        if not outtmpl:
            return
        pasta = Path(str(outtmpl)).parent
        if not pasta.exists():
            return
        for padrao in ("*.part", "*.ytdl", "*.tmp"):
            for arquivo in pasta.glob(padrao):
                try:
                    arquivo.unlink()
                except OSError:
                    pass

    def _executar_ytdlp(self, ydl_opts, url, *, download, tipo, cookies_file=None):
        tentativas = max(1, min(int(self.limits.retries), 5))
        for tentativa in range(1, tentativas + 1):
            try:
                with YoutubeDL(ydl_opts) as ydl:
                    return ydl.extract_info(url, download=download)
            except Exception as erro:
                if tentativa < tentativas and self._erro_temporario_ytdlp(erro):
                    self._limpar_arquivos_parciais(ydl_opts)
                    time.sleep(min(2 * tentativa, 6))
                    continue
                raise UrlVideoError(self._formatar_erro_download(erro, tipo, cookies_file)) from erro

        raise UrlVideoError("Nao consegui baixar esse conteudo agora.")

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

    def _converter_para_discord(self, input_path: Path, temp_dir: str, max_width=None) -> Path:
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
        self._run_ffmpeg(args)

        return output_path

    def _validar_tamanho_saida(self, path: Path, limite_bytes: int):
        tamanho = path.stat().st_size
        if tamanho <= limite_bytes:
            return

        limite_mb = round(limite_bytes / (1024 * 1024), 2)
        tamanho_mb = round(tamanho / (1024 * 1024), 2)
        raise UrlVideoError(f"Arquivo ficou grande demais ({tamanho_mb} MB). Limite: {limite_mb} MB.")

    def download_video(self, url: str, temp_dir: str, max_bytes=None, max_width=None) -> Path:
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
            **self._opcoes_rede_ytdlp(),
            **self._opcoes_plataforma(url),
            "outtmpl": outtmpl,
            "format": os.getenv(
                "AMZ_URLVIDEO_LIGHT_FORMAT" if max_width else "AMZ_URLVIDEO_FORMAT",
                "bv*[height<=540]+ba/b[height<=540]/b" if max_width else "bv*+ba/b",
            ),
            "merge_output_format": "mp4",
            "ffmpeg_location": self.ffmpeg,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "max_filesize": limite_bytes,
            "match_filter": filtro_por_duracao,
            "overwrites": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        self._executar_ytdlp(ydl_opts, url, download=True, tipo="video", cookies_file=cookies_file)

        candidato = Path(temp_dir) / "video.mp4"
        if not candidato.exists():
            arquivos = sorted(
                (
                    item for item in Path(temp_dir).glob("video.*")
                    if item.is_file() and not item.name.endswith((".part", ".ytdl", ".tmp")) and item.stat().st_size > 0
                ),
                key=lambda item: item.stat().st_mtime,
                reverse=True,
            )
            if arquivos:
                candidato = arquivos[0]

        if not candidato.exists() or candidato.stat().st_size <= 0:
            raise UrlVideoError("Nao consegui gerar o arquivo final do video.")

        convertido = candidato
        if candidato.suffix.lower() != ".mp4":
            convertido = self._converter_para_discord(candidato, temp_dir, max_width=max_width)
        self._validar_tamanho_saida(convertido, limite_bytes)

        return convertido

    def download_audio(self, url: str, temp_dir: str, max_bytes=None) -> Path:
        if YoutubeDL is None:
            raise UrlVideoError("Dependencia `yt-dlp` nao instalada no servidor.")

        url = self._validar_url(url)

        limite_bytes = int(max_bytes or self.limits.max_output_bytes)
        limite_bytes = min(max(limite_bytes, 1), self.limits.max_output_bytes)

        outtmpl = str(Path(temp_dir) / "audio.%(ext)s")
        cookies_file = self._cookies_file(temp_dir)

        def filtro_por_duracao(info_dict, *, incomplete=False):
            if incomplete:
                return None
            duracao = info_dict.get("duration")
            if duracao and int(duracao) > int(self.limits.max_seconds):
                return "Video longo demais para extrair MP3."
            return None

        ydl_opts = {
            **self._opcoes_rede_ytdlp(),
            **self._opcoes_plataforma(url),
            "outtmpl": outtmpl,
            # Equivalente a: yt-dlp -x --audio-format mp3 <link>
            "format": os.getenv("AMZ_URLAUDIO_FORMAT", "bestaudio/best"),
            "ffmpeg_location": self.ffmpeg,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "max_filesize": limite_bytes,
            "match_filter": filtro_por_duracao,
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
            }],
            "overwrites": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        self._executar_ytdlp(ydl_opts, url, download=True, tipo="audio", cookies_file=cookies_file)

        arquivos = sorted(
            (
                item for item in Path(temp_dir).glob("audio.*")
                if item.is_file() and not item.name.endswith((".part", ".ytdl", ".tmp")) and item.stat().st_size > 0
            ),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )

        if not arquivos:
            raise UrlVideoError("Nao consegui gerar o arquivo de audio.")

        output_path = next((arquivo for arquivo in arquivos if arquivo.suffix.lower() == ".mp3"), None)
        if output_path is None:
            raise UrlVideoError("Nao consegui converter o audio para MP3.")
        self._validar_tamanho_saida(output_path, limite_bytes)

        return output_path
