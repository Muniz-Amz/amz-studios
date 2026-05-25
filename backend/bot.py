import asyncio
import os
from datetime import datetime, timedelta, timezone

import discord
import requests
from discord.ext import commands, tasks
from dotenv import load_dotenv

from database import buscar_limpezas, buscar_todas_limpezas

load_dotenv()

intents = discord.Intents.default()
intents.message_content = True
bot = commands.Bot(command_prefix="!", intents=intents)

INTERVALO_LIMPEZA_MINUTOS = int(os.getenv("AMZ_CLEANUP_INTERVAL_MINUTES", "30"))
MAX_MENSAGENS_POR_CANAL = int(os.getenv("AMZ_CLEANUP_MAX_MESSAGES_PER_CHANNEL", "200"))
PAUSA_ENTRE_DELECOES = float(os.getenv("AMZ_CLEANUP_DELETE_DELAY_SECONDS", "0.35"))
RENDER_DEPLOY_HOOK_URL = os.getenv("RENDER_DEPLOY_HOOK_URL", "").strip()
DEPLOY_TIMEOUT_SEGUNDOS = int(os.getenv("AMZ_DEPLOY_TIMEOUT_SECONDS", "15"))
DEPLOY_ALLOWED_USER_IDS = {
    user_id.strip()
    for user_id in os.getenv("AMZ_DEPLOY_ALLOWED_USER_IDS", "").replace(",", " ").split()
    if user_id.strip()
}


def normalizar_dias(dias):
    try:
        valor = int(dias)
    except (TypeError, ValueError):
        valor = 1

    return min(max(valor, 1), 14)


def bot_tem_permissoes_limpeza(channel):
    guild = getattr(channel, "guild", None)
    bot_member = getattr(guild, "me", None)

    if not bot_member:
        return False

    permissoes = channel.permissions_for(bot_member)
    return permissoes.manage_messages and permissoes.read_message_history


def usuario_pode_deploy(ctx):
    if str(ctx.author.id) in DEPLOY_ALLOWED_USER_IDS:
        return True

    if ctx.guild and ctx.guild.owner_id == ctx.author.id:
        return True

    return False


def disparar_deploy_render():
    response = requests.post(RENDER_DEPLOY_HOOK_URL, timeout=DEPLOY_TIMEOUT_SEGUNDOS)
    response.raise_for_status()
    return response.status_code


async def excluir_mensagens_antigas(server_id, limpeza):
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

    dias = normalizar_dias(limpeza.get("dias", 1))
    limite = datetime.now(timezone.utc) - timedelta(days=dias)
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
                print(f"[LIMPEZA] Discord recusou delete em #{channel.name}: {erro}")
                await asyncio.sleep(2)
    except discord.Forbidden:
        print(f"[LIMPEZA] Sem acesso ao historico de #{channel.name} em {guild.name}.")
    except discord.HTTPException as erro:
        print(f"[LIMPEZA] Erro ao ler historico de #{channel.name}: {erro}")

    return removidas


@tasks.loop(minutes=INTERVALO_LIMPEZA_MINUTOS)
async def limpeza_automatica():
    servidores = await buscar_todas_limpezas()
    total_removidas = 0

    for servidor in servidores:
        server_id = servidor.get("id")

        if not server_id:
            continue

        for limpeza in servidor.get("limpezas", []):
            total_removidas += await excluir_mensagens_antigas(server_id, limpeza)

    if total_removidas:
        print(f"[LIMPEZA] {total_removidas} mensagens antigas removidas.")


@limpeza_automatica.before_loop
async def antes_da_limpeza_automatica():
    await bot.wait_until_ready()


@bot.event
async def on_ready():
    print(f"[{bot.user.name}] esta online e conectado ao Discord!")

    if not limpeza_automatica.is_running():
        limpeza_automatica.start()
        print(f"[LIMPEZA] Rotina automatica ativa a cada {INTERVALO_LIMPEZA_MINUTOS} minutos.")


@bot.command()
async def info(ctx):
    limpezas = await buscar_limpezas(str(ctx.guild.id))

    if not limpezas:
        await ctx.send("Esse servidor ainda nao tem limpeza configurada no painel web.")
        return

    linhas = [
        f"#{limpeza.get('canal_nome', limpeza.get('canal_id'))}: {normalizar_dias(limpeza.get('dias'))} dias"
        for limpeza in limpezas
    ]
    await ctx.send("Limpezas configuradas:\n" + "\n".join(linhas))


@bot.command()
async def deploy(ctx):
    if not usuario_pode_deploy(ctx):
        await ctx.send("Apenas o dono do servidor ou usuarios autorizados podem usar `!deploy`.")
        return

    if not RENDER_DEPLOY_HOOK_URL:
        await ctx.send("Deploy Hook nao configurado. Adicione `RENDER_DEPLOY_HOOK_URL` nas variaveis do Render.")
        return

    await ctx.send("Iniciando deploy no Render...")

    try:
        status_code = await asyncio.to_thread(disparar_deploy_render)
    except requests.RequestException as erro:
        await ctx.send(f"Nao consegui iniciar o deploy: `{erro}`")
        return

    await ctx.send(f"Deploy solicitado com sucesso. Status HTTP: `{status_code}`")
