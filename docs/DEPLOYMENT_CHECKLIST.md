# Checklist de Deploy e Manutencao

## Antes de subir para o Git

- Rodar `git status --short` e conferir se nao tem `.env`, logs ou arquivos temporarios.
- Rodar `node --check script.js`.
- Rodar `node --check amz-chat-secreto/app.js`.
- Rodar `git diff --check`.

## GitHub Pages

- A raiz do site precisa manter `index.html`.
- O chat secreto fica em `/amz-chat-secreto/`.
- Se mudar arquivos CSS/JS, atualizar o `?v=` no HTML para evitar cache antigo.
- Nao colocar o chat secreto no `sitemap.xml`.

## Supabase do chat secreto

- `supabase.sql`: estrutura base.
- `supabase-storage-hotfix.sql`: fila segura de limpeza de midias.
- `supabase-e2ee-hotfix.sql`: aceita arquivos criptografados.
- `supabase-cleanup-schedule-10h.sql`: agenda limpeza periodica.

## Backend do bot

- Variaveis sensiveis ficam em `backend/.env`.
- O `.env` nao deve ser commitado.
- Reiniciar/deployar o servidor quando mudar `backend/`.

## API de video

- A pasta `huggingface-video-api/` vai separada para o Hugging Face Space.
- O site principal usa a URL configurada em `script.js`.
- Se a URL do Space mudar, atualizar `VIDEO_API_URL`.
