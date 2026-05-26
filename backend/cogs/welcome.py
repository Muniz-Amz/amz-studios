import discord
from discord import app_commands
from discord.ext import commands

from security.discord_permissions import usuario_e_admin_ou_dono
from services.welcome_service import enviar_aviso_membro


class WelcomeCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    @commands.Cog.listener()
    async def on_member_join(self, member):
        ok, mensagem = await enviar_aviso_membro(member, "entrada", detalhado=True)

        if not ok:
            print(f"[WELCOME] Entrada nao enviada em {member.guild.id}: {mensagem}")

    @commands.Cog.listener()
    async def on_member_remove(self, member):
        ok, mensagem = await enviar_aviso_membro(member, "saida", detalhado=True)

        if not ok:
            print(f"[WELCOME] Saida nao enviada em {member.guild.id}: {mensagem}")

    @app_commands.command(name="testaravisos", description="Envia um teste dos avisos de entrada ou saida.")
    @app_commands.describe(tipo="Escolha qual aviso voce quer testar.")
    @app_commands.choices(
        tipo=[
            app_commands.Choice(name="Entrada", value="entrada"),
            app_commands.Choice(name="Saida", value="saida"),
        ]
    )
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def slash_testaravisos(self, interaction: discord.Interaction, tipo: app_commands.Choice[str]):
        if not usuario_e_admin_ou_dono(interaction.guild, interaction.user):
            await interaction.response.send_message(
                "Apenas o dono do servidor ou usuarios com `Administrador` podem usar `/testaravisos`.",
                ephemeral=True,
            )
            return

        await interaction.response.defer(ephemeral=True, thinking=True)
        ok, mensagem = await enviar_aviso_membro(interaction.user, tipo.value, detalhado=True)

        if ok:
            await interaction.followup.send(
                f"{mensagem}\nSe este teste chegou no canal, as permissoes do canal estao certas. "
                "Se entrada/saida real ainda nao disparar, ative `Server Members Intent` no Discord Developer Portal.",
                ephemeral=True,
            )
            return

        await interaction.followup.send(
            f"Nao consegui enviar o teste: {mensagem}",
            ephemeral=True,
        )


async def setup(bot):
    await bot.add_cog(WelcomeCog(bot))
