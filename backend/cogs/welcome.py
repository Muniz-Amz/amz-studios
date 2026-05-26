import asyncio
from datetime import datetime, timezone

import discord
from discord import app_commands
from discord.ext import commands

from security.discord_permissions import usuario_e_admin_ou_dono
from services.welcome_service import enviar_aviso_membro


class WelcomeCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot

    async def detectar_contexto_saida(self, member):
        contexto_padrao = {
            "audit_action": "Saida voluntaria",
            "leave_action": "saiu do servidor",
            "leave_reason": "Sem motivo registrado",
            "moderator": "Sistema",
            "moderator_tag": "Sistema",
        }
        guild = member.guild

        if not guild or not guild.me:
            return contexto_padrao

        if not guild.me.guild_permissions.view_audit_log:
            return {
                **contexto_padrao,
                "audit_action": "Saida nao identificada",
                "leave_reason": "Bot sem permissao Ver registro de auditoria.",
            }

        await asyncio.sleep(1.5)

        async def buscar_entrada(action):
            try:
                async for entry in guild.audit_logs(limit=8, action=action):
                    target_id = getattr(entry.target, "id", None)

                    if target_id != member.id:
                        continue

                    criada_em = entry.created_at
                    if criada_em and criada_em.tzinfo is None:
                        criada_em = criada_em.replace(tzinfo=timezone.utc)

                    if criada_em and (datetime.now(timezone.utc) - criada_em).total_seconds() > 30:
                        continue

                    return entry
            except discord.Forbidden:
                return None
            except discord.HTTPException as erro:
                print(f"[WELCOME] Erro ao consultar auditoria em {guild.id}: {erro}")
                return None

            return None

        ban = await buscar_entrada(discord.AuditLogAction.ban)
        if ban:
            return {
                "audit_action": "Banimento",
                "leave_action": "foi banido",
                "leave_reason": ban.reason or "Sem motivo registrado",
                "moderator": getattr(ban.user, "display_name", None) or str(ban.user or "Desconhecido"),
                "moderator_tag": str(ban.user or "Desconhecido"),
            }

        kick = await buscar_entrada(discord.AuditLogAction.kick)
        if kick:
            return {
                "audit_action": "Expulsao",
                "leave_action": "foi expulso",
                "leave_reason": kick.reason or "Sem motivo registrado",
                "moderator": getattr(kick.user, "display_name", None) or str(kick.user or "Desconhecido"),
                "moderator_tag": str(kick.user or "Desconhecido"),
            }

        return contexto_padrao

    @commands.Cog.listener()
    async def on_member_join(self, member):
        ok, mensagem = await enviar_aviso_membro(member, "entrada", detalhado=True)

        if not ok:
            print(f"[WELCOME] Entrada nao enviada em {member.guild.id}: {mensagem}")

    @commands.Cog.listener()
    async def on_member_remove(self, member):
        contexto = await self.detectar_contexto_saida(member)
        ok, mensagem = await enviar_aviso_membro(member, "saida", detalhado=True, contexto=contexto)

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
        contexto = None

        if tipo.value == "saida":
            contexto = {
                "audit_action": "Teste manual",
                "leave_action": "foi testado no aviso de saida",
                "leave_reason": "Comando /testaravisos",
                "moderator": interaction.user.display_name,
                "moderator_tag": str(interaction.user),
            }

        ok, mensagem = await enviar_aviso_membro(
            interaction.user,
            tipo.value,
            detalhado=True,
            contexto=contexto,
        )

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
