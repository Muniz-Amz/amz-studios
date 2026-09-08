"""API isolada de MP3 para o site AMZ Studios.

Este processo é intencionalmente independente do bot Discord. O container deve
ser publicado no Render com `backend/mp3_extractor` como Root Directory.
"""

from __future__ import annotations

import os
import ipaddress
import shutil
import tempfile
import threading
import time
import uuid
from collections import defaultdict, deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from flask import Flask, jsonify, request, send_file
from flask_cors import CORS
from werkzeug.exceptions import RequestEntityTooLarge

from mp3_service import Mp3DownloadError, Mp3DownloadService, Mp3UploadTooLargeError


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))


def _origins() -> list[str]:
    values = os.getenv("AMZ_MP3_ALLOWED_ORIGINS", "https://muniz-amz.github.io")
    return [item.strip() for item in values.split(",") if item.strip()]


app = Flask(__name__)
mp3_service = Mp3DownloadService()
# O teto do corpo impede que o parser multipart aceite uploads enormes. A
# gravação abaixo aplica novamente o teto exato do arquivo, sem considerar a
# pequena sobrecarga do formulário multipart.
app.config["MAX_CONTENT_LENGTH"] = mp3_service.limits.max_upload_bytes + (1024 * 1024)
app.config["MAX_FORM_MEMORY_SIZE"] = 1024 * 1024
CORS(
    app,
    resources={r"/api/*": {"origins": _origins()}},
    methods=["GET", "POST", "OPTIONS"],
    expose_headers=["Content-Disposition"],
    max_age=600,
)

JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()
# Mesmo com quatro threads HTTP, a sondagem de mídia usa CPU. Serializar o
# ffprobe evita que vários uploads pesem no Render enquanto uma conversão já
# está usando o único worker da fila.
UPLOAD_PROBE_LOCK = threading.Lock()
REQUEST_LOG: dict[str, deque[float]] = defaultdict(deque)
JOB_TTL_SECONDS = _env_int("AMZ_MP3_JOB_TTL_SECONDS", 1800, 300, 7200)
UPLOAD_RESERVATION_TTL_SECONDS = _env_int("AMZ_MP3_UPLOAD_RESERVATION_TTL_SECONDS", 900, 120, 3600)
CLEANUP_INTERVAL_SECONDS = _env_int("AMZ_MP3_CLEANUP_INTERVAL_SECONDS", 60, 30, 600)
RATE_WINDOW_SECONDS = _env_int("AMZ_MP3_RATE_WINDOW_SECONDS", 600, 60, 3600)
MAX_JOBS_PER_IP = _env_int("AMZ_MP3_MAX_JOBS_PER_IP", 3, 1, 20)
MAX_QUEUE_SIZE = _env_int("AMZ_MP3_MAX_QUEUE_SIZE", 8, 1, 30)
AVERAGE_AUDIO_SECONDS = float(_env_int("AMZ_MP3_ESTIMATE_SECONDS", 75, 20, 300))
ACTIVE_JOB_STATUSES = frozenset({"uploading", "queued", "running"})
# Um único worker protege CPU/memória e permite informar posição na fila.
EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="amz-mp3")


def _client_key() -> str:
    """Extrai IP só de cabeçalho encaminhado por um proxy local/confiável.

    O X-Forwarded-For enviado diretamente por um cliente é controlável pelo
    próprio cliente. No Render, a conexão da aplicação vem do proxy interno;
    apenas nesse caso usamos o último IP encaminhado. A fila global continua
    sendo a proteção principal contra uso abusivo sem depender de CORS.
    """

    def normalize_ip(value: str | None) -> str | None:
        try:
            return str(ipaddress.ip_address(str(value or "").strip()))
        except ValueError:
            return None

    remote = normalize_ip(request.remote_addr)
    if remote:
        remote_ip = ipaddress.ip_address(remote)
        if remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local:
            forwarded = request.headers.get("X-Forwarded-For", "")
            for item in reversed(forwarded.split(",")):
                forwarded_ip = normalize_ip(item)
                if forwarded_ip:
                    return f"ip:{forwarded_ip}"
        return f"ip:{remote}"
    return "unknown"


