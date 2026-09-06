FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PORT=7860
ENV AMZ_URLVIDEO_MAX_OUTPUT_MB=50
ENV AMZ_URLVIDEO_MAX_SECONDS=300
ENV AMZ_URLVIDEO_TIMEOUT_SECONDS=420
ENV AMZ_URLVIDEO_MAX_WIDTH=720
ENV AMZ_URLVIDEO_FPS=24

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir --upgrade -r requirements.txt \
    && python -m yt_dlp --version

COPY . .

EXPOSE 7860

CMD ["gunicorn", "-b", "0.0.0.0:7860", "--timeout", "450", "--workers", "1", "app:app"]
