import asyncio
import base64
import hashlib
import hmac
import json
import os
import platform
import sys
import time
from datetime import datetime, timezone

import discord
import requests
import werkzeug.serving
from dotenv import load_dotenv
from flask import Flask, jsonify, request
from flask_cors import CORS

from bot import bot
from database import buscar_limpezas, remover_limpeza, salvar_config, salvar_limpeza, status_banco_dados

load_dotenv()

app = Flask(__name__)
CORS(app)

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
API_STARTED_AT = datetime.now(timezone.utc)


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
        futuro = asyncio.run_coroutine_threadsafe(
            usuario_tem_permissao_pelo_bot(server_id, user_id),
            bot.loop,
        )
        return futuro.result(timeout=10)
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
            })

    return {
        "usuario": {
            "id": str(user_id),
            "nome": usuario.get("username"),
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
        })

    return canais


def permissoes_bot_servidor(guild):
    membro_bot = guild.me

    if not membro_bot:
        return {}

    permissoes = membro_bot.guild_permissions
    return {
        "administrador": permissoes.administrator,
        "gerenciar_servidor": permissoes.manage_guild,
        "gerenciar_mensagens": permissoes.manage_messages,
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


def bot_pode_banir_membro(guild, member):
    membro_bot = guild.me

    if not membro_bot:
        return False, "Bot nao encontrado no servidor."

    if not membro_bot.guild_permissions.ban_members:
        return False, "Bot nao tem permissao de banir membros."

    if member.id == guild.owner_id:
        return False, "Nao e possivel banir o dono do servidor."

    if bot.user and member.id == bot.user.id:
        return False, "O bot nao pode banir a si mesmo."

    if member.top_role >= membro_bot.top_role:
        return False, "Cargo do membro esta igual ou acima do cargo do bot."

    return True, "Pode banir."


def montar_info_membro(member, guild):
    pode_banir, motivo_bloqueio = bot_pode_banir_membro(guild, member)
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
    futuro = asyncio.run_coroutine_threadsafe(listar_membros_admin_async(server_id, limite), bot.loop)
    return futuro.result(timeout=25)


async def banir_membro_admin_async(server_id, user_id, motivo):
    guild = obter_guild_bot(server_id)

    if not guild:
        return False, "Servidor nao encontrado pelo bot."

    if not guild.me or not guild.me.guild_permissions.ban_members:
        return False, "Bot nao tem permissao de banir membros neste servidor."

    try:
        user_id_int = int(user_id)
    except (TypeError, ValueError):
        return False, "ID do membro invalido."

    try:
        member = guild.get_member(user_id_int) or await guild.fetch_member(user_id_int)
    except discord.NotFound:
        return False, "Membro nao encontrado no servidor."
    except discord.Forbidden:
        return False, "Discord negou acesso ao membro. Verifique a intent de membros."
    except discord.HTTPException as erro:
        return False, f"Discord recusou a busca do membro: {erro}"

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
    futuro = asyncio.run_coroutine_threadsafe(banir_membro_admin_async(server_id, user_id, motivo), bot.loop)
    return futuro.result(timeout=25)


def buscar_limpezas_sync(server_id):
    try:
        futuro = asyncio.run_coroutine_threadsafe(buscar_limpezas(str(server_id)), bot.loop)
        return futuro.result(timeout=10)
    except Exception:
        return []


def status_banco_sync():
    try:
        futuro = asyncio.run_coroutine_threadsafe(status_banco_dados(), bot.loop)
        return futuro.result(timeout=12)
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
    )

    return {nome: variavel_configurada(nome) for nome in variaveis}


def montar_status_bot_admin():
    comandos_prefixo = sorted(bot.commands, key=lambda comando: comando.qualified_name)

    return {
        "prefixo": os.getenv("AMZ_COMMAND_PREFIX", "!"),
        "cogs": sorted(bot.cogs.keys()),
        "comandos_prefixo": [comando.qualified_name for comando in comandos_prefixo],
        "total_comandos_prefixo": len(comandos_prefixo),
        "total_comandos_slash": len(bot.tree.get_commands()),
        "slash_guilds_sincronizadas": len(getattr(bot, "slash_synced_guilds", set())),
        "intents": {
            "message_content": bot.intents.message_content,
            "members": bot.intents.members,
            "guilds": bot.intents.guilds,
        },
        "totais": {
            "servidores": len(bot.guilds),
            "membros_aproximados": sum(guild.member_count or 0 for guild in bot.guilds),
            "canais": sum(len(guild.channels) for guild in bot.guilds),
            "cargos": sum(len(guild.roles) for guild in bot.guilds),
        },
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
        "limpezas_configuradas": limpezas,
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

    return jsonify({
        **status_publico_bot(),
        "admin": True,
        "comandos_slash_sincronizados": len(getattr(bot, "slash_synced_guilds", set())),
        "sistema": montar_status_sistema(),
        "servidores": servidores,
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

        return jsonify({"status": "sucesso", "mensagem": mensagem}), 200
    except Exception as erro_ban:
        return jsonify({"status": "erro", "mensagem": str(erro_ban)}), 500


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
        futuro = asyncio.run_coroutine_threadsafe(salvar_config(server_id, dados), bot.loop)
        futuro.result(timeout=15)
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
        futuro = asyncio.run_coroutine_threadsafe(salvar_limpeza(server_id, dados), bot.loop)
        limpezas = futuro.result(timeout=15)
        return jsonify({
            "status": "sucesso",
            "mensagem": "Limpeza de canal salva!",
            "limpezas": limpezas,
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


@app.route("/api/config/<server_id>/limpezas", methods=["GET"])
def listar_limpezas(server_id):
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor invalido."}), 400

    erro = validar_admin_requisicao(server_id)
    if erro:
        return erro

    try:
        futuro = asyncio.run_coroutine_threadsafe(buscar_limpezas(server_id), bot.loop)
        limpezas = futuro.result(timeout=15)
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
        futuro = asyncio.run_coroutine_threadsafe(remover_limpeza(server_id, canal_id), bot.loop)
        limpezas = futuro.result(timeout=15)
        return jsonify({
            "status": "sucesso",
            "mensagem": "Limpeza removida!",
            "limpezas": limpezas,
        }), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500


async def main():
    port = int(os.getenv("PORT", 5000))
    loop = asyncio.get_event_loop()

    loop.run_in_executor(
        None,
        lambda: werkzeug.serving.run_simple("0.0.0.0", port, app, use_debugger=False, use_reloader=False),
    )
    print(f"[API] Servidor Flask iniciado na porta {port}")

    async with bot:
        await bot.start(os.getenv("DISCORD_TOKEN"))


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Desligando aplicacao...")
