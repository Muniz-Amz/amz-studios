import asyncio
import math
import time
from collections import defaultdict

import discord
from discord import app_commands
from discord.ext import commands, tasks

from security.discord_permissions import usuario_e_admin_ou_dono
from services.cleanup_service import INTERVALO_LIMPEZA_MINUTOS, executar_limpezas


MAX_LIMPAR_MENSAGENS = 250
COOLDOWN_LIMPEZA_SEGUNDOS = 20
RATE_LIMIT_PADRAO_SEGUNDOS = 30


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


async def setup(bot):
    await bot.add_cog(CleanupCog(bot))