def _cleanup_old_jobs() -> None:
    now = time.time()
    expired: list[str | None] = []

    with JOBS_LOCK:
        for job_id, job in list(JOBS.items()):
            status = job.get("status")
            updated_at = float(job.get("updated_at", job.get("created_at", now)))
            terminal_expired = status in {"done", "error"} and now - updated_at > JOB_TTL_SECONDS
            reservation_expired = status == "uploading" and now - updated_at > UPLOAD_RESERVATION_TTL_SECONDS
            if terminal_expired or reservation_expired:
                expired.append(job.get("temp_dir"))
                JOBS.pop(job_id, None)

        for key, timestamps in list(REQUEST_LOG.items()):
            while timestamps and now - timestamps[0] > RATE_WINDOW_SECONDS:
                timestamps.popleft()
            if not timestamps:
                REQUEST_LOG.pop(key, None)

    for temp_dir in expired:
        if temp_dir:
            shutil.rmtree(temp_dir, ignore_errors=True)


def _cleanup_loop() -> None:
    """Remove arquivos temporários mesmo quando ninguém acessa a API."""

    while True:
        time.sleep(CLEANUP_INTERVAL_SECONDS)
        try:
            _cleanup_old_jobs()
        except Exception as error:  # O reaper não pode derrubar a API.
            print(f"[MP3] limpeza periódica falhou: {type(error).__name__}: {str(error)[-300:]}")


def _get_job(job_id: str) -> dict | None:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        return dict(job) if job else None


def _update_job(job_id: str, **fields) -> None:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return
        job.update(fields)
        job["updated_at"] = time.time()


def _queue_estimate(job_id: str) -> tuple[int, int]:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job or job.get("status") != "queued":
            return 0, 0

        waiting = sorted(
            (item for item in JOBS.values() if item.get("status") in {"uploading", "queued"}),
            key=lambda item: float(item.get("created_at", 0)),
        )
        position = next((index for index, item in enumerate(waiting, start=1) if item.get("id") == job_id), 0)
        has_running_job = any(item.get("status") == "running" for item in JOBS.values())
        units_waiting = position if has_running_job else max(position - 1, 0)
        return position, max(0, round(units_waiting * AVERAGE_AUDIO_SECONDS))


def _remember_duration(seconds: float) -> None:
    global AVERAGE_AUDIO_SECONDS
    safe_duration = max(20.0, min(float(seconds), 300.0))
    with JOBS_LOCK:
        AVERAGE_AUDIO_SECONDS = (AVERAGE_AUDIO_SECONDS * 0.7) + (safe_duration * 0.3)


def _payload(job: dict) -> dict:
    position, eta_seconds = _queue_estimate(job.get("id", ""))
    return {
        "id": job.get("id"),
        "status": job.get("status"),
        "etapa": job.get("stage"),
        "progresso": job.get("progress", 0),
        "mensagem": job.get("message", ""),
        "erro": job.get("error", ""),
        "filename": job.get("filename", ""),
        "mimetype": job.get("mimetype", ""),
        "origem": job.get("source", "url"),
        "arquivo_origem": job.get("source_filename", ""),
        "duracao_segundos": job.get("source_duration"),
        "posicao_fila": position,
        "tempo_estimado_segundos": eta_seconds,
        "download_url": f"/api/mp3/jobs/{job.get('id')}/download" if job.get("status") == "done" else None,
    }


def _process_job(job_id: str, url: str) -> None:
    temp_dir = tempfile.mkdtemp(prefix="amz-mp3-")

    def report(stage: str, progress: int, message: str) -> None:
        _update_job(job_id, stage=stage, progress=progress, message=message)

    _update_job(
        job_id,
        status="running",
        stage="validando",
        progress=3,
        message="Preparando o servidor de MP3…",
        temp_dir=temp_dir,
        mimetype="audio/mpeg",
        filename="amz-audio.mp3",
        started_at=time.time(),
    )

    try:
        output_path = mp3_service.download_audio(url, temp_dir, progress_callback=report)
        job = _get_job(job_id)
        if job and job.get("started_at"):
            _remember_duration(time.time() - float(job["started_at"]))

        _update_job(
            job_id,
            status="done",
            stage="pronto",
            progress=100,
            message="MP3 pronto para baixar.",
            output_path=str(output_path),
        )
    except Mp3DownloadError as error:
        _update_job(job_id, status="error", stage="erro", progress=100, error=str(error), message=str(error))
        shutil.rmtree(temp_dir, ignore_errors=True)
    except Exception as error:  # Não expõe detalhe interno ao site.
        print(f"[MP3] Erro inesperado no job {job_id}: {error}")
        _update_job(
            job_id,
            status="error",
            stage="erro",
            progress=100,
            error="Não consegui extrair esse áudio agora.",
            message="Não consegui extrair esse áudio agora.",
        )
        shutil.rmtree(temp_dir, ignore_errors=True)


