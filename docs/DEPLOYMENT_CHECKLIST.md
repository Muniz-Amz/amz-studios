# Checklist de Deploy e Manutencao

## Antes de subir para o Git

- Rodar `git status --short` e conferir se nao tem `.env`, logs ou arquivos temporarios.
- Rodar `node --check script.js`.
- Rodar `git diff --check`.

## GitHub Pages

- A raiz do site precisa manter `index.html`.
- Se mudar arquivos CSS/JS, atualizar o `?v=` no HTML para evitar cache antigo.
## Backend do bot

- Variaveis sensiveis ficam em `backend/.env`.
- O `.env` nao deve ser commitado.
- Reiniciar/deployar o servidor quando mudar `backend/`.
- Root Directory: `backend`; Start Command: `python app.py`.
- Health Check Path interno: `/`. O watchdog supervisiona a conexao do Discord.
- Auto-Deploy: `After CI Checks Pass`; o workflow `Backend startup` valida os
  tres servicos em Linux com as respectivas versoes de Python.
- Monitor externo do bot: `https://amz-studios-api.onrender.com/api/health`.
  HTTP 200 confirma o bot conectado; HTTP 503 indica indisponibilidade.

## Servico de extracao MP3

- A raiz do servico no Render e `backend/mp3_extractor/`; ele deve permanecer separado do bot para poder ser removido depois sem alterar o restante do backend.
- O site principal usa `MP3_API_URL` em `script.js`, apontando para `https://amz-mp3-api.onrender.com`.
- O contrato publico do site usa `POST /api/mp3/jobs`, `GET /api/mp3/jobs/<id>` e `GET /api/mp3/jobs/<id>/download` para manter fila, estimativa e progresso.
- Se a URL do servico mudar, atualizar somente `MP3_API_URL`.
- Health Check Path: `/health`; Auto-Deploy: `After CI Checks Pass`.
- O Dockerfile testa a API e converte audio gerado localmente antes do deploy.

## Hugging Face

- Space: `Dreadlord007/amz-video-api`, Docker, porta 7860.
- Os arquivos de `huggingface-video-api/`, incluindo `tests/`, devem estar na raiz
  do Space. Um push apenas no GitHub nao sincroniza esse repositorio separado.
- O Dockerfile testa concorrencia, fila, limites de pedidos e downloads antes
  de iniciar. Confirme `/api/health` depois da publicacao.
- Endereco de monitoramento: `https://dreadlord007-amz-video-api.hf.space/api/health`.
- Render e Hugging Face continuam nos planos gratuitos; monitores nao eliminam
  cotas de uso ou suspensoes determinadas pelos provedores.
