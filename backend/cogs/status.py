from datetime import datetime, timezone

import discord
from discord import app_commands
from discord.ext import commands

from database import buscar_boas_vindas, buscar_limpezas, status_banco_dados
from services.cleanup_service import INTERVALO_LIMPEZA_MINUTOS, rotulo_tempo_limpeza


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


def status_ligado(valor):
    return "Ativo" if valor else "Inativo"


class StatusCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    async def montar_embed_info(self, guild=None, solicitante=None):
        banco = await status_banco_dados()
        erro_config = None

        try:
            limpezas = await buscar_limpezas(str(guild.id)) if guild else []
            boas_vindas = await buscar_boas_vindas(str(guild.id)) if guild else {}
        except Exception as erro:
            limpezas = []
            boas_vindas = {}
            erro_config = str(erro)

        comandos = sorted(command.name for command in self.bot.tree.get_commands())
        latencia = self.bot.latency * 1000 if self.bot.latency is not None else None
        total_membros = sum((server.member_count or len(server.members)) for server in self.bot.guilds)
        total_canais = sum(len(server.channels) for server in self.bot.guilds)
        avatar_bot = str(self.bot.user.display_avatar.url) if self.bot.user else None

        embed = discord.Embed(
            title="AMZ Bot | Info",
            description="Resumo de saude, performance e configuracoes do bot.",
            color=discord.Color.from_rgb(255, 255, 255),
            timestamp=datetime.now(timezone.utc),
        )

        if self.bot.user:
            embed.set_author(name=str(self.bot.user), icon_url=avatar_bot)

        if guild and guild.icon:
            embed.set_thumbnail(url=str(guild.icon.url))
        elif avatar_bot:
            embed.set_thumbnail(url=avatar_bot)

        embed.add_field(
            name="Bot",
            value=(
                f"Status: Online\n"
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
                f"Membros: {formatar_numero(total_membros)}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Banco de dados",
            value=(
                f"Status: {'Online' if banco.get('online') else 'Offline'}\n"
                f"Ping: {formatar_ms(banco.get('ping_ms'))}\n"
                f"Docs: {formatar_numero(banco.get('documentos'))}"
            ),
            inline=True,
        )

        if guild:
            embed.add_field(
                name="Servidor atual",
                value=(
                    f"Nome: {guild.name}\n"
                    f"Membros: {formatar_numero(guild.member_count or len(guild.members))}\n"
                    f"Canais: {formatar_numero(len(guild.channels))}"
                ),
                inline=True,
            )
            embed.add_field(
                name="Configuracoes",
                value=(
                    f"Limpezas: {formatar_numero(len(limpezas))}\n"
                    f"Entrada: {status_ligado(boas_vindas.get('entrada_ativa'))}\n"
                    f"Saida: {status_ligado(boas_vindas.get('saida_ativa'))}"
                ),
                inline=True,
            )
        else:
            embed.add_field(
                name="Configuracoes",
                value=f"Rotina de limpeza: a cada {INTERVALO_LIMPEZA_MINUTOS} min",
                inline=True,
            )

        embed.add_field(
            name="Comandos",
            value=(
                f"Slash carregados: {formatar_numero(len(comandos))}\n"
                f"Guilds sincronizadas: {formatar_numero(len(getattr(self.bot, 'slash_synced_guilds', set())))}\n"
                f"Ultima sync: {formatar_data_discord(getattr(self.bot, 'last_slash_sync_at', None))}"
            ),
            inline=True,
        )

        if limpezas:
            resumo_limpezas = "\n".join(
                f"#{limpeza.get('canal_nome', limpeza.get('canal_id'))}: {rotulo_tempo_limpeza(limpeza)}"
                for limpeza in limpezas[:4]
            )
            if len(limpezas) > 4:
                resumo_limpezas += f"\n+{len(limpezas) - 4} configuracao(oes)"
            embed.add_field(name="Limpeza automatica", value=resumo_limpezas, inline=False)

        if not banco.get("online") and banco.get("erro"):
            embed.add_field(name="Erro do banco", value=str(banco.get("erro"))[:900], inline=False)

        if erro_config:
            embed.add_field(name="Erro nas configs", value=erro_config[:900], inline=False)

        if comandos:
            embed.add_field(
                name="Disponiveis",
                value=", ".join(f"/{nome}" for nome in comandos[:12]),
                inline=False,
            )

        nome_solicitante = getattr(solicitante, "display_name", None) or str(solicitante or "AMZ Studios")
        embed.set_footer(text=f"Solicitado por {nome_solicitante} | AMZ Studios")
        return embed

    @commands.command(name="info")
    async def info_prefix(self, ctx):
        embed = await self.montar_embed_info(ctx.guild, ctx.author)
        await ctx.reply(embed=embed, mention_author=False)

    @app_commands.command(name="info", description="Mostra latencia, banco, servidores e status geral do bot.")
    @app_commands.guild_only()
    async def slash_info(self, interaction: discord.Interaction):
        await interaction.response.defer(thinking=True)
        embed = await self.montar_embed_info(interaction.guild, interaction.user)
        await interaction.followup.send(embed=embed)


async def setup(bot):
    await bot.add_cog(StatusCog(bot))
