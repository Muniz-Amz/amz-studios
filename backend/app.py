import asyncio
import os

import discord
import requests
import werkzeug.serving
from dotenv import load_dotenv
from flask import Flask, jsonify, request
from flask_cors import CORS

from bot import bot
from database import buscar_limpezas, remover_limpeza, salvar_config, salvar_limpeza

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
