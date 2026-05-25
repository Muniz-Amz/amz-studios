import asyncio
import os
import tempfile
from pathlib import Path

import discord
from discord import app_commands
from discord.ext import commands

from services.media_service import MediaError, MediaLimits, MediaService, nome_seguro, tipo_anexo, validar_tamanho_entrada


class MediaCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot
        self.limits = MediaLimits()
        self.service = MediaService(self.limits)
        self.semaphore = asyncio.Semaphore(int(os.getenv("AMZ_MEDIA_CONCURRENCY", "1")))

    def obter_anexo(self, ctx):
        if ctx.message.attachments:
            return ctx.message.attachments[0]

        referencia = getattr(ctx.message.reference, "resolved", None)

        if referencia and getattr(referencia, "attachments", None):
            return referencia.attachments[0]

        return None

    async def salvar_anexo(self, attachment, temp_dir):
        validar_tamanho_entrada(attachment, self.limits)
        input_path = Path(temp_dir) / nome_seguro(attachment.filename)
        await attachment.save(input_path)
        return input_path

    async def converter_anexo(self, attachment, modo, temp_dir):
        media_type = tipo_anexo(attachment)

        if modo == "image_gif" and media_type != "image":
            raise MediaError("Envie uma imagem para usar esse comando.")

        if modo in {"video_gif", "audio"} and media_type != "video":
            raise MediaError("Envie um video para usar esse comando.")

        if modo == "auto" and media_type not in {"image", "video"}:
            raise MediaError("Envie uma imagem ou video.")

        input_path = await self.salvar_anexo(attachment, temp_dir)
        base = Path(nome_seguro(Path(attachment.filename).stem)).stem or "amz"

        if modo == "image_gif" or (modo == "auto" and media_type == "image"):
            output_path = Path(temp_dir) / f"{base}.gif"
            await asyncio.to_thread(self.service.imagem_para_gif, input_path, output_path)
            legenda = "Imagem convertida para GIF."
        elif modo == "video_gif" or (modo == "auto" and media_type == "video"):
            output_path = Path(temp_dir) / f"{base}.gif"
            await asyncio.to_thread(self.service.video_para_gif, input_path, output_path)
            legenda = f"Video convertido para GIF com limite de {self.limits.max_video_seconds}s."
        else:
            output_path = Path(temp_dir) / f"{base}.mp3"
            await asyncio.to_thread(self.service.video_para_audio, input_path, output_path)
            legenda = f"Audio extraido com limite de {self.limits.max_audio_seconds}s."

        return legenda, output_path

    async def processar(self, ctx, attachment, modo):
        media_type = tipo_anexo(attachment)

        if modo == "image_gif" and media_type != "image":
            raise MediaError("Envie ou responda uma imagem para usar esse comando.")

        if modo in {"video_gif", "audio"} and media_type != "video":
            raise MediaError("Envie ou responda um video para usar esse comando.")

        if modo == "auto" and media_type not in {"image", "video"}:
            raise MediaError("Envie ou responda uma imagem ou video.")

        status = await ctx.reply("Processando arquivo...")

        try:
            async with self.semaphore:
                with tempfile.TemporaryDirectory() as temp_dir:
                    legenda, output_path = await self.converter_anexo(attachment, modo, temp_dir)
                    await ctx.reply(legenda, file=discord.File(output_path, filename=output_path.name))
        except Exception:
            await status.edit(content="Falha ao processar o arquivo.")
            raise

        await status.edit(content="Pronto.")

    async def responder_erro(self, ctx, erro):
        if isinstance(erro, MediaError):
            await ctx.reply(str(erro))
            return

        print(f"[MIDIA] Erro inesperado: {erro}")
        await ctx.reply("Nao consegui processar esse arquivo. Tente um arquivo menor ou outro formato.")

    async def processar_slash(self, interaction, attachment, modo):
        await interaction.response.defer(thinking=True)

        try:
            async with self.semaphore:
                with tempfile.TemporaryDirectory() as temp_dir:
                    legenda, output_path = await self.converter_anexo(attachment, modo, temp_dir)
                    await interaction.followup.send(legenda, file=discord.File(output_path, filename=output_path.name))
        except MediaError as erro:
            await interaction.followup.send(str(erro), ephemeral=True)
        except Exception as erro:
            print(f"[MIDIA] Erro inesperado em slash command: {erro}")
            await interaction.followup.send(
                "Nao consegui processar esse arquivo. Tente um arquivo menor ou outro formato.",
                ephemeral=True,
            )

    @commands.command(name="gif")
    async def gif(self, ctx):
        attachment = self.obter_anexo(ctx)

        if not attachment:
            await ctx.reply("Envie uma imagem/video junto com `!gif` ou responda uma mensagem com arquivo.")
            return

        try:
            await self.processar(ctx, attachment, "auto")
        except Exception as erro:
            await self.responder_erro(ctx, erro)

    @commands.command(name="foto_gif", aliases=["fotogif", "imagemgif", "imagegif"])
    async def foto_gif(self, ctx):
        attachment = self.obter_anexo(ctx)

        if not attachment:
            await ctx.reply("Envie uma imagem junto com `!foto_gif` ou responda uma imagem com o comando.")
            return

        try:
            await self.processar(ctx, attachment, "image_gif")
        except Exception as erro:
            await self.responder_erro(ctx, erro)

    @commands.command(name="video_gif", aliases=["videogif"])
    async def video_gif(self, ctx):
        attachment = self.obter_anexo(ctx)

        if not attachment:
            await ctx.reply("Envie um video junto com `!video_gif` ou responda um video com o comando.")
            return

        try:
            await self.processar(ctx, attachment, "video_gif")
        except Exception as erro:
            await self.responder_erro(ctx, erro)

    @commands.command(name="video_audio", aliases=["audio", "mp3"])
    async def video_audio(self, ctx):
        attachment = self.obter_anexo(ctx)

        if not attachment:
            await ctx.reply("Envie um video junto com `!audio` ou responda um video com o comando.")
            return

        try:
            await self.processar(ctx, attachment, "audio")
        except Exception as erro:
            await self.responder_erro(ctx, erro)

    @commands.command(name="midia_limites", aliases=["limites_midia"])
    async def midia_limites(self, ctx):
        await ctx.reply(
            "Limites de midia:\n"
            f"- Entrada: {self.limits.max_input_mb} MB\n"
            f"- Saida: {self.limits.max_output_mb} MB\n"
            f"- Video para GIF: {self.limits.max_video_seconds}s, {self.limits.gif_fps} FPS, largura {self.limits.max_width}px\n"
            f"- Video para audio: {self.limits.max_audio_seconds}s\n"
            f"- Conversoes simultaneas: 1 por padrao"
        )

    @app_commands.command(name="gifimg", description="Transforma uma imagem enviada em GIF.")
    @app_commands.describe(arquivo="Imagem que sera transformada em GIF.")
    async def slash_gifimg(self, interaction: discord.Interaction, arquivo: discord.Attachment):
        await self.processar_slash(interaction, arquivo, "image_gif")

    @app_commands.command(name="videogif", description="Transforma um video enviado em GIF.")
    @app_commands.describe(arquivo="Video que sera transformado em GIF.")
    async def slash_videogif(self, interaction: discord.Interaction, arquivo: discord.Attachment):
        await self.processar_slash(interaction, arquivo, "video_gif")

    @app_commands.command(name="audio", description="Extrai o audio de um video enviado.")
    @app_commands.describe(arquivo="Video de onde o audio sera extraido.")
    async def slash_audio(self, interaction: discord.Interaction, arquivo: discord.Attachment):
        await self.processar_slash(interaction, arquivo, "audio")

    @app_commands.command(name="midialimites", description="Mostra os limites dos comandos de midia.")
    async def slash_midialimites(self, interaction: discord.Interaction):
        await interaction.response.send_message(
            "Limites de midia:\n"
            f"- Entrada: {self.limits.max_input_mb} MB\n"
            f"- Saida: {self.limits.max_output_mb} MB\n"
            f"- Video para GIF: {self.limits.max_video_seconds}s, {self.limits.gif_fps} FPS, largura {self.limits.max_width}px\n"
            f"- Video para audio: {self.limits.max_audio_seconds}s\n"
            f"- Conversoes simultaneas: 1 por padrao",
            ephemeral=True,
        )


async def setup(bot):
    await bot.add_cog(MediaCog(bot))
