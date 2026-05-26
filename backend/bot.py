import os
from datetime import datetime, timezone

import discord
from discord.ext import commands
from dotenv import load_dotenv

load_dotenv()

EXTENSIONS = (
    "cogs.cleanup",
    "cogs.admin",
    "cogs.media",
    "cogs.welcome",
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

    async def setup_hook(self):
        for extension in EXTENSIONS:
            await self.load_extension(extension)
            print(f"[BOT] Extensao carregada: {extension}")

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
