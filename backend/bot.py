import os
import threading
from flask import Flask, jsonify, request
from flask_cors import CORS
import discord
from discord.ext import commands
from dotenv import load_dotenv
from motor.motor_asyncio import AsyncIOMotorClient

# 1. Carrega as variáveis do arquivo .env
load_dotenv()

TOKEN = os.getenv("DISCORD_TOKEN")
MONGO_URI = os.getenv("MONGO_URI")

# 2. Configuração do Bot do Discord
intents = discord.Intents.default()
intents.message_content = True
bot = commands.Bot(command_prefix="!", intents=intents)

# 3. Conexão Assíncrona com o MongoDB usando Motor
cluster = AsyncIOMotorClient(MONGO_URI)
db = cluster["AMZCore"]          # Nome do seu banco de dados
collection = db["servidores"]     # Nome da sua coleção

# ==========================================
# 4. CONFIGURAÇÃO DA API FLASK (PARA O SITE)
# ==========================================
app = Flask(__name__)
CORS(app) # Permite que o site no GitHub Pages faça requisições sem ser bloqueado pelo navegador

@app.route("/", methods=["GET"])
def home():
    # Rota básica de teste para ver se a API da AMZ Studios está online
    return jsonify({"mensagem": "API da AMZ Studios está online!"})

@app.route("/api/config", methods=["POST"])
def receber_config():
    dados = request.json
    # No futuro, o site em preto e branco vai enviar os dados por aqui para salvarmos no MongoDB
    return jsonify({"status": "sucesso", "dados_recebidos": dados}), 200

def rodar_flask():
    port = int(os.getenv("PORT", 5000))
    # O debug=False é importante para usar junto com o bot sem dar erro de thread
    app.run(host="0.0.0.0", port=port, debug=False)

# ==========================================
# 5. EVENTOS E COMANDOS DO BOT
# ==========================================
@bot.event
async def on_ready():
    print(f"[{bot.user.name}] está online e conectado ao Discord!")
    
    # Testando a conexão com o banco de dados de forma assíncrona
    try:
        await cluster.admin.command('ping')
        print("[MongoDB] Conexão com o Cluster Atlas estabelecida com sucesso!")
    except Exception as e:
        print(f"[MongoDB] Erro ao conectar ao banco: {e}")

# Exemplo de comando assíncrono buscando dados no MongoDB
@bot.command()
async def info(ctx):
    # Buscando as configurações do servidor atual no banco
    server_data = await collection.find_one({"id": str(ctx.guild.id)})
    
    if server_data:
        dias = server_data.get("dias", "Não definido")
        await ctx.send(f"⚙️ Esse servidor está configurado para retenção de {dias} dias.")
    else:
        await ctx.send("❌ Esse servidor ainda não foi configurado no painel web.")

# ==========================================
# 6. INICIALIZAÇÃO DO BOT E DA API JUNTOS
# ==========================================
if __name__ == "__main__":
    # Inicia a API Flask numa "thread" separada para não travar o bot do Discord
    threading.Thread(target=rodar_flask, daemon=True).start()
    
    # Inicia o bot
    bot.run(TOKEN)