def _reserve_upload_job(client: str, source_filename: str, output_filename: str, source_mimetype: str) -> tuple[dict | None, str | None]:
    """Reserva fila e rate limit antes de qualquer byte ser salvo no disco."""

    now = time.time()
    with JOBS_LOCK:
        active_jobs = sum(job.get("status") in ACTIVE_JOB_STATUSES for job in JOBS.values())
        if active_jobs >= MAX_QUEUE_SIZE:
            return None, "A fila está cheia. Aguarde alguns minutos e tente novamente."

        timestamps = REQUEST_LOG[client]
        while timestamps and now - timestamps[0] > RATE_WINDOW_SECONDS:
            timestamps.popleft()
        if len(timestamps) >= MAX_JOBS_PER_IP:
            return None, "Aguarde alguns minutos antes de iniciar outro MP3."

        timestamps.append(now)
        job_id = uuid.uuid4().hex
        job = {
            "id": job_id,
            "status": "uploading",
            "stage": "enviando",
            "progress": 1,
            "message": "Recebendo arquivo para conversão…",
            "error": "",
            "created_at": now,
            "updated_at": now,
            "source": "upload",
            "source_filename": source_filename,
            "source_mimetype": source_mimetype,
            "filename": output_filename,
            "mimetype": "audio/mpeg",
            "client": client,
            "rate_timestamp": now,
        }
        JOBS[job_id] = job
        return dict(job), None


def _cancel_upload_reservation(job_id: str, *, release_rate_limit: bool = False) -> None:
    """Descarta uma reserva que não chegou a entrar na fila."""

    temp_dir = None
    with JOBS_LOCK:
        job = JOBS.pop(job_id, None)
        if job:
            temp_dir = job.get("temp_dir")
            if release_rate_limit:
                timestamps = REQUEST_LOG.get(str(job.get("client") or ""))
                timestamp = job.get("rate_timestamp")
                if timestamps and timestamp in timestamps:
                    timestamps.remove(timestamp)
                if timestamps is not None and not timestamps:
                    REQUEST_LOG.pop(str(job.get("client") or ""), None)
    if temp_dir:
        shutil.rmtree(temp_dir, ignore_errors=True)


def _save_uploaded_file(uploaded, temp_dir: str, extension: str, job_id: str) -> Path:
    """Copia o stream multipart com limite estrito e nome interno controlado."""

    destination = Path(temp_dir) / f"source{extension}"
    written = 0
    last_update = time.monotonic()

    try:
        with destination.open("xb") as output:
            while True:
                chunk = uploaded.stream.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > mp3_service.limits.max_upload_bytes:
                    raise Mp3UploadTooLargeError(
                        f"O arquivo enviado ultrapassa o limite de {mp3_service.limits.max_upload_mb} MB."
                    )
                output.write(chunk)
                if time.monotonic() - last_update >= 2:
                    _update_job(job_id, stage="enviando", progress=3, message="Recebendo arquivo para conversão…")
                    last_update = time.monotonic()
    except OSError as error:
        print(f"[MP3] falha ao gravar upload: {type(error).__name__}: {str(error)[-300:]}")
        raise Mp3DownloadError("Não consegui receber esse arquivo. Tente novamente.") from error

    if written <= 0:
        raise Mp3DownloadError("O arquivo enviado está vazio.")
    return destination


def _process_upload_job(job_id: str) -> None:
    job = _get_job(job_id)
    if not job:
        return

    temp_dir = str(job.get("temp_dir") or "")
    input_path = Path(str(job.get("input_path") or ""))

    def report(stage: str, progress: int, message: str) -> None:
        _update_job(job_id, stage=stage, progress=progress, message=message)

    _update_job(
        job_id,
        status="running",
        stage="convertendo",
        progress=10,
        message="Preparando a conversão do seu arquivo…",
        started_at=time.time(),
    )

    try:
        output_path = mp3_service.convert_uploaded_media(
            input_path,
            temp_dir,
            float(job.get("source_duration") or 0),
            progress_callback=report,
        )
        current_job = _get_job(job_id)
        if current_job and current_job.get("started_at"):
            _remember_duration(time.time() - float(current_job["started_at"]))

        _update_job(
            job_id,
            status="done",
            stage="pronto",
            progress=100,
            message="MP3 pronto para baixar.",
            output_path=str(output_path),
        )
    except Mp3DownloadError as error:
        _update_job(job_id, status="error", stage="erro", progress=100, error=str(error), message=str(error))
        shutil.rmtree(temp_dir, ignore_errors=True)
    except Exception as error:  # Não expõe detalhe interno ao site.
        print(f"[MP3] Erro inesperado no upload {job_id}: {type(error).__name__}: {str(error)[-500:]}")
        _update_job(
            job_id,
            status="error",
            stage="erro",
            progress=100,
            error="Não consegui converter esse arquivo agora.",
            message="Não consegui converter esse arquivo agora.",
        )
        shutil.rmtree(temp_dir, ignore_errors=True)
    finally:
        # O original não é necessário após a conversão. Em caso de erro o
        # diretório inteiro já foi apagado; `missing_ok` mantém a limpeza segura.
        try:
            input_path.unlink(missing_ok=True)
        except OSError as error:
            print(f"[MP3] não consegui apagar upload original: {type(error).__name__}: {str(error)[-200:]}")


