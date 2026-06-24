import asyncio
import os
import re
import uuid
from datetime import datetime, timezone

import discord

from bot import bot


ROLE_ANNOUNCEMENT_MAX_RECIPIENTS = max(1, int(os.getenv("AMZ_ROLE_ANNOUNCEMENT_MAX_RECIPIENTS", "200")))
ROLE_ANNOUNCEMENT_SEND_DELAY = max(0.2, float(os.getenv("AMZ_ROLE_ANNOUNCEMENT_SEND_DELAY", "0.7")))
ROLE_ANNOUNCEMENT_DEFAULT_BATCH_SIZE = max(1, int(os.getenv("AMZ_ROLE_ANNOUNCEMENT_BATCH_SIZE", "5")))
ROLE_ANNOUNCEMENT_MAX_BATCH_SIZE = max(1, int(os.getenv("AMZ_ROLE_ANNOUNCEMENT_MAX_BATCH_SIZE", "20")))
ROLE_ANNOUNCEMENT_BATCH_PAUSE_SECONDS = max(3, int(os.getenv("AMZ_ROLE_ANNOUNCEMENT_BATCH_PAUSE_SECONDS", "15")))
ROLE_ANNOUNCEMENT_ACTIVE_JOBS = {}


def agora_iso():
    return datetime.now(timezone.utc).isoformat()


def obter_guild_bot(server_id):
    try:
        return bot.get_guild(int(server_id))
    except (TypeError, ValueError):
        return None


def limpar_texto_anuncio_cargo(valor, limite=900):
    texto = str(valor or "").strip()
    if len(texto) <= limite:
        return texto
    return texto[:limite].rstrip()


def normalizar_bool_anuncio(valor, padrao=False):
    if isinstance(valor, bool):
        return valor
    if valor is None:
        return padrao
    if isinstance(valor, str):
        return valor.strip().lower() in ("1", "true", "sim", "yes", "on")
    return bool(valor)


def normalizar_cor_anuncio(valor):
    texto = str(valor or "#35d8ff").strip()
    if not texto.startswith("#"):
        texto = f"#{texto}"
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", texto):
        texto = "#35d8ff"
    return int(texto[1:], 16), texto.lower()


def normalizar_limite_anuncio(valor):
    try:
        numero = int(valor)
    except (TypeError, ValueError):
        numero = 50
    return min(max(numero, 1), ROLE_ANNOUNCEMENT_MAX_RECIPIENTS)


def normalizar_lote_anuncio(valor):
    try:
        numero = int(valor)
    except (TypeError, ValueError):
        numero = ROLE_ANNOUNCEMENT_DEFAULT_BATCH_SIZE
    return min(max(numero, 1), ROLE_ANNOUNCEMENT_MAX_BATCH_SIZE)


def normalizar_pausa_lote_anuncio(valor):
    try:
        numero = int(valor)
    except (TypeError, ValueError):
        numero = ROLE_ANNOUNCEMENT_BATCH_PAUSE_SECONDS
    return min(max(numero, 3), 300)


def normalizar_delay_dm_anuncio(valor):
    try:
        numero = float(valor)
    except (TypeError, ValueError):
        numero = ROLE_ANNOUNCEMENT_SEND_DELAY
    return min(max(numero, 0.7), 30.0)


def substituir_variaveis_anuncio(texto, guild, role, member=None):
    usuario_nome = getattr(member, "display_name", "membro do cargo" if role else "membro")
    usuario_tag = str(member) if member else "usuario#0000"
    role_name = getattr(role, "name", "Todos")
    role_mention = getattr(role, "mention", "@everyone")
    substituicoes = {
        "{user}": usuario_nome,
        "{username}": usuario_nome,
        "{user_tag}": usuario_tag,
        "{mention}": getattr(member, "mention", "@usuario"),
        "{id}": str(getattr(member, "id", "")),
        "{server}": guild.name,
        "{server_upper}": guild.name.upper(),
        "{member_count}": str(guild.member_count or len(guild.members)),
        "{role}": role_name,
        "{role_mention}": role_mention,
    }

    mensagem = str(texto or "")
    for chave, valor in substituicoes.items():
        mensagem = mensagem.replace(chave, valor)
    return mensagem.strip()


