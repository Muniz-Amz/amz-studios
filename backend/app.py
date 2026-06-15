import asyncio
import base64
import hashlib
import hmac
import io
import json
import os
import platform
import shutil
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone

import discord
import requests
import werkzeug.serving
from dotenv import load_dotenv
from flask import Flask, jsonify, request, send_file
from flask_cors import CORS

from bot import bot
from database import (
    buscar_boas_vindas,
    buscar_limpezas,
    buscar_moderacao,
    listar_crachas_privados,
    remover_limpeza,
    remover_cracha_privado,
    salvar_boas_vindas,
    salvar_cracha_privado,
    salvar_config,
    salvar_limpeza,
    salvar_moderacao,
    status_banco_dados,
)
from services.url_video_service import UrlVideoError, UrlVideoService

load_dotenv()

app = Flask(__name__)
CORS(app)
url_video_service = UrlVideoService()

CLIENT_ID = os.getenv("DISCORD_CLIENT_ID", "").strip()
CLIENT_SECRET = os.getenv("DISCORD_CLIENT_SECRET", "").strip()
REDIRECT_URI = os.getenv("DISCORD_REDIRECT_URI", "").strip()
REDIRECT_URIS_PERMITIDAS = {
    uri.strip()
    for uri in os.getenv("DISCORD_REDIRECT_URIS", REDIRECT_URI).split(",")
    if uri.strip()
}

DISCORD_API_URL = "https://discord.com/api/v10"
DISCORD_TIMEOUT = 12
PERMISSAO_ADMINISTRADOR = 0x8
PERMISSAO_GERENCIAR_SERVIDOR = 0x20
ADMIN_PASSWORD = os.getenv("AMZ_ADMIN_PASSWORD", "").strip()
ADMIN_SESSION_SECONDS = int(os.getenv("AMZ_ADMIN_SESSION_SECONDS", "28800"))
ADMIN_MEMBERS_LIMIT = int(os.getenv("AMZ_ADMIN_MEMBERS_LIMIT", "500"))
BOT_STARTUP_GRACE_SECONDS = max(60, int(os.getenv("AMZ_BOT_STARTUP_GRACE_SECONDS", "240")))
BOT_OFFLINE_GRACE_SECONDS = max(60, int(os.getenv("AMZ_BOT_OFFLINE_GRACE_SECONDS", "180")))
BOT_WATCHDOG_INTERVAL_SECONDS = max(15, int(os.getenv("AMZ_BOT_WATCHDOG_INTERVAL_SECONDS", "30")))
API_STARTED_AT = datetime.now(timezone.utc)
BOT_RUNTIME_LOOP = None


def obter_private_guild_id():
    for nome in ("PRIVATE_GUILD_ID", "AMZ_PRIVATE_GUILD_ID", "AMZ_OWNER_GUILD_ID"):
        valor = os.getenv(nome, "").strip()
        if valor:
            return valor
    return ""


def guild_privada_habilitada(server_id):
    private_guild_id = obter_private_guild_id()
    return bool(private_guild_id and str(server_id) == str(private_guild_id))


def registrar_loop_bot(loop):
    global BOT_RUNTIME_LOOP

    if loop and loop_temporizador_disponivel(loop):
        BOT_RUNTIME_LOOP = loop
        setattr(bot, "amz_runtime_loop", loop)


def loop_temporizador_disponivel(loop):
    try:
        return (
            loop is not None
            and callable(getattr(loop, "call_soon_threadsafe", None))
            and callable(getattr(loop, "is_closed", None))
            and not loop.is_closed()
        )
    except Exception:
        return False


def obter_loop_bot():
    candidatos = (
        getattr(bot, "amz_runtime_loop", None),
        BOT_RUNTIME_LOOP,
    )

    for loop in candidatos:
        if loop_temporizador_disponivel(loop):
            return loop

    raise RuntimeError("Bot ainda esta inicializando. Tente novamente em alguns segundos.")


def executar_corrotina_bot(corrotina, timeout=15):
    try:
        loop = obter_loop_bot()
    except Exception:
        if hasattr(corrotina, "close"):
            corrotina.close()
        raise

    futuro = asyncio.run_coroutine_threadsafe(corrotina, loop)
    return futuro.result(timeout=timeout)


def data_iso(valor):
    if not valor:
        return None

    if isinstance(valor, datetime):
        return valor.astimezone(timezone.utc).isoformat()

    return str(valor)


def agora_iso():
    return datetime.now(timezone.utc).isoformat()


def segundos_desde(valor):
    if not valor:
        return None

    return max(0, int((datetime.now(timezone.utc) - valor.astimezone(timezone.utc)).total_seconds()))


def obter_admin_secret():
    return (
        os.getenv("AMZ_ADMIN_SESSION_SECRET", "").strip()
        or CLIENT_SECRET
        or os.getenv("DISCORD_TOKEN", "").strip()
        or ADMIN_PASSWORD
    )


def codificar_admin_payload(payload):
    dados = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return base64.urlsafe_b64encode(dados).decode("ascii").rstrip("=")


def decodificar_admin_payload(payload_b64):
    padding = "=" * (-len(payload_b64) % 4)
    dados = base64.urlsafe_b64decode(f"{payload_b64}{padding}".encode("ascii"))
    return json.loads(dados.decode("utf-8"))


def assinar_admin_payload(payload_b64):
    secret = obter_admin_secret()
    return hmac.new(secret.encode("utf-8"), payload_b64.encode("ascii"), hashlib.sha256).hexdigest()


def criar_admin_token():
    agora = int(time.time())
    payload_b64 = codificar_admin_payload({
        "iat": agora,
        "exp": agora + ADMIN_SESSION_SECONDS,
        "scope": "amz-admin",
    })
    assinatura = assinar_admin_payload(payload_b64)
    return f"{payload_b64}.{assinatura}"


def validar_admin_token(token):
    if not token or "." not in token:
        return False

    payload_b64, assinatura = token.rsplit(".", 1)
    assinatura_esperada = assinar_admin_payload(payload_b64)

    if not hmac.compare_digest(assinatura, assinatura_esperada):
        return False

    try:
        payload = decodificar_admin_payload(payload_b64)
    except (ValueError, json.JSONDecodeError):
        return False

    return payload.get("scope") == "amz-admin" and int(payload.get("exp", 0)) > int(time.time())


def validar_admin_painel():
    if not ADMIN_PASSWORD:
        return jsonify({
            "status": "erro",
            "mensagem": "Painel ADM nao configurado. Defina AMZ_ADMIN_PASSWORD no Render.",
        }), 503

    token = obter_token_autorizacao()

    if not validar_admin_token(token):
        return jsonify({"status": "erro", "mensagem": "Login ADM expirado ou invalido."}), 401

    return None


def bot_online():
    return bot.is_ready() and not bot.is_closed()


def status_publico_bot():
    online = bot_online()
    usuario = bot.user
    started_at = getattr(bot, "started_at", API_STARTED_AT)
    last_ready_at = getattr(bot, "last_ready_at", None)
    last_sync_at = getattr(bot, "last_slash_sync_at", None) or last_ready_at

    return {
        "status": "sucesso",
        "online": online,
        "bot": {
            "id": str(usuario.id) if usuario else None,
            "nome": usuario.name if usuario else "AMZ Bot",
            "display": str(usuario) if usuario else "AMZ Bot",
        },
        "servidores": len(bot.guilds) if online else 0,
        "latencia_ms": round(bot.latency * 1000) if online and bot.latency is not None else None,
        "iniciado_em": data_iso(started_at),
        "online_ha_segundos": segundos_desde(started_at) if online else None,
        "ultimo_ready_em": data_iso(last_ready_at),
        "ultima_sincronizacao_em": data_iso(last_sync_at),
        "erro_inicializacao": getattr(bot, "last_start_error", None),
        "erro_inicializacao_em": data_iso(getattr(bot, "last_start_error_at", None)),
        "watchdog": {
            "ativo": True,
            "estado": getattr(bot, "watchdog_state", "inicializando"),
            "ultima_verificacao_em": data_iso(getattr(bot, "watchdog_last_check_at", None)),
            "ultimo_online_em": data_iso(getattr(bot, "watchdog_last_online_at", None)),
            "inicio_monitoramento_em": data_iso(getattr(bot, "watchdog_started_at", None)),
            "startup_grace_segundos": BOT_STARTUP_GRACE_SECONDS,
            "offline_grace_segundos": BOT_OFFLINE_GRACE_SECONDS,
        },
        "atualizado_em": agora_iso(),
    }


