from discord.ext import commands, tasks

from database import buscar_limpezas
from services.cleanup_service import INTERVALO_LIMPEZA_MINUTOS, executar_limpezas, normalizar_dias


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

    @commands.command()
    async def info(self, ctx):
        limpezas = await buscar_limpezas(str(ctx.guild.id))

        if not limpezas:
            await ctx.send("Esse servidor ainda nao tem limpeza configurada no painel web.")
            return

        linhas = [
            f"#{limpeza.get('canal_nome', limpeza.get('canal_id'))}: {normalizar_dias(limpeza.get('dias'))} dias"
            for limpeza in limpezas
        ]
        await ctx.send("Limpezas configuradas:\n" + "\n".join(linhas))


async def setup(bot):
    await bot.add_cog(CleanupCog(bot))
