import os
import shutil
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from flask import Flask, jsonify, request, send_file
from flask_cors import CORS
from werkzeug.exceptions import BadRequest
from werkzeug.wsgi import ClosingIterator

from url_video_service import UrlVideoError, UrlVideoService


app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 16 * 1024
CORS(app)

video_service = UrlVideoService()
JOBS = {}
JOBS_LOCK = threading.Lock()
JOB_TTL_SECONDS = 60 * 30
MAX_ACTIVE_JOBS = 4
MAX_RETAINED_JOBS = 20
CHECK_SLOT = threading.BoundedSemaphore(1)
CONVERSION_SLOT = threading.Lock()
STARTED_AT = time.monotonic()
AUDIO_ESTIMATE_DEFAULT_SECONDS = max(20, int(os.getenv("AMZ_AUDIO_ESTIMATE_SECONDS", "75")))
AUDIO_AVERAGE_SECONDS = float(AUDIO_ESTIMATE_DEFAULT_SECONDS)
# Um unico worker evita que varios ffmpeg/yt-dlp concorram pela CPU e memoria
# limitada do Space. Novos pedidos continuam recebendo progresso de fila.
VIDEO_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="amz-video")


def limpar_jobs_antigos():
    agora = time.time()
    expirados = []

    with JOBS_LOCK:
        for job_id, job in JOBS.items():
            # A validade comeca quando o processamento termina. Nunca apagar
            # arquivos em processamento ou enquanto uma resposta os transmite.
            if (job.get("status") in {"done", "error"}
                    and not job.get("downloads_ativos", 0)
                    and agora - float(job.get("atualizado_em_ts", agora)) > JOB_TTL_SECONDS):
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


def estimativa_fila(job_id):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job or job.get("status") != "queued":
            return 0, 0

        fila = sorted(
            (item for item in JOBS.values() if item.get("status") == "queued"),
            key=lambda item: float(item.get("criado_em_ts", 0)),
        )
        posicao = next((indice for indice, item in enumerate(fila, start=1) if item.get("id") == job_id), 0)
        ha_execucao = any(item.get("status") == "running" for item in JOBS.values())
        espera_unidades = posicao if ha_execucao else max(posicao - 1, 0)
        return posicao, max(0, round(espera_unidades * AUDIO_AVERAGE_SECONDS))


def registrar_tempo_audio(segundos):
    global AUDIO_AVERAGE_SECONDS
    tempo = max(20, min(float(segundos), 300))
    with JOBS_LOCK:
        AUDIO_AVERAGE_SECONDS = (AUDIO_AVERAGE_SECONDS * 0.7) + (tempo * 0.3)


def payload_job(job):
    posicao_fila, tempo_estimado_segundos = estimativa_fila(job.get("id"))
    return {
        "id": job.get("id"),
        "status": job.get("status"),
        "etapa": job.get("etapa"),
        "progresso": job.get("progresso", 0),
        "mensagem": job.get("mensagem", ""),
        "erro": job.get("erro", ""),
        "filename": job.get("filename", ""),
        "mimetype": job.get("mimetype", ""),
        "posicao_fila": posicao_fila,
        "tempo_estimado_segundos": tempo_estimado_segundos,
        "download_url": f"/api/video/jobs/{job.get('id')}/download" if job.get("status") == "done" else None,
    }


def configurar_saida_download(modo):
    return "audio/mpeg", "amz-audio.mp3"


def processar_job_video(job_id, url, modo):
    with CONVERSION_SLOT:
        _processar_job_video(job_id, url, modo)