def usuario_admin_ou_dono(guild):
    if guild.get("owner") is True:
        return True

    try:
        permissions = int(guild.get("permissions", 0))
    except (TypeError, ValueError):
        permissions = 0

    return (permissions & (PERMISSAO_ADMINISTRADOR | PERMISSAO_GERENCIAR_SERVIDOR)) != 0


def buscar_usuario_discord(token):
    headers = {"Authorization": f"Bearer {token}"}

    try:
        response = requests.get(
            f"{DISCORD_API_URL}/users/@me",
            headers=headers,
            timeout=DISCORD_TIMEOUT,
        )
    except requests.RequestException:
        return None, "discord_indisponivel"

    if response.status_code in (401, 403):
        return None, "token_expirado"

    if response.status_code != 200:
        return None, "discord_recusou"

    try:
        return response.json(), None
    except ValueError:
        return None, "resposta_invalida"


def buscar_guilds_usuario(token):
    headers = {"Authorization": f"Bearer {token}"}

    try:
        response = requests.get(
            f"{DISCORD_API_URL}/users/@me/guilds",
            headers=headers,
            timeout=DISCORD_TIMEOUT,
        )
    except requests.RequestException:
        return None, "discord_indisponivel"

    if response.status_code in (401, 403):
        return None, "token_expirado"

    if response.status_code != 200:
        return None, "discord_recusou"

    try:
        return response.json(), None
    except ValueError:
        return None, "resposta_invalida"


def verificar_admin(token, server_id):
    guilds, erro = buscar_guilds_usuario(token)

    if erro or not guilds:
        return False

    for guild in guilds:
        if str(guild.get("id")) == str(server_id):
            return usuario_admin_ou_dono(guild)

    return False


def obter_guild_bot(server_id):
    try:
        return bot.get_guild(int(server_id))
    except (TypeError, ValueError):
        return None


async def usuario_tem_permissao_pelo_bot(server_id, user_id):
    guild = obter_guild_bot(server_id)

    if not guild:
        return None

    try:
        user_id_int = int(user_id)
    except (TypeError, ValueError):
        return None

    if guild.owner_id == user_id_int:
        return True

    member = guild.get_member(user_id_int)

    if member is None:
        try:
            member = await guild.fetch_member(user_id_int)
        except discord.NotFound:
            return False
        except (discord.Forbidden, discord.HTTPException):
            return None

    permissoes = member.guild_permissions
    return permissoes.administrator or permissoes.manage_guild


def confirmar_permissao_pelo_bot(server_id, user_id):
    try:
        return executar_corrotina_bot(usuario_tem_permissao_pelo_bot(server_id, user_id), timeout=10)
    except Exception:
        return None


def usuario_pode_configurar_servidor(guild, user_id):
    if usuario_admin_ou_dono(guild):
        return True

    return confirmar_permissao_pelo_bot(guild.get("id"), user_id) is True


def montar_servidores_autorizados(token):
    usuario, erro_usuario = buscar_usuario_discord(token)

    if erro_usuario:
        return None, erro_usuario

    guilds, erro_guilds = buscar_guilds_usuario(token)

    if erro_guilds:
        return None, erro_guilds

    user_id = usuario.get("id")
    servidores_autorizados = []

    for guild in guilds or []:
        guild_id = guild.get("id")
        bot_guild = obter_guild_bot(guild_id)

        if not guild_id or not bot_guild:
            continue

        if usuario_pode_configurar_servidor(guild, user_id):
            servidores_autorizados.append({
                "id": str(guild_id),
                "nome": guild.get("name", bot_guild.name),
                "icon": guild.get("icon"),
                "icon_url": montar_icon_url(guild_id, guild.get("icon")),
                "owner": bool(guild.get("owner")),
                "permissions": str(guild.get("permissions", "0")),
            })

    return {
        "usuario": {
            "id": str(user_id),
            "nome": usuario.get("username"),
            "username": usuario.get("username"),
            "global_name": usuario.get("global_name"),
            "discriminator": usuario.get("discriminator"),
            "avatar": usuario.get("avatar"),
        },
        "servidores": servidores_autorizados,
    }, None


def obter_redirect_uri_permitida(dados_frontend):
    redirect_uri = (dados_frontend.get("redirect_uri") or "").strip()

    if redirect_uri in REDIRECT_URIS_PERMITIDAS:
        return redirect_uri

    return REDIRECT_URI


def obter_token_autorizacao():
    auth_header = request.headers.get("Authorization")

    if not auth_header or not auth_header.startswith("Bearer "):
        return None

    return auth_header.split(" ")[1]


def validar_admin_requisicao(server_id):
    token = obter_token_autorizacao()

    if not token:
        return jsonify({"status": "erro", "mensagem": "Sessao expirada. Entre novamente com o Discord."}), 401

    usuario, erro_usuario = buscar_usuario_discord(token)

    if erro_usuario == "token_expirado":
        return jsonify({"status": "erro", "mensagem": "Sessao expirada. Entre novamente com o Discord."}), 401

    if erro_usuario:
        return jsonify({"status": "erro", "mensagem": "Nao consegui confirmar sua conta no Discord agora."}), 503

    guilds, erro = buscar_guilds_usuario(token)

    if erro == "token_expirado":
        return jsonify({"status": "erro", "mensagem": "Sessao expirada. Entre novamente com o Discord."}), 401

    if erro:
        return jsonify({"status": "erro", "mensagem": "Nao consegui confirmar suas permissoes no Discord agora."}), 503

    for guild in guilds:
        if str(guild.get("id")) == str(server_id):
            if usuario_pode_configurar_servidor(guild, usuario.get("id")):
                return None

            return jsonify({
                "status": "erro",
                "mensagem": "Acesso negado: sua conta nao tem permissao de administrador ou gerenciar servidor.",
            }), 403

    return jsonify({"status": "erro", "mensagem": "Servidor nao encontrado na sua conta Discord."}), 403


def montar_icon_url(guild_id, icon_hash):
    if not icon_hash:
        return None

    extensao = "gif" if str(icon_hash).startswith("a_") else "png"
    return f"https://cdn.discordapp.com/icons/{guild_id}/{icon_hash}.{extensao}?size=128"


def canais_texto_do_servidor(server_id):
    guild = bot.get_guild(int(server_id))

    if not guild:
        return None

    canais = []

    for canal in sorted(
        guild.text_channels,
        key=lambda item: (item.category.position if item.category else -1, item.position),
    ):
        canais.append({
            "id": str(canal.id),
            "nome": canal.name,
            "mention": f"#{canal.name}",
            "categoria": canal.category.name if canal.category else None,
            "tipo": str(canal.type),
            "posicao": canal.position,
            "permissoes_bot": permissoes_bot_canal(canal),
        })

    return canais


def cargos_do_servidor(server_id):
    guild = bot.get_guild(int(server_id))

    if not guild:
        return None

    cargos = []
    for cargo in sorted(guild.roles, key=lambda item: item.position, reverse=True):
        if cargo.is_default():
            continue
        cargos.append({
            "id": str(cargo.id),
            "nome": cargo.name,
            "posicao": cargo.position,
            "cor": str(cargo.color),
            "gerenciado": cargo.managed,
        })

    return cargos


def permissoes_bot_servidor(guild):
    membro_bot = guild.me

    if not membro_bot:
        return {}

    permissoes = membro_bot.guild_permissions
    return {
        "administrador": permissoes.administrator,
        "gerenciar_servidor": permissoes.manage_guild,
        "gerenciar_mensagens": permissoes.manage_messages,
        "banir_membros": permissoes.ban_members,
        "expulsar_membros": permissoes.kick_members,
        "castigar_membros": permissoes.moderate_members,
        "ver_canais": permissoes.view_channel,
        "enviar_mensagens": permissoes.send_messages,
        "ler_historico": permissoes.read_message_history,
        "gerenciar_cargos": permissoes.manage_roles,
        "ver_auditoria": permissoes.view_audit_log,
        "valor": str(permissoes.value),
    }


