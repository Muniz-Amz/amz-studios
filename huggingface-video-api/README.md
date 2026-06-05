---
title: AMZ Video API
emoji: 🎬
colorFrom: blue
colorTo: gray
sdk: docker
app_port: 7860
pinned: false
license: mit
---

# AMZ Video API

API isolada para a pagina `Baixar Videos` do site AMZ Studios.

Limites padrao do Space:

- Duracao maxima: 5 minutos.
- Saida maxima: 50 MB.
- Timeout de processamento: 420 segundos.

## Rotas

- `GET /` verifica se a API esta online.
- `GET /api/status` mostra limites do servidor.
- `POST /api/video/check` verifica titulo/duracao antes do download pesado.
- `POST /api/video/jobs` cria um download com progresso.
- `GET /api/video/jobs/<job_id>` mostra etapa/progresso do download.
- `GET /api/video/jobs/<job_id>/download` baixa o arquivo pronto.
- `POST /api/video/download` baixa/converte links em MP4 ou MP3.

## Payload

```json
{
  "url": "https://vt.tiktok.com/...",
  "modo": "video_hd"
}
```

Modos aceitos:

- `video_hd`
- `video`
- `mp3`

## Hugging Face Space

Crie o Space como `Docker` e envie todos os arquivos desta pasta para a raiz do Space.
