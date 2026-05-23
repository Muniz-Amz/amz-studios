import os
import discord
from discord.ext import commands
from dotenv import load_dotenv

# Importa a função do seu novo arquivo database.py
from database import buscar_config

load_dotenv()

# Configuração do Bot do Discord
intents = discord.Intents.default()
intents.message_content = True
bot = commands.Bot(command_prefix="!", intents=intents)

@bot.event
async def on_ready():
    print(f"[{bot.user.name}] está online e conectado ao Discord!")

# Exemplo de comando assíncrono buscando dados no MongoDB
@bot.command()
async def info(ctx):
    # Buscando as configurações do servidor usando a função do database.py
    server_data = await buscar_config(str(ctx.guild.id))
    
    if server_data:
        dias = server_data.get("dias", "Não definido")
        await ctx.send(f"⚙️ Esse servidor está configurado para retenção de {dias} dias.")
    else:
        await ctx.send("❌ Esse servidor ainda não foi configurado no painel web.")