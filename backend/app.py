import os
import asyncio
import threading
import requests  # Necessário para falar com a API do Discord
from flask import Flask, jsonify, request
from flask_cors import CORS
from dotenv import load_dotenv

from bot import bot
from database import salvar_config

load_dotenv()
app = Flask(__name__)
CORS(app)

# Configurações do Discord OAuth2 (Coloque no seu arquivo .env)
CLIENT_ID = os.getenv("DISCORD_CLIENT_ID")
CLIENT_SECRET = os.getenv("DISCORD_CLIENT_SECRET")
REDIRECT_URI = os.getenv("DISCORD_REDIRECT_URI")

DISCORD_API_URL = "https://discord.com/api/v10"

# ==========================================
# ROTA DE LOGIN OAUTH2 (TROCA O CÓDIGO POR TOKEN)
# ==========================================
@app.route("/api/auth/callback", methods=["POST"])
def discord_callback():
    dados_frontend = request.json
    code = dados_frontend.get("code")
    
    if not code:
        return jsonify({"erro": "Código de autenticação ausente"}), 400

    # 1. Troca o 'code' do Discord por um Access Token do usuário
    data = {
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": REDIRECT_URI
    }
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    
    token_response = requests.post(f"{DISCORD_API_URL}/oauth2/token", data=data, headers=headers)
    if token_response.status_code != 200:
        return jsonify({"erro": "Falha ao obter token do Discord"}), 400
        
    access_token = token_response.json().get("access_token")

    # 2. Busca os servidores (guilds) do usuário logado
    user_headers = {"Authorization": f"Bearer {access_token}"}
    guilds_response = requests.get(f"{DISCORD_API_URL}/users/@me/guilds", headers=user_headers)
    
    if guilds_response.status_code != 200:
        return jsonify({"erro": "Falha ao buscar servidores do usuário"}), 400

    user_guilds = guilds_response.json()
    servidores_autorizados = []

    # 3. FILTRO DE SEGURANÇA: Só libera servidores onde o usuário é ADM E o bot está dentro
    for guild in user_guilds:
        # A permissão 'administrator' no Discord é representada pelo bit 0x8
        permissions = int(guild.get("permissions", 0))
        is_admin = (permissions & 0x8) == 0x8
        
        # Verifica se o Celestial Bot está nesse servidor
        bot_guild = bot.get_guild(int(guild["id"]))
        
        if is_admin and bot_guild:
            servidores_autorizados.append({
                "id": str(guild["id"]),
                "nome": guild["name"],
                "icon": guild.get("icon")
            })

    # Retorna apenas os servidores que o usuário REALMENTE pode mexer
    return jsonify({
        "status": "sucesso",
        "servidores": servidores_autorizados
    }), 200

# ==========================================
# ROTA DE CONFIGURAÇÃO SALVA (PROTEGIDA POR LOGICA DE USO)
# ==========================================
@app.route("/api/config", methods=["POST"])
def receber_config():
    dados = request.json
    server_id = dados.get("id")
    
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor inválido."}), 400
    
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(salvar_config(server_id, dados))
        loop.close()
        return jsonify({"status": "sucesso", "mensagem": "Configurações salvas!"}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500

def rodar_flask():
    port = int(os.getenv("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=False)

if __name__ == "__main__":
    threading.Thread(target=rodar_flask, daemon=True).start()
    bot.run(os.getenv("DISCORD_TOKEN"))