def preparar_config_anuncio_cargo(guild, dados):
    role = None
    role_id_bruto = str(dados.get("roleId") or "").strip()
    if role_id_bruto:
        try:
            role_id = int(role_id_bruto)
        except (TypeError, ValueError):
            return None, "Escolha um cargo valido para filtrar os membros."

        role = guild.get_role(role_id)
        if not role or role.is_default():
            return None, "Cargo nao encontrado ou invalido para filtro."

    enviar_canal = normalizar_bool_anuncio(dados.get("sendChannel"), True)
    enviar_dm = normalizar_bool_anuncio(dados.get("sendDm"), True) and role is not None
    incluir_bots = normalizar_bool_anuncio(dados.get("includeBots"), False)

    if not enviar_canal and not enviar_dm:
        if role is None:
            return None, "Sem cargo selecionado, ative o envio no canal para publicar um anuncio geral."
        return None, "Ative pelo menos um destino: canal de anuncio ou privado dos membros."

    canal = None
    if enviar_canal:
        try:
            channel_id = int(dados.get("channelId") or 0)
        except (TypeError, ValueError):
            return None, "Escolha um canal de anuncio valido."

        canal = guild.get_channel(channel_id)
        if not isinstance(canal, discord.TextChannel):
            return None, "Canal de anuncio nao encontrado."

        membro_bot = guild.me
        if not membro_bot:
            return None, "Bot nao encontrado no servidor."
        permissoes = canal.permissions_for(membro_bot)
        if not permissoes.view_channel or not permissoes.send_messages:
            return None, "Bot sem permissao para ver ou enviar mensagem no canal escolhido."

    titulo = limpar_texto_anuncio_cargo(dados.get("title"), 240) or "Comunicado importante"
    mensagem = limpar_texto_anuncio_cargo(dados.get("message"), 1800)
    if not mensagem:
        return None, "Escreva a mensagem do anuncio antes de enviar."

    cor_int, cor_texto = normalizar_cor_anuncio(dados.get("color"))
    imagem_url = limpar_texto_anuncio_cargo(dados.get("imageUrl"), 500)
    if imagem_url and not imagem_url.lower().startswith(("http://", "https://")):
        imagem_url = ""

    return {
        "role": role,
        "canal": canal,
        "sendChannel": enviar_canal,
        "sendDm": enviar_dm,
        "includeBots": incluir_bots,
        "maxRecipients": normalizar_limite_anuncio(dados.get("maxRecipients")),
        "safeMode": True,
        "batchSize": normalizar_lote_anuncio(dados.get("batchSize")),
        "batchPauseSeconds": normalizar_pausa_lote_anuncio(dados.get("batchPauseSeconds")),
        "dmDelaySeconds": normalizar_delay_dm_anuncio(dados.get("dmDelaySeconds")),
        "title": titulo,
        "message": mensagem,
        "colorInt": cor_int,
        "color": cor_texto,
        "imageUrl": imagem_url,
        "footer": limpar_texto_anuncio_cargo(dados.get("footer"), 200) or f"AMZ Bot - {guild.name}",
    }, None


def construir_embed_anuncio_cargo(guild, config, member=None):
    role = config["role"]
    embed = discord.Embed(
        title=substituir_variaveis_anuncio(config["title"], guild, role, member),
        description=substituir_variaveis_anuncio(config["message"], guild, role, member),
        color=discord.Color(config["colorInt"]),
        timestamp=datetime.now(timezone.utc),
    )
    embed.add_field(name="Servidor", value=guild.name, inline=True)
    if role:
        embed.add_field(name="Cargo", value=role.name, inline=True)
    if config["imageUrl"]:
        embed.set_image(url=config["imageUrl"])
    if config["footer"]:
        embed.set_footer(text=config["footer"])
    return embed


def conteudo_texto_anuncio_cargo(guild, config, member=None):
    role = config["role"]
    titulo = substituir_variaveis_anuncio(config["title"], guild, role, member)
    mensagem = substituir_variaveis_anuncio(config["message"], guild, role, member)
    return f"**{titulo}**\n{mensagem}"[:1900]


async def coletar_membros_anuncio_cargo(guild, role, incluir_bots, limite):
    membros = []
    vistos = set()

    def adicionar(member):
        if not member or member.id in vistos:
            return
        if member.bot and not incluir_bots:
            return
        if role not in getattr(member, "roles", []):
            return
        vistos.add(member.id)
        membros.append(member)

    for member in role.members:
        adicionar(member)
        if len(membros) >= limite:
            return membros

    try:
        async for member in guild.fetch_members(limit=None):
            adicionar(member)
            if len(membros) >= limite:
                break
    except (discord.Forbidden, discord.HTTPException):
        pass

    return membros


