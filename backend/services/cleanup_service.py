import asyncio
import os
from datetime import datetime, timedelta, timezone

import discord

from database import buscar_todas_limpezas

INTERVALO_LIMPEZA_MINUTOS = int(os.getenv("AMZ_CLEANUP_INTERVAL_MINUTES", "1"))
MAX_MENSAGENS_POR_CANAL = int(os.getenv("AMZ_CLEANUP_MAX_MESSAGES_PER_CHANNEL", "200"))
PAUSA_ENTRE_DELECOES = float(os.getenv("AMZ_CLEANUP_DELETE_DELAY_SECONDS", "0.35"))


def normalizar_dias(dias):
    try:
        valor = int(dias)
    except (TypeError, ValueError):
        valor = 1

    return min(max(valor, 1), 14)


def normalizar_minutos(minutos):
    try:
        valor = int(minutos)
    except (TypeError, ValueError):
        valor = 1

    return min(max(valor, 1), 1440)


def obter_tempo_limpeza(limpeza):
    if limpeza.get("unidade") == "minutos" or limpeza.get("minutos") is not None:
        minutos = normalizar_minutos(limpeza.get("minutos", 1))
        if minutos >= 1440:
            return timedelta(days=1), "1 dia"
        if minutos >= 60 and minutos % 60 == 0:
            horas = minutos // 60
            return timedelta(minutes=minutos), f"{horas} hora{'s' if horas != 1 else ''}"
        return timedelta(minutes=minutos), f"{minutos} minuto{'s' if minutos != 1 else ''}"

    dias = normalizar_dias(limpeza.get("dias", 1))
    return timedelta(days=dias), f"{dias} dia{'s' if dias != 1 else ''}"


def rotulo_tempo_limpeza(limpeza):
    _, rotulo = obter_tempo_limpeza(limpeza)
    return rotulo


def bot_tem_permissoes_limpeza(channel):
    guild = getattr(channel, "guild", None)
    bot_member = getattr(guild, "me", None)

    if not bot_member:
        return False

    permissoes = channel.permissions_for(bot_member)
    return permissoes.manage_messages and permissoes.read_message_history


async def excluir_mensagens_antigas(bot, server_id, limpeza):
    guild = bot.get_guild(int(server_id))

    if not guild:
        return 0

    canal_id = int(limpeza.get("canal_id", 0))
    channel = guild.get_channel(canal_id)

    if not isinstance(channel, discord.TextChannel):
        return 0

    if not bot_tem_permissoes_limpeza(channel):
        print(f"[LIMPEZA] Sem permissao para limpar #{channel.name} em {guild.name}.")
        return 0

    tempo_limpeza, rotulo = obter_tempo_limpeza(limpeza)
    limite = datetime.now(timezone.utc) - tempo_limpeza
    removidas = 0

    try:
        async for mensagem in channel.history(limit=MAX_MENSAGENS_POR_CANAL, before=limite, oldest_first=True):
            if mensagem.pinned:
                continue

            try:
                await mensagem.delete()
                removidas += 1
                await asyncio.sleep(PAUSA_ENTRE_DELECOES)
            except discord.NotFound:
                continue
            except discord.Forbidden:
                print(f"[LIMPEZA] Permissao negada ao apagar mensagens em #{channel.name}.")
                break
            except discord.HTTPException as erro:
                print(f"[LIMPEZA] Discord recusou delete em #{channel.name}: {erro}")
                await asyncio.sleep(2)
    except discord.Forbidden:
        print(f"[LIMPEZA] Sem acesso ao historico de #{channel.name} em {guild.name}.")
    except discord.HTTPException as erro:
        print(f"[LIMPEZA] Erro ao ler historico de #{channel.name}: {erro}")

    if removidas:
        print(f"[LIMPEZA] #{channel.name} em {guild.name}: {removidas} mensagens com mais de {rotulo}.")

    return removidas


async def executar_limpezas(bot):
    servidores = await buscar_todas_limpezas()
    total_removidas = 0

    for servidor in servidores:
        server_id = servidor.get("id")

        if not server_id:
            continue

        for limpeza in servidor.get("limpezas", []):
            total_removidas += await excluir_mensagens_antigas(bot, server_id, limpeza)

    return total_removidas