def _processar_job_video(job_id, url, modo):
    temp_dir = None
    mimetype, filename = configurar_saida_download(modo)

    def progresso(etapa, valor, mensagem):
        atualizar_job(job_id, etapa=etapa, progresso=valor, mensagem=mensagem)

    try:
        temp_dir = tempfile.mkdtemp(prefix="amz-video-job-")
        atualizar_job(
            job_id,
            status="running",
            etapa="validando",
            progresso=2,
            mensagem="Preparando servidor...",
            temp_dir=temp_dir,
            mimetype=mimetype,
            filename=filename,
            iniciado_em_ts=time.time(),
        )
        limite_bytes = video_service.limits.max_output_bytes

        output_path = video_service.download_audio(url, temp_dir, max_bytes=limite_bytes, progress_callback=progresso)
        job = obter_job(job_id)
        if job and job.get("iniciado_em_ts"):
            registrar_tempo_audio(time.time() - float(job["iniciado_em_ts"]))

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
        if temp_dir:
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
        if temp_dir:
            shutil.rmtree(temp_dir, ignore_errors=True)


def servidor_ocupado(mensagem="Servidor ocupado. Tente novamente em alguns segundos."):
    resposta = jsonify({"status": "erro", "mensagem": mensagem})
    resposta.status_code = 503
    resposta.headers["Retry-After"] = "15"
    resposta.headers["Cache-Control"] = "no-store"
    return resposta


def dados_pedido():
    dados = request.get_json(silent=True)
    if dados is None:
        return {}
    if not isinstance(dados, dict):
        raise BadRequest("Envie um objeto JSON com o link.")
    return dados


@app.errorhandler(400)
def pedido_invalido(_erro):
    return jsonify({"status": "erro", "mensagem": "Envie um objeto JSON com o link."}), 400


def ao_fechar_download(resposta, callback):
    # send_file usa direct_passthrough: call_on_close sozinho nao recebe o
    # fechamento do iterador WSGI. Fechar o arquivo antes de liberar/apagar.
    resposta.response = ClosingIterator(resposta.response, [callback])
    resposta.headers["Cache-Control"] = "no-store"
    return resposta


@app.errorhandler(413)
def pedido_grande_demais(_erro):
    return jsonify({"status": "erro", "mensagem": "Pedido grande demais. Envie somente o link."}), 413


@app.get("/api/health")
def health():
    # Nao consulta plataformas externas nem inicia processamento.
    with JOBS_LOCK:
        queued = sum(job.get("status") == "queued" for job in JOBS.values())
        running = sum(job.get("status") == "running" for job in JOBS.values())
    resposta = jsonify({
        "status": "ok", "service": "amz-audio-api",
        "uptime_seconds": round(time.monotonic() - STARTED_AT),
        "queued_jobs": queued, "running_jobs": running,
        "max_active_jobs": MAX_ACTIVE_JOBS,
    })
    resposta.headers["Cache-Control"] = "no-store"
    return resposta


@app.get("/")
def root():
    return jsonify({
        "status": "online",
        "service": "amz-audio-api",
        "routes": ["/api/health", "/api/status", "/api/video/check", "/api/video/jobs", "/api/video/download"],
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
        "output_mode": "mp3",
    })


@app.post("/api/video/check")
def verificar_video():
    limpar_jobs_antigos()
    dados = dados_pedido()
    url = str(dados.get("url") or "").strip()

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para verificar."}), 400

    if not CHECK_SLOT.acquire(blocking=False):
        return servidor_ocupado()

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
    finally:
        CHECK_SLOT.release()


