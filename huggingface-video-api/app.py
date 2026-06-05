import io
import shutil
import tempfile
import threading
import time
import uuid
from pathlib import Path

from flask import Flask, jsonify, request, send_file
from flask_cors import CORS

from url_video_service import UrlVideoError, UrlVideoService


app = Flask(__name__)
CORS(app)

video_service = UrlVideoService()
JOBS = {}
JOBS_LOCK = threading.Lock()
JOB_TTL_SECONDS = 60 * 30


def limpar_jobs_antigos():
    agora = time.time()
    expirados = []

    with JOBS_LOCK:
        for job_id, job in JOBS.items():
            if agora - float(job.get("criado_em_ts", agora)) > JOB_TTL_SECONDS:
                expirados.append((job_id, job.get("temp_dir")))

        for job_id, _ in expirados:
            JOBS.pop(job_id, None)

    for _, temp_dir in expirados:
        if temp_dir:
            shutil.rmtree(temp_dir, ignore_errors=True)


def obter_job(job_id):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        return dict(job) if job else None


def atualizar_job(job_id, **campos):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return

        job.update(campos)
        job["atualizado_em_ts"] = time.time()


def payload_job(job):
    return {
        "id": job.get("id"),
        "status": job.get("status"),
        "etapa": job.get("etapa"),
        "progresso": job.get("progresso", 0),
        "mensagem": job.get("mensagem", ""),
        "erro": job.get("erro", ""),
        "filename": job.get("filename", ""),
        "mimetype": job.get("mimetype", ""),
        "download_url": f"/api/video/jobs/{job.get('id')}/download" if job.get("status") == "done" else None,
    }


def configurar_saida_download(modo):
    if modo == "mp3":
        return "audio/mpeg", "amz-audio.mp3"
    if modo == "video":
        return "video/mp4", "amz-video.mp4"
    return "video/mp4", "amz-video-hd.mp4"


def processar_job_video(job_id, url, modo):
    temp_dir = tempfile.mkdtemp(prefix="amz-video-job-")
    mimetype, filename = configurar_saida_download(modo)

    def progresso(etapa, valor, mensagem):
        atualizar_job(job_id, etapa=etapa, progresso=valor, mensagem=mensagem)

    atualizar_job(
        job_id,
        status="running",
        etapa="validando",
        progresso=2,
        mensagem="Preparando servidor...",
        temp_dir=temp_dir,
        mimetype=mimetype,
        filename=filename,
    )

    try:
        limite_bytes = video_service.limits.max_output_bytes

        if modo == "mp3":
            output_path = video_service.download_audio(url, temp_dir, max_bytes=limite_bytes, progress_callback=progresso)
        elif modo == "video":
            output_path = video_service.download_video(url, temp_dir, max_bytes=limite_bytes, max_width=540, progress_callback=progresso)
        else:
            output_path = video_service.download_video(url, temp_dir, max_bytes=limite_bytes, max_width=720, progress_callback=progresso)

        atualizar_job(
            job_id,
            status="done",
            etapa="pronto",
            progresso=100,
            mensagem="Arquivo pronto para baixar.",
            output_path=str(output_path),
        )
    except UrlVideoError as erro:
        atualizar_job(job_id, status="error", etapa="erro", progresso=100, erro=str(erro), mensagem=str(erro))
        shutil.rmtree(temp_dir, ignore_errors=True)
    except Exception as erro:
        print(f"[VIDEO] Erro inesperado no job {job_id}: {erro}")
        atualizar_job(
            job_id,
            status="error",
            etapa="erro",
            progresso=100,
            erro="Nao consegui baixar esse link agora.",
            mensagem="Nao consegui baixar esse link agora.",
        )
        shutil.rmtree(temp_dir, ignore_errors=True)


@app.get("/")
def root():
    return jsonify({
        "status": "online",
        "service": "amz-video-api",
        "routes": ["/api/status", "/api/video/check", "/api/video/jobs", "/api/video/download"],
    })


@app.get("/api/status")
def status():
    return jsonify({
        "status": "online",
        "max_output_mb": video_service.limits.max_output_mb,
        "max_seconds": video_service.limits.max_seconds,
        "max_width": video_service.limits.max_width,
        "fps": video_service.limits.fps,
        "check_timeout_seconds": video_service.limits.check_timeout_seconds,
        "yt_dlp_version": video_service.versao_ytdlp(),
    })


