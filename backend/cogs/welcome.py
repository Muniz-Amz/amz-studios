from discord.ext import commands

from services.welcome_service import enviar_aviso_membro


class WelcomeCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    @commands.Cog.listener()
    async def on_member_join(self, member):
        await enviar_aviso_membro(member, "entrada")

    @commands.Cog.listener()
    async def on_member_remove(self, member):
        await enviar_aviso_membro(member, "saida")


async def setup(bot):
    await bot.add_cog(WelcomeCog(bot))