def permissoes_bot_canal(canal):
    guild = getattr(canal, "guild", None)
    membro_bot = getattr(guild, "me", None)

    if not membro_bot:
        return {}

    permissoes = canal.permissions_for(membro_bot)
    return {
        "ver": permissoes.view_channel,
        "enviar": permissoes.send_messages,
        "enviar_embeds": permissoes.embed_links,
        "gerenciar_mensagens": permissoes.manage_messages,
        "ler_historico": permissoes.read_message_history,
    }


def montar_info_canal(canal):
    return {
        "id": str(canal.id),
        "nome": canal.name,
        "tipo": str(canal.type),
        "categoria": canal.category.name if getattr(canal, "category", None) else None,
        "posicao": getattr(canal, "position", None),
        "nsfw": getattr(canal, "nsfw", False),
        "slowmode": getattr(canal, "slowmode_delay", 0),
        "bitrate": getattr(canal, "bitrate", None),
        "limite_usuarios": getattr(canal, "user_limit", None),
        "permissoes_bot": permissoes_bot_canal(canal),
    }


def montar_info_cargo(cargo):
    return {
        "id": str(cargo.id),
        "nome": cargo.name,
        "posicao": cargo.position,
        "cor": str(cargo.color),
        "membros": len(cargo.members),
        "gerenciado": cargo.managed,
        "mencionavel": cargo.mentionable,
        "permissoes": str(cargo.permissions.value),
    }


def bot_pode_moderar_membro(guild, member):
    membro_bot = guild.me

    if not membro_bot:
        return False, "Bot nao encontrado no servidor."

    if member.id == guild.owner_id:
        return False, "Nao e possivel moderar o dono do servidor."

    if bot.user and member.id == bot.user.id:
        return False, "O bot nao pode moderar a si mesmo."

    if member.top_role >= membro_bot.top_role:
        return False, "Cargo do membro esta igual ou acima do cargo do bot."

    return True, "Pode moderar."


def bot_pode_banir_membro(guild, member):
    pode_moderar, motivo = bot_pode_moderar_membro(guild, member)

    if not pode_moderar:
        return False, motivo

    if not guild.me.guild_permissions.ban_members:
        return False, "Bot nao tem permissao de banir membros."

    return True, "Pode banir."


def bot_pode_expulsar_membro(guild, member):
    pode_moderar, motivo = bot_pode_moderar_membro(guild, member)

    if not pode_moderar:
        return False, motivo

    if not guild.me.guild_permissions.kick_members:
        return False, "Bot nao tem permissao de expulsar membros."

    return True, "Pode expulsar."


def bot_pode_castigar_membro(guild, member):
    pode_moderar, motivo = bot_pode_moderar_membro(guild, member)

    if not pode_moderar:
        return False, motivo

    if not guild.me.guild_permissions.moderate_members:
        return False, "Bot nao tem permissao de castigar membros."

    if member.guild_permissions.administrator:
        return False, "Discord nao aplica castigo em administradores."

    return True, "Pode castigar."


def montar_info_membro(member, guild):
    pode_banir, motivo_bloqueio = bot_pode_banir_membro(guild, member)
    pode_expulsar, motivo_expulsar = bot_pode_expulsar_membro(guild, member)
    pode_castigar, motivo_castigar = bot_pode_castigar_membro(guild, member)
    cargos = [
        {
            "id": str(cargo.id),
            "nome": cargo.name,
            "posicao": cargo.position,
            "cor": str(cargo.color),
        }
        for cargo in sorted(member.roles, key=lambda item: item.position, reverse=True)
        if cargo.name != "@everyone"
    ]

    return {
        "id": str(member.id),
        "nome": member.name,
        "display": member.display_name,
        "global_name": getattr(member, "global_name", None),
        "tag": str(member),
        "bot": member.bot,
        "avatar_url": str(member.display_avatar.url) if member.display_avatar else None,
        "entrou_em": data_iso(member.joined_at),
        "criado_em": data_iso(member.created_at),
        "cargo_topo": member.top_role.name if member.top_role else None,
        "cargos": cargos,
        "permissoes": {
            "administrador": member.guild_permissions.administrator,
            "banir_membros": member.guild_permissions.ban_members,
            "expulsar_membros": member.guild_permissions.kick_members,
            "gerenciar_servidor": member.guild_permissions.manage_guild,
            "gerenciar_mensagens": member.guild_permissions.manage_messages,
        },
        "moderacao": {
            "pode_banir": pode_banir,
            "motivo_bloqueio": motivo_bloqueio,
            "pode_expulsar": pode_expulsar,
            "motivo_expulsar": motivo_expulsar,
            "pode_castigar": pode_castigar,
            "motivo_castigar": motivo_castigar,
        },
    }


async def listar_membros_admin_async(server_id, limite):
    guild = obter_guild_bot(server_id)

    if not guild:
        return None, "Servidor nao encontrado pelo bot.", "erro"

    limite = min(max(int(limite or ADMIN_MEMBERS_LIMIT), 1), ADMIN_MEMBERS_LIMIT)
    membros_por_id = {}
    origem = "cache"

    for member in guild.members:
        membros_por_id[member.id] = member

    try:
        async for member in guild.fetch_members(limit=limite):
            membros_por_id[member.id] = member
        origem = "discord_api"
    except discord.Forbidden:
        origem = "cache_sem_intent"
    except discord.HTTPException:
        origem = "cache_api_indisponivel"

    membros = sorted(
        membros_por_id.values(),
        key=lambda item: (item.bot, item.display_name.lower(), item.id),
    )[:limite]

    return {
        "server_id": str(guild.id),
        "server_name": guild.name,
        "origem": origem,
        "limite": limite,
        "total_cache": len(guild.members),
        "total_servidor": guild.member_count,
        "membros": [montar_info_membro(member, guild) for member in membros],
    }, None, origem


def listar_membros_admin_sync(server_id, limite):
    return executar_corrotina_bot(listar_membros_admin_async(server_id, limite), timeout=25)


async def obter_membro_admin_async(server_id, user_id):
    guild = obter_guild_bot(server_id)

    if not guild:
        return None, None, "Servidor nao encontrado pelo bot."

    try:
        user_id_int = int(user_id)
    except (TypeError, ValueError):
        return None, None, "ID do membro invalido."

    try:
        member = guild.get_member(user_id_int) or await guild.fetch_member(user_id_int)
    except discord.NotFound:
        return None, None, "Membro nao encontrado no servidor."
    except discord.Forbidden:
        return None, None, "Discord negou acesso ao membro. Verifique a intent de membros."
    except discord.HTTPException as erro:
        return None, None, f"Discord recusou a busca do membro: {erro}"

    return guild, member, None


async def banir_membro_admin_async(server_id, user_id, motivo):
    guild, member, erro = await obter_membro_admin_async(server_id, user_id)

    if erro:
        return False, erro

    pode_banir, motivo_bloqueio = bot_pode_banir_membro(guild, member)

    if not pode_banir:
        return False, motivo_bloqueio

    motivo_limpo = str(motivo or "Banido pelo painel ADM AMZ.").strip()[:480]
    razao = f"Painel ADM AMZ: {motivo_limpo}"

    try:
        await guild.ban(member, reason=razao)
        return True, f"{member} foi banido de {guild.name}."
    except discord.Forbidden:
        return False, "Discord negou o ban. Confira permissao e hierarquia do cargo do bot."
    except discord.HTTPException as erro:
        return False, f"Discord recusou o ban: {erro}"


def banir_membro_admin_sync(server_id, user_id, motivo):
    return executar_corrotina_bot(banir_membro_admin_async(server_id, user_id, motivo), timeout=25)


async def expulsar_membro_admin_async(server_id, user_id, motivo):
    guild, member, erro = await obter_membro_admin_async(server_id, user_id)

    if erro:
        return False, erro

    pode_expulsar, motivo_bloqueio = bot_pode_expulsar_membro(guild, member)

    if not pode_expulsar:
        return False, motivo_bloqueio

    motivo_limpo = str(motivo or "Expulso pelo painel ADM AMZ.").strip()[:480]
    razao = f"Painel ADM AMZ: {motivo_limpo}"

    try:
        await member.kick(reason=razao)
        return True, f"{member} foi expulso de {guild.name}."
    except discord.Forbidden:
        return False, "Discord negou a expulsao. Confira permissao e hierarquia do cargo do bot."
    except discord.HTTPException as erro:
        return False, f"Discord recusou a expulsao: {erro}"


