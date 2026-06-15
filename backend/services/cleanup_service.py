import asyncio
import os
import time
from datetime import datetime, timedelta, timezone

import discord

from database import buscar_todas_limpezas

INTERVALO_LIMPEZA_MINUTOS = max(10, int(os.getenv("AMZ_CLEANUP_INTERVAL_MINUTES", "15")))
MAX_MENSAGENS_POR_CANAL = min(max(int(os.getenv("AMZ_CLEANUP_MAX_MESSAGES_PER_CHANNEL", "40")), 1), 50)
MAX_CANAIS_POR_CICLO = min(max(int(os.getenv("AMZ_CLEANUP_MAX_CHANNELS_PER_RUN", "3")), 1), 10)
PAUSA_ENTRE_DELECOES = max(float(os.getenv("AMZ_CLEANUP_DELETE_DELAY_SECONDS", "0.9")), 0.5)
PAUSA_ENTRE_CANAIS = max(float(os.getenv("AMZ_CLEANUP_CHANNEL_DELAY_SECONDS", "2.5")), 1.0)
RATE_LIMIT_BACKOFF_PADRAO = max(int(os.getenv("AMZ_CLEANUP_RATE_LIMIT_BACKOFF_SECONDS", "300")), 60)

LIMPEZA_PAUSADA_ATE = 0
PROXIMO_INDICE_LIMPEZA = 0


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


def eh_rate_limit(erro):
    texto = str(erro)
    return isinstance(erro, discord.HTTPException) and (
        getattr(erro, "status", None) == 429
        or "429" in texto
        or "Too Many Requests" in texto
    )


def extrair_espera_rate_limit(erro):
    retry_after = getattr(erro, "retry_after", None)

    if retry_after is not None:
        try:
            return max(float(retry_after), 1)
        except (TypeError, ValueError):
            pass

    headers = getattr(getattr(erro, "response", None), "headers", {}) or {}

    for chave in ("Retry-After", "retry-after", "X-RateLimit-Reset-After", "x-ratelimit-reset-after"):
        valor = headers.get(chave)
        if valor is None:
            continue

        try:
            return max(float(valor), 1)
        except (TypeError, ValueError):
            continue

    return RATE_LIMIT_BACKOFF_PADRAO


def registrar_backoff_rate_limit(bot, erro, origem, channel=None):
    global LIMPEZA_PAUSADA_ATE

    espera = max(int(extrair_espera_rate_limit(erro)), RATE_LIMIT_BACKOFF_PADRAO)
    LIMPEZA_PAUSADA_ATE = max(LIMPEZA_PAUSADA_ATE, time.monotonic() + espera)
    alvo = f"#{getattr(channel, 'name', 'canal')}" if channel else "limpeza automatica"
    mensagem = f"Discord bloqueou a limpeza automatica em {alvo}. Pausando por {espera}s."

    if hasattr(bot, "registrar_evento"):
        bot.registrar_evento(
            "cleanup_auto_rate_limited",
            mensagem,
            nivel="warn",
            guild_id=getattr(getattr(channel, "guild", None), "id", None),
            channel_id=getattr(channel, "id", None),
            retry_after=espera,
            origem=origem,
        )

    print(f"[LIMPEZA] {mensagem}")
    return espera


def limpeza_em_backoff():
    return max(0, LIMPEZA_PAUSADA_ATE - time.monotonic())


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
                if eh_rate_limit(erro):
                    registrar_backoff_rate_limit(bot, erro, "delete", channel)
                    break

                print(f"[LIMPEZA] Discord recusou delete em #{channel.name}: {erro}")
                await asyncio.sleep(2)
    except discord.Forbidden:
        print(f"[LIMPEZA] Sem acesso ao historico de #{channel.name} em {guild.name}.")
    except discord.HTTPException as erro:
        if eh_rate_limit(erro):
            registrar_backoff_rate_limit(bot, erro, "history", channel)
            return removidas

        print(f"[LIMPEZA] Erro ao ler historico de #{channel.name}: {erro}")

    if removidas:
        print(f"[LIMPEZA] #{channel.name} em {guild.name}: {removidas} mensagens com mais de {rotulo}.")

    return removidas


async def executar_limpezas(bot):
    global PROXIMO_INDICE_LIMPEZA

    pausa_restante = limpeza_em_backoff()

    if pausa_restante > 0:
        if hasattr(bot, "registrar_evento"):
            bot.registrar_evento(
                "cleanup_auto_paused",
                f"Limpeza automatica pausada por rate limit. Restam {int(pausa_restante)}s.",
                nivel="warn",
            )
        return 0

    servidores = await buscar_todas_limpezas()
    trabalhos = [
        (servidor.get("id"), limpeza)
        for servidor in servidores
        if servidor.get("id")
        for limpeza in servidor.get("limpezas", [])
    ]
    total_removidas = 0

    if not trabalhos:
        PROXIMO_INDICE_LIMPEZA = 0
        return 0

    inicio = PROXIMO_INDICE_LIMPEZA % len(trabalhos)
    limite = min(MAX_CANAIS_POR_CICLO, len(trabalhos))
    canais_processados = 0

    for deslocamento in range(limite):
        if limpeza_em_backoff() > 0:
            break

        indice = (inicio + deslocamento) % len(trabalhos)
        server_id, limpeza = trabalhos[indice]
        total_removidas += await excluir_mensagens_antigas(bot, server_id, limpeza)
        canais_processados += 1
        PROXIMO_INDICE_LIMPEZA = (indice + 1) % len(trabalhos)

        if deslocamento < limite - 1:
            await asyncio.sleep(PAUSA_ENTRE_CANAIS)

    mensagem = (
        f"Ciclo de limpeza concluido: {canais_processados}/{len(trabalhos)} canal(is) verificado(s), "
        f"{total_removidas} mensagem(ns) removida(s)."
    )

    if hasattr(bot, "registrar_evento"):
        bot.registrar_evento(
            "cleanup_auto_cycle",
            mensagem,
            nivel="info",
            canais_processados=canais_processados,
            canais_configurados=len(trabalhos),
            mensagens_removidas=total_removidas,
            proximo_indice=PROXIMO_INDICE_LIMPEZA,
        )

    print(f"[LIMPEZA] {mensagem}")
    return total_removidas
