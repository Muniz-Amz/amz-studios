import os
import discord
from discord.ext import commands
from dotenv import load_dotenv
# Importando o cliente assíncrono do Motor
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

# Inicia o bot
bot.run(TOKEN)