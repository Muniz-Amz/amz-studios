import os
from datetime import datetime, timezone

import discord
from discord.ext import commands
from dotenv import load_dotenv

load_dotenv()

EXTENSIONS = (
    "cogs.cleanup",
    "cogs.status",
    "cogs.admin",
    "cogs.media",
    "cogs.welcome",
    "cogs.mention",
    "cogs.moderation",
)
SINCRONIZAR_SLASH_GLOBAL = os.getenv("AMZ_SYNC_GLOBAL_SLASH", "false").strip().lower() in (
    "1",
    "true",
    "sim",
    "yes",
    "on",
)
LIMPAR_SLASH_GLOBAL = os.getenv("AMZ_CLEAR_GLOBAL_SLASH", "true").strip().lower() in (
    "1",
    "true",
    "sim",
    "yes",
    "on",
)
SLASH_GUILD_IDS = [
    int(guild_id.strip())
    for guild_id in os.getenv("AMZ_SLASH_GUILD_IDS", "").replace(",", " ").split()
    if guild_id.strip()
]


class AMZBot(commands.Bot):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.slash_synced_guilds = set()
        self.started_at = datetime.now(timezone.utc)
        self.last_ready_at = None
        self.last_slash_sync_at = None

    async def clear_global_slash_commands(self):
        comandos_locais = list(self.tree.get_commands(guild=None))

        if not comandos_locais:
            return

        self.tree.clear_commands(guild=None)
        await self.tree.sync()

        for comando in comandos_locais:
            self.tree.add_command(comando, override=True)

        self.last_slash_sync_at = datetime.now(timezone.utc)
        print("[BOT] Slash commands globais removidos para evitar duplicidade.")

    async def setup_hook(self):
        for extension in EXTENSIONS:
            await self.load_extension(extension)
            print(f"[BOT] Extensao carregada: {extension}")

        if LIMPAR_SLASH_GLOBAL:
            await self.clear_global_slash_commands()
        elif SINCRONIZAR_SLASH_GLOBAL:
            comandos = await self.tree.sync()
            self.last_slash_sync_at = datetime.now(timezone.utc)
            print(f"[BOT] {len(comandos)} slash commands globais sincronizados.")

        if SLASH_GUILD_IDS:
            for guild_id in SLASH_GUILD_IDS:
                guild = discord.Object(id=guild_id)
                self.tree.copy_global_to(guild=guild)
                comandos = await self.tree.sync(guild=guild)
                self.slash_synced_guilds.add(guild_id)
                self.last_slash_sync_at = datetime.now(timezone.utc)
                print(f"[BOT] {len(comandos)} slash commands sincronizados no servidor {guild_id}.")

    async def sync_slash_guild(self, guild_id):
        if guild_id in self.slash_synced_guilds:
            return

        guild = discord.Object(id=guild_id)
        self.tree.copy_global_to(guild=guild)
        comandos = await self.tree.sync(guild=guild)
        self.slash_synced_guilds.add(guild_id)
        self.last_slash_sync_at = datetime.now(timezone.utc)
        print(f"[BOT] {len(comandos)} slash commands sincronizados no servidor {guild_id}.")

    async def sync_slash_connected_guilds(self):
        for guild in self.guilds:
            await self.sync_slash_guild(guild.id)


intents = discord.Intents.default()
intents.message_content = True
intents.members = True

bot = AMZBot(command_prefix=os.getenv("AMZ_COMMAND_PREFIX", "!"), intents=intents)


@bot.event
async def on_ready():
    bot.last_ready_at = datetime.now(timezone.utc)
    print(f"[{bot.user.name}] esta online e conectado ao Discord!")
    await bot.sync_slash_connected_guilds()


@bot.event
async def on_guild_join(guild):
    await bot.sync_slash_guild(guild.id)