@app.errorhandler(RequestEntityTooLarge)
def upload_too_large(_error):
    return jsonify({
        "status": "erro",
        "mensagem": f"O arquivo enviado ultrapassa o limite de {mp3_service.limits.max_upload_mb} MB.",
    }), 413


# A limpeza também roda quando o serviço fica ocioso; sem isso, um upload ou
# MP3 abandonado ficaria no disco efêmero até chegar outra requisição.
threading.Thread(target=_cleanup_loop, name="amz-mp3-cleanup", daemon=True).start()


@app.get("/health")
def health():
    """Resposta mínima para o health check interno do Render."""
    return jsonify({"status": "ok"})


@app.get("/")
def root():
    return jsonify({
        "status": "online",
        "service": "amz-mp3-api",
        "routes": ["/api/mp3/status", "/api/mp3/jobs", "/api/mp3/uploads"],
    })


@app.get("/api/mp3/status")
def status():
    _cleanup_old_jobs()
    with JOBS_LOCK:
        uploading = sum(job.get("status") == "uploading" for job in JOBS.values())
        queued = sum(job.get("status") == "queued" for job in JOBS.values())
        running = sum(job.get("status") == "running" for job in JOBS.values())

    return jsonify({
        "status": "online",
        "service": "amz-mp3-api",
        "output_mode": "mp3",
        "yt_dlp_version": mp3_service.ytdlp_version(),
        "max_output_mb": mp3_service.limits.max_output_mb,
        "max_upload_mb": mp3_service.limits.max_upload_mb,
        "max_seconds": mp3_service.limits.max_seconds,
        "queue": {
            "uploading": uploading,
            "queued": queued,
            "running": running,
            "max": MAX_QUEUE_SIZE,
        },
    })


@app.post("/api/mp3/jobs")
def create_job():
    _cleanup_old_jobs()
    data = request.get_json(silent=True) or {}
    try:
        url = mp3_service.validate_url(str(data.get("url") or ""))
    except Mp3DownloadError as error:
        return jsonify({"status": "erro", "mensagem": str(error)}), 400

    client = _client_key()
    now = time.time()
    duplicate = None
    with JOBS_LOCK:
        duplicate = next((
            dict(job) for job in JOBS.values()
            if job.get("url") == url and job.get("client") == client and job.get("status") in {"queued", "running"}
        ), None)

        if not duplicate:
            active_jobs = sum(job.get("status") in ACTIVE_JOB_STATUSES for job in JOBS.values())
            if active_jobs >= MAX_QUEUE_SIZE:
                return jsonify({"status": "erro", "mensagem": "A fila está cheia. Aguarde alguns minutos e tente novamente."}), 429

            timestamps = REQUEST_LOG[client]
            while timestamps and now - timestamps[0] > RATE_WINDOW_SECONDS:
                timestamps.popleft()
            if len(timestamps) >= MAX_JOBS_PER_IP:
                return jsonify({"status": "erro", "mensagem": "Aguarde alguns minutos antes de iniciar outro MP3."}), 429
            timestamps.append(now)

            job_id = uuid.uuid4().hex
            job = {
                "id": job_id,
                "status": "queued",
                "stage": "fila",
                "progress": 1,
                "message": "Áudio entrou na fila prioritária.",
                "error": "",
                "created_at": now,
                "updated_at": now,
                "url": url,
                "source": "url",
                "client": client,
            }
            JOBS[job_id] = job

    if duplicate:
        return jsonify({"status": "sucesso", "job": _payload(duplicate), "reutilizado": True}), 202

    EXECUTOR.submit(_process_job, job_id, url)
    return jsonify({"status": "sucesso", "job": _payload(_get_job(job_id))}), 202


