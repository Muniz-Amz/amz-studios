import asyncio
import os
import time
from datetime import datetime, timedelta, timezone

import discord

from database import buscar_todas_limpezas

LIMPEZA_PAUSADA_ATE = 0
PROXIMO_INDICE_LIMPEZA = 0


def ler_int_env(nome, padrao, minimo=None, maximo=None):
    try:
        valor = int(os.getenv(nome, str(padrao)))
    except (TypeError, ValueError):
        valor = padrao

    if minimo is not None:
        valor = max(valor, minimo)

    if maximo is not None:
        valor = min(valor, maximo)

    return valor


def ler_float_env(nome, padrao, minimo=None, maximo=None):
    try:
        valor = float(os.getenv(nome, str(padrao)))
    except (TypeError, ValueError):
        valor = padrao

    if minimo is not None:
        valor = max(valor, minimo)

    if maximo is not None:
        valor = min(valor, maximo)

    return valor


INTERVALO_LIMPEZA_MINUTOS = ler_int_env("AMZ_CLEANUP_INTERVAL_MINUTES", 10, 10)
MAX_MENSAGENS_POR_CANAL = ler_int_env("AMZ_CLEANUP_MAX_MESSAGES_PER_CHANNEL", 40, 1, 50)
MAX_CANAIS_POR_CICLO = ler_int_env("AMZ_CLEANUP_MAX_CHANNELS_PER_RUN", 8, 1, 10)
PAUSA_ENTRE_DELECOES = ler_float_env("AMZ_CLEANUP_DELETE_DELAY_SECONDS", 0.9, 0.5)
PAUSA_ENTRE_CANAIS = ler_float_env("AMZ_CLEANUP_CHANNEL_DELAY_SECONDS", 2.5, 1.0)
RATE_LIMIT_BACKOFF_PADRAO = ler_int_env("AMZ_CLEANUP_RATE_LIMIT_BACKOFF_SECONDS", 300, 60)


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


def id_discord(valor):
    try:
        return int(str(valor).strip())
    except (TypeError, ValueError):
        return None


def registrar_evento_limpeza(bot, tipo, mensagem, nivel="info", **contexto):
    if hasattr(bot, "registrar_evento"):
        bot.registrar_evento(tipo, mensagem, nivel=nivel, **contexto)

    print(f"[LIMPEZA] {mensagem}")


def bot_tem_permissoes_limpeza(channel):
    guild = getattr(channel, "guild", None)
    bot_member = getattr(guild, "me", None)

    if not bot_member:
        return False

    permissoes = channel.permissions_for(bot_member)
    return permissoes.manage_messages and permissoes.read_message_history


def canal_suporta_limpeza(channel):
    return isinstance(channel, (discord.TextChannel, discord.VoiceChannel)) and callable(getattr(channel, "history", None))


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


