# AMZ MP3 Extractor

Serviço Docker independente do bot Discord. Ele aceita links públicos de YouTube,
TikTok e Instagram e entrega somente áudio MP3 através de uma fila de um worker.

## Publicar no Render

1. Crie um **Web Service** a partir deste repositório.
2. Defina **Root Directory** como `backend/mp3_extractor`.
3. Escolha o ambiente **Docker** e use o nome `amz-mp3-api`.
4. Configure o health check como `/api/mp3/status`.
5. Depois do primeiro deploy, confirme que `https://amz-mp3-api.onrender.com/api/mp3/status` responde `online`.

O Dockerfile já instala `ffmpeg`, `node` e a edição atual do `yt-dlp` com
`curl-cffi`. Não copie tokens nem cookies do navegador para o repositório.

## Variáveis opcionais

- `AMZ_MP3_MAX_OUTPUT_MB` (padrão: `50`)
- `AMZ_MP3_MAX_SECONDS` (padrão: `300`)
- `AMZ_MP3_MAX_QUEUE_SIZE` (padrão: `8`)
- `AMZ_MP3_MAX_JOBS_PER_IP` (padrão: `3` por 10 minutos)
- `AMZ_MP3_ALLOWED_ORIGINS` (padrão: `https://muniz-amz.github.io`)
- `AMZ_MP3_YTDLP_COOKIES_B64` (secret opcional; nunca versionar)

## Remover depois

Desative ou apague somente o serviço `amz-mp3-api` no Render e esta pasta
`backend/mp3_extractor/`. Em seguida, remova a integração MP3 do site. O bot,
o painel e os demais serviços não dependem deste diretório.
