import discord
from discord import app_commands
from discord.ext import commands, tasks

from services.cleanup_service import INTERVALO_LIMPEZA_MINUTOS, executar_limpezas


MAX_LIMPAR_MENSAGENS = 1000


class CleanupCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

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

    async def limpar_canal(self, interaction, quantidade):
        channel = interaction.channel
        guild = interaction.guild

        if not guild or not channel or not hasattr(channel, "purge"):
            return None, "Use este comando dentro de um canal de texto do servidor."

        if not guild.me:
            return None, "Nao consegui identificar o cargo do bot neste servidor."

        user_permissions = channel.permissions_for(interaction.user)
        bot_permissions = channel.permissions_for(guild.me)

        if not user_permissions.manage_messages:
            return None, "Voce precisa da permissao `Gerenciar mensagens` para usar `/limpar`."

        if not bot_permissions.manage_messages or not bot_permissions.read_message_history:
            return None, "O bot precisa de `Gerenciar mensagens` e `Ler historico de mensagens` neste canal."

        limite = min(max(int(quantidade), 1), MAX_LIMPAR_MENSAGENS)

        try:
            apagadas = await channel.purge(
                limit=limite,
                check=lambda mensagem: not mensagem.pinned,
                bulk=True,
                reason=f"Limpeza manual AMZ solicitada por {interaction.user} ({interaction.user.id})",
            )
        except discord.Forbidden:
            return None, "O Discord negou a limpeza. Confira a hierarquia/permissoes do cargo do bot."
        except discord.HTTPException as erro:
            return None, f"O Discord recusou a limpeza: `{erro}`"

        return len(apagadas), None

    @app_commands.command(name="limpar", description="Apaga mensagens recentes do canal atual.")
    @app_commands.describe(quantidade="Quantidade de mensagens para apagar. Maximo: 1000.")
    @app_commands.default_permissions(manage_messages=True)
    @app_commands.guild_only()
    async def slash_limpar(self, interaction: discord.Interaction, quantidade: app_commands.Range[int, 1, 1000]):
        await interaction.response.defer(ephemeral=True, thinking=True)
        apagadas, erro = await self.limpar_canal(interaction, quantidade)

        if erro:
            await interaction.followup.send(erro, ephemeral=True)
            return

        embed = discord.Embed(
            title="Limpeza concluida",
            description="Mensagens fixadas foram preservadas.",
            color=discord.Color.from_rgb(255, 255, 255),
        )
        embed.add_field(name="Canal", value=getattr(interaction.channel, "mention", "Canal atual"), inline=True)
        embed.add_field(name="Solicitadas", value=str(quantidade), inline=True)
        embed.add_field(name="Apagadas", value=str(apagadas), inline=True)
        embed.set_footer(text=f"Limite do comando: {MAX_LIMPAR_MENSAGENS} mensagens")

        await interaction.followup.send(embed=embed, ephemeral=True)


async def setup(bot):
    await bot.add_cog(CleanupCog(bot))
