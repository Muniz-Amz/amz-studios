# AMZ MP3 Extractor

Serviço Docker independente do bot Discord. Ele aceita links públicos de YouTube,
TikTok e Instagram, além de arquivos próprios de áudio ou vídeo, e entrega
somente áudio MP3 através de uma fila de um worker.

## Publicar no Render

1. Crie um **Web Service** a partir deste repositório.
2. Defina **Root Directory** como `backend/mp3_extractor`.
3. Escolha o ambiente **Docker** e use o nome `amz-mp3-api`.
4. Configure o health check como `/health`.
5. Depois do primeiro deploy, confirme que `https://amz-mp3-api.onrender.com/api/mp3/status` responde `online`.

O Dockerfile já instala `ffmpeg`, `node` e a edição atual do `yt-dlp` com
`curl-cffi`. A extração de links é pública e não aceita nem usa cookies,
tokens ou login de navegador.

## Endpoints

- `POST /api/mp3/jobs`: recebe `{ "url": "https://..." }` e cria um job
  para um link público.
- `POST /api/mp3/uploads`: recebe `multipart/form-data` com o campo `file`.
  Aceita mídia própria de até 50 MB por padrão; a extensão e o MIME são
  conferidos e o `ffprobe` confirma que existe uma faixa de áudio real.
- `GET /api/mp3/jobs/<id>` e `GET /api/mp3/jobs/<id>/download`: acompanham e
  baixam jobs dos dois tipos.

Arquivos enviados ficam em diretório temporário, o original é apagado ao fim
da conversão e o MP3 restante expira automaticamente.

## Variáveis opcionais

- `AMZ_MP3_MAX_OUTPUT_MB` (padrão: `50`)
- `AMZ_MP3_MAX_UPLOAD_MB` (padrão: `50`; mínimo `5`, máximo `75`)
- `AMZ_MP3_MAX_SECONDS` (padrão: `300`)
- `AMZ_MP3_MAX_QUEUE_SIZE` (padrão: `8`)
- `AMZ_MP3_MAX_JOBS_PER_IP` (padrão: `3` por 10 minutos)
- `AMZ_MP3_ALLOWED_ORIGINS` (padrão: `https://muniz-amz.github.io`)
- `AMZ_MP3_JOB_TTL_SECONDS` (padrão: `1800`)
- `AMZ_MP3_CLEANUP_INTERVAL_SECONDS` (padrão: `60`)

## Remover depois

Desative ou apague somente o serviço `amz-mp3-api` no Render e esta pasta
`backend/mp3_extractor/`. Em seguida, remova a integração MP3 do site. O bot,
o painel e os demais serviços não dependem deste diretório.