@app.post("/api/mp3/uploads")
def create_upload_job():
    """Recebe uma mídia própria, valida o conteúdo e a envia à mesma fila MP3."""

    _cleanup_old_jobs()
    if request.mimetype != "multipart/form-data":
        return jsonify({
            "status": "erro",
            "mensagem": "Envie o arquivo usando formulário multipart.",
        }), 415

    uploaded = request.files.get("file")
    if not uploaded or not uploaded.filename:
        return jsonify({
            "status": "erro",
            "mensagem": "Selecione um arquivo de áudio ou vídeo para converter.",
        }), 400

    try:
        metadata = mp3_service.validate_upload_metadata(uploaded.filename, uploaded.mimetype)
    except Mp3DownloadError as error:
        return jsonify({"status": "erro", "mensagem": str(error)}), 400

    reservation, reservation_error = _reserve_upload_job(
        _client_key(),
        metadata.source_filename,
        metadata.output_filename,
        metadata.mimetype,
    )
    if reservation_error:
        return jsonify({"status": "erro", "mensagem": reservation_error}), 429
    if not reservation:  # Protege o contrato caso a reserva seja alterada no futuro.
        return jsonify({"status": "erro", "mensagem": "Não consegui reservar espaço na fila."}), 503

    job_id = str(reservation["id"])
    try:
        # A reserva de fila/rate já existe antes de criar este diretório ou
        # gravar o stream do cliente.
        temp_dir = tempfile.mkdtemp(prefix="amz-mp3-upload-")
        _update_job(job_id, temp_dir=temp_dir)
        input_path = _save_uploaded_file(uploaded, temp_dir, metadata.extension, job_id)
        _update_job(job_id, stage="validando", progress=7, message="Validando o arquivo enviado…")
        with UPLOAD_PROBE_LOCK:
            media_info = mp3_service.probe_uploaded_media(input_path)
    except Mp3UploadTooLargeError as error:
        _cancel_upload_reservation(job_id)
        return jsonify({"status": "erro", "mensagem": str(error)}), 413
    except Mp3DownloadError as error:
        _cancel_upload_reservation(job_id)
        return jsonify({"status": "erro", "mensagem": str(error)}), 400
    except Exception as error:  # Falha de infraestrutura não revela detalhe interno.
        print(f"[MP3] upload falhou antes da fila: {type(error).__name__}: {str(error)[-500:]}")
        _cancel_upload_reservation(job_id, release_rate_limit=True)
        return jsonify({
            "status": "erro",
            "mensagem": "Não consegui preparar esse arquivo agora. Tente novamente.",
        }), 500

    _update_job(
        job_id,
        status="queued",
        stage="fila",
        progress=9,
        message="Arquivo validado e entrou na fila de conversão.",
        input_path=str(input_path),
        source_duration=media_info.duration_seconds,
        source_format=media_info.format_name,
    )
    try:
        EXECUTOR.submit(_process_upload_job, job_id)
    except RuntimeError as error:
        print(f"[MP3] fila de upload indisponível: {type(error).__name__}: {str(error)[-300:]}")
        _cancel_upload_reservation(job_id, release_rate_limit=True)
        return jsonify({
            "status": "erro",
            "mensagem": "O conversor está reiniciando. Tente novamente em alguns segundos.",
        }), 503

    return jsonify({"status": "sucesso", "job": _payload(_get_job(job_id))}), 202


@app.get("/api/mp3/jobs/<job_id>")
def job_status(job_id: str):
    _cleanup_old_jobs()
    job = _get_job(job_id)
    if not job:
        return jsonify({"status": "erro", "mensagem": "Download não encontrado ou expirado."}), 404
    return jsonify({"status": "sucesso", "job": _payload(job)})


@app.get("/api/mp3/jobs/<job_id>/download")
def download_result(job_id: str):
    _cleanup_old_jobs()
    job = _get_job(job_id)
    if not job:
        return jsonify({"status": "erro", "mensagem": "Download não encontrado ou expirado."}), 404
    if job.get("status") != "done":
        return jsonify({"status": "erro", "mensagem": "O MP3 ainda não ficou pronto."}), 409

    output_path = Path(str(job.get("output_path") or ""))
    if not output_path.is_file():
        return jsonify({"status": "erro", "mensagem": "O arquivo expirou. Inicie o download novamente."}), 410

    response = send_file(
        output_path,
        mimetype=job.get("mimetype") or "audio/mpeg",
        as_attachment=True,
        download_name=job.get("filename") or "amz-audio.mp3",
        conditional=False,
    )
    response.headers["Cache-Control"] = "no-store"
    return response


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "10000")), threaded=True)
