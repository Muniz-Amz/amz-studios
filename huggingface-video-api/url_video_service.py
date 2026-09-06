import base64
import binascii
import os
import shutil
import subprocess
import tempfile
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


class UrlVideoError(Exception):
    pass


@dataclass(frozen=True)
class UrlVideoLimits:
    max_output_mb: int = int(os.getenv("AMZ_URLVIDEO_MAX_OUTPUT_MB", "50"))
    max_seconds: int = int(os.getenv("AMZ_URLVIDEO_MAX_SECONDS", "300"))
    timeout_seconds: int = int(os.getenv("AMZ_URLVIDEO_TIMEOUT_SECONDS", "420"))
    check_timeout_seconds: int = int(os.getenv("AMZ_URLVIDEO_CHECK_TIMEOUT_SECONDS", "35"))
    max_width: int = int(os.getenv("AMZ_URLVIDEO_MAX_WIDTH", "720"))
    fps: int = int(os.getenv("AMZ_URLVIDEO_FPS", "24"))

    @property
    def max_output_bytes(self):
        return self.max_output_mb * 1024 * 1024


class UrlVideoService:
    def __init__(self, limits=None):
        self.limits = limits or UrlVideoLimits()
        self.ffmpeg = os.getenv("FFMPEG_BINARY", "").strip() or imageio_ffmpeg.get_ffmpeg_exe()

    def versao_ytdlp(self):
        if YoutubeDL is None:
            return "indisponivel"

        try:
            from yt_dlp.version import __version__
            return __version__
        except Exception:
            return "desconhecida"

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

    def _opcoes_plataforma(self, url: str):
        """Ajustes leves para plataformas que rejeitam clientes sem referer."""
        host = (urlparse(url).hostname or "").lower()
        if host == "tiktok.com" or host.endswith(".tiktok.com"):
            return {
                "http_headers": {
                    "User-Agent": (
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                    ),
                    "Referer": "https://www.tiktok.com/",
                }
            }
        return {}

    def _opcoes_rede_ytdlp(self, timeout_seconds=None, attempts=None):
        opcoes = {
            "socket_timeout": int(timeout_seconds or self.limits.timeout_seconds),
            "retries": int(os.getenv("AMZ_YTDLP_RETRIES", "2")),
            "fragment_retries": int(os.getenv("AMZ_YTDLP_FRAGMENT_RETRIES", "2")),
            "extractor_retries": int(os.getenv("AMZ_YTDLP_EXTRACTOR_RETRIES", "2")),
            "file_access_retries": int(os.getenv("AMZ_YTDLP_FILE_RETRIES", "2")),
        }

        if attempts is not None:
            opcoes["retries"] = max(1, int(attempts))
            opcoes["fragment_retries"] = max(1, int(attempts))
            opcoes["extractor_retries"] = max(1, int(attempts))
            opcoes["file_access_retries"] = max(1, int(attempts))

        if os.getenv("AMZ_YTDLP_FORCE_IPV4", "").strip().lower() in {"1", "true", "yes"}:
            opcoes["force_ipv4"] = True

        return opcoes

    def _max_tentativas_ytdlp(self):
        try:
            return max(1, min(int(os.getenv("AMZ_YTDLP_ATTEMPTS", "2")), 5))
        except ValueError:
            return 2

    def _timeout_audio(self):
        try:
            configurado = int(os.getenv("AMZ_URLAUDIO_TIMEOUT_SECONDS", "240"))
            return max(30, min(configurado, int(self.limits.timeout_seconds)))
        except ValueError:
            return min(240, int(self.limits.timeout_seconds))

    def _fragmentos_audio(self):
        try:
            return max(1, min(int(os.getenv("AMZ_URLAUDIO_CONCURRENT_FRAGMENTS", "2")), 4))
        except ValueError:
            return 2

    def _texto_erro_ytdlp(self, erro):
        return " ".join(linha.strip() for linha in str(erro).splitlines() if linha.strip())

    def _erro_temporario_ytdlp(self, erro):
        texto = self._texto_erro_ytdlp(erro).lower()
        termos = (
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
        )
        return any(termo in texto for termo in termos)

    def _mensagem_erro_ytdlp(self, erro, acao):
        texto = self._texto_erro_ytdlp(erro)
        texto_lower = texto.lower()

        if "video longo demais" in texto_lower:
            return "Video longo demais para este servidor. Use um link de ate " + self._rotulo_limite_tempo() + "."

        if "sign in to confirm" in texto_lower or "confirm you're not a bot" in texto_lower:
            return "YouTube bloqueou este link com verificacao anti-bot. Tente outro video publico ou outra plataforma."

        if "private video" in texto_lower or "this video is private" in texto_lower:
            return "Este video e privado ou exige login. Use um link publico."

        if "unsupported url" in texto_lower:
            return "Esse tipo de link ainda nao e suportado. Use Instagram, TikTok ou YouTube publico."

        if "requested format is not available" in texto_lower:
            return "A plataforma nao liberou um formato compativel para baixar este arquivo."

        if (
            "unexpected_eof_while_reading" in texto_lower
            or "eof occurred in violation of protocol" in texto_lower
            or "ssl:" in texto_lower
        ):
            return (
                "A conexao segura com a plataforma falhou temporariamente. "
                "Tente novamente em alguns segundos; se repetir, reinicie o Space para atualizar o yt-dlp."
            )

        if "unable to download api page" in texto_lower or "connection reset" in texto_lower:
            return "A plataforma recusou a conexao agora. Tente novamente ou use outro link publico."

        if (
            "unexpected response from webpage request" in texto_lower
            or "unable to extract webpage video data" in texto_lower
            or "unable to extract universal data" in texto_lower
        ):
            return (
                "O TikTok bloqueou temporariamente a extracao desse video. "
                "O servidor ja esta atualizado; tente outro link publico ou aguarde uma correcao do TikTok/yt-dlp."
            )

        if "yt-dlp -u" in texto_lower or "latest version" in texto_lower:
            return "O yt-dlp informou que precisa de uma versao mais nova. Rebuild o Space e tente novamente."

        detalhe = texto[-260:] if texto else "erro desconhecido"
        return f"Nao consegui {acao}. {detalhe}"

    def _limpar_arquivos_parciais(self, ydl_opts):
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

    def _executar_ytdlp(self, ydl_opts, url, *, download, acao, progress_callback=None):
        total_tentativas = self._max_tentativas_ytdlp()

        for tentativa in range(1, total_tentativas + 1):
            try:
                with YoutubeDL(ydl_opts) as ydl:
                    return ydl.extract_info(url, download=download)
            except Exception as erro:
                if tentativa < total_tentativas and self._erro_temporario_ytdlp(erro):
                    etapa = "baixando" if download else "validando"
                    progresso = 10 if download else 6
                    mensagem = f"Conexao instavel. Tentando novamente ({tentativa + 1}/{total_tentativas})..."
                    self._notificar_progresso(progress_callback, etapa, progresso, mensagem)
                    self._limpar_arquivos_parciais(ydl_opts)
                    time.sleep(min(2 * tentativa, 6))
                    continue

                raise UrlVideoError(self._mensagem_erro_ytdlp(erro, acao)) from erro

        raise UrlVideoError(f"Nao consegui {acao} agora.")

    def analisar_url(self, url: str) -> dict:
        if YoutubeDL is None:
            raise UrlVideoError("Dependencia yt-dlp nao instalada no servidor.")

        url = self._validar_url(url)
        temp_dir = tempfile.mkdtemp(prefix="amz-video-check-")
        cookies_file = self._cookies_file(temp_dir)
        ydl_opts = {
            **self._opcoes_rede_ytdlp(timeout_seconds=min(int(self.limits.check_timeout_seconds), int(self.limits.timeout_seconds)), attempts=1),
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "skip_download": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        try:
            info = self._executar_ytdlp(ydl_opts, url, download=False, acao="verificar esse link")

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
            **self._opcoes_rede_ytdlp(),
            **self._opcoes_plataforma(url),
            "outtmpl": outtmpl,
            # Equivalente a `yt-dlp <link>` para HD; no modo leve, pede uma
            # faixa menor diretamente ao provedor em vez de reencodar depois.
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
            "progress_hooks": [self._criar_hook_download(progress_callback)],
            "overwrites": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        self._executar_ytdlp(ydl_opts, url, download=True, acao="baixar esse video", progress_callback=progress_callback)

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

        convertido = candidato
        if candidato.suffix.lower() != ".mp4":
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
            **self._opcoes_rede_ytdlp(timeout_seconds=self._timeout_audio()),
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
            "progress_hooks": [self._criar_hook_download(progress_callback)],
            # Dois fragmentos aceleram HLS sem disputar a pouca memoria do Space.
            "concurrent_fragment_downloads": self._fragmentos_audio(),
            "cachedir": False,
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
            }],
            "overwrites": True,
        }

        if cookies_file:
            ydl_opts["cookiefile"] = str(cookies_file)

        self._executar_ytdlp(ydl_opts, url, download=True, acao="baixar o audio desse link", progress_callback=progress_callback)

        arquivos = sorted(
            (item for item in Path(temp_dir).glob("audio.*") if item.is_file() and not item.name.endswith(".part")),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )

        if not arquivos:
            raise UrlVideoError("Nao consegui gerar o arquivo de audio.")

        output_path = next((arquivo for arquivo in arquivos if arquivo.suffix.lower() == ".mp3"), None)
        if output_path is None:
            raise UrlVideoError("Nao consegui converter o audio para MP3.")
        self._notificar_progresso(progress_callback, "finalizando", 94, "Validando tamanho final...")
        self._validar_tamanho_saida(output_path, limite_bytes)

        return output_path
