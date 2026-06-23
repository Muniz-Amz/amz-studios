import discord
from discord import app_commands
from discord.ext import commands

from database import buscar_moderacao
from security.discord_permissions import usuario_e_admin_ou_dono
from services.role_announcement_service import iniciar_anuncio_cargo_async


def ids_lista(valores):
    if isinstance(valores, (list, tuple, set)):
        return {str(valor).strip() for valor in valores if str(valor).strip()}

    return {
        item.strip()
        for item in str(valores or "").replace(",", "\n").splitlines()
        if item.strip()
    }


def valores_automacao(config, automacao_id):
    opcoes = config.get("automacoes", {}).get("options", [])
    for opcao in opcoes:
        if opcao.get("id") == automacao_id:
            return opcao.get("values") or {}
    return {}


def usuario_pode_usar_aviso(guild, member, config):
    if usuario_e_admin_ou_dono(guild, member):
        return True

    cargos_liberados = ids_lista(config.get("permissoes", {}).get("cargos_aviso"))
    return any(str(role.id) in cargos_liberados for role in getattr(member, "roles", []))


def montar_dados_aviso(config, cargo, mensagem, canal=None):
    dados = {
        **valores_automacao(config, "roleAnnouncement"),
        "roleId": str(cargo.id),
        "roleIdName": cargo.name,
        "message": mensagem.strip(),
        "imageUrl": "",
    }

    if canal:
        dados["channelId"] = str(canal.id)
        dados["channelIdName"] = canal.name
        dados["sendChannel"] = True

    return dados


class AnnouncementCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    @app_commands.command(name="aviso", description="Envia aviso por cargo usando as configuracoes do painel.")
    @app_commands.describe(
        cargo="Cargo que vai receber o aviso.",
        mensagem="Mensagem enviada no privado e/ou canal configurado.",
        canal="Canal opcional para publicar o aviso tambem.",
    )
    @app_commands.guild_only()
    async def aviso(
        self,
        interaction: discord.Interaction,
        cargo: discord.Role,
        mensagem: app_commands.Range[str, 1, 1800],
        canal: discord.TextChannel = None,
    ):
        if not interaction.guild or not isinstance(interaction.user, discord.Member):
            await interaction.response.send_message("Use esse comando dentro de um servidor.", ephemeral=True)
            return

        await interaction.response.defer(ephemeral=True, thinking=True)

        config = await buscar_moderacao(str(interaction.guild.id))
        if not usuario_pode_usar_aviso(interaction.guild, interaction.user, config):
            await interaction.followup.send(
                "Apenas administradores ou cargos liberados no painel podem usar `/aviso`.",
                ephemeral=True,
            )
            return

        dados = montar_dados_aviso(config, cargo, str(mensagem), canal)
        resultado, erro = await iniciar_anuncio_cargo_async(str(interaction.guild.id), dados)
        if erro:
            await interaction.followup.send(f"Nao consegui iniciar o aviso: {erro}", ephemeral=True)
            return

        destinos = []
        if resultado.get("sendChannel") and resultado.get("channelName"):
            destinos.append(f"canal #{resultado['channelName']}")
        if resultado.get("sendDm"):
            destinos.append("privado dos membros")

        await interaction.followup.send(
            "Aviso iniciado para "
            f"`@{resultado.get('roleName') or cargo.name}`. "
            f"Destino: {', '.join(destinos) or 'configuracao do painel'}. "
            f"Lote: `{resultado.get('batchSize')}` com pausa de `{resultado.get('batchPauseSeconds')}s`.",
            ephemeral=True,
        )


async def setup(bot):
    await bot.add_cog(AnnouncementCog(bot))
