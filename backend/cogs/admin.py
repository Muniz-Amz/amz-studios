import asyncio

import requests
from discord.ext import commands

from services.deploy_service import deploy_configurado, disparar_deploy_render, usuario_pode_deploy


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


async def setup(bot):
    await bot.add_cog(AdminCog(bot))
