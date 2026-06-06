# AMZ Studios

Repositorio principal do site AMZ Studios, painel do bot, chat secreto, backend do bot Discord e API isolada de videos.

## Mapa rapido

- `index.html`, `style.css`, `script.js`: site principal publicado no GitHub Pages.
- `assets/`: logos e arquivos publicos de download.
- `amz-chat-secreto/`: pagina privada de conversa, midias, criptografia e chamadas.
- `backend/`: API/painel e bot Discord hospedados fora do GitHub Pages.
- `huggingface-video-api/`: API Docker separada para baixar/converter videos.
- `docs/`: documentacao de organizacao, deploy e manutencao.

## Regras para nao quebrar o site

- Nao mover `index.html`, `style.css`, `script.js`, `robots.txt`, `sitemap.xml` ou `google8c9936da8e6a1d03.html` sem atualizar URLs.
- Nao mover arquivos dentro de `assets/meu-site-downloads/` sem atualizar os links do site.
- Nao publicar `.env`, tokens, cookies ou chaves privadas.
- O chat secreto nao deve entrar no sitemap nem no menu publico.

## Validacao rapida

```powershell
node --check script.js
node --check amz-chat-secreto/app.js
git diff --check
```

## Links principais

- Site: `https://muniz-amz.github.io/amz-studios/`
- Chat secreto: `https://muniz-amz.github.io/amz-studios/amz-chat-secreto/`
