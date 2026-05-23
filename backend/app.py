import os
import asyncio
import requests
from flask import Flask, jsonify, request
from flask_cors import CORS
from dotenv import load_dotenv

# Importa as instâncias criadas nos outros arquivos
from bot import bot
from database import salvar_config

load_dotenv()
app = Flask(__name__)
CORS(app)

# Proteção contra espaços acidentais nas variáveis de ambiente
CLIENT_ID = os.getenv("DISCORD_CLIENT_ID", "").strip()
CLIENT_SECRET = os.getenv("DISCORD_CLIENT_SECRET", "").strip()
REDIRECT_URI = os.getenv("DISCORD_REDIRECT_URI", "").strip()

DISCORD_API_URL = "https://discord.com/api/v10"

@app.route("/api/auth/callback", methods=["POST"])
def discord_callback():
    dados_frontend = request.json
    code = dados_frontend.get("code")
    
    if not code:
        return jsonify({"erro": "Código de autenticação ausente"}), 400

    data = {
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": REDIRECT_URI
    }
    
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    
    # Faz a requisição ao Discord
    token_response = requests.post(f"{DISCORD_API_URL}/oauth2/token", data=data, headers=headers)
    
    # Debug: Se falhar, imprime o erro real nos logs do Render
    if token_response.status_code != 200:
        print(f"[DEBUG] Erro Discord: {token_response.text}")
        return jsonify({"erro": "Falha ao obter token do Discord", "detalhe": token_response.text}), 400
        
    access_token = token_response.json().get("access_token")

    # Busca os servidores do usuário
    user_headers = {"Authorization": f"Bearer {access_token}"}
    guilds_response = requests.get(f"{DISCORD_API_URL}/users/@me/guilds", headers=user_headers)
    
    if guilds_response.status_code != 200:
        return jsonify({"erro": "Falha ao buscar servidores do usuário"}), 400

    user_guilds = guilds_response.json()
    servidores_autorizados = []

    for guild in user_guilds:
        permissions = int(guild.get("permissions", 0))
        is_admin = (permissions & 0x8) == 0x8
        
        bot_guild = bot.get_guild(int(guild["id"]))
        
        if is_admin and bot_guild:
            servidores_autorizados.append({
                "id": str(guild["id"]),
                "nome": guild["name"],
                "icon": guild.get("icon")
            })

    return jsonify({
        "status": "sucesso",
        "servidores": servidores_autorizados
    }), 200

@app.route("/api/config", methods=["POST"])
def receber_config():
    dados = request.json
    server_id = dados.get("id")
    
    if not server_id:
        return jsonify({"status": "erro", "mensagem": "ID do servidor inválido."}), 400
    
    try:
        futuro = asyncio.run_coroutine_threadsafe(salvar_config(server_id, dados), bot.loop)
        futuro.result()
        return jsonify({"status": "sucesso", "mensagem": "Configurações salvas!"}), 200
    except Exception as e:
        return jsonify({"status": "erro", "mensagem": str(e)}), 500

# Inicialização combinada
async def main():
    port = int(os.getenv("PORT", 5000))
    import werkzeug.serving
    loop = asyncio.get_event_loop()
    
    loop.run_in_executor(None, lambda: werkzeug.serving.run_simple("0.0.0.0", port, app, use_debugger=False, use_reloader=False))
    print(f"[API] Servidor Flask iniciado na porta {port}")

    async with bot:
        await bot.start(os.getenv("DISCORD_TOKEN"))

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Desligando aplicação...")