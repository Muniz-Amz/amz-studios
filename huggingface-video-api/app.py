import io
import shutil
import tempfile
from pathlib import Path

from flask import Flask, jsonify, request, send_file
from flask_cors import CORS

from url_video_service import UrlVideoError, UrlVideoService


app = Flask(__name__)
CORS(app)

video_service = UrlVideoService()


@app.get("/")
def root():
    return jsonify({
        "status": "online",
        "service": "amz-video-api",
        "routes": ["/api/status", "/api/video/download"],
    })


@app.get("/api/status")
def status():
    return jsonify({
        "status": "online",
        "max_output_mb": video_service.limits.max_output_mb,
        "max_seconds": video_service.limits.max_seconds,
        "max_width": video_service.limits.max_width,
        "fps": video_service.limits.fps,
    })


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
