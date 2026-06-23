import asyncio
import math
import time
from collections import defaultdict

import discord
from discord import app_commands
from discord.ext import commands, tasks

from database import (
    buscar_moderacao,
    contar_advertencias_ativas,
    listar_advertencias,
    registrar_advertencia,
    remover_advertencia,
)
from security.discord_permissions import usuario_e_admin_ou_dono
from services.cleanup_service import INTERVALO_LIMPEZA_MINUTOS, executar_limpezas


MAX_LIMPAR_MENSAGENS = 250
COOLDOWN_LIMPEZA_SEGUNDOS = 20
RATE_LIMIT_PADRAO_SEGUNDOS = 30


def ids_lista(valores):
    if isinstance(valores, (list, tuple, set)):
        return {str(valor).strip() for valor in valores if str(valor).strip()}

    return {
        item.strip()
        for item in str(valores or "").replace(",", "\n").splitlines()
        if item.strip()
    }


def extrair_espera_rate_limit(erro):
    retry_after = getattr(erro, "retry_after", None)

    if retry_after is not None:
        try:
            return max(float(retry_after), 1)
        except (TypeError, ValueError):
            pass

    headers = getattr(getattr(erro, "response", None), "headers", {}) or {}

    for chave in ("Retry-After", "retry-after", "X-RateLimit-Reset-After", "x-ratelimit-reset-after"):
        valor = headers.get(chave)
        if valor is None:
            continue

        try:
            return max(float(valor), 1)
        except (TypeError, ValueError):
            continue

    return RATE_LIMIT_PADRAO_SEGUNDOS


def eh_rate_limit(erro):
    return isinstance(erro, discord.HTTPException) and (
        getattr(erro, "status", None) == 429
        or "429" in str(erro)
        or "Too Many Requests" in str(erro)
    )


