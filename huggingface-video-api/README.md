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

## Estabilidade e monitoramento

- `GET /api/health` responde sem consultar plataformas externas e informa a fila.
- Um processo Gunicorn atende com quatro threads HTTP; uma conversao por vez
  evita concorrencia excessiva de ffmpeg. Nao aumente o numero de processos,
  pois os jobs sao mantidos na memoria deste processo.
- A fila aceita ate quatro jobs ativos e mantem ate vinte resultados/erros.
  Quando cheia, retorna HTTP 503 e `Retry-After: 15`.
- Resultados expiram trinta minutos depois da conclusao. Jobs em execucao e
  arquivos sendo baixados ficam protegidos da limpeza.
- O download envia o arquivo em partes, sem carregar o arquivo inteiro na RAM.
- Pedidos JSON tem limite de 16 KiB.
- O Dockerfile executa `python -m unittest discover -s tests -v` antes de publicar.
  Envie tambem a pasta `tests/` ao Space.

O plano gratuito pode adormecer por inatividade. O monitor de disponibilidade
deve usar `https://dreadlord007-amz-video-api.hf.space/api/health`.
