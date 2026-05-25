import os

import discord
from discord.ext import commands
from dotenv import load_dotenv

load_dotenv()

EXTENSIONS = (
    "cogs.cleanup",
    "cogs.admin",
    "cogs.media",
)


class AMZBot(commands.Bot):
    async def setup_hook(self):
        for extension in EXTENSIONS:
            await self.load_extension(extension)
            print(f"[BOT] Extensao carregada: {extension}")


intents = discord.Intents.default()
intents.message_content = True

bot = AMZBot(command_prefix=os.getenv("AMZ_COMMAND_PREFIX", "!"), intents=intents)


@bot.event
async def on_ready():
    print(f"[{bot.user.name}] esta online e conectado ao Discord!")