def expulsar_membro_admin_sync(server_id, user_id, motivo):
    return executar_corrotina_bot(expulsar_membro_admin_async(server_id, user_id, motivo), timeout=25)


async def castigar_membro_admin_async(server_id, user_id, minutos, motivo):
    guild, member, erro = await obter_membro_admin_async(server_id, user_id)

    if erro:
        return False, erro

    pode_castigar, motivo_bloqueio = bot_pode_castigar_membro(guild, member)

    if not pode_castigar:
        return False, motivo_bloqueio

    try:
        minutos_int = int(minutos or 10)
    except (TypeError, ValueError):
        minutos_int = 10

    minutos_int = min(max(minutos_int, 1), 10080)
    ate = datetime.now(timezone.utc) + timedelta(minutes=minutos_int)
    motivo_limpo = str(motivo or "Castigo aplicado pelo painel ADM AMZ.").strip()[:480]
    razao = f"Painel ADM AMZ: {motivo_limpo}"

    try:
        if hasattr(member, "timeout"):
            await member.timeout(ate, reason=razao)
        else:
            await member.edit(timed_out_until=ate, reason=razao)
        return True, f"{member} foi castigado por {minutos_int} minuto(s)."
    except discord.Forbidden:
        return False, "Discord negou o castigo. Confira permissao e hierarquia do cargo do bot."
    except discord.HTTPException as erro:
        return False, f"Discord recusou o castigo: {erro}"


def castigar_membro_admin_sync(server_id, user_id, minutos, motivo):
    return executar_corrotina_bot(castigar_membro_admin_async(server_id, user_id, minutos, motivo), timeout=25)


async def sair_servidor_admin_async(server_id, motivo):
    guild = obter_guild_bot(server_id)

    if not guild:
        return False, "Servidor nao encontrado pelo bot."

    nome = guild.name
    guild_id = guild.id
    motivo_limpo = str(motivo or "Solicitado pelo painel ADM AMZ.").strip()[:240]

    try:
        await guild.leave()
        print(f"[ADM] Bot saiu do servidor {nome} ({guild_id}). Motivo: {motivo_limpo}")
        return True, f"Bot saiu de {nome}."
    except discord.HTTPException as erro:
        return False, f"Discord recusou a saida do servidor: {erro}"


def sair_servidor_admin_sync(server_id, motivo):
    return executar_corrotina_bot(sair_servidor_admin_async(server_id, motivo), timeout=25)


def buscar_limpezas_sync(server_id):
    try:
        return executar_corrotina_bot(buscar_limpezas(str(server_id)), timeout=10)
    except Exception:
        return []


def buscar_boas_vindas_sync(server_id):
    try:
        return executar_corrotina_bot(buscar_boas_vindas(str(server_id)), timeout=10)
    except Exception:
        return {}


def buscar_moderacao_sync(server_id):
    try:
        return executar_corrotina_bot(buscar_moderacao(str(server_id)), timeout=10)
    except Exception:
        return {}


def listar_crachas_privados_sync(server_id):
    try:
        return executar_corrotina_bot(listar_crachas_privados(str(server_id)), timeout=10)
    except Exception:
        return []


def valor_booleano(valor):
    if isinstance(valor, bool):
        return valor

    if isinstance(valor, str):
        return valor.strip().lower() in ("1", "true", "sim", "yes", "on")

    return bool(valor)


def validar_canais_boas_vindas(server_id, dados):
    canais = canais_texto_do_servidor(server_id)

    if canais is None:
        return None, "Servidor nao encontrado pelo bot."

    canais_por_id = {str(canal["id"]): canal for canal in canais}
    dados = dict(dados or {})

    for tipo in ("entrada", "saida"):
        ativo = valor_booleano(dados.get(f"{tipo}_ativa"))
        canal_id = str(dados.get(f"canal_{tipo}_id") or "").strip()

        if not ativo and not canal_id:
            continue

        if not ativo and canal_id not in canais_por_id:
            dados[f"canal_{tipo}_id"] = ""
            dados[f"canal_{tipo}_nome"] = ""
            continue

        canal = canais_por_id.get(canal_id)

        if not canal:
            return None, f"Selecione um canal valido para o aviso de {tipo}."

        permissoes = canal.get("permissoes_bot", {})

        if ativo and (not permissoes.get("ver") or not permissoes.get("enviar")):
            return None, f"O bot nao consegue enviar mensagens no canal de {tipo}."

        dados[f"canal_{tipo}_id"] = canal_id
        dados[f"canal_{tipo}_nome"] = canal.get("nome", "")

    return dados, None


def status_banco_sync():
    try:
        return executar_corrotina_bot(status_banco_dados(), timeout=12)
    except Exception as erro:
        return {
            "online": False,
            "ping_ms": None,
            "database": "AMZCore",
            "collection": "servidores",
            "mongo_uri_configurada": bool(os.getenv("MONGO_URI")),
            "documentos": None,
            "documentos_com_limpeza": None,
            "indices": [],
            "ultimo_documento": None,
            "erro": str(erro),
        }


def variavel_configurada(nome):
    return bool(os.getenv(nome, "").strip())


def montar_status_render():
    return {
        "ambiente": "render" if variavel_configurada("RENDER") or variavel_configurada("RENDER_SERVICE_ID") else "local",
        "porta": os.getenv("PORT"),
        "servico_id": os.getenv("RENDER_SERVICE_ID"),
        "servico_nome": os.getenv("RENDER_SERVICE_NAME"),
        "url_externa": os.getenv("RENDER_EXTERNAL_URL"),
        "git_commit": os.getenv("RENDER_GIT_COMMIT"),
        "git_branch": os.getenv("RENDER_GIT_BRANCH"),
        "instance_id": os.getenv("RENDER_INSTANCE_ID"),
        "deploy_hook_configurado": variavel_configurada("RENDER_DEPLOY_HOOK_URL"),
    }


def montar_status_configuracoes():
    variaveis = (
        "DISCORD_TOKEN",
        "DISCORD_CLIENT_ID",
        "DISCORD_CLIENT_SECRET",
        "DISCORD_REDIRECT_URI",
        "MONGO_URI",
        "RENDER_DEPLOY_HOOK_URL",
        "AMZ_ADMIN_PASSWORD",
        "AMZ_ADMIN_SESSION_SECRET",
        "AMZ_ADMIN_MEMBERS_LIMIT",
        "AMZ_SLASH_GUILD_IDS",
        "AMZ_CLEANUP_INTERVAL_MINUTES",
        "AMZ_CLEANUP_MAX_MESSAGES_PER_CHANNEL",
        "AMZ_CLEANUP_DELETE_DELAY_SECONDS",
        "AMZ_BOT_STARTUP_GRACE_SECONDS",
        "AMZ_BOT_OFFLINE_GRACE_SECONDS",
        "AMZ_BOT_WATCHDOG_INTERVAL_SECONDS",
        "PRIVATE_GUILD_ID",
        "AMZ_PRIVATE_GUILD_ID",
    )

    return {nome: variavel_configurada(nome) for nome in variaveis}


def montar_status_bot_admin():
    comandos_prefixo = sorted(bot.commands, key=lambda comando: comando.qualified_name)
    comandos_slash = sorted(bot.tree.get_commands(), key=lambda comando: comando.qualified_name)

    return {
        "prefixo": os.getenv("AMZ_COMMAND_PREFIX", "!"),
        "cogs": sorted(bot.cogs.keys()),
        "comandos_prefixo": [comando.qualified_name for comando in comandos_prefixo],
        "total_comandos_prefixo": len(comandos_prefixo),
        "comandos_slash": [serializar_comando_slash(comando) for comando in comandos_slash],
        "total_comandos_slash": len(comandos_slash),
        "slash_guilds_sincronizadas": len(getattr(bot, "slash_synced_guilds", set())),
        "intents": {
            "message_content": bot.intents.message_content,
            "members": bot.intents.members,
            "guilds": bot.intents.guilds,
        },
        "watchdog": {
            "estado": getattr(bot, "watchdog_state", "inicializando"),
            "ultima_verificacao_em": data_iso(getattr(bot, "watchdog_last_check_at", None)),
            "ultimo_online_em": data_iso(getattr(bot, "watchdog_last_online_at", None)),
            "ultimo_restart_motivo": getattr(bot, "watchdog_last_restart_reason", None),
            "startup_grace_segundos": BOT_STARTUP_GRACE_SECONDS,
            "offline_grace_segundos": BOT_OFFLINE_GRACE_SECONDS,
            "intervalo_segundos": BOT_WATCHDOG_INTERVAL_SECONDS,
        },
        "totais": {
            "servidores": len(bot.guilds),
            "membros_aproximados": sum(guild.member_count or 0 for guild in bot.guilds),
            "canais": sum(len(guild.channels) for guild in bot.guilds),
            "cargos": sum(len(guild.roles) for guild in bot.guilds),
        },
    }


