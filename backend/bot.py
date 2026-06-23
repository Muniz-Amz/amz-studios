import math
import os
import traceback
from collections import deque
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
    "cogs.announcement",
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
LIMPAR_SLASH_GLOBAL = os.getenv("AMZ_CLEAR_GLOBAL_SLASH", "false").strip().lower() in (
    "1",
    "true",
    "sim",
    "yes",
    "on",
)
SINCRONIZAR_SLASH_SERVIDORES_AO_INICIAR = os.getenv("AMZ_SYNC_CONNECTED_GUILDS_ON_READY", "false").strip().lower() in (
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


def erro_original(erro):
    atual = erro

    while hasattr(atual, "original"):
        atual = atual.original

    return atual


def extrair_espera_rate_limit(erro):
    original = erro_original(erro)
    retry_after = getattr(original, "retry_after", None)

    if retry_after is not None:
        try:
            return max(float(retry_after), 1)
        except (TypeError, ValueError):
            pass

    headers = getattr(getattr(original, "response", None), "headers", {}) or {}

    for chave in ("Retry-After", "retry-after", "X-RateLimit-Reset-After", "x-ratelimit-reset-after"):
        valor = headers.get(chave)
        if valor is None:
            continue

        try:
            return max(float(valor), 1)
        except (TypeError, ValueError):
            continue

    return 30


def eh_rate_limit_discord(erro):
    original = erro_original(erro)
    texto = str(erro)

    return isinstance(original, discord.HTTPException) and (
        getattr(original, "status", None) == 429
        or "429" in texto
        or "Too Many Requests" in texto
    )


class AMZBot(commands.Bot):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.slash_synced_guilds = set()
        self.started_at = datetime.now(timezone.utc)
        self.last_ready_at = None
        self.last_disconnect_at = None
        self.last_resumed_at = None
        self.last_slash_sync_at = None
        self.runtime_events = deque(maxlen=120)

    def registrar_evento(self, tipo, mensagem, nivel="info", **contexto):
        evento = {
            "tipo": tipo,
            "nivel": nivel,
            "mensagem": str(mensagem)[:900],
            "criado_em": datetime.now(timezone.utc).isoformat(),
            "contexto": {
                chave: str(valor)[:300]
                for chave, valor in contexto.items()
                if valor is not None
            },
        }
        self.runtime_events.appendleft(evento)
        prefixo = "ERRO" if nivel == "error" else nivel.upper()
        print(f"[{prefixo}] {tipo}: {evento['mensagem']}")
        return evento

    def eventos_recentes(self, limite=50):
        try:
            limite = int(limite)
        except (TypeError, ValueError):
            limite = 50

        limite = min(max(limite, 1), 120)
        return list(self.runtime_events)[:limite]

    async def clear_global_slash_commands(self):
        comandos_locais = list(self.tree.get_commands(guild=None))

        if not comandos_locais:
            return

        self.tree.clear_commands(guild=None)
        await self.tree.sync()

        for comando in comandos_locais:
            self.tree.add_command(comando, override=True)

        self.last_slash_sync_at = datetime.now(timezone.utc)
        self.registrar_evento("slash_clear_global", "Slash commands globais removidos para evitar duplicidade.")

    async def setup_hook(self):
        for extension in EXTENSIONS:
            await self.load_extension(extension)
            self.registrar_evento("cog_loaded", f"Extensao carregada: {extension}")

        async def slash_error_handler(interaction, error):
            comando = interaction.command.qualified_name if interaction.command else "desconhecido"
            guild_id = interaction.guild_id
            rate_limited = eh_rate_limit_discord(error)
            espera = math.ceil(extrair_espera_rate_limit(error)) if rate_limited else None
            tipo = "slash_rate_limited" if rate_limited else "slash_error"
            nivel = "warn" if rate_limited else "error"
            detalhe = (
                f"Discord limitou /{comando}. Aguarde {espera}s antes de tentar novamente."
                if rate_limited
                else f"Erro no slash /{comando}: {error}"
            )
            self.registrar_evento(
                tipo,
                detalhe,
                nivel=nivel,
                guild_id=guild_id,
                user_id=getattr(interaction.user, "id", None),
                retry_after=espera,
            )

            mensagem = (
                f"O Discord limitou esse comando. Aguarde {espera}s e tente novamente."
                if rate_limited
                else "Nao consegui executar esse comando agora. Tente novamente em alguns segundos."
            )
            try:
                if interaction.response.is_done():
                    await interaction.followup.send(mensagem, ephemeral=True)
                else:
                    await interaction.response.send_message(mensagem, ephemeral=True)
            except Exception:
                pass

        self.tree.on_error = slash_error_handler

        if LIMPAR_SLASH_GLOBAL:
            await self.clear_global_slash_commands()
        elif SINCRONIZAR_SLASH_GLOBAL:
            comandos = await self.tree.sync()
            self.last_slash_sync_at = datetime.now(timezone.utc)
            self.registrar_evento("slash_sync_global", f"{len(comandos)} slash commands globais sincronizados.")

        if SLASH_GUILD_IDS:
            for guild_id in SLASH_GUILD_IDS:
                guild = discord.Object(id=guild_id)
                self.tree.copy_global_to(guild=guild)
                comandos = await self.tree.sync(guild=guild)
                self.slash_synced_guilds.add(guild_id)
                self.last_slash_sync_at = datetime.now(timezone.utc)
                self.registrar_evento("slash_sync_guild", f"{len(comandos)} slash commands sincronizados.", guild_id=guild_id)

    async def sync_slash_guild(self, guild_id):
        if guild_id in self.slash_synced_guilds:
            return

        guild = discord.Object(id=guild_id)
        self.tree.copy_global_to(guild=guild)
        comandos = await self.tree.sync(guild=guild)
        self.slash_synced_guilds.add(guild_id)
        self.last_slash_sync_at = datetime.now(timezone.utc)
        self.registrar_evento("slash_sync_guild", f"{len(comandos)} slash commands sincronizados.", guild_id=guild_id)

    async def sync_slash_connected_guilds(self):
        for guild in self.guilds:
            await self.sync_slash_guild(guild.id)


intents = discord.Intents.default()
intents.message_content = True
intents.members = True

bot = AMZBot(command_prefix=os.getenv("AMZ_COMMAND_PREFIX", "!"), intents=intents, help_command=None)


@bot.event
async def on_ready():
    bot.last_ready_at = datetime.now(timezone.utc)
    bot.last_disconnect_at = None
    bot.registrar_evento("bot_ready", f"{bot.user.name} esta online e conectado ao Discord.")
    if SINCRONIZAR_SLASH_SERVIDORES_AO_INICIAR:
        await bot.sync_slash_connected_guilds()


@bot.event
async def on_disconnect():
    bot.last_disconnect_at = datetime.now(timezone.utc)
    bot.registrar_evento("bot_disconnect", "Gateway do Discord desconectou. Aguardando reconexao automatica.", nivel="warn")


@bot.event
async def on_resumed():
    bot.last_resumed_at = datetime.now(timezone.utc)
    bot.last_disconnect_at = None
    bot.registrar_evento("bot_resumed", "Gateway do Discord reconectado com sucesso.")


@bot.event
async def on_guild_join(guild):
    bot.registrar_evento("guild_join", f"Bot entrou em {guild.name}.", guild_id=guild.id)
    await bot.sync_slash_guild(guild.id)


@bot.event
async def on_guild_remove(guild):
    bot.registrar_evento("guild_remove", f"Bot saiu de {guild.name}.", guild_id=guild.id)


@bot.event
async def on_command_error(ctx, error):
    if isinstance(error, commands.CommandNotFound):
        return

    comando = getattr(ctx.command, "qualified_name", "desconhecido")
    rate_limited = eh_rate_limit_discord(error)
    espera = math.ceil(extrair_espera_rate_limit(error)) if rate_limited else None
    bot.registrar_evento(
        "prefix_rate_limited" if rate_limited else "prefix_error",
        (
            f"Discord limitou !{comando}. Aguarde {espera}s antes de tentar novamente."
            if rate_limited
            else f"Erro no comando !{comando}: {error}"
        ),
        nivel="warn" if rate_limited else "error",
        guild_id=getattr(ctx.guild, "id", None),
        user_id=getattr(ctx.author, "id", None),
        retry_after=espera,
    )
    await ctx.reply(
        f"O Discord limitou esse comando. Aguarde {espera}s e tente novamente."
        if rate_limited
        else "Nao consegui executar esse comando agora. Tente novamente em alguns segundos.",
        mention_author=False,
    )


@bot.event
async def on_error(event_method, *args, **kwargs):
    bot.registrar_evento(
        "discord_event_error",
        f"Erro no evento {event_method}: {traceback.format_exc()[-850:]}",
        nivel="error",
    )
