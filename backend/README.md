# Backend AMZ

Backend do painel e bot Discord da AMZ Studios.

## Organizacao

- `app.py`: servidor HTTP usado pelo painel do site.
- `bot.py`: inicializacao do bot Discord.
- `database.py`: camada de banco de dados.
- `cogs/`: comandos e eventos do Discord separados por categoria.
- `services/`: logica compartilhada entre comandos e API.
- `security/`: verificacoes de permissao.

## Cuidados

- Nunca subir `backend/.env`.
- Evitar duplicar regras nos cogs; preferir `services/` quando a logica tambem for usada pela API.
- Se adicionar comando slash novo, manter nome/categoria consistente com os grupos atuais.

## Validacao rapida

```powershell
python -m py_compile backend\app.py backend\bot.py backend\database.py
```

Para verificar dependencias e importacao da API e das extensoes do bot, use um
ambiente virtual limpo com Python 3.14 e execute na raiz do repositorio:

```powershell
python -m pip install -r backend/requirements.txt
python -m pip check
python -m unittest discover -s backend/tests -v
```

O teste nao carrega `.env`, nao conecta ao Discord ou MongoDB e bloqueia acesso
a rede. O workflow `Backend startup` executa a mesma verificacao em Linux.
No Render, mantenha `backend` como Root Directory, `pip install -r requirements.txt`
como Build Command e `python app.py` como Start Command.

## Operacao no Render

A API usa Waitress com quatro workers HTTP, limite de 64 conexoes e descarte de
conexoes inativas apos 60 segundos. A API e o bot continuam no mesmo processo;
nao use multiplos workers de processo nem execute `bot.py` em outro servico, pois
isso abriria sessoes Discord duplicadas. Falha ao abrir a porta HTTP impede a
conexao do bot, e a parada inesperada de um servico essencial encerra o processo
para que o Render possa reinicia-lo. No Linux, SIGTERM/SIGINT encerra os trabalhos
do bot e o servidor HTTP; requisicoes em andamento podem ser interrompidas.

Use `/` como health check HTTP do Render, que verifica se o processo aceita
requisicoes enquanto o Discord inicia. Para o UptimeRobot, use `/api/health`:
retorna 200 quando o bot esta conectado e 503 quando esta offline. A resposta nao
e armazenada em cache, tolera latencia ainda indisponivel e informa `git_commit`
para identificar a versao publicada. O erro detalhado de inicializacao fica nos
logs do servico. Um monitor nao elimina suspensoes ou limites do provedor.

## Teste opcional de acesso ao YouTube no servidor

O YouTube pode recusar o IP do servidor com `Sign in to confirm you're not a bot`,
antes mesmo da conversao. Isso e diferente dos limites de tamanho/duracao.
Um PO-token provider pode ajudar em alguns casos, mas nao garante acesso.

Para testar o provider local `bgutil-ytdlp-pot-provider` 2.0.0 no Render:

- Build Command: `python build_youtube.py`.
- Start Command: `python start_with_youtube.py`.
- Requer Node.js 22.13+ LTS ou 24+ e npm, disponiveis nos runtimes nativos do Render.

O build fixa o plugin e o codigo JavaScript na mesma versao e verifica a revisao
Git. O provider roda como processo curto durante a extracao, sem abrir porta
publica e sem importar cookies pessoais. A inicializacao usa o cliente `mweb`
somente para YouTube. Uma trava compartilhada limita o provider a uma extracao
por vez, inclusive pela API publica; pedidos concorrentes recebem aviso de ocupado.
Valide o download completo a partir do Render; sucesso no PC nao comprova acesso
do servidor. Se continuar bloqueado, nao repita indefinidamente.

Para desativar, volte o Start Command para `python app.py` e o Build Command para
`pip install -r requirements.txt`. Sem `AMZ_YOUTUBE_POT_SERVER_HOME`, a extracao
continua com os clientes padrao do yt-dlp.

Referencias: [guia do yt-dlp](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide)
e [provider](https://github.com/Brainicism/bgutil-ytdlp-pot-provider).