def serializar_comando_slash(comando):
    filhos = sorted(getattr(comando, "commands", []) or [], key=lambda item: item.name)

    return {
        "nome": comando.qualified_name,
        "descricao": getattr(comando, "description", "") or "",
        "categoria": comando.name,
        "filhos": [
            {
                "nome": f"{comando.name} {filho.name}",
                "descricao": getattr(filho, "description", "") or "",
            }
            for filho in filhos
        ],
    }


def montar_status_sistema():
    return {
        "api": {
            "online": True,
            "iniciada_em": data_iso(API_STARTED_AT),
            "uptime_segundos": segundos_desde(API_STARTED_AT),
            "python": sys.version.split()[0],
            "plataforma": platform.platform(),
            "processo_id": os.getpid(),
            "cwd": os.getcwd(),
        },
        "render": montar_status_render(),
        "configuracoes": montar_status_configuracoes(),
        "bot": montar_status_bot_admin(),
        "banco": status_banco_sync(),
    }


PERMISSOES_ADMIN_RECOMENDADAS = {
    "gerenciar_servidor": "Gerenciar servidor",
    "gerenciar_mensagens": "Gerenciar mensagens",
    "banir_membros": "Banir membros",
    "expulsar_membros": "Expulsar membros",
    "castigar_membros": "Castigar membros",
    "ver_canais": "Ver canais",
    "enviar_mensagens": "Enviar mensagens",
    "ler_historico": "Ler historico",
    "gerenciar_cargos": "Gerenciar cargos",
    "ver_auditoria": "Ver auditoria",
}

PERMISSOES_CANAL_RECOMENDADAS = {
    "ver": "Ver canal",
    "enviar": "Enviar mensagens",
    "enviar_embeds": "Enviar embeds",
    "ler_historico": "Ler historico",
}


def obter_cog_moderacao():
    for cog in bot.cogs.values():
        if hasattr(cog, "automation_queues") and hasattr(cog, "automation_workers"):
            return cog

    return None


def montar_fila_automacoes_admin():
    cog = obter_cog_moderacao()

    if not cog:
        return {
            "ativa": False,
            "pendentes_total": 0,
            "workers_ativos": 0,
            "filas": [],
            "cooldowns": {},
            "mensagem": "Cog de moderacao ainda nao carregado.",
        }

    filas = []
    pendentes_total = 0

    for guild_id, fila in getattr(cog, "automation_queues", {}).items():
        pendentes = fila.qsize()
        pendentes_total += pendentes
        guild = bot.get_guild(int(guild_id)) if str(guild_id).isdigit() else None
        filas.append({
            "guild_id": str(guild_id),
            "guild_nome": guild.name if guild else str(guild_id),
            "pendentes": pendentes,
            "limite": getattr(fila, "maxsize", None),
            "worker_ativo": bool(getattr(cog, "automation_workers", {}).get(guild_id))
                and not getattr(cog, "automation_workers", {}).get(guild_id).done(),
        })

    workers_ativos = sum(
        1
        for worker in getattr(cog, "automation_workers", {}).values()
        if worker and not worker.done()
    )

    return {
        "ativa": True,
        "pendentes_total": pendentes_total,
        "workers_ativos": workers_ativos,
        "filas": sorted(filas, key=lambda item: item["pendentes"], reverse=True),
        "cooldowns": {
            "auto_respostas": len(getattr(cog, "auto_response_cooldowns", {}) or {}),
            "comandos_bloqueados": len(getattr(cog, "command_block_cooldowns", {}) or {}),
            "anti_raid_logs": len(getattr(cog, "anti_raid_log_cooldowns", {}) or {}),
        },
        "mensagem": "Fila saudavel." if pendentes_total == 0 else "Ha automacoes aguardando processamento.",
    }


def listar_permissoes_faltantes_admin(servidores):
    faltantes = []

    for servidor in servidores:
        permissoes = servidor.get("permissoes_bot") or {}

        if permissoes.get("administrador"):
            continue

        for chave, rotulo in PERMISSOES_ADMIN_RECOMENDADAS.items():
            if not permissoes.get(chave):
                faltantes.append({
                    "escopo": "servidor",
                    "servidor_id": servidor.get("id"),
                    "servidor_nome": servidor.get("nome"),
                    "alvo": servidor.get("nome"),
                    "permissao": rotulo,
                    "severidade": "alta" if chave in ("ver_canais", "enviar_mensagens", "ler_historico") else "media",
                })

        for canal in servidor.get("canais") or []:
            tipo = str(canal.get("tipo") or "")

            if "text" not in tipo and "news" not in tipo and "forum" not in tipo:
                continue

            permissoes_canal = canal.get("permissoes_bot") or {}

            for chave, rotulo in PERMISSOES_CANAL_RECOMENDADAS.items():
                if not permissoes_canal.get(chave):
                    faltantes.append({
                        "escopo": "canal",
                        "servidor_id": servidor.get("id"),
                        "servidor_nome": servidor.get("nome"),
                        "canal_id": canal.get("id"),
                        "alvo": f"#{canal.get('nome')}",
                        "permissao": rotulo,
                        "severidade": "alta" if chave in ("ver", "enviar") else "media",
                    })

    return faltantes


def montar_erros_recentes_admin(logs):
    erros = []

    for log in logs:
        tipo = str(log.get("tipo") or "").lower()
        nivel = str(log.get("nivel") or "").lower()

        if nivel == "error" or "error" in tipo or "erro" in tipo:
            erros.append(log)

    return erros[:8]


def montar_saude_admin(servidores, logs, sistema):
    banco = (sistema or {}).get("banco") or {}
    erros_recentes = montar_erros_recentes_admin(logs)
    permissoes_faltantes = listar_permissoes_faltantes_admin(servidores)
    automacoes = montar_fila_automacoes_admin()
    online = bot_online()
    ping_ms = round(bot.latency * 1000) if online and bot.latency is not None else None
    status = "ok"

    if not online or banco.get("online") is False:
        status = "erro"
    elif erros_recentes or permissoes_faltantes or automacoes.get("pendentes_total", 0) > 0:
        status = "alerta"

    servidores_afetados = {
        item.get("servidor_id")
        for item in permissoes_faltantes
        if item.get("servidor_id")
    }

    return {
        "status": status,
        "rotulo": {
            "ok": "Saude boa",
            "alerta": "Atenção",
            "erro": "Critico",
        }.get(status, "Atenção"),
        "descricao": "Leitura geral do bot, banco, erros, permissoes e automacoes.",
        "bot": {
            "online": online,
            "ping_ms": ping_ms,
            "uptime_segundos": segundos_desde(getattr(bot, "started_at", API_STARTED_AT)) if online else None,
            "servidores": len(bot.guilds) if online else 0,
            "ultimo_ready_em": data_iso(getattr(bot, "last_ready_at", None)),
        },
        "erros": {
            "total": len(erros_recentes),
            "itens": erros_recentes,
        },
        "permissoes": {
            "faltando_total": len(permissoes_faltantes),
            "servidores_afetados": len(servidores_afetados),
            "itens": permissoes_faltantes[:14],
        },
        "automacoes": automacoes,
        "checks": [
            {
                "nome": "Bot Discord",
                "status": "ok" if online else "erro",
                "detalhe": f"Ping {ping_ms} ms" if ping_ms is not None else "Bot offline ou sem websocket.",
            },
            {
                "nome": "MongoDB",
                "status": "ok" if banco.get("online") else "erro",
                "detalhe": f"Ping {banco.get('ping_ms')} ms" if banco.get("online") else banco.get("erro") or "Banco indisponivel.",
            },
            {
                "nome": "Erros recentes",
                "status": "ok" if not erros_recentes else "alerta",
                "detalhe": f"{len(erros_recentes)} evento(s) de erro nos logs recentes.",
            },
            {
                "nome": "Permissoes",
                "status": "ok" if not permissoes_faltantes else "alerta",
            "detalhe": f"{len(permissoes_faltantes)} permissão(ões) faltando em {len(servidores_afetados)} servidor(es).",
            },
            {
                "nome": "Fila de automacoes",
                "status": "ok" if automacoes.get("pendentes_total", 0) == 0 else "alerta",
                "detalhe": f"{automacoes.get('pendentes_total', 0)} tarefa(s) pendente(s), {automacoes.get('workers_ativos', 0)} worker(s) ativo(s).",
            },
        ],
    }


