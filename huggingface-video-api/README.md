---
title: AMZ Audio API
emoji: 🎵
colorFrom: blue
colorTo: gray
sdk: docker
app_port: 7860
pinned: false
license: mit
---

# AMZ Audio API

API isolada para a pagina `Baixar MP3` do site AMZ Studios.

Limites padrao do Space:

- Duracao maxima: 5 minutos.
- Saida maxima: 50 MB.
- Timeout de processamento: 420 segundos.

## Rotas

- `GET /` verifica se a API esta online.
- `GET /api/status` mostra limites do servidor.
- `POST /api/video/jobs` cria um download com progresso.
- `GET /api/video/jobs/<job_id>` mostra etapa/progresso do download.
- `GET /api/video/jobs/<job_id>/download` baixa o arquivo pronto.
- `POST /api/video/download` baixa áudio em MP3 para compatibilidade com versões anteriores do site.

## Payload

```json
{
  "url": "https://exemplo.com/conteudo-publico",
  "modo": "mp3"
}
```

O único modo aceito é `mp3`. Pedidos idênticos em andamento reutilizam a mesma fila.

## Hugging Face Space

Crie o Space como `Docker` e envie todos os arquivos desta pasta para a raiz do Space.
