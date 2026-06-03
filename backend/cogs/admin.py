import asyncio

import discord
import requests
from discord import app_commands
from discord.ext import commands

from services.deploy_service import (
    deploy_configurado,
    disparar_deploy_render,
    usuario_pode_deploy,
    usuario_pode_deploy_interaction,
)


class AdminCog(commands.Cog):
    admin = app_commands.Group(name="admin", description="Comandos administrativos do AMZ Bot.")

    def __init__(self, bot):
        self.bot = bot

    @commands.command()
    async def deploy(self, ctx):
        if not usuario_pode_deploy(ctx):
            await ctx.send("Apenas o dono do servidor ou usuarios autorizados podem usar `!deploy`.")
            return

        if not deploy_configurado():
            await ctx.send("Deploy Hook nao configurado. Adicione `RENDER_DEPLOY_HOOK_URL` nas variaveis do Render.")
            return

        await ctx.send("Iniciando deploy no Render...")

        try:
            status_code = await asyncio.to_thread(disparar_deploy_render)
        except requests.RequestException as erro:
            await ctx.send(f"Nao consegui iniciar o deploy: `{erro}`")
            self.bot.registrar_evento("deploy_error", f"Falha ao solicitar deploy: {erro}", nivel="error", guild_id=getattr(ctx.guild, "id", None))
            return

        self.bot.registrar_evento("deploy_requested", f"Deploy solicitado por {ctx.author}.", guild_id=getattr(ctx.guild, "id", None), status_code=status_code)
        await ctx.send(f"Deploy solicitado com sucesso. Status HTTP: `{status_code}`")

    @app_commands.command(name="deploy", description="Solicita um redeploy no Render.")
    async def slash_deploy(self, interaction: discord.Interaction):
        if not usuario_pode_deploy_interaction(interaction):
            await interaction.response.send_message(
                "Apenas o dono do servidor ou usuarios autorizados podem usar `/deploy`.",
                ephemeral=True,
            )
            return

        if not deploy_configurado():
            await interaction.response.send_message(
                "Deploy Hook nao configurado. Adicione `RENDER_DEPLOY_HOOK_URL` nas variaveis do Render.",
                ephemeral=True,
            )
            return

        await interaction.response.defer(thinking=True)

        try:
            status_code = await asyncio.to_thread(disparar_deploy_render)
        except requests.RequestException as erro:
            await interaction.followup.send(f"Nao consegui iniciar o deploy: `{erro}`", ephemeral=True)
            self.bot.registrar_evento("deploy_error", f"Falha ao solicitar deploy: {erro}", nivel="error", guild_id=interaction.guild_id)
            return

        self.bot.registrar_evento("deploy_requested", f"Deploy solicitado por {interaction.user}.", guild_id=interaction.guild_id, status_code=status_code)
        await interaction.followup.send(f"Deploy solicitado com sucesso. Status HTTP: `{status_code}`")

    @admin.command(name="deploy", description="Solicita redeploy no Render.")
    @app_commands.default_permissions(administrator=True)
    @app_commands.guild_only()
    async def admin_deploy(self, interaction: discord.Interaction):
        if not usuario_pode_deploy_interaction(interaction):
            await interaction.response.send_message(
                "Apenas o dono do servidor ou usuarios autorizados podem usar `/admin deploy`.",
                ephemeral=True,
            )
            return

        if not deploy_configurado():
            await interaction.response.send_message(
                "Deploy Hook nao configurado. Adicione `RENDER_DEPLOY_HOOK_URL` nas variaveis do Render.",
                ephemeral=True,
            )
            return

        await interaction.response.defer(thinking=True, ephemeral=True)

        try:
            status_code = await asyncio.to_thread(disparar_deploy_render)
        except requests.RequestException as erro:
            self.bot.registrar_evento("deploy_error", f"Falha ao solicitar deploy: {erro}", nivel="error", guild_id=interaction.guild_id)
            await interaction.followup.send(f"Nao consegui iniciar o deploy: `{erro}`", ephemeral=True)
            return

        self.bot.registrar_evento("deploy_requested", f"Deploy solicitado por {interaction.user}.", guild_id=interaction.guild_id, status_code=status_code)
        await interaction.followup.send(
            f"Deploy solicitado com sucesso. Status HTTP: `{status_code}`",
            ephemeral=True,
        )


async def setup(bot):
    await bot.add_cog(AdminCog(bot))
