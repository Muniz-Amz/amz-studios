# AMZ Video API

API isolada para a pagina `Baixar Videos` do site AMZ Studios.

## Rotas

- `GET /` verifica se a API esta online.
- `GET /api/status` mostra limites do servidor.
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
