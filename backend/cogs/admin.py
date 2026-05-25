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
            return

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
            return

        await interaction.followup.send(f"Deploy solicitado com sucesso. Status HTTP: `{status_code}`")


async def setup(bot):
    await bot.add_cog(AdminCog(bot))