async def excluir_mensagens_antigas(bot, server_id, limpeza, origem="auto", registrar_sem_remocao=False):
    guild_id = id_discord(server_id)
    canal_id = id_discord(limpeza.get("canal_id"))

    if guild_id is None:
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_config_invalid",
            f"Servidor invalido na configuracao de limpeza: {server_id}.",
            nivel="error",
            origem=origem,
            server_id=server_id,
        )
        return 0

    if canal_id is None:
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_config_invalid",
            f"Canal invalido na configuracao de limpeza do servidor {server_id}: {limpeza.get('canal_id')}.",
            nivel="error",
            origem=origem,
            guild_id=guild_id,
            channel_id=limpeza.get("canal_id"),
        )
        return 0

    guild = bot.get_guild(guild_id)

    if not guild:
        if registrar_sem_remocao:
            registrar_evento_limpeza(
                bot,
                "cleanup_auto_guild_missing",
                f"Servidor {guild_id} nao foi encontrado pelo bot durante a limpeza.",
                nivel="warn",
                origem=origem,
                guild_id=guild_id,
                channel_id=canal_id,
            )
        return 0

    channel = guild.get_channel(canal_id)

    if not canal_suporta_limpeza(channel):
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_channel_missing",
            f"Canal {canal_id} nao existe mais ou nao tem chat limpavel em {guild.name}.",
            nivel="warn",
            origem=origem,
            guild_id=guild_id,
            channel_id=canal_id,
        )
        return 0

    if not bot_tem_permissoes_limpeza(channel):
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_missing_permission",
            f"Sem permissao para limpar #{channel.name} em {guild.name}. Preciso de Gerenciar mensagens e Ler historico.",
            nivel="warn",
            origem=origem,
            guild_id=guild_id,
            channel_id=canal_id,
        )
        return 0

    tempo_limpeza, rotulo = obter_tempo_limpeza(limpeza)
    limite = datetime.now(timezone.utc) - tempo_limpeza
    removidas = 0

    try:
        async for mensagem in channel.history(limit=MAX_MENSAGENS_POR_CANAL, before=limite, oldest_first=False):
            if mensagem.pinned:
                continue

            try:
                await mensagem.delete()
                removidas += 1
                await asyncio.sleep(PAUSA_ENTRE_DELECOES)
            except discord.NotFound:
                continue
            except discord.Forbidden:
                registrar_evento_limpeza(
                    bot,
                    "cleanup_auto_delete_forbidden",
                    f"Discord negou apagar mensagens em #{channel.name}. Confira permissao e hierarquia do bot.",
                    nivel="warn",
                    origem=origem,
                    guild_id=guild_id,
                    channel_id=canal_id,
                )
                break
            except discord.HTTPException as erro:
                if eh_rate_limit(erro):
                    registrar_backoff_rate_limit(bot, erro, "delete", channel)
                    break

                registrar_evento_limpeza(
                    bot,
                    "cleanup_auto_delete_error",
                    f"Discord recusou delete em #{channel.name}: {erro}",
                    nivel="warn",
                    origem=origem,
                    guild_id=guild_id,
                    channel_id=canal_id,
                )
                await asyncio.sleep(2)
    except discord.Forbidden:
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_history_forbidden",
            f"Sem acesso ao historico de #{channel.name} em {guild.name}.",
            nivel="warn",
            origem=origem,
            guild_id=guild_id,
            channel_id=canal_id,
        )
    except discord.HTTPException as erro:
        if eh_rate_limit(erro):
            registrar_backoff_rate_limit(bot, erro, "history", channel)
            return removidas

        registrar_evento_limpeza(
            bot,
            "cleanup_auto_history_error",
            f"Erro ao ler historico de #{channel.name}: {erro}",
            nivel="warn",
            origem=origem,
            guild_id=guild_id,
            channel_id=canal_id,
        )

    if removidas:
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_deleted",
            f"#{channel.name} em {guild.name}: {removidas} mensagens com mais de {rotulo} removida(s).",
            nivel="info",
            origem=origem,
            guild_id=guild_id,
            channel_id=canal_id,
            mensagens_removidas=removidas,
            tempo=rotulo,
        )
    elif registrar_sem_remocao:
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_no_messages",
            f"#{channel.name} em {guild.name}: nenhuma mensagem vencida encontrada para {rotulo}.",
            nivel="info",
            origem=origem,
            guild_id=guild_id,
            channel_id=canal_id,
            tempo=rotulo,
        )

    return removidas


async def executar_limpeza_configurada(bot, server_id, limpeza):
    return await excluir_mensagens_antigas(
        bot,
        server_id,
        limpeza,
        origem="config_save",
        registrar_sem_remocao=True,
    )


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

    try:
        servidores = await buscar_todas_limpezas()
    except Exception as erro:
        registrar_evento_limpeza(
            bot,
            "cleanup_auto_database_error",
            f"Nao consegui carregar configuracoes de limpeza do banco: {erro}",
            nivel="error",
        )
        return 0

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
        try:
            total_removidas += await excluir_mensagens_antigas(bot, server_id, limpeza)
        except Exception as erro:
            registrar_evento_limpeza(
                bot,
                "cleanup_auto_unhandled_error",
                f"Erro inesperado limpando canal {limpeza.get('canal_id')} do servidor {server_id}: {erro}",
                nivel="error",
                guild_id=server_id,
                channel_id=limpeza.get("canal_id"),
            )

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
