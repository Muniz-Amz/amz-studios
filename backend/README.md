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
