from datetime import datetime, timezone

import discord
from discord import app_commands
from discord.ext import commands

from database import status_banco_dados
from security.discord_permissions import usuario_e_admin_ou_dono


COMANDOS_HELP = {
    "Administracao e status": [
        ("/amz info", "Mostra status, uptime, sincronizacao slash e banco de dados."),
        ("/amz ajuda", "Mostra esta central de comandos para administradores."),
        ("/admin deploy", "Solicita redeploy no Render quando o deploy hook estiver configurado."),
        ("@AMZ Bot", "Envia o link do site/dashboard no canal."),
    ],
    "Moderacao": [
        ("/mod limpar quantidade", "Apaga mensagens recentes sem remover mensagens fixadas."),
        ("/mod advertir usuario motivo", "Registra advertencia, envia DM e publica o log configurado."),
        ("/mod advertencias usuario", "Consulta as advertencias ativas de um membro."),
        ("/mod remover-advertencia usuario id motivo", "Remove uma advertencia pelo ID."),
        ("Painel ADM", "Banir, expulsar, castigar e consultar membros pelo site."),
    ],
    "Anuncios e mensagens": [
        ("/aviso cargo mensagem [canal] [imagem]", "Envia aviso ao cargo; canal e imagem sao opcionais."),
        ("Anuncio pelo painel", "Sem cargo publica no canal; com cargo pode enviar DMs em lotes."),
        ("Entrada e saida", "Mensagens automaticas configuradas pelo dashboard."),
    ],
    "Automacoes do painel": [
        ("Auto cargos", "Cargo de entrada e cargos entregues por reacao."),
        ("Mensagens", "Auto respostas, convites, metas e mensagens agendadas."),
        ("Canais", "Auto threads, limpeza e bloqueio de comandos por canal."),
        ("Auditoria e seguranca", "Logs por evento, anti-raid e protecao contra links suspeitos."),
    ],
    "Midia": [
        ("/midia gifimagem arquivo", "Transforma uma imagem enviada em GIF."),
        ("/midia gifvideo arquivo", "Transforma um video enviado em GIF."),
        ("/midia audio arquivo", "Extrai o audio de um video enviado."),
        ("/midia baixar url", "Baixa um video por link (Reels/Shorts) e envia como arquivo."),
        ("/midia limites", "Mostra os limites de conversao do bot."),
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
    amz = app_commands.Group(name="amz", description="Status, ajuda e atalhos principais do AMZ Bot.")

    def __init__(self, bot):
        self.bot = bot

    def usuario_autorizado(self, guild, usuario):
        return usuario_e_admin_ou_dono(guild, usuario)

    def contar_comandos_slash(self):
        def contar(comando):
            filhos = list(getattr(comando, "commands", []) or [])
            if filhos:
                return sum(contar(filho) for filho in filhos)
            return 1

        return sum(contar(comando) for comando in self.bot.tree.get_commands())

    async def montar_embed_info(self, guild=None, solicitante=None):
        banco = await status_banco_dados()
        latencia = self.bot.latency * 1000 if self.bot.latency is not None else None
        total_membros = sum((server.member_count or len(server.members)) for server in self.bot.guilds)
        total_canais = sum(len(server.channels) for server in self.bot.guilds)
        total_comandos = self.contar_comandos_slash()
        guilds_sincronizadas = len(getattr(self.bot, "slash_synced_guilds", set()))
        avatar_bot = str(self.bot.user.display_avatar.url) if self.bot.user else None

        embed = discord.Embed(
            title="AMZ Bot | Status",
            description="Saude do bot, sincronizacao dos comandos e recursos disponiveis.",
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
        embed.add_field(
            name="Comandos e modulos",
            value=(
                f"Slash commands: {formatar_numero(total_comandos)}\n"
                f"Modulos carregados: {formatar_numero(len(self.bot.cogs))}\n"
                f"Servidores sincronizados: {formatar_numero(guilds_sincronizadas)}\n"
                f"Ultima sincronizacao: {formatar_data_discord(getattr(self.bot, 'last_slash_sync_at', None))}"
            ),
            inline=False,
        )
        embed.add_field(
            name="Recursos principais",
            value=(
                "Advertencias persistentes e logs por evento\n"
                "Anuncios gerais ou filtrados por cargo\n"
                "Auto cargo, cargos por reacao e auto respostas\n"
                "Entrada/saida, anti-raid, limpeza e auditoria"
            ),
            inline=False,
        )

        if guild:
            embed.add_field(
                name="Servidor atual",
                value=(
                    f"Nome: {guild.name}\n"
                    f"ID: `{guild.id}`\n"
                    f"Membros: {formatar_numero(guild.member_count or len(guild.members))}\n"
                    f"Canais: {formatar_numero(len(guild.channels))}"
                ),
                inline=False,
            )

        if not banco.get("online") and banco.get("erro"):
            embed.add_field(name="Erro do banco", value=str(banco.get("erro"))[:900], inline=False)

        nome_solicitante = getattr(solicitante, "display_name", None) or str(solicitante or "AMZ Studios")
        embed.set_footer(text=f"Solicitado por {nome_solicitante} | AMZ Studios")
        return embed

    def montar_embed_help(self, solicitante=None):
        embed = discord.Embed(
            title="AMZ Bot | Help",
            description=(
                "Central atualizada de comandos e recursos. "
                "Parametros entre `[colchetes]` sao opcionais."
            ),
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

    @amz.command(name="info", description="Status rapido do bot, Discord e banco de dados.")
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def amz_info(self, interaction: discord.Interaction):
        if not self.usuario_autorizado(interaction.guild, interaction.user):
            await interaction.response.send_message(
                "Apenas administradores podem ver o status interno do AMZ Bot.",
                ephemeral=True,
            )
            return

        await interaction.response.defer(ephemeral=True, thinking=True)
        embed = await self.montar_embed_info(interaction.guild, interaction.user)
        await interaction.followup.send(embed=embed, ephemeral=True)

    @amz.command(name="ajuda", description="Mostra comandos por categoria e explica os atalhos principais.")
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def amz_ajuda(self, interaction: discord.Interaction):
        if not self.usuario_autorizado(interaction.guild, interaction.user):
            await interaction.response.send_message(
                "Apenas administradores podem ver a central de comandos.",
                ephemeral=True,
            )
            return

        embed = self.montar_embed_help(interaction.user)
        await interaction.response.send_message(embed=embed, ephemeral=True)


async def setup(bot):
    await bot.add_cog(StatusCog(bot))