def montar_info_servidor_admin(guild):
    dono = guild.owner
    canais = sorted(
        guild.channels,
        key=lambda canal: (
            getattr(canal, "category", None).position if getattr(canal, "category", None) else -1,
            getattr(canal, "position", 0),
        ),
    )
    cargos = sorted(guild.roles, key=lambda cargo: cargo.position, reverse=True)
    limpezas = buscar_limpezas_sync(guild.id)
    boas_vindas = buscar_boas_vindas_sync(guild.id)
    moderacao = buscar_moderacao_sync(guild.id)
    private_guild = guild_privada_habilitada(guild.id)
    crachas_privados = listar_crachas_privados_sync(guild.id) if private_guild else []

    return {
        "id": str(guild.id),
        "nome": guild.name,
        "icone_url": str(guild.icon.url) if guild.icon else None,
        "dono_id": str(guild.owner_id),
        "dono_nome": str(dono) if dono else None,
        "membros": guild.member_count,
        "criado_em": data_iso(guild.created_at),
        "bot_entrou_em": data_iso(guild.me.joined_at) if guild.me and guild.me.joined_at else None,
        "premium_tier": guild.premium_tier,
        "boosts": guild.premium_subscription_count,
        "features": sorted(guild.features),
        "private_guild": {
            "enabled": private_guild,
            "badges_total": len(crachas_privados),
            "module": "guild_badges" if private_guild else None,
        },
        "limpezas_configuradas": limpezas,
        "boas_vindas_config": boas_vindas,
        "moderacao_config": moderacao,
        "contagens": {
            "canais": len(guild.channels),
            "texto": len(guild.text_channels),
            "voz": len(guild.voice_channels),
            "categorias": len(guild.categories),
            "cargos": len(guild.roles),
            "emojis": len(guild.emojis),
            "stickers": len(guild.stickers),
        },
        "permissoes_bot": permissoes_bot_servidor(guild),
        "canais": [montar_info_canal(canal) for canal in canais],
        "cargos": [montar_info_cargo(cargo) for cargo in cargos],
    }


@app.route("/api/status", methods=["GET"])
def status_bot():
    return jsonify(status_publico_bot()), 200


@app.route("/api/health", methods=["GET", "HEAD"])
def healthcheck_bot():
    status = status_publico_bot()
    codigo = 200 if status.get("online") else 503

    return jsonify({
        "status": "ok" if status.get("online") else "erro",
        "api": "online",
        "bot": "online" if status.get("online") else "offline",
        "watchdog": status.get("watchdog"),
        "erro_inicializacao": status.get("erro_inicializacao"),
        "atualizado_em": agora_iso(),
    }), codigo


@app.route("/", methods=["GET"])
def root():
    return jsonify({
        "status": "sucesso",
        "servico": "amz-studios-api",
        "online": bot_online(),
        "health_url": "/api/health",
        "status_url": "/api/status",
        "atualizado_em": agora_iso(),
    }), 200


@app.route("/api/video/download", methods=["POST"])
def baixar_video_publico():
    dados = request.get_json(silent=True) or {}
    url = str(dados.get("url") or "").strip()
    modo = str(dados.get("modo") or "video_hd").strip()

    if not url:
        return jsonify({"status": "erro", "mensagem": "Envie um link para baixar."}), 400

    temp_dir = tempfile.mkdtemp(prefix="amz-video-")

    try:
        limite_bytes = url_video_service.limits.max_output_bytes

        if modo == "mp3":
            output_path = url_video_service.download_audio(url, temp_dir, max_bytes=limite_bytes)
            mimetype = "audio/mpeg"
            filename = "amz-audio.mp3"
        elif modo == "video":
            output_path = url_video_service.download_video(url, temp_dir, max_bytes=limite_bytes, max_width=540)
            mimetype = "video/mp4"
            filename = "amz-video.mp4"
        else:
            output_path = url_video_service.download_video(url, temp_dir, max_bytes=limite_bytes)
            mimetype = "video/mp4"
            filename = "amz-video-hd.mp4"

        conteudo = output_path.read_bytes()
        resposta = send_file(
            io.BytesIO(conteudo),
            mimetype=mimetype,
            as_attachment=True,
            download_name=filename,
        )
        resposta.headers["Cache-Control"] = "no-store"
        return resposta
    except UrlVideoError as erro:
        return jsonify({"status": "erro", "mensagem": str(erro)}), 400
    except Exception as erro:
        print(f"[VIDEO] Erro inesperado ao baixar link: {erro}")
        return jsonify({"status": "erro", "mensagem": "Nao consegui baixar esse link agora."}), 500
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


@app.route("/api/admin/login", methods=["POST"])
def admin_login():
    if not ADMIN_PASSWORD:
        return jsonify({
            "status": "erro",
            "mensagem": "Painel ADM nao configurado. Defina AMZ_ADMIN_PASSWORD no Render.",
        }), 503

    dados = request.json or {}
    senha = str(dados.get("senha", ""))

    if not hmac.compare_digest(senha, ADMIN_PASSWORD):
        return jsonify({"status": "erro", "mensagem": "Senha ADM invalida."}), 401

    return jsonify({
        "status": "sucesso",
        "token": criar_admin_token(),
        "expira_em_segundos": ADMIN_SESSION_SECONDS,
    }), 200


@app.route("/api/admin/status", methods=["GET"])
def admin_status():
    erro = validar_admin_painel()

    if erro:
        return erro

    servidores = [montar_info_servidor_admin(guild) for guild in sorted(bot.guilds, key=lambda item: item.name.lower())]
    logs = bot.eventos_recentes(50) if hasattr(bot, "eventos_recentes") else []
    sistema = montar_status_sistema()

    return jsonify({
        **status_publico_bot(),
        "admin": True,
        "comandos_slash_sincronizados": len(getattr(bot, "slash_synced_guilds", set())),
        "sistema": sistema,
        "saude": montar_saude_admin(servidores, logs, sistema),
        "logs": logs,
        "servidores": servidores,
    }), 200


@app.route("/api/admin/logs", methods=["GET"])
def admin_logs():
    erro = validar_admin_painel()

    if erro:
        return erro

    try:
        limite = int(request.args.get("limit", 50))
    except (TypeError, ValueError):
        limite = 50

    return jsonify({
        "status": "sucesso",
        "logs": bot.eventos_recentes(limite) if hasattr(bot, "eventos_recentes") else [],
        "atualizado_em": agora_iso(),
    }), 200


@app.route("/api/admin/servidores/<server_id>/membros", methods=["GET"])
def admin_listar_membros(server_id):
    erro = validar_admin_painel()

    if erro:
        return erro

    try:
        limite = int(request.args.get("limit", ADMIN_MEMBERS_LIMIT))
    except (TypeError, ValueError):
        limite = ADMIN_MEMBERS_LIMIT

    try:
        dados, mensagem, origem = listar_membros_admin_sync(server_id, limite)

        if not dados:
            return jsonify({"status": "erro", "mensagem": mensagem}), 404

        aviso = None
        if origem in ("cache_sem_intent", "cache_api_indisponivel"):
            aviso = "Lista pode estar incompleta. Ative Server Members Intent no Discord Developer Portal."

        return jsonify({
            "status": "sucesso",
            "aviso": aviso,
            **dados,
        }), 200
    except Exception as erro_membros:
        return jsonify({"status": "erro", "mensagem": str(erro_membros)}), 500