class CleanupCog(commands.Cog):
    mod = app_commands.Group(name="mod", description="Comandos de moderacao rapida.")

    def __init__(self, bot):
        self.bot = bot
        self.limpeza_locks = defaultdict(asyncio.Lock)
        self.limpeza_cooldowns = {}

    @commands.Cog.listener()
    async def on_ready(self):
        if not self.limpeza_automatica.is_running():
            self.limpeza_automatica.start()
            print(f"[LIMPEZA] Rotina automatica ativa a cada {INTERVALO_LIMPEZA_MINUTOS} minutos.")

    @tasks.loop(minutes=INTERVALO_LIMPEZA_MINUTOS)
    async def limpeza_automatica(self):
        total_removidas = await executar_limpezas(self.bot)

        if total_removidas:
            print(f"[LIMPEZA] {total_removidas} mensagens antigas removidas.")

    @limpeza_automatica.before_loop
    async def antes_da_limpeza_automatica(self):
        await self.bot.wait_until_ready()

    def registrar_rate_limit(self, interaction, erro, etapa):
        espera = math.ceil(extrair_espera_rate_limit(erro))
        self.bot.registrar_evento(
            "cleanup_rate_limited",
            f"Discord limitou /mod limpar na etapa {etapa}. Aguardar {espera}s.",
            nivel="warn",
            guild_id=getattr(interaction, "guild_id", None),
            channel_id=getattr(getattr(interaction, "channel", None), "id", None),
            user_id=getattr(getattr(interaction, "user", None), "id", None),
            retry_after=espera,
        )
        return espera

    async def responder_seguro(self, interaction, *args, **kwargs):
        try:
            await interaction.followup.send(*args, **kwargs)
            return True
        except discord.HTTPException as erro:
            if eh_rate_limit(erro):
                self.registrar_rate_limit(interaction, erro, "resposta")
                return False

            raise

    def usuario_pode_advertir(self, guild, member, config):
        if usuario_e_admin_ou_dono(guild, member):
            return True

        permissoes = config.get("permissoes", {})
        cargos_liberados = ids_lista(permissoes.get("cargos_admin")) | ids_lista(permissoes.get("cargos_moderador"))
        return any(str(role.id) in cargos_liberados for role in getattr(member, "roles", []))

    def validar_alvo_advertencia(self, guild, responsavel, usuario):
        if usuario.bot:
            return "Bots nao podem receber advertencias."

        if usuario.id == responsavel.id:
            return "Voce nao pode advertir a si mesmo."

        if usuario.id == guild.owner_id:
            return "O dono do servidor nao pode receber advertencias."

        if responsavel.id != guild.owner_id:
            if usuario.guild_permissions.administrator:
                return "Apenas o dono do servidor pode advertir outro administrador."

            if usuario.top_role >= responsavel.top_role:
                return "Voce nao pode advertir um membro com cargo igual ou superior ao seu."

        return None

    async def enviar_log_advertencia(self, guild, config, usuario, responsavel, registro, total, removida=False):
        cog_moderacao = self.bot.get_cog("ModerationCog")
        if not cog_moderacao or not hasattr(cog_moderacao, "enviar_log"):
            return

        titulo = "Advertencia removida" if removida else "Membro advertido"
        event_id = "remocao_punicoes" if removida else "advertencias"
        cor = discord.Color.green() if removida else discord.Color.orange()
        motivo = registro.get("removal_reason") if removida else registro.get("reason")
        await cog_moderacao.enviar_log(
            guild,
            config,
            "moderacao",
            titulo,
            f"{usuario.mention} (`{usuario.id}`)",
            cor,
            fields=[
                ("Responsavel", f"{responsavel.mention} (`{responsavel.id}`)", False),
                ("Motivo", motivo or "Sem motivo informado", False),
                ("ID da advertencia", f"`{registro.get('id')}`", True),
                ("Advertencias ativas", str(total), True),
            ],
            event_id=event_id,
            responsavel=responsavel,
        )

    async def enviar_dm_advertencia(self, guild, usuario, responsavel, registro, total, removida=False):
        embed = discord.Embed(
            title="Advertencia removida" if removida else "Voce recebeu uma advertencia",
            color=discord.Color.green() if removida else discord.Color.orange(),
        )
        embed.add_field(name="Servidor", value=guild.name, inline=False)
        embed.add_field(name="Responsavel", value=str(responsavel), inline=True)
        embed.add_field(name="ID", value=f"`{registro.get('id')}`", inline=True)
        embed.add_field(
            name="Motivo",
            value=(registro.get("removal_reason") if removida else registro.get("reason")) or "Sem motivo informado",
            inline=False,
        )
        embed.add_field(name="Advertencias ativas", value=str(total), inline=False)
        embed.set_footer(text="AMZ Moderacao")

        try:
            await usuario.send(embed=embed)
            return True
        except (discord.Forbidden, discord.HTTPException):
            return False

    async def limpar_canal(self, interaction, quantidade):
        channel = interaction.channel
        guild = interaction.guild

        if not guild or not channel or not hasattr(channel, "purge"):
            return None, "Use este comando dentro de um canal de texto do servidor.", None

        if not guild.me:
            return None, "Nao consegui identificar o cargo do bot neste servidor.", None

        bot_permissions = channel.permissions_for(guild.me)

        if not usuario_e_admin_ou_dono(guild, interaction.user):
            return None, "Apenas o dono do servidor ou usuarios com `Administrador` podem limpar mensagens.", None

        if not bot_permissions.manage_messages or not bot_permissions.read_message_history:
            return None, "O bot precisa de `Gerenciar mensagens` e `Ler historico de mensagens` neste canal.", None

        limite = min(max(int(quantidade), 1), MAX_LIMPAR_MENSAGENS)
        chave = (guild.id, channel.id)
        agora = time.monotonic()
        restante = self.limpeza_cooldowns.get(chave, 0) - agora

        if restante > 0:
            return None, f"Aguarde {math.ceil(restante)}s antes de limpar este canal novamente.", limite

        lock = self.limpeza_locks[chave]

        if lock.locked():
            return None, "Ja existe uma limpeza em andamento neste canal. Aguarde terminar.", limite

        async with lock:
            try:
                apagadas = await channel.purge(
                    limit=limite,
                    check=lambda mensagem: not mensagem.pinned,
                    bulk=True,
                    reason=f"Limpeza manual AMZ solicitada por {interaction.user} ({interaction.user.id})",
                )
            except discord.Forbidden:
                return None, "O Discord negou a limpeza. Confira a hierarquia/permissoes do cargo do bot.", limite
            except discord.HTTPException as erro:
                if eh_rate_limit(erro):
                    espera = self.registrar_rate_limit(interaction, erro, "exclusao")
                    self.limpeza_cooldowns[chave] = time.monotonic() + espera
                    return None, f"O Discord limitou a limpeza. Aguarde {espera}s e tente novamente com menos mensagens.", limite

                return None, "O Discord recusou a limpeza agora. Confira permissões e tente novamente.", limite

            self.limpeza_cooldowns[chave] = time.monotonic() + COOLDOWN_LIMPEZA_SEGUNDOS

        return len(apagadas), None, limite

    @mod.command(name="limpar", description="Apaga mensagens recentes do canal atual.")
    @app_commands.describe(quantidade="Quantidade de mensagens para apagar. Acima de 250 sera limitado por seguranca.")
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def mod_limpar(self, interaction: discord.Interaction, quantidade: app_commands.Range[int, 1, 1000]):
        try:
            await interaction.response.defer(ephemeral=True, thinking=True)
        except discord.HTTPException as erro:
            if eh_rate_limit(erro):
                self.registrar_rate_limit(interaction, erro, "defer")
                return

            raise

        apagadas, erro, limite = await self.limpar_canal(interaction, quantidade)

        if erro:
            await self.responder_seguro(interaction, f"Nao consegui limpar: {erro}", ephemeral=True)
            return

        embed = discord.Embed(
            title="Limpeza concluida",
            description="Mensagens fixadas foram preservadas.",
            color=discord.Color.from_rgb(255, 255, 255),
        )
        embed.add_field(name="Canal", value=getattr(interaction.channel, "mention", "Canal atual"), inline=True)
        embed.add_field(name="Solicitadas", value=str(quantidade), inline=True)
        embed.add_field(name="Apagadas", value=str(apagadas), inline=True)
        if limite and int(quantidade) > limite:
            embed.add_field(name="Limite aplicado", value=f"{limite} por execucao para evitar rate limit.", inline=False)
        embed.set_footer(text=f"Comando organizado: /mod limpar | Limite: {MAX_LIMPAR_MENSAGENS}")

        await self.responder_seguro(interaction, embed=embed, ephemeral=True)

    @mod.command(name="advertir", description="Registra uma advertencia para um membro.")
    @app_commands.describe(
        usuario="Membro que recebera a advertencia.",
        motivo="Motivo da advertencia.",
    )
    @app_commands.guild_only()
    async def mod_advertir(
        self,
        interaction: discord.Interaction,
        usuario: discord.Member,
        motivo: app_commands.Range[str, 3, 500],
    ):
        await interaction.response.defer(ephemeral=True, thinking=True)
        config = await buscar_moderacao(str(interaction.guild.id))

        if not self.usuario_pode_advertir(interaction.guild, interaction.user, config):
            await interaction.followup.send(
                "Apenas administradores ou cargos de moderacao configurados no painel podem advertir.",
                ephemeral=True,
            )
            return

        erro_alvo = self.validar_alvo_advertencia(interaction.guild, interaction.user, usuario)
        if erro_alvo:
            await interaction.followup.send(erro_alvo, ephemeral=True)
            return

        registro = await registrar_advertencia(
            interaction.guild.id,
            usuario,
            interaction.user,
            str(motivo).strip(),
        )
        total = await contar_advertencias_ativas(interaction.guild.id, usuario.id)
        dm_enviada = await self.enviar_dm_advertencia(
            interaction.guild,
            usuario,
            interaction.user,
            registro,
            total,
        )
        await self.enviar_log_advertencia(
            interaction.guild,
            config,
            usuario,
            interaction.user,
            registro,
            total,
        )

        await interaction.followup.send(
            f"{usuario.mention} recebeu a advertencia `{registro['id']}`. "
            f"Total ativo: `{total}`. DM: `{'enviada' if dm_enviada else 'bloqueada'}`.",
            ephemeral=True,
        )

    @mod.command(name="advertencias", description="Consulta as advertencias ativas de um membro.")
    @app_commands.describe(usuario="Membro que voce deseja consultar.")
    @app_commands.guild_only()
    async def mod_advertencias(self, interaction: discord.Interaction, usuario: discord.Member):
        await interaction.response.defer(ephemeral=True, thinking=True)
        config = await buscar_moderacao(str(interaction.guild.id))

        if not self.usuario_pode_advertir(interaction.guild, interaction.user, config):
            await interaction.followup.send(
                "Apenas administradores ou cargos de moderacao configurados no painel podem consultar advertencias.",
                ephemeral=True,
            )
            return

        registros = await listar_advertencias(interaction.guild.id, usuario.id, limite=10)
        if not registros:
            await interaction.followup.send(f"{usuario.mention} nao possui advertencias ativas.", ephemeral=True)
            return

        linhas = [
            f"`{registro['id']}` • {registro.get('reason') or 'Sem motivo'}\n"
            f"Por: {registro.get('responsible_name') or 'Desconhecido'} • {registro.get('created_at') or '--'}"
            for registro in registros
        ]
        embed = discord.Embed(
            title=f"Advertencias de {usuario}",
            description="\n\n".join(linhas)[:4000],
            color=discord.Color.orange(),
        )
        embed.set_footer(text=f"Mostrando {len(registros)} advertencia(s) ativa(s)")
        await interaction.followup.send(embed=embed, ephemeral=True)

    @mod.command(name="remover-advertencia", description="Remove uma advertencia ativa de um membro.")
    @app_commands.describe(
        usuario="Membro que possui a advertencia.",
        id="ID exibido ao criar ou consultar a advertencia.",
        motivo="Motivo da remocao.",
    )
    @app_commands.guild_only()
    async def mod_remover_advertencia(
        self,
        interaction: discord.Interaction,
        usuario: discord.Member,
        id: app_commands.Range[str, 6, 40],
        motivo: app_commands.Range[str, 3, 500] = "Removida manualmente",
    ):
        await interaction.response.defer(ephemeral=True, thinking=True)
        config = await buscar_moderacao(str(interaction.guild.id))

        if not self.usuario_pode_advertir(interaction.guild, interaction.user, config):
            await interaction.followup.send(
                "Apenas administradores ou cargos de moderacao configurados no painel podem remover advertencias.",
                ephemeral=True,
            )
            return

        registro = await remover_advertencia(
            interaction.guild.id,
            usuario.id,
            str(id),
            interaction.user,
            str(motivo).strip(),
        )
        if not registro:
            await interaction.followup.send(
                "Advertencia ativa nao encontrada para esse membro. Confira o ID com `/mod advertencias`.",
                ephemeral=True,
            )
            return

        total = await contar_advertencias_ativas(interaction.guild.id, usuario.id)
        dm_enviada = await self.enviar_dm_advertencia(
            interaction.guild,
            usuario,
            interaction.user,
            registro,
            total,
            removida=True,
        )
        await self.enviar_log_advertencia(
            interaction.guild,
            config,
            usuario,
            interaction.user,
            registro,
            total,
            removida=True,
        )
        await interaction.followup.send(
            f"Advertencia `{registro['id']}` removida de {usuario.mention}. "
            f"Total ativo: `{total}`. DM: `{'enviada' if dm_enviada else 'bloqueada'}`.",
            ephemeral=True,
        )


async def setup(bot):
    await bot.add_cog(CleanupCog(bot))
