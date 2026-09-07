"""API isolada de MP3 para o site AMZ Studios.

Este processo é intencionalmente independente do bot Discord. O container deve
ser publicado no Render com `backend/mp3_extractor` como Root Directory.
"""

from __future__ import annotations

import os
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

from mp3_service import Mp3DownloadError, Mp3DownloadService


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
CORS(
    app,
    resources={r"/api/*": {"origins": _origins()}},
    methods=["GET", "POST", "OPTIONS"],
    expose_headers=["Content-Disposition"],
    max_age=600,
)

mp3_service = Mp3DownloadService()
JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()
REQUEST_LOG: dict[str, deque[float]] = defaultdict(deque)
JOB_TTL_SECONDS = _env_int("AMZ_MP3_JOB_TTL_SECONDS", 1800, 300, 7200)
RATE_WINDOW_SECONDS = _env_int("AMZ_MP3_RATE_WINDOW_SECONDS", 600, 60, 3600)
MAX_JOBS_PER_IP = _env_int("AMZ_MP3_MAX_JOBS_PER_IP", 3, 1, 20)
MAX_QUEUE_SIZE = _env_int("AMZ_MP3_MAX_QUEUE_SIZE", 8, 1, 30)
AVERAGE_AUDIO_SECONDS = float(_env_int("AMZ_MP3_ESTIMATE_SECONDS", 75, 20, 300))
# Um único worker protege CPU/memória e permite informar posição na fila.
EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="amz-mp3")


def _client_key() -> str:
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()[:120] or "unknown"
    return (request.remote_addr or "unknown")[:120]


def _cleanup_old_jobs() -> None:
    now = time.time()
    expired: list[str | None] = []

    with JOBS_LOCK:
        for job_id, job in list(JOBS.items()):
            if now - float(job.get("created_at", now)) > JOB_TTL_SECONDS:
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
            (item for item in JOBS.values() if item.get("status") == "queued"),
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


@app.get("/health")
def health():
    """Resposta mínima para o health check interno do Render."""
    return jsonify({"status": "ok"})


@app.get("/")
def root():
    return jsonify({
        "status": "online",
        "service": "amz-mp3-api",
        "routes": ["/api/mp3/status", "/api/mp3/jobs"],
    })


@app.get("/api/mp3/status")
def status():
    _cleanup_old_jobs()
    with JOBS_LOCK:
        queued = sum(job.get("status") == "queued" for job in JOBS.values())
        running = sum(job.get("status") == "running" for job in JOBS.values())

    return jsonify({
        "status": "online",
        "service": "amz-mp3-api",
        "output_mode": "mp3",
        "yt_dlp_version": mp3_service.ytdlp_version(),
        "max_output_mb": mp3_service.limits.max_output_mb,
        "max_seconds": mp3_service.limits.max_seconds,
        "queue": {"queued": queued, "running": running, "max": MAX_QUEUE_SIZE},
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
            active_jobs = sum(job.get("status") in {"queued", "running"} for job in JOBS.values())
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
                "client": client,
            }
            JOBS[job_id] = job

    if duplicate:
        return jsonify({"status": "sucesso", "job": _payload(duplicate), "reutilizado": True}), 202

    EXECUTOR.submit(_process_job, job_id, url)
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