def validar_guild_privada_admin(server_id):
    erro = validar_admin_painel()

    if erro:
        return erro

    if not obter_private_guild_id():
        return jsonify({
            "status": "erro",
            "mensagem": "Área privada não configurada. Defina PRIVATE_GUILD_ID no Render.",
        }), 503

    if not guild_privada_habilitada(server_id):
        return jsonify({
            "status": "erro",
            "mensagem": "Esta função é exclusiva da guilda privada configurada.",
        }), 403

    return None


@app.route("/api/admin/servidores/<server_id>/privado/crachas", methods=["GET"])
def admin_listar_crachas_privados(server_id):
    erro = validar_guild_privada_admin(server_id)

    if erro:
        return erro

    try:
        crachas = executar_corrotina_bot(listar_crachas_privados(str(server_id)), timeout=10)
        return jsonify({
            "status": "sucesso",
            "server_id": str(server_id),
            "crachas": crachas,
        }), 200
    except Exception as erro_crachas:
        return jsonify({"status": "erro", "mensagem": str(erro_crachas)}), 500


@app.route("/api/admin/servidores/<server_id>/privado/crachas", methods=["POST"])
def admin_salvar_cracha_privado(server_id):
    erro = validar_guild_privada_admin(server_id)

    if erro:
        return erro

    dados = request.json or {}

    try:
        cracha, crachas = executar_corrotina_bot(salvar_cracha_privado(str(server_id), dados), timeout=12)
        if hasattr(bot, "registrar_evento"):
            bot.registrar_evento(
                "admin_private_badge_save",
                f"Crachá privado salvo: {cracha.get('memberName') or cracha.get('memberId') or cracha.get('id')}.",
                guild_id=server_id,
            )
        return jsonify({
            "status": "sucesso",
            "mensagem": "Crachá privado salvo.",
            "cracha": cracha,
            "crachas": crachas,
        }), 200
    except ValueError as erro_validacao:
        return jsonify({"status": "erro", "mensagem": str(erro_validacao)}), 400
    except Exception as erro_cracha:
        return jsonify({"status": "erro", "mensagem": str(erro_cracha)}), 500


@app.route("/api/admin/servidores/<server_id>/privado/crachas/<cracha_id>", methods=["DELETE"])
def admin_remover_cracha_privado(server_id, cracha_id):
    erro = validar_guild_privada_admin(server_id)

    if erro:
        return erro

    try:
        crachas = executar_corrotina_bot(remover_cracha_privado(str(server_id), str(cracha_id)), timeout=12)
        if hasattr(bot, "registrar_evento"):
            bot.registrar_evento(
                "admin_private_badge_delete",
                f"Crachá privado removido: {cracha_id}.",
                guild_id=server_id,
            )
        return jsonify({
            "status": "sucesso",
            "mensagem": "Crachá privado removido.",
            "crachas": crachas,
        }), 200
    except Exception as erro_cracha:
        return jsonify({"status": "erro", "mensagem": str(erro_cracha)}), 500


@app.route("/api/admin/servidores/<server_id>/leave", methods=["POST"])
def admin_sair_servidor(server_id):
    erro = validar_admin_painel()

    if erro:
        return erro

    dados = request.json or {}
    confirmar = str(dados.get("confirmar") or "").strip()
    motivo = dados.get("motivo", "")
    guild = obter_guild_bot(server_id)

    if not guild:
        return jsonify({"status": "erro", "mensagem": "Servidor nao encontrado pelo bot."}), 404

    if confirmar != guild.name:
        return jsonify({
            "status": "erro",
            "mensagem": "Confirmacao invalida. Digite exatamente o nome do servidor.",
        }), 400

    try:
        sucesso, mensagem = sair_servidor_admin_sync(server_id, motivo)

        if not sucesso:
            return jsonify({"status": "erro", "mensagem": mensagem}), 502

        bot.registrar_evento("admin_leave_guild", mensagem, guild_id=server_id, motivo=motivo)
        return jsonify({"status": "sucesso", "mensagem": mensagem}), 200
    except Exception as erro_saida:
        return jsonify({"status": "erro", "mensagem": str(erro_saida)}), 500


@app.route("/api/admin/servidores/<server_id>/membros/<user_id>/ban", methods=["POST"])
def admin_banir_membro(server_id, user_id):
    erro = validar_admin_painel()

    if erro:
        return erro

    dados = request.json or {}
    motivo = dados.get("motivo", "")

    try:
        sucesso, mensagem = banir_membro_admin_sync(server_id, user_id, motivo)

        if not sucesso:
            return jsonify({"status": "erro", "mensagem": mensagem}), 403

        bot.registrar_evento("admin_member_ban", mensagem, guild_id=server_id, user_id=user_id)
        return jsonify({"status": "sucesso", "mensagem": mensagem}), 200
    except Exception as erro_ban:
        return jsonify({"status": "erro", "mensagem": str(erro_ban)}), 500


@app.route("/api/admin/servidores/<server_id>/membros/<user_id>/kick", methods=["POST"])
def admin_expulsar_membro(server_id, user_id):
    erro = validar_admin_painel()

    if erro:
        return erro

    dados = request.json or {}
    motivo = dados.get("motivo", "")

    try:
        sucesso, mensagem = expulsar_membro_admin_sync(server_id, user_id, motivo)

        if not sucesso:
            return jsonify({"status": "erro", "mensagem": mensagem}), 403

        bot.registrar_evento("admin_member_kick", mensagem, guild_id=server_id, user_id=user_id)
        return jsonify({"status": "sucesso", "mensagem": mensagem}), 200
    except Exception as erro_kick:
        return jsonify({"status": "erro", "mensagem": str(erro_kick)}), 500


@app.route("/api/admin/servidores/<server_id>/membros/<user_id>/timeout", methods=["POST"])
def admin_castigar_membro(server_id, user_id):
    erro = validar_admin_painel()

    if erro:
        return erro

    dados = request.json or {}
    motivo = dados.get("motivo", "")
    minutos = dados.get("minutos", 10)

    try:
        sucesso, mensagem = castigar_membro_admin_sync(server_id, user_id, minutos, motivo)

        if not sucesso:
            return jsonify({"status": "erro", "mensagem": mensagem}), 403

        bot.registrar_evento("admin_member_timeout", mensagem, guild_id=server_id, user_id=user_id)
        return jsonify({"status": "sucesso", "mensagem": mensagem}), 200
    except Exception as erro_timeout:
        return jsonify({"status": "erro", "mensagem": str(erro_timeout)}), 500


@app.route("/api/auth/callback", methods=["POST"])
def discord_callback():
    dados_frontend = request.json or {}
    code = dados_frontend.get("code")
    redirect_uri = obter_redirect_uri_permitida(dados_frontend)

    if not code:
        return jsonify({"erro": "Codigo de autenticacao ausente"}), 400

    data = {
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri,
    }

    headers = {"Content-Type": "application/x-www-form-urlencoded"}

    try:
        token_response = requests.post(
            f"{DISCORD_API_URL}/oauth2/token",
            data=data,
            headers=headers,
            timeout=DISCORD_TIMEOUT,
        )
    except requests.RequestException:
        return jsonify({"erro": "Falha ao conectar ao Discord"}), 502

    if token_response.status_code != 200:
        return jsonify({"erro": "Falha ao obter token do Discord", "detalhe": token_response.text}), 400

    token_data = token_response.json()
    access_token = token_data.get("access_token")

    if not access_token:
        return jsonify({"erro": "Falha ao obter token do Discord"}), 400

    dados_autorizados, erro = montar_servidores_autorizados(access_token)

    if erro:
        return jsonify({"erro": "Falha ao buscar servidores do usuario"}), 400

    return jsonify({
        "status": "sucesso",
        "access_token": access_token,
        **dados_autorizados,
    }), 200


@app.route("/api/servidores", methods=["GET"])
def listar_servidores_usuario():
    token = obter_token_autorizacao()

    if not token:
        return jsonify({"status": "erro", "mensagem": "Sessao expirada. Entre novamente com o Discord."}), 401

    dados_autorizados, erro = montar_servidores_autorizados(token)

    if erro == "token_expirado":
        return jsonify({"status": "erro", "mensagem": "Sessao expirada. Entre novamente com o Discord."}), 401

    if erro:
        return jsonify({"status": "erro", "mensagem": "Nao consegui atualizar seus servidores agora."}), 503

    return jsonify({
        "status": "sucesso",
        **dados_autorizados,
    }), 200


