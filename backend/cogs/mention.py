import os

import discord
from discord.ext import commands


DASHBOARD_URL = os.getenv("AMZ_SITE_URL", "https://muniz-amz.github.io/amz-studios/#dashboard")


class MentionCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    @commands.Cog.listener()
    async def on_message(self, message):
        if message.author.bot or not self.bot.user:
            return

        if self.bot.user not in message.mentions:
            return

        embed = discord.Embed(
            title="AMZ Bot Dashboard",
            description=(
                "Acesse o painel para configurar limpeza, avisos de entrada/saida, "
                "moderacao e outras ferramentas do servidor."
            ),
            color=discord.Color.from_rgb(255, 255, 255),
            url=DASHBOARD_URL,
        )
        embed.add_field(name="Painel", value=f"[Abrir dashboard]({DASHBOARD_URL})", inline=False)
        embed.set_footer(text="AMZ Studios")

        if self.bot.user.display_avatar:
            embed.set_thumbnail(url=str(self.bot.user.display_avatar.url))

        await message.reply(embed=embed, mention_author=False)


async def setup(bot):
    await bot.add_cog(MentionCog(bot))
