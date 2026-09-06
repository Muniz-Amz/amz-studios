# Estrutura do Projeto AMZ

Este arquivo serve como mapa para mexer no projeto sem baguncar caminhos publicos.

## Raiz

- `index.html`: pagina principal do GitHub Pages.
- `style.css`: estilos do site principal.
- `script.js`: painel, downloads, tema, login Discord e integracoes do site.
- `robots.txt`, `sitemap.xml`, `sitemap.txt`: SEO e indexacao.
- `google8c9936da8e6a1d03.html`: verificacao do Google Search Console.

## `assets/`

- `logo.png` e `logo.ico`: identidade visual usada pelo site.
- `meu-site-downloads/`: arquivos publicados para download no site.

## `backend/`

Servidor do painel/API e bot Discord.

- `app.py`: API HTTP do painel e rotas principais.
- `bot.py`: inicializacao do bot Discord.
- `database.py`: acesso e persistencia no MongoDB.
- `cogs/`: grupos de comandos do Discord.
- `services/`: regras de negocio reutilizadas por API e cogs.
- `security/`: verificacoes de permissao.

## `huggingface-video-api/`

Servidor separado para downloads/conversoes de video.

- `app.py`: rotas HTTP.
- `url_video_service.py`: logica de validacao, download e conversao.
- `Dockerfile`: ambiente do Hugging Face Space.
