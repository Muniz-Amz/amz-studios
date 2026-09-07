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

## Servico de extracao MP3

- A raiz do servico no Render e `backend/mp3_extractor/`; ele deve permanecer separado do bot para poder ser removido depois sem alterar o restante do backend.
- O site principal usa `MP3_API_URL` em `script.js`, apontando para `https://amz-mp3-api.onrender.com`.
- O contrato publico do site usa `POST /api/mp3/jobs`, `GET /api/mp3/jobs/<id>` e `GET /api/mp3/jobs/<id>/download` para manter fila, estimativa e progresso.
- Se a URL do servico mudar, atualizar somente `MP3_API_URL`.