async def executar_envio_anuncio_cargo_seguro(server_id, config, job_id, job_key):
    guild = obter_guild_bot(server_id)
    if not guild:
        ROLE_ANNOUNCEMENT_ACTIVE_JOBS.pop(job_key, None)
        return

    canal_enviado = False
    mensagem_canal_id = ""
    jump_url = ""
    canal = config["canal"]
    dms_enviadas = 0
    dms_falharam = 0
    membros = []
    role = config["role"]
    alvo_nome = f"@{role.name}" if role else "anuncio geral"
    role_id = getattr(role, "id", None)

    try:
        if hasattr(bot, "registrar_evento"):
            bot.registrar_evento(
                "role_announcement_started",
                f"Envio seguro iniciado para {alvo_nome}.",
                guild_id=guild.id,
                channel_id=getattr(canal, "id", None),
                role_id=role_id,
                job_id=job_id,
                batch_size=config["batchSize"],
                batch_pause_seconds=config["batchPauseSeconds"],
                dm_delay_seconds=config["dmDelaySeconds"],
            )

        if config["sendChannel"] and canal:
            permissoes = canal.permissions_for(guild.me)
            if permissoes.embed_links:
                mensagem_canal = await canal.send(
                    embed=construir_embed_anuncio_cargo(guild, config),
                    allowed_mentions=discord.AllowedMentions.none(),
                )
            else:
                mensagem_canal = await canal.send(
                    conteudo_texto_anuncio_cargo(guild, config),
                    allowed_mentions=discord.AllowedMentions.none(),
                )
            canal_enviado = True
            mensagem_canal_id = str(mensagem_canal.id)
            jump_url = mensagem_canal.jump_url

        if config["sendDm"] and role:
            membros = await coletar_membros_anuncio_cargo(
                guild,
                role,
                config["includeBots"],
                config["maxRecipients"],
            )

            total_membros = len(membros)
            for indice, member in enumerate(membros, start=1):
                try:
                    await member.send(embed=construir_embed_anuncio_cargo(guild, config, member))
                    dms_enviadas += 1
                except discord.Forbidden:
                    dms_falharam += 1
                except discord.HTTPException:
                    dms_falharam += 1

                terminou = indice >= total_membros
                fechou_lote = indice % config["batchSize"] == 0

                if hasattr(bot, "registrar_evento") and (fechou_lote or terminou):
                    bot.registrar_evento(
                        "role_announcement_progress",
                        f"Anuncio por cargo em progresso: {indice}/{total_membros} membro(s) processado(s).",
                        guild_id=guild.id,
                        channel_id=getattr(canal, "id", None),
                        role_id=role_id,
                        job_id=job_id,
                        processed=indice,
                        total=total_membros,
                        dm_sent=dms_enviadas,
                        dm_failed=dms_falharam,
                    )

                if terminou:
                    break

                if fechou_lote:
                    await asyncio.sleep(config["batchPauseSeconds"])
                else:
                    await asyncio.sleep(config["dmDelaySeconds"])

        if hasattr(bot, "registrar_evento"):
            bot.registrar_evento(
                "role_announcement_sent",
                f"Envio concluido para {alvo_nome}.",
                guild_id=guild.id,
                channel_id=getattr(canal, "id", None),
                role_id=role_id,
                job_id=job_id,
                channel_sent=canal_enviado,
                message_id=mensagem_canal_id,
                jump_url=jump_url,
                dm_sent=dms_enviadas,
                dm_failed=dms_falharam,
                members_matched=len(membros),
            )
    except Exception as erro:
        if hasattr(bot, "registrar_evento"):
            bot.registrar_evento(
                "role_announcement_error",
                f"Erro no envio seguro de anuncio: {erro}",
                nivel="error",
                guild_id=getattr(guild, "id", server_id),
                channel_id=getattr(canal, "id", None),
                role_id=getattr(config.get("role"), "id", None),
                job_id=job_id,
            )
    finally:
        ROLE_ANNOUNCEMENT_ACTIVE_JOBS.pop(job_key, None)


async def iniciar_anuncio_cargo_async(server_id, dados):
    guild = obter_guild_bot(server_id)
    if not guild:
        return None, "Servidor nao encontrado pelo bot."

    config, erro = preparar_config_anuncio_cargo(guild, dados or {})
    if erro:
        return None, erro

    role = config["role"]
    role_id = str(role.id) if role else ""
    role_name = role.name if role else ""
    job_key = f"{guild.id}:{role_id or 'geral'}:{getattr(config['canal'], 'id', 'sem-canal')}"
    if job_key in ROLE_ANNOUNCEMENT_ACTIVE_JOBS:
        return None, "Ja existe um anuncio igual em envio. Aguarde terminar antes de iniciar outro."

    job_id = uuid.uuid4().hex[:12]
    ROLE_ANNOUNCEMENT_ACTIVE_JOBS[job_key] = {
        "jobId": job_id,
        "guildId": str(guild.id),
        "roleId": role_id,
        "startedAt": agora_iso(),
    }
    asyncio.create_task(executar_envio_anuncio_cargo_seguro(server_id, config, job_id, job_key))

    return {
        "jobId": job_id,
        "status": "queued",
        "roleId": role_id,
        "roleName": role_name,
        "mode": "role" if role else "general",
        "channelId": str(config["canal"].id) if config["canal"] else "",
        "channelName": config["canal"].name if config["canal"] else "",
        "sendChannel": config["sendChannel"],
        "sendDm": config["sendDm"],
        "maxRecipients": config["maxRecipients"],
        "batchSize": config["batchSize"],
        "batchPauseSeconds": config["batchPauseSeconds"],
        "dmDelaySeconds": config["dmDelaySeconds"],
    }, None
