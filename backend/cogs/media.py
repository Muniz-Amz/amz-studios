import asyncio
import os
import tempfile
from pathlib import Path

import discord
from discord import app_commands
from discord.ext import commands

from services.media_service import MediaError, MediaLimits, MediaService, nome_seguro, tipo_anexo, validar_tamanho_entrada
from services.url_video_service import UrlVideoError, UrlVideoService


def formatar_limite_segundos(segundos):
    segundos = int(segundos or 0)

    if segundos >= 60 and segundos % 60 == 0:
        minutos = segundos // 60
        return f"{minutos} minuto" if minutos == 1 else f"{minutos} minutos"

    return f"{segundos}s"


class MediaCog(commands.Cog):
    midia = app_commands.Group(name="midia", description="Conversao e download de midia do AMZ Bot.")

    def __init__(self, bot):
        self.bot = bot
        self.limits = MediaLimits()
        self.service = MediaService(self.limits)
        self.url_video_service = UrlVideoService()
        self.semaphore = asyncio.Semaphore(int(os.getenv("AMZ_MEDIA_CONCURRENCY", "1")))

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
            legenda = f"Audio extraido com limite de {formatar_limite_segundos(self.limits.max_audio_seconds)}."

        return legenda, output_path

    async def processar_slash(self, interaction, attachment, modo):
        await interaction.response.defer(thinking=True)

        try:
            async with self.semaphore:
                with tempfile.TemporaryDirectory() as temp_dir:
                    legenda, output_path = await self.converter_anexo(attachment, modo, temp_dir)
                    await interaction.followup.send(
                        f"Pronto: {legenda}",
                        file=discord.File(output_path, filename=output_path.name),
                    )
        except MediaError as erro:
            await interaction.followup.send(f"Nao deu para processar: {erro}", ephemeral=True)
        except Exception as erro:
            print(f"[MIDIA] Erro inesperado em slash command: {erro}")
            self.bot.registrar_evento("media_slash_error", f"Erro ao processar midia: {erro}", nivel="error", guild_id=interaction.guild_id)
            await interaction.followup.send(
                "Nao consegui processar esse arquivo. Tente um arquivo menor ou outro formato.",
                ephemeral=True,
            )

    @midia.command(name="gifimagem", description="Transforma uma imagem enviada em GIF.")
    @app_commands.describe(arquivo="Imagem que sera transformada em GIF.")
    async def midia_gifimagem(self, interaction: discord.Interaction, arquivo: discord.Attachment):
        await self.processar_slash(interaction, arquivo, "image_gif")

    @midia.command(name="gifvideo", description="Transforma um video enviado em GIF.")
    @app_commands.describe(arquivo="Video que sera transformado em GIF.")
    async def midia_gifvideo(self, interaction: discord.Interaction, arquivo: discord.Attachment):
        await self.processar_slash(interaction, arquivo, "video_gif")

    @midia.command(name="audio", description="Extrai o audio de um video enviado.")
    @app_commands.describe(arquivo="Video de onde o audio sera extraido.")
    async def midia_audio(self, interaction: discord.Interaction, arquivo: discord.Attachment):
        await self.processar_slash(interaction, arquivo, "audio")

    @midia.command(name="limites", description="Mostra os limites dos comandos de midia.")
    async def midia_limites_grupo(self, interaction: discord.Interaction):
        await interaction.response.send_message(
            "Limites de midia:\n"
            f"- Entrada: {self.limits.max_input_mb} MB\n"
            f"- Saida: {self.limits.max_output_mb} MB\n"
            f"- Video para GIF: {self.limits.max_video_seconds}s, {self.limits.gif_fps} FPS, largura {self.limits.max_width}px\n"
            f"- Video para audio: {formatar_limite_segundos(self.limits.max_audio_seconds)}\n"
            f"- Conversoes simultaneas: 1 por padrao",
            ephemeral=True,
        )

    @midia.command(name="baixar", description="Baixa um video por link e envia no chat.")
    @app_commands.describe(url="Link do video (Instagram Reels, TikTok, YouTube Shorts, etc.)")
    async def midia_baixar(self, interaction: discord.Interaction, url: str):
        await self.processar_download_url(interaction, url)

    async def processar_download_url(self, interaction: discord.Interaction, url: str):
        await interaction.response.defer(thinking=True)

        limite_bytes = self.url_video_service.limits.max_output_bytes
        if interaction.guild and getattr(interaction.guild, "filesize_limit", None):
            limite_bytes = min(limite_bytes, interaction.guild.filesize_limit)

        try:
            async with self.semaphore:
                with tempfile.TemporaryDirectory() as temp_dir:
                    output_path = await asyncio.to_thread(self.url_video_service.download_video, url, temp_dir, limite_bytes)
                    await interaction.followup.send(
                        "Video pronto. Se ele abrir parado no Discord, baixe o arquivo e teste no player do celular.",
                        file=discord.File(output_path, filename=output_path.name),
                    )
        except UrlVideoError as erro:
            await interaction.followup.send(f"Nao deu para baixar esse link: {erro}", ephemeral=True)
        except Exception as erro:
            print(f"[MIDIA] Erro inesperado em /baixarvideo: {erro}")
            self.bot.registrar_evento("video_download_error", f"Erro ao baixar video por link: {erro}", nivel="error", guild_id=interaction.guild_id)
            await interaction.followup.send(
                "Nao consegui baixar esse video agora. Tente novamente em alguns segundos.",
                ephemeral=True,
            )


async def setup(bot):
    await bot.add_cog(MediaCog(bot))