@app.post("/api/video/jobs")
def criar_job_video():
    limpar_jobs_antigos()
    dados = dados_pedido()
    url = str(dados.get("url") or "").strip()
    modo = str(dados.get("modo") or "mp3").strip().lower()

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para baixar."}), 400

    if modo != "mp3":
        return jsonify({"status": "erro", "mensagem": "Este serviço processa somente áudio MP3."}), 400

    job_id = uuid.uuid4().hex
    job = {
        "id": job_id,
        "status": "queued",
        "etapa": "fila",
        "progresso": 1,
        "mensagem": "Áudio entrou na fila prioritária.",
        "erro": "",
        "criado_em_ts": time.time(),
        "atualizado_em_ts": time.time(),
        "url": url,
        "modo": modo,
    }

    with JOBS_LOCK:
        # Deduplique e reserve a vaga no mesmo lock: requisicoes simultaneas
        # nao podem ultrapassar o limite ou gerar dois trabalhos identicos.
        job_existente = next((
            dict(item) for item in JOBS.values()
            if item.get("url") == url and item.get("modo") == modo
            and item.get("status") in {"queued", "running"}
        ), None)
        if not job_existente:
            ativos = sum(item.get("status") in {"queued", "running"} for item in JOBS.values())
            if ativos >= MAX_ACTIVE_JOBS or len(JOBS) >= MAX_RETAINED_JOBS:
                return servidor_ocupado("Fila cheia. Aguarde os downloads atuais e tente novamente.")
            JOBS[job_id] = job
            try:
                VIDEO_EXECUTOR.submit(processar_job_video, job_id, url, modo)
            except RuntimeError:
                JOBS.pop(job_id, None)
                return servidor_ocupado()

    if job_existente:
        return jsonify({"status": "sucesso", "job": payload_job(job_existente), "reutilizado": True}), 202

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
    with JOBS_LOCK:
        original = JOBS.get(job_id)
        if not original:
            return jsonify({"status": "erro", "mensagem": "Download nao encontrado ou expirado."}), 404
        if original.get("status") != "done":
            return jsonify({"status": "erro", "mensagem": "Download ainda nao terminou."}), 409
        original["downloads_ativos"] = original.get("downloads_ativos", 0) + 1
        job = dict(original)

    def liberar_download():
        with JOBS_LOCK:
            original = JOBS.get(job_id)
            if original:
                original["downloads_ativos"] = max(0, original.get("downloads_ativos", 0) - 1)

    output_path = Path(str(job.get("output_path") or ""))

    if not output_path.is_file():
        liberar_download()
        return jsonify({"status": "erro", "mensagem": "Arquivo expirou. Baixe novamente."}), 410

    try:
        resposta = send_file(
            output_path,
            mimetype=job.get("mimetype") or "application/octet-stream",
            as_attachment=True,
            download_name=job.get("filename") or output_path.name,
        )
        return ao_fechar_download(resposta, liberar_download)
    except Exception:
        liberar_download()
        raise


@app.post("/api/video/download")
def baixar_video():
    dados = dados_pedido()
    url = str(dados.get("url") or "").strip()
    modo = str(dados.get("modo") or "mp3").strip().lower()

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para baixar."}), 400

    if modo != "mp3":
        return jsonify({"status": "erro", "mensagem": "Este serviço processa somente áudio MP3."}), 400

    if not CONVERSION_SLOT.acquire(blocking=False):
        return servidor_ocupado("Servidor processando outro audio. Use a fila ou tente novamente.")
    temp_dir = None
    resposta_enviada = False

    try:
        temp_dir = tempfile.mkdtemp(prefix="amz-video-")
        limite_bytes = video_service.limits.max_output_bytes

        output_path = video_service.download_audio(url, temp_dir, max_bytes=limite_bytes)
        mimetype = "audio/mpeg"
        filename = "amz-audio.mp3"

        resposta = send_file(
            output_path,
            mimetype=mimetype,
            as_attachment=True,
            download_name=filename,
        )
        resposta = ao_fechar_download(resposta, lambda: shutil.rmtree(temp_dir, ignore_errors=True))
        resposta_enviada = True
        return resposta
    except UrlVideoError as erro:
        return jsonify({"status": "erro", "mensagem": str(erro)}), 400
    except Exception as erro:
        print(f"[VIDEO] Erro inesperado: {erro}")
        return jsonify({"status": "erro", "mensagem": "Nao consegui baixar esse link agora."}), 500
    finally:
        CONVERSION_SLOT.release()
        if temp_dir and not resposta_enviada:
            shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=7860)