@app.route("/api/config", methods=["POST"])
def receber_config():
    dados = request.json or {}
    server_id = dados.get("id")

    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        executar_corrotina_bot(salvar_config(server_id, dados), timeout=15)
        return jsonify({"status": "sucesso", "mensagem": "Configuracoes salvas!"}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/limpezas", methods=["POST"])
def salvar_limpeza_canal():
    dados = request.json or {}
    server_id = dados.get("id")
    canal_id = dados.get("canal_id")

    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    if not canal_id:
        return jsonify({"status": "erro", "mensagem": "ID do canal invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        limpezas = executar_corrotina_bot(salvar_limpeza(server_id, dados), timeout=15)
        return jsonify({
            "status": "sucesso",
            "mensagem": "Limpeza de canal salva!",
            "limpezas": limpezas,
        }), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/boas-vindas", methods=["POST"])
def salvar_config_boas_vindas():
    dados = request.json or {}
    server_id = dados.get("id")

    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    dados_validados, erro_canal = validar_canais_boas_vindas(server_id, dados)

    if erro_canal:
        return jsonify({"status": "erro", "mensagem": erro_canal}), 400

    try:
        config = executar_corrotina_bot(salvar_boas_vindas(server_id, dados_validados), timeout=15)
        return jsonify({
            "status": "sucesso",
            "mensagem": "Avisos de entrada e saida salvos!",
            "boas_vindas": config,
        }), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/moderacao", methods=["POST"])
def salvar_config_moderacao():
    dados = request.json or {}
    server_id = dados.get("id")

    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        config = executar_corrotina_bot(salvar_moderacao(server_id, dados), timeout=15)
        return jsonify({
            "status": "sucesso",
            "mensagem": "Moderacao salva!",
            "moderacao": config,
        }), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/servidores/<server_id>/canais", methods=["GET"])
def listar_canais_servidor(server_id):
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        canais = canais_texto_do_servidor(server_id)

        if canais is None:
            return jsonify({"status": "erro", "mensagem": "Servidor nao encontrado pelo bot."}), 404

        return jsonify({"status": "sucesso", "canais": canais}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/servidores/<server_id>/cargos", methods=["GET"])
def listar_cargos_servidor(server_id):
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        cargos = cargos_do_servidor(server_id)

        if cargos is None:
            return jsonify({"status": "erro", "mensagem": "Servidor nao encontrado pelo bot."}), 404

        return jsonify({"status": "sucesso", "cargos": cargos}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/<server_id>/boas-vindas", methods=["GET"])
def listar_config_boas_vindas(server_id):
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        config = executar_corrotina_bot(buscar_boas_vindas(server_id), timeout=15)
        return jsonify({"status": "sucesso", "boas_vindas": config}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/<server_id>/moderacao", methods=["GET"])
def listar_config_moderacao(server_id):
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        config = executar_corrotina_bot(buscar_moderacao(server_id), timeout=15)
        return jsonify({"status": "sucesso", "moderacao": config}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/<server_id>/limpezas", methods=["GET"])
def listar_limpezas(server_id):
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        limpezas = executar_corrotina_bot(buscar_limpezas(server_id), timeout=15)
        return jsonify({"status": "sucesso", "limpezas": limpezas}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


@app.route("/api/config/<server_id>/limpezas/<canal_id>", methods=["DELETE"])
def excluir_limpeza(server_id, canal_id):
    if not server_id or not canal_id:
        return jsonify({"status": "erro", "mensagem": "Servidor ou canal invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        limpezas = executar_corrotina_bot(remover_limpeza(server_id, canal_id), timeout=15)
        return jsonify({
            "status": "sucesso",
            "mensagem": "Limpeza removida!",
            "limpezas": limpezas,
        }), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


def reiniciar_processo_por_watchdog(motivo):
    agora = datetime.now(timezone.utc)
    setattr(bot, "watchdog_last_restart_reason", motivo)
    setattr(bot, "watchdog_last_restart_at", agora)
    setattr(bot, "watchdog_state", "reiniciando")

    if hasattr(bot, "registrar_evento"):
        bot.registrar_evento("bot_watchdog_restart", motivo, nivel="error")

    print(f"[WATCHDOG] {motivo}")
    os._exit(1)


async def monitorar_bot_watchdog():
    inicio = datetime.now(timezone.utc)
    setattr(bot, "watchdog_started_at", inicio)
    setattr(bot, "watchdog_state", "inicializando")
    setattr(bot, "watchdog_last_online_at", None)
    setattr(bot, "watchdog_last_check_at", inicio)

    while True:
        await asyncio.sleep(BOT_WATCHDOG_INTERVAL_SECONDS)
        agora = datetime.now(timezone.utc)
        setattr(bot, "watchdog_last_check_at", agora)

        if bot_online():
            setattr(bot, "watchdog_state", "online")
            setattr(bot, "watchdog_last_online_at", agora)
            continue

        if bot.is_closed():
            reiniciar_processo_por_watchdog("Bot Discord fechou. Reiniciando processo para o Render reconectar.")

        ultimo_online = getattr(bot, "watchdog_last_online_at", None)
        ultimo_ready = getattr(bot, "last_ready_at", None)

        if ultimo_online or ultimo_ready:
            referencia = ultimo_online or ultimo_ready
            offline_segundos = (agora - referencia.astimezone(timezone.utc)).total_seconds()
            setattr(bot, "watchdog_state", f"offline_{int(offline_segundos)}s")

            if offline_segundos >= BOT_OFFLINE_GRACE_SECONDS:
                reiniciar_processo_por_watchdog(
                    f"Bot Discord ficou offline por {int(offline_segundos)}s. Reiniciando para recuperar conexao."
                )
            continue

        inicializando_segundos = (agora - inicio).total_seconds()
        setattr(bot, "watchdog_state", f"aguardando_ready_{int(inicializando_segundos)}s")

        if inicializando_segundos >= BOT_STARTUP_GRACE_SECONDS:
            erro = getattr(bot, "last_start_error", None)
            detalhe = f" Ultimo erro: {erro}" if erro else ""
            reiniciar_processo_por_watchdog(
                f"Bot Discord nao chegou ao on_ready em {int(inicializando_segundos)}s.{detalhe}"
            )


async def iniciar_bot_supervisionado():
    token = os.getenv("DISCORD_TOKEN", "").strip()

    if not token:
        mensagem = "DISCORD_TOKEN nao configurado. Bot Discord nao pode iniciar."
        setattr(bot, "last_start_error", mensagem)
        setattr(bot, "last_start_error_at", datetime.now(timezone.utc))
        bot.registrar_evento("bot_start_failed", mensagem, nivel="error")
        await asyncio.sleep(5)
        os._exit(1)

    try:
        setattr(bot, "last_start_error", None)
        setattr(bot, "last_start_error_at", None)

        async with bot:
            await bot.start(token, reconnect=True)

        mensagem = "bot.start retornou sem excecao, mas o bot nao deveria encerrar sozinho."
        setattr(bot, "last_start_error", mensagem)
        setattr(bot, "last_start_error_at", datetime.now(timezone.utc))
        bot.registrar_evento("bot_start_returned", mensagem, nivel="error")
        await asyncio.sleep(5)
        os._exit(1)
    except Exception as erro:
        mensagem = f"{type(erro).__name__}: {erro}"
        setattr(bot, "last_start_error", mensagem[:900])
        setattr(bot, "last_start_error_at", datetime.now(timezone.utc))
        bot.registrar_evento("bot_start_failed", mensagem, nivel="error")
        print(f"[BOT] Falha critica ao iniciar/conectar. Reiniciando processo: {mensagem}")
        await asyncio.sleep(8)
        os._exit(1)


async def main():
    port = int(os.getenv("PORT", 5000))
    loop = asyncio.get_running_loop()
    registrar_loop_bot(loop)

    loop.run_in_executor(
        None,
        lambda: werkzeug.serving.run_simple("0.0.0.0", port, app, use_debugger=False, use_reloader=False, threaded=True),
    )
    print(f"[API] Servidor Flask iniciado na porta {port}")

    asyncio.create_task(monitorar_bot_watchdog())
    await iniciar_bot_supervisionado()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Desligando aplicacao...")
