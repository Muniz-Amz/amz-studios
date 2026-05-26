from datetime import datetime, timezone

import discord
from discord import app_commands
from discord.ext import commands

from database import status_banco_dados
from security.discord_permissions import usuario_e_admin_ou_dono


COMANDOS_HELP = {
    "Administracao": [
        ("/info", "Mostra status resumido do bot, Discord e banco de dados."),
        ("/help", "Mostra esta central de comandos para administradores."),
        ("/deploy", "Solicita redeploy no Render quando o deploy hook estiver configurado."),
        ("@AMZ Bot", "Envia o link do site/dashboard no canal."),
    ],
    "Moderacao": [
        ("/limpar quantidade", "Apaga de 1 a 1000 mensagens recentes do canal atual."),
        ("/testaravisos tipo", "Envia um teste do aviso de entrada ou saida no canal configurado."),
    ],
    "Midia": [
        ("/gifimg arquivo", "Transforma uma imagem enviada em GIF."),
        ("/videogif arquivo", "Transforma um video enviado em GIF."),
        ("/audio arquivo", "Extrai o audio de um video enviado."),
        ("/midialimites", "Mostra os limites de conversao do bot."),
    ],
}


def formatar_numero(valor):
    try:
        return f"{int(valor):,}".replace(",", ".")
    except (TypeError, ValueError):
        return "0"


def formatar_ms(valor):
    if valor is None:
        return "Indisponivel"

    return f"{round(valor)} ms"


def formatar_duracao(inicio):
    if not inicio:
        return "Sem registro"

    agora = datetime.now(timezone.utc)
    if inicio.tzinfo is None:
        inicio = inicio.replace(tzinfo=timezone.utc)

    total = max(int((agora - inicio).total_seconds()), 0)
    dias, resto = divmod(total, 86400)
    horas, resto = divmod(resto, 3600)
    minutos, segundos = divmod(resto, 60)

    partes = []
    if dias:
        partes.append(f"{dias}d")
    if horas:
        partes.append(f"{horas}h")
    if minutos:
        partes.append(f"{minutos}min")

    if not partes:
        partes.append(f"{segundos}s")

    return " ".join(partes[:3])


def formatar_data_discord(data):
    if not data:
        return "Sem registro"

    if data.tzinfo is None:
        data = data.replace(tzinfo=timezone.utc)

    return f"<t:{int(data.timestamp())}:R>"


class StatusCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    def usuario_autorizado(self, guild, usuario):
        return usuario_e_admin_ou_dono(guild, usuario)

    async def montar_embed_info(self, guild=None, solicitante=None):
        banco = await status_banco_dados()
        latencia = self.bot.latency * 1000 if self.bot.latency is not None else None
        total_membros = sum((server.member_count or len(server.members)) for server in self.bot.guilds)
        total_canais = sum(len(server.channels) for server in self.bot.guilds)
        avatar_bot = str(self.bot.user.display_avatar.url) if self.bot.user else None

        embed = discord.Embed(
            title="AMZ Bot | Status",
            description="Resumo rapido de saude e performance.",
            color=discord.Color.from_rgb(255, 255, 255),
            timestamp=datetime.now(timezone.utc),
        )

        if self.bot.user:
            embed.set_author(name=str(self.bot.user), icon_url=avatar_bot)

        if avatar_bot:
            embed.set_thumbnail(url=avatar_bot)

        embed.add_field(
            name="Bot",
            value=(
                "Status: Online\n"
                f"Uptime: {formatar_duracao(getattr(self.bot, 'started_at', None))}\n"
                f"Online desde: {formatar_data_discord(getattr(self.bot, 'last_ready_at', None))}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Discord",
            value=(
                f"Latencia: {formatar_ms(latencia)}\n"
                f"Servidores: {formatar_numero(len(self.bot.guilds))}\n"
                f"Membros: {formatar_numero(total_membros)}\n"
                f"Canais: {formatar_numero(total_canais)}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Banco de dados",
            value=(
                f"Status: {'Online' if banco.get('online') else 'Offline'}\n"
                f"Ping: {formatar_ms(banco.get('ping_ms'))}\n"
                f"Database: {banco.get('database') or 'AMZCore'}\n"
                f"Docs: {formatar_numero(banco.get('documentos'))}"
            ),
            inline=True,
        )

        if not banco.get("online") and banco.get("erro"):
            embed.add_field(name="Erro do banco", value=str(banco.get("erro"))[:900], inline=False)

        nome_solicitante = getattr(solicitante, "display_name", None) or str(solicitante or "AMZ Studios")
        embed.set_footer(text=f"Solicitado por {nome_solicitante} | AMZ Studios")
        return embed

    def montar_embed_help(self, solicitante=None):
        embed = discord.Embed(
            title="AMZ Bot | Help",
            description="Central de comandos do bot. Visivel apenas para administradores.",
            color=discord.Color.from_rgb(255, 255, 255),
            timestamp=datetime.now(timezone.utc),
        )

        avatar_bot = str(self.bot.user.display_avatar.url) if self.bot.user else None
        if self.bot.user:
            embed.set_author(name=str(self.bot.user), icon_url=avatar_bot)
        if avatar_bot:
            embed.set_thumbnail(url=avatar_bot)

        for categoria, comandos in COMANDOS_HELP.items():
            linhas = [f"`{nome}` - {descricao}" for nome, descricao in comandos]
            embed.add_field(name=categoria, value="\n".join(linhas), inline=False)

        nome_solicitante = getattr(solicitante, "display_name", None) or str(solicitante or "AMZ Studios")
        embed.set_footer(text=f"Solicitado por {nome_solicitante} | AMZ Studios")
        return embed

    @commands.command(name="info")
    async def info_prefix(self, ctx):
        if not self.usuario_autorizado(ctx.guild, ctx.author):
            await ctx.reply("Apenas o dono do servidor ou usuarios com `Administrador` podem usar `!info`.")
            return

        embed = await self.montar_embed_info(ctx.guild, ctx.author)
        await ctx.reply(embed=embed, mention_author=False)

    @app_commands.command(name="info", description="Mostra status do bot, Discord e banco de dados.")
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def slash_info(self, interaction: discord.Interaction):
        if not self.usuario_autorizado(interaction.guild, interaction.user):
            await interaction.response.send_message(
                "Apenas o dono do servidor ou usuarios com `Administrador` podem usar `/info`.",
                ephemeral=True,
            )
            return

        await interaction.response.defer(ephemeral=True, thinking=True)
        embed = await self.montar_embed_info(interaction.guild, interaction.user)
        await interaction.followup.send(embed=embed, ephemeral=True)

    @app_commands.command(name="help", description="Mostra todos os comandos do AMZ Bot.")
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def slash_help(self, interaction: discord.Interaction):
        if not self.usuario_autorizado(interaction.guild, interaction.user):
            await interaction.response.send_message(
                "Apenas o dono do servidor ou usuarios com `Administrador` podem usar `/help`.",
                ephemeral=True,
            )
            return

        embed = self.montar_embed_help(interaction.user)
        await interaction.response.send_message(embed=embed, ephemeral=True)


async def setup(bot):
    await bot.add_cog(StatusCog(bot))