@app.post("/api/video/check")
def verificar_video():
    limpar_jobs_antigos()
    dados = request.get_json(silent=True) or {}
    url = str(dados.get("url") or "").strip()

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para verificar."}), 400

    try:
        info = video_service.analisar_url(url)
        mensagem = "Link aceito."
        if not info.get("permitido"):
            mensagem = f"Video longo demais. Limite atual: {video_service._rotulo_limite_tempo()}."

        return jsonify({
            "status": "sucesso",
            "mensagem": mensagem,
            **info,
        }), 200
    except UrlVideoError as erro:
        return jsonify({"status": "erro", "mensagem": str(erro)}), 400
    except Exception as erro:
        print(f"[VIDEO] Erro inesperado ao verificar link: {erro}")
        return jsonify({"status": "erro", "mensagem": "Nao consegui verificar esse link agora."}), 500


@app.post("/api/video/jobs")
def criar_job_video():
    limpar_jobs_antigos()
    dados = request.get_json(silent=True) or {}
    url = str(dados.get("url") or "").strip()
    modo = str(dados.get("modo") or "video_hd").strip()
    modo = modo if modo in {"video_hd", "video", "mp3"} else "video_hd"

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para baixar."}), 400

    job_id = uuid.uuid4().hex
    job = {
        "id": job_id,
        "status": "queued",
        "etapa": "fila",
        "progresso": 1,
        "mensagem": "Download entrou na fila.",
        "erro": "",
        "criado_em_ts": time.time(),
        "atualizado_em_ts": time.time(),
    }

    with JOBS_LOCK:
        JOBS[job_id] = job

    thread = threading.Thread(target=processar_job_video, args=(job_id, url, modo), daemon=True)
    thread.start()

    return jsonify({
        "status": "sucesso",
        "job": payload_job(obter_job(job_id)),
    }), 202


@app.get("/api/video/jobs/<job_id>")
def status_job_video(job_id):
    limpar_jobs_antigos()
    job = obter_job(job_id)

    if not job:
        return jsonify({"status": "erro", "mensagem": "Download nao encontrado ou expirado."}), 404

    return jsonify({
        "status": "sucesso",
        "job": payload_job(job),
    }), 200


@app.get("/api/video/jobs/<job_id>/download")
def baixar_resultado_job(job_id):
    limpar_jobs_antigos()
    job = obter_job(job_id)

    if not job:
        return jsonify({"status": "erro", "mensagem": "Download nao encontrado ou expirado."}), 404

    if job.get("status") != "done":
        return jsonify({"status": "erro", "mensagem": "Download ainda nao terminou."}), 409

    output_path = Path(str(job.get("output_path") or ""))

    if not output_path.exists():
        return jsonify({"status": "erro", "mensagem": "Arquivo expirou. Baixe novamente."}), 410

    conteudo = output_path.read_bytes()
    resposta = send_file(
        io.BytesIO(conteudo),
        mimetype=job.get("mimetype") or "application/octet-stream",
        as_attachment=True,
        download_name=job.get("filename") or output_path.name,
    )
    resposta.headers["Cache-Control"] = "no-store"
    return resposta


@app.post("/api/video/download")
def baixar_video():
    dados = request.get_json(silent=True) or {}
    url = str(dados.get("url") or "").strip()
    modo = str(dados.get("modo") or "video_hd").strip()

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para baixar."}), 400

    temp_dir = tempfile.mkdtemp(prefix="amz-video-")

    try:
        limite_bytes = video_service.limits.max_output_bytes

        if modo == "mp3":
            output_path = video_service.download_audio(url, temp_dir, max_bytes=limite_bytes)
            mimetype = "audio/mpeg"
            filename = "amz-audio.mp3"
        elif modo == "video":
            output_path = video_service.download_video(url, temp_dir, max_bytes=limite_bytes, max_width=540)
            mimetype = "video/mp4"
            filename = "amz-video.mp4"
        else:
            output_path = video_service.download_video(url, temp_dir, max_bytes=limite_bytes, max_width=720)
            mimetype = "video/mp4"
            filename = "amz-video-hd.mp4"

        conteudo = Path(output_path).read_bytes()
        resposta = send_file(
            io.BytesIO(conteudo),
            mimetype=mimetype,
            as_attachment=True,
            download_name=filename,
        )
        resposta.headers["Cache-Control"] = "no-store"
        return resposta
    except UrlVideoError as erro:
        return jsonify({"status": "erro", "mensagem": str(erro)}), 400
    except Exception as erro:
        print(f"[VIDEO] Erro inesperado: {erro}")
        return jsonify({"status": "erro", "mensagem": "Nao consegui baixar esse link agora."}), 500
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=7860)
