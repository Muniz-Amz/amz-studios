import asyncio
import ipaddress
import re
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

import discord
from discord.ext import commands

from database import buscar_moderacao, registrar_historico_auditoria


URL_RE = re.compile(r"https?://|www\.", re.IGNORECASE)
URL_EXTRACT_RE = re.compile(r"(https?://[^\s<>()]+|www\.[^\s<>()]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>()]*)?)", re.IGNORECASE)
INVITE_RE = re.compile(r"(discord\.gg/|discord(?:app)?\.com/invite/)", re.IGNORECASE)
SPAM_WINDOW_SECONDS = 8
SPAM_LIMIT = 5
SUSPICIOUS_SHORTENER_DOMAINS = {
    "bit.ly",
    "bitly.com",
    "tinyurl.com",
    "t.co",
    "is.gd",
    "cutt.ly",
    "ow.ly",
    "rebrand.ly",
    "shorturl.at",
    "buff.ly",
    "adf.ly",
    "goo.gl",
}
TRUSTED_LINK_DOMAINS = {
    "discord.com",
    "discord.gg",
    "discordapp.com",
    "github.com",
    "youtube.com",
    "youtu.be",
    "twitch.tv",
    "x.com",
    "twitter.com",
}
SUSPICIOUS_LINK_KEYWORDS = re.compile(
    r"(free[-_ ]?nitro|nitro[-_ ]?free|steam[-_ ]?gift|airdrop|claim[-_ ]?reward|verify[-_ ]?account|"
    r"login[-_ ]?verify|wallet[-_ ]?connect|metamask|robux|gift[-_ ]?card|giveaway|scam|phishing|"
    r"presente|premio|brinde)",
    re.IGNORECASE,
)
LOG_EVENT_CHANNEL_KEYS = {
    "mensagens_deletadas": "canal_mensagens_deletadas_id",
    "mensagens_editadas": "canal_mensagens_editadas_id",
    "banimentos": "canal_banimentos_id",
    "desbanimentos": "canal_desbanimentos_id",
    "expulsoes": "canal_expulsoes_id",
    "castigos": "canal_castigos_id",
    "canais": "canal_canais_id",
    "cargos": "canal_cargos_id",
}


def texto_curto(valor, limite=900):
    texto = str(valor or "").strip()
    if len(texto) <= limite:
        return texto or "--"
    return f"{texto[:limite - 3]}..."


def ids_lista(valores):
    return {str(valor).strip() for valor in valores or [] if str(valor).strip()}


def contem_palavra_bloqueada(conteudo, palavras):
    texto = str(conteudo or "").lower()
    for palavra in palavras or []:
        item = str(palavra or "").strip().lower()
        if item and item in texto:
            return item
    return None


def normalizar_dominio(valor):
    dominio = str(valor or "").strip().lower()
    dominio = re.sub(r"^https?://", "", dominio)
    dominio = dominio.split("/")[0].split(":")[0].strip(".")

    try:
        return dominio.encode("idna").decode("ascii")
    except UnicodeError:
        return dominio


def dominio_em_lista(dominio, dominios):
    alvo = normalizar_dominio(dominio)
    if not alvo:
        return False

    for item in dominios or []:
        permitido = normalizar_dominio(item)
        if permitido and (alvo == permitido or alvo.endswith(f".{permitido}")):
            return True

    return False


def extrair_links(conteudo):
    links = []
    for match in URL_EXTRACT_RE.finditer(str(conteudo or "")):
        bruto = match.group(0).strip(".,;:!?)]}>\"'")
        if bruto:
            links.append(bruto)
    return links


def caps_percentual(conteudo):
    letras = [char for char in str(conteudo or "") if char.isalpha()]
    if not letras:
        return 0
    maiusculas = sum(1 for char in letras if char.isupper())
    return round((maiusculas / len(letras)) * 100)


class ModerationCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot
        self.spam_cache = defaultdict(lambda: deque(maxlen=12))
        self.auto_response_cooldowns = {}
        self._interaction_check_original = None

    async def cog_load(self):
        self.bot.add_check(self.comando_prefixo_permitido)
        self._interaction_check_original = self.bot.tree.interaction_check

        async def verificar_interacao(interaction):
            if self._interaction_check_original:
                permitido = await self._interaction_check_original(interaction)
                if not permitido:
                    return False

            return await self.comando_slash_permitido(interaction)

        self.bot.tree.interaction_check = verificar_interacao

    async def cog_unload(self):
        self.bot.remove_check(self.comando_prefixo_permitido)
        if self._interaction_check_original:
            self.bot.tree.interaction_check = self._interaction_check_original

    async def obter_config(self, guild):
        if not guild:
            return {}
        return await buscar_moderacao(str(guild.id))

    def usuario_imune(self, member, config):
        if not isinstance(member, discord.Member):
            return True

        if member.guild_permissions.administrator:
            return True

        blacklist = config.get("blacklist", {})
        cargos_imunes = ids_lista(blacklist.get("cargos_imunes"))
        return any(str(role.id) in cargos_imunes for role in member.roles)

    def usuario_staff(self, member, config):
        if not isinstance(member, discord.Member):
            return False

        permissoes = member.guild_permissions
        if permissoes.administrator or permissoes.manage_guild or permissoes.manage_messages:
            return True

        config_permissoes = config.get("permissoes", {})
        cargos_staff = ids_lista(config_permissoes.get("cargos_admin")) | ids_lista(config_permissoes.get("cargos_moderador"))
        return any(str(role.id) in cargos_staff for role in member.roles)

    def automacao_ativa(self, config, automacao_id):
        opcoes = config.get("automacoes", {}).get("options", [])
        return any(opcao.get("id") == automacao_id and opcao.get("enabled") for opcao in opcoes)

    def valores_automacao(self, config, automacao_id):
        opcoes = config.get("automacoes", {}).get("options", [])
        for opcao in opcoes:
            if opcao.get("id") == automacao_id:
                return opcao.get("values") or {}
        return {}

    def setting_seguranca(self, config, setting_id):
        settings = config.get("seguranca", {}).get("antiRaid", {}).get("settings", [])
        for setting in settings:
            if setting.get("id") == setting_id:
                return setting
        return {}

    def valores_seguranca(self, config, setting_id):
        return self.setting_seguranca(config, setting_id).get("values") or {}

    def seguranca_ativa(self, config, setting_id):
        setting = self.setting_seguranca(config, setting_id)
        return bool(setting and setting.get("enabled"))

    def comando_bloqueado(self, config, channel_id, command_name):
        if not self.automacao_ativa(config, "commandChannelBlock"):
            return False

        nome_comando = str(command_name or "").strip().lower().lstrip("/!")
        if not nome_comando:
            return False

        for regra in config.get("automacoes", {}).get("commandBlockRules", []):
            if not regra.get("enabled"):
                continue

            canais = ids_lista(regra.get("channelIds"))
            if str(channel_id) not in canais:
                continue

            comandos = {
                str(comando or "").strip().lower().lstrip("/!")
                for comando in regra.get("commands", [])
                if str(comando or "").strip()
            }

            if not comandos or nome_comando in comandos:
                return True

        return False

    async def comando_prefixo_permitido(self, ctx):
        if not ctx.guild or not ctx.command:
            return True

        config = await self.obter_config(ctx.guild)
        if self.usuario_staff(ctx.author, config):
            return True

        if not self.comando_bloqueado(config, ctx.channel.id, ctx.command.qualified_name):
            return True

        try:
            await ctx.reply("Este comando esta bloqueado neste canal.", mention_author=False, delete_after=8)
        except discord.HTTPException:
            pass

        return False

    async def comando_slash_permitido(self, interaction):
        if not interaction.guild or not interaction.command:
            return True

        config = await self.obter_config(interaction.guild)
        if self.usuario_staff(interaction.user, config):
            return True

        command_name = getattr(interaction.command, "qualified_name", None) or getattr(interaction.command, "name", None)
        if not self.comando_bloqueado(config, interaction.channel_id, command_name):
            return True

        try:
            if not interaction.response.is_done():
                await interaction.response.send_message("Este comando esta bloqueado neste canal.", ephemeral=True)
        except discord.HTTPException:
            pass

        return False

    def auditoria_ativa_ou_log_legado(self, config, chave_legada):
        if config.get("auditoria", {}).get("enabled"):
            return True
        return config.get("logs", {}).get(chave_legada)

    def regra_auto_resposta_corresponde(self, conteudo, regra):
        palavra = str(regra.get("keyword") or "").strip().lower()
        texto = str(conteudo or "").lower()

        if not palavra:
            return False

        tipo = regra.get("detectionType") or "contains"
        if tipo == "exact":
            return texto.strip() == palavra
        if tipo == "startsWith":
            return texto.strip().startswith(palavra)
        if tipo == "endsWith":
            return texto.strip().endswith(palavra)
        return palavra in texto

    async def aplicar_auto_respostas(self, message, config):
        automacoes = config.get("automacoes", {})
        if not self.automacao_ativa(config, "autoResponse"):
            return

        for regra in automacoes.get("autoResponses", []):
            if not regra.get("enabled"):
                continue

            canal_id = str(regra.get("channelId") or "")
            if canal_id and canal_id != str(message.channel.id):
                continue

            if regra.get("ignoreStaff") and self.usuario_staff(message.author, config):
                continue

            if not self.regra_auto_resposta_corresponde(message.content, regra):
                continue

            cooldown = max(0, int(regra.get("cooldownSeconds") or 0))
            chave = (message.guild.id, message.channel.id, regra.get("id"))
            agora = datetime.now(timezone.utc).timestamp()
            ultimo = self.auto_response_cooldowns.get(chave, 0)

            if cooldown and agora - ultimo < cooldown:
                continue

            self.auto_response_cooldowns[chave] = agora
            delete_after = int(regra.get("deleteAfterSeconds") or 0) or None

            try:
                await message.channel.send(
                    regra.get("response", ""),
                    delete_after=delete_after,
                    allowed_mentions=discord.AllowedMentions.none(),
                )
            except discord.HTTPException as erro:
                print(f"[AUTO-RESPOSTA] Falha ao responder em {message.guild.id}: {erro}")

    async def resolver_canal_texto(self, guild, canal_id):
        try:
            canal = guild.get_channel(int(canal_id)) if canal_id else None
        except (TypeError, ValueError):
            canal = None

        if canal is None and canal_id:
            try:
                canal = await guild.fetch_channel(int(canal_id))
            except (discord.Forbidden, discord.HTTPException, discord.NotFound, TypeError, ValueError):
                return None

        if not isinstance(canal, discord.TextChannel) or not guild.me:
            return None

        permissoes = canal.permissions_for(guild.me)
        if not permissoes.view_channel or not permissoes.send_messages:
            return None

        return canal

    async def canal_log(self, guild, config, tipo, log_key=None):
        logs = config.get("logs", {})
        campo_evento = LOG_EVENT_CHANNEL_KEYS.get(log_key or "")
        canal_id = logs.get(campo_evento) if campo_evento else None

        if not canal_id:
            canal_id = logs.get({
                "mensagens": "canal_mensagens_id",
                "moderacao": "canal_moderacao_id",
                "servidor": "canal_servidor_id",
            }.get(tipo, "canal_moderacao_id"))

        if not canal_id:
            canal_id = logs.get("canal_moderacao_id") or logs.get("canal_mensagens_id") or logs.get("canal_servidor_id")

        return await self.resolver_canal_texto(guild, canal_id)

    async def canal_seguranca(self, guild, config):
        if not self.seguranca_ativa(config, "securityLogChannel"):
            return None

        canal_id = self.valores_seguranca(config, "securityLogChannel").get("channelId")
        return await self.resolver_canal_texto(guild, canal_id)

    def evento_auditoria(self, config, event_id):
        auditoria = config.get("auditoria", {})
        for evento in auditoria.get("events", []):
            if evento.get("id") == event_id:
                return evento
        return None

    async def canal_auditoria(self, guild, config, event_id):
        auditoria = config.get("auditoria", {})
        evento = self.evento_auditoria(config, event_id)

        if not auditoria.get("enabled") or not evento or not evento.get("enabled"):
            return None

        canal_id = evento.get("channelId") or auditoria.get("defaultChannelId")
        return await self.resolver_canal_texto(guild, canal_id)

    def data_hora_log(self):
        return datetime.now(timezone.utc).strftime("%d/%m/%Y %H:%M UTC")

    async def registrar_historico(self, guild, event_id, titulo, canal, responsavel, status):
        if not event_id:
            return

        try:
            await registrar_historico_auditoria(str(guild.id), {
                "eventType": titulo,
                "channelName": f"#{canal.name}" if canal else "Canal nao configurado",
                "responsibleUser": str(responsavel or "Sistema"),
                "dateTime": self.data_hora_log(),
                "status": status,
            })
        except Exception as erro:
            print(f"[AUDITORIA] Falha ao registrar historico em {guild.id}: {erro}")

    async def enviar_log(self, guild, config, tipo, titulo, descricao, color=None, fields=None, event_id=None, responsavel=None, log_key=None):
        logs = config.get("logs", {})
        usar_auditoria = bool(event_id and config.get("auditoria", {}).get("enabled"))

        if usar_auditoria:
            canal = await self.canal_auditoria(guild, config, event_id)
        else:
            if tipo == "seguranca":
                canal = await self.canal_seguranca(guild, config)
                if not canal and logs.get("ativo"):
                    canal = await self.canal_log(guild, config, "moderacao", log_key)
                if not canal:
                    return
            elif tipo == "auditoria" or not logs.get("ativo"):
                return
            else:
                canal = await self.canal_log(guild, config, tipo, log_key)

        if not canal:
            if usar_auditoria:
                await self.registrar_historico(guild, event_id, titulo, None, responsavel, "falhou")
            return

        embed = discord.Embed(
            title=titulo,
            description=texto_curto(descricao, 3500),
            color=color or discord.Color.from_rgb(160, 160, 160),
            timestamp=datetime.now(timezone.utc),
        )

        for nome, valor, inline in fields or []:
            embed.add_field(name=nome, value=texto_curto(valor, 1024), inline=inline)

        embed.set_footer(text=f"AMZ Moderacao | {guild.name}")

        try:
            await canal.send(embed=embed)
            if usar_auditoria:
                await self.registrar_historico(guild, event_id, titulo, canal, responsavel, "enviado")
        except discord.HTTPException as erro:
            print(f"[MOD] Falha ao enviar log em {guild.id}: {erro}")
            if usar_auditoria:
                await self.registrar_historico(guild, event_id, titulo, canal, responsavel, "falhou")

    async def buscar_auditoria_membro(self, guild, member_id, action):
        if not guild.me or not guild.me.guild_permissions.view_audit_log:
            return None

        try:
            async for entry in guild.audit_logs(limit=8, action=action):
                target_id = getattr(entry.target, "id", None)
                if target_id != member_id:
                    continue

                criada_em = entry.created_at
                if criada_em and criada_em.tzinfo is None:
                    criada_em = criada_em.replace(tzinfo=timezone.utc)

                if criada_em and (datetime.now(timezone.utc) - criada_em).total_seconds() <= 30:
                    return entry
        except (discord.Forbidden, discord.HTTPException):
            return None

        return None

    async def buscar_auditoria_recente(self, guild, member_id, actions):
        if not guild.me or not guild.me.guild_permissions.view_audit_log:
            return None

        for action in [acao for acao in actions if acao]:
            try:
                async for entry in guild.audit_logs(limit=8, action=action):
                    criada_em = entry.created_at
                    if criada_em and criada_em.tzinfo is None:
                        criada_em = criada_em.replace(tzinfo=timezone.utc)

                    if criada_em and (datetime.now(timezone.utc) - criada_em).total_seconds() > 30:
                        continue

                    target_id = getattr(entry.target, "id", None)
                    extra_user = getattr(getattr(entry, "extra", None), "user", None)
                    extra_user_id = getattr(extra_user, "id", None)

                    if target_id in (None, member_id) or extra_user_id == member_id:
                        return entry
            except (discord.Forbidden, discord.HTTPException):
                continue

        return None

    def link_suspeito(self, link, config):
        valores = self.valores_seguranca(config, "suspiciousLinks")
        permitidos = valores.get("whitelistDomains") or []
        bloqueados = valores.get("blacklistDomains") or []
        auto_detection = valores.get("autoDetection", True)
        url = link if re.match(r"^https?://", link, re.IGNORECASE) else f"https://{link}"

        try:
            parsed = urlparse(url)
        except ValueError:
            return "link com formato invalido"

        dominio = normalizar_dominio(parsed.hostname or "")
        if not dominio:
            return None

        if dominio_em_lista(dominio, permitidos):
            return None

        if dominio_em_lista(dominio, bloqueados):
            return f"dominio bloqueado: {dominio}"

        if not auto_detection:
            return None

        if parsed.username or parsed.password:
            return "link tenta esconder o destino com usuario/senha"

        if dominio in SUSPICIOUS_SHORTENER_DOMAINS:
            return f"encurtador suspeito: {dominio}"

        try:
            ipaddress.ip_address(dominio)
            return f"link com IP direto: {dominio}"
        except ValueError:
            pass

        if dominio.startswith("xn--") or ".xn--" in dominio:
            return f"dominio internacionalizado suspeito: {dominio}"

        partes = [parte for parte in dominio.split(".") if parte]
        if len(partes) >= 5:
            return f"muitos subdominios: {dominio}"

        texto_link = f"{dominio} {parsed.path} {parsed.query}".lower()
        if dominio_em_lista(dominio, TRUSTED_LINK_DOMAINS):
            return None

        if SUSPICIOUS_LINK_KEYWORDS.search(texto_link):
            return f"padrao de phishing/scam: {dominio}"

        if "discord" in dominio and not dominio_em_lista(dominio, {"discord.com", "discord.gg", "discordapp.com"}):
            return f"dominio parecido com Discord: {dominio}"

        return None

    def detectar_link_suspeito(self, conteudo, config):
        if not self.seguranca_ativa(config, "suspiciousLinks"):
            return None

        for link in extrair_links(conteudo):
            motivo = self.link_suspeito(link, config)
            if motivo:
                return motivo

        return None

    async def punir_link_suspeito(self, message, config, motivo):
        valores = self.valores_seguranca(config, "suspiciousLinks")
        acao_configurada = str(valores.get("action") or "Apagar e alertar").strip().lower()
        acoes = []

        try:
            await message.delete()
            acoes.append("mensagem apagada")
        except (discord.Forbidden, discord.HTTPException):
            acoes.append("falha ao apagar")

        if "alertar" in acao_configurada:
            try:
                await message.channel.send(
                    f"{message.author.mention}, seu link foi bloqueado por parecer suspeito.",
                    delete_after=8,
                    allowed_mentions=discord.AllowedMentions(users=True, roles=False, everyone=False),
                )
                acoes.append("usuario alertado")
            except discord.HTTPException:
                acoes.append("falha ao alertar")

        if "silenciar" in acao_configurada and isinstance(message.author, discord.Member):
            minutos = int(config.get("automod", {}).get("castigo_minutos", 10) or 10)
            ate = datetime.now(timezone.utc) + timedelta(minutes=min(max(minutos, 1), 10080))
            try:
                if hasattr(message.author, "timeout"):
                    await message.author.timeout(ate, reason=f"AMZ Seguranca: {motivo}")
                else:
                    await message.author.edit(timed_out_until=ate, reason=f"AMZ Seguranca: {motivo}")
                acoes.append(f"silenciado {minutos}min")
            except (discord.Forbidden, discord.HTTPException):
                acoes.append("falha ao silenciar")

        if "expulsar" in acao_configurada and isinstance(message.author, discord.Member):
            try:
                await message.author.kick(reason=f"AMZ Seguranca: {motivo}")
                acoes.append("usuario expulso")
            except (discord.Forbidden, discord.HTTPException):
                acoes.append("falha ao expulsar")

        if "banir" in acao_configurada and isinstance(message.author, discord.Member):
            try:
                try:
                    await message.author.ban(reason=f"AMZ Seguranca: {motivo}", delete_message_seconds=0)
                except TypeError:
                    await message.author.ban(reason=f"AMZ Seguranca: {motivo}", delete_message_days=0)
                acoes.append("usuario banido")
            except (discord.Forbidden, discord.HTTPException):
                acoes.append("falha ao banir")

        await self.enviar_log(
            message.guild,
            config,
            "seguranca",
            "Link suspeito bloqueado",
            f"{message.author} em {message.channel.mention}",
            discord.Color.red(),
            [
                ("Motivo", motivo, False),
                ("Acao configurada", valores.get("action") or "Apagar e alertar", True),
                ("Acoes executadas", ", ".join(acoes) or "nenhuma", False),
                ("Conteudo", message.content or "--", False),
            ],
            event_id="links_suspeitos_bloqueados",
            responsavel=message.author,
        )

    async def punir_automod(self, message, config, motivo):
        automod = config.get("automod", {})
        acoes = []

        if automod.get("apagar_mensagem"):
            try:
                await message.delete()
                acoes.append("mensagem apagada")
            except (discord.Forbidden, discord.HTTPException):
                acoes.append("falha ao apagar")

        if automod.get("castigar_usuario") and isinstance(message.author, discord.Member):
            minutos = int(automod.get("castigo_minutos", 10))
            ate = datetime.now(timezone.utc) + timedelta(minutes=min(max(minutos, 1), 10080))
            try:
                if hasattr(message.author, "timeout"):
                    await message.author.timeout(ate, reason=f"AMZ AutoMod: {motivo}")
                else:
                    await message.author.edit(timed_out_until=ate, reason=f"AMZ AutoMod: {motivo}")
                acoes.append(f"castigo {minutos}min")
            except (discord.Forbidden, discord.HTTPException):
                acoes.append("falha ao castigar")

        if automod.get("avisar_usuario"):
            try:
                await message.channel.send(
                    f"{message.author.mention}, sua mensagem foi bloqueada: {motivo}.",
                    delete_after=8,
                    allowed_mentions=discord.AllowedMentions(users=True, roles=False, everyone=False),
                )
            except discord.HTTPException:
                pass

        event_id = None
        if "spam" in motivo.lower():
            event_id = "spam_detectado"
        elif "link" in motivo.lower():
            event_id = "links_suspeitos_bloqueados"

        await self.enviar_log(
            message.guild,
            config,
            "moderacao",
            "AutoMod acionado",
            f"{message.author} em {message.channel.mention}",
            discord.Color.orange(),
            [
                ("Motivo", motivo, False),
                ("Acoes", ", ".join(acoes) or "nenhuma", False),
                ("Conteudo", message.content or "--", False),
            ],
            event_id=event_id,
            responsavel=message.author,
        )

    def detectar_violacao(self, message, config):
        automod = config.get("automod", {})
        blacklist = config.get("blacklist", {})
        conteudo = message.content or ""

        if automod.get("bloquear_convites") and INVITE_RE.search(conteudo):
            return "convite Discord bloqueado"

        if automod.get("bloquear_links") and URL_RE.search(conteudo):
            return "link bloqueado"

        if automod.get("bloquear_palavras"):
            palavra = contem_palavra_bloqueada(conteudo, blacklist.get("palavras"))
            if palavra:
                return f"palavra bloqueada: {palavra}"

        max_mencoes = int(automod.get("max_mencoes", 6))
        total_mencoes = len(message.mentions) + len(message.role_mentions)
        if total_mencoes >= max_mencoes:
            return f"excesso de mencoes ({total_mencoes})"

        if automod.get("anti_caps") and len(conteudo) >= 20:
            percentual = caps_percentual(conteudo)
            if percentual >= int(automod.get("max_caps_percent", 85)):
                return f"caps lock excessivo ({percentual}%)"

        if automod.get("anti_spam"):
            chave = (message.guild.id, message.author.id)
            agora = datetime.now(timezone.utc).timestamp()
            fila = self.spam_cache[chave]
            fila.append(agora)
            recentes = [item for item in fila if agora - item <= SPAM_WINDOW_SECONDS]
            if len(recentes) >= SPAM_LIMIT:
                return "spam/flood detectado"

        return None

    @commands.Cog.listener()
    async def on_message(self, message):
        if not message.guild or message.author.bot:
            return

        config = await self.obter_config(message.guild)
        automod = config.get("automod", {})
        blacklist = config.get("blacklist", {})

        await self.aplicar_auto_respostas(message, config)

        if self.usuario_imune(message.author, config):
            return

        motivo_link = self.detectar_link_suspeito(message.content, config)
        if motivo_link:
            await self.punir_link_suspeito(message, config, motivo_link)
            return

        if not automod.get("ativo") or str(message.channel.id) in ids_lista(blacklist.get("canais_ignorados")):
            return

        motivo = self.detectar_violacao(message, config)
        if motivo:
            await self.punir_automod(message, config, motivo)

    @commands.Cog.listener()
    async def on_message_delete(self, message):
        if not message.guild or message.author.bot:
            return

        config = await self.obter_config(message.guild)
        if not self.auditoria_ativa_ou_log_legado(config, "mensagens_deletadas"):
            return

        anexos = "\n".join(attachment.url for attachment in message.attachments) or "--"
        await self.enviar_log(
            message.guild,
            config,
            "mensagens",
            "Mensagem deletada",
            f"Canal: {message.channel.mention}\nAutor: {message.author} (`{message.author.id}`)",
            discord.Color.red(),
            [("Conteudo", message.content or "--", False), ("Anexos", anexos, False)],
            event_id="mensagem_apagada",
            responsavel=message.author,
            log_key="mensagens_deletadas",
        )

    @commands.Cog.listener()
    async def on_message_edit(self, before, after):
        if not before.guild or before.author.bot or before.content == after.content:
            return

        config = await self.obter_config(before.guild)
        if not self.auditoria_ativa_ou_log_legado(config, "mensagens_editadas"):
            return

        await self.enviar_log(
            before.guild,
            config,
            "mensagens",
            "Mensagem editada",
            f"Canal: {before.channel.mention}\nAutor: {before.author} (`{before.author.id}`)",
            discord.Color.gold(),
            [("Antes", before.content or "--", False), ("Depois", after.content or "--", False)],
            event_id="mensagem_editada",
            responsavel=before.author,
            log_key="mensagens_editadas",
        )

    @commands.Cog.listener()
    async def on_member_ban(self, guild, user):
        config = await self.obter_config(guild)
        if not self.auditoria_ativa_ou_log_legado(config, "banimentos"):
            return
        await self.enviar_log(guild, config, "moderacao", "Membro banido", f"{user} (`{user.id}`)", discord.Color.dark_red(), event_id="banimentos", responsavel=user, log_key="banimentos")

    @commands.Cog.listener()
    async def on_member_remove(self, member):
        config = await self.obter_config(member.guild)
        if not self.auditoria_ativa_ou_log_legado(config, "expulsoes"):
            return

        await asyncio.sleep(1.5)
        kick = await self.buscar_auditoria_membro(member.guild, member.id, discord.AuditLogAction.kick)

        if not kick:
            return

        await self.enviar_log(
            member.guild,
            config,
            "moderacao",
            "Membro expulso",
            f"{member} (`{member.id}`)",
            discord.Color.orange(),
            [
                ("Responsavel", str(kick.user or "Desconhecido"), True),
                ("Motivo", kick.reason or "Sem motivo registrado", False),
            ],
            event_id="expulsoes",
            responsavel=kick.user,
            log_key="expulsoes",
        )

    @commands.Cog.listener()
    async def on_member_unban(self, guild, user):
        config = await self.obter_config(guild)
        if not self.auditoria_ativa_ou_log_legado(config, "desbanimentos"):
            return
        await self.enviar_log(guild, config, "moderacao", "Membro desbanido", f"{user} (`{user.id}`)", discord.Color.green(), event_id="remocao_punicoes", responsavel=user, log_key="desbanimentos")

    @commands.Cog.listener()
    async def on_member_join(self, member):
        config = await self.obter_config(member.guild)
        if not self.automacao_ativa(config, "autoRole"):
            return

        role_id = self.valores_automacao(config, "autoRole").get("roleId")
        try:
            role = member.guild.get_role(int(role_id)) if role_id else None
        except (TypeError, ValueError):
            role = None

        if not role:
            return

        try:
            await member.add_roles(role, reason="AMZ Automacoes: auto cargo")
        except (discord.Forbidden, discord.HTTPException) as erro:
            print(f"[AUTO-CARGO] Falha ao adicionar cargo em {member.guild.id}: {erro}")

    @commands.Cog.listener()
    async def on_member_update(self, before, after):
        config = await self.obter_config(after.guild)

        if before.timed_out_until != after.timed_out_until:
            if self.auditoria_ativa_ou_log_legado(config, "castigos"):
                estado = "Castigo aplicado" if after.timed_out_until else "Castigo removido"
                valor = after.timed_out_until.isoformat() if after.timed_out_until else "sem castigo"
                await self.enviar_log(after.guild, config, "moderacao", estado, f"{after} (`{after.id}`)\nAte: {valor}", discord.Color.orange(), event_id="silenciamentos" if after.timed_out_until else "remocao_punicoes", responsavel=after, log_key="castigos")

        antes = {role.id: role for role in before.roles}
        depois = {role.id: role for role in after.roles}
        adicionados = [role.name for role_id, role in depois.items() if role_id not in antes]
        removidos = [role.name for role_id, role in antes.items() if role_id not in depois]

        if (adicionados or removidos) and self.auditoria_ativa_ou_log_legado(config, "cargos"):
            await self.enviar_log(
                after.guild,
                config,
                "servidor",
                "Cargos de usuario alterados",
                f"Usuario: {after} (`{after.id}`)",
                discord.Color.blurple(),
                [
                    ("Adicionados", ", ".join(adicionados) or "--", False),
                    ("Removidos", ", ".join(removidos) or "--", False),
                ],
                event_id="cargo_usuario",
                responsavel=after,
                log_key="cargos",
            )

    @commands.Cog.listener()
    async def on_guild_channel_create(self, channel):
        config = await self.obter_config(channel.guild)
        if self.auditoria_ativa_ou_log_legado(config, "canais"):
            await self.enviar_log(channel.guild, config, "servidor", "Canal criado", f"#{channel.name} (`{channel.id}`)", discord.Color.green(), event_id="canal_alterado", log_key="canais")

    @commands.Cog.listener()
    async def on_guild_channel_delete(self, channel):
        config = await self.obter_config(channel.guild)
        if self.auditoria_ativa_ou_log_legado(config, "canais"):
            await self.enviar_log(channel.guild, config, "servidor", "Canal deletado", f"#{channel.name} (`{channel.id}`)", discord.Color.red(), event_id="canal_alterado", log_key="canais")

    @commands.Cog.listener()
    async def on_guild_channel_update(self, before, after):
        config = await self.obter_config(after.guild)
        if not self.auditoria_ativa_ou_log_legado(config, "canais"):
            return

        mudancas = []
        if before.name != after.name:
            mudancas.append(f"Nome: {before.name} -> {after.name}")
        if before.category != after.category:
            mudancas.append(f"Categoria: {before.category or '--'} -> {after.category or '--'}")
        if before.overwrites != after.overwrites:
            mudancas.append("Permissoes alteradas")

        if mudancas:
            await self.enviar_log(
                after.guild,
                config,
                "servidor",
                "Canal editado",
                f"#{after.name} (`{after.id}`)",
                discord.Color.gold(),
                [("Mudancas", "\n".join(mudancas), False)],
                event_id="permissoes_alteradas" if any("Permissoes" in item for item in mudancas) else "canal_alterado",
                log_key="canais",
            )

    @commands.Cog.listener()
    async def on_guild_role_create(self, role):
        config = await self.obter_config(role.guild)
        if self.auditoria_ativa_ou_log_legado(config, "cargos"):
            await self.enviar_log(role.guild, config, "servidor", "Cargo criado", f"{role.name} (`{role.id}`)", discord.Color.green(), event_id="cargo_alterado", log_key="cargos")

    @commands.Cog.listener()
    async def on_guild_role_delete(self, role):
        config = await self.obter_config(role.guild)
        if self.auditoria_ativa_ou_log_legado(config, "cargos"):
            await self.enviar_log(role.guild, config, "servidor", "Cargo deletado", f"{role.name} (`{role.id}`)", discord.Color.red(), event_id="cargo_alterado", log_key="cargos")

    @commands.Cog.listener()
    async def on_guild_role_update(self, before, after):
        config = await self.obter_config(after.guild)
        if not self.auditoria_ativa_ou_log_legado(config, "cargos"):
            return

        mudancas = []
        if before.name != after.name:
            mudancas.append(f"Nome: {before.name} -> {after.name}")
        if before.permissions.value != after.permissions.value:
            mudancas.append("Permissoes alteradas")

        if mudancas:
            await self.enviar_log(
                after.guild,
                config,
                "servidor",
                "Cargo editado",
                f"{after.name} (`{after.id}`)",
                discord.Color.gold(),
                [("Mudancas", "\n".join(mudancas), False)],
                event_id="permissoes_alteradas" if any("Permissoes" in item for item in mudancas) else "cargo_alterado",
                log_key="cargos",
            )

    async def enviar_log_voz(self, guild, config, event_id, titulo, descricao, fields, responsavel=None):
        await self.enviar_log(
            guild,
            config,
            "auditoria",
            titulo,
            descricao,
            discord.Color.from_rgb(120, 160, 255),
            fields,
            event_id=event_id,
            responsavel=responsavel,
        )

    @commands.Cog.listener()
    async def on_voice_state_update(self, member, before, after):
        if not member.guild or member.bot:
            return

        config = await self.obter_config(member.guild)
        if not config.get("auditoria", {}).get("enabled"):
            return

        data_hora = self.data_hora_log()
        canal_antes = before.channel
        canal_depois = after.channel

        if before.mute != after.mute:
            entry = await self.buscar_auditoria_recente(
                member.guild,
                member.id,
                [getattr(discord.AuditLogAction, "member_update", None)],
            )
            responsavel = entry.user if entry else None
            acao = "mutado" if after.mute else "desmutado"
            canal = canal_depois or canal_antes
            await self.enviar_log_voz(
                member.guild,
                config,
                "voz_mute",
                "[VOZ] Usuario mutado/desmutado na call",
                f"Usuario afetado: {member.mention}",
                [
                    ("Responsavel", str(responsavel or "Desconhecido"), True),
                    ("Usuario afetado", f"{member} (`{member.id}`)", True),
                    ("Canal de voz", getattr(canal, "name", "--"), True),
                    ("Tipo da acao", acao, True),
                    ("Data", data_hora, True),
                    ("ID do responsavel", str(getattr(responsavel, "id", "--")), True),
                ],
                responsavel,
            )

        if before.deaf != after.deaf:
            entry = await self.buscar_auditoria_recente(
                member.guild,
                member.id,
                [getattr(discord.AuditLogAction, "member_update", None)],
            )
            responsavel = entry.user if entry else None
            acao = "ensurdecido" if after.deaf else "desensurdecido"
            canal = canal_depois or canal_antes
            await self.enviar_log_voz(
                member.guild,
                config,
                "voz_deafen",
                "[VOZ] Usuario ensurdecido/desensurdecido na call",
                f"Usuario afetado: {member.mention}",
                [
                    ("Responsavel", str(responsavel or "Desconhecido"), True),
                    ("Usuario afetado", f"{member} (`{member.id}`)", True),
                    ("Canal de voz", getattr(canal, "name", "--"), True),
                    ("Tipo da acao", acao, True),
                    ("Data", data_hora, True),
                    ("ID do responsavel", str(getattr(responsavel, "id", "--")), True),
                ],
                responsavel,
            )

        if canal_antes is None and canal_depois is not None:
            await self.enviar_log_voz(
                member.guild,
                config,
                "voz_entrada",
                "[VOZ] Usuario entrou em call",
                f"Usuario: {member.mention}",
                [
                    ("Usuario", f"{member} (`{member.id}`)", True),
                    ("Canal de voz", canal_depois.name, True),
                    ("Data", data_hora, True),
                ],
                member,
            )
            return

        if canal_antes is not None and canal_depois is None:
            entry = await self.buscar_auditoria_recente(
                member.guild,
                member.id,
                [getattr(discord.AuditLogAction, "member_disconnect", None)],
            )
            responsavel = entry.user if entry else None
            desconectado_por_outro = responsavel and responsavel.id != member.id
            await self.enviar_log_voz(
                member.guild,
                config,
                "voz_desconectado" if desconectado_por_outro else "voz_saida",
                "[VOZ] Usuario desconectado da call" if desconectado_por_outro else "[VOZ] Usuario saiu da call",
                f"Usuario afetado: {member.mention}",
                [
                    ("Responsavel", str(responsavel or "Acao voluntaria"), True),
                    ("Usuario afetado", f"{member} (`{member.id}`)", True),
                    ("Canal de voz", canal_antes.name, True),
                    ("Data", data_hora, True),
                    ("ID do responsavel", str(getattr(responsavel, "id", "--")), True),
                    ("ID do usuario afetado", str(member.id), True),
                ],
                responsavel or member,
            )
            return

        if canal_antes is not None and canal_depois is not None and canal_antes.id != canal_depois.id:
            entry = await self.buscar_auditoria_recente(
                member.guild,
                member.id,
                [getattr(discord.AuditLogAction, "member_move", None)],
            )
            responsavel = entry.user if entry else None
            await self.enviar_log_voz(
                member.guild,
                config,
                "voz_movido",
                "[VOZ] Usuario movido de call",
                f"Usuario movido: {member.mention}",
                [
                    ("Responsavel", str(responsavel or "Acao voluntaria ou desconhecida"), True),
                    ("Usuario movido", f"{member} (`{member.id}`)", True),
                    ("De", canal_antes.name, True),
                    ("Para", canal_depois.name, True),
                    ("Data", data_hora, True),
                    ("ID do responsavel", str(getattr(responsavel, "id", "--")), True),
                    ("ID do usuario afetado", str(member.id), True),
                ],
                responsavel or member,
            )


async def setup(bot):
    await bot.add_cog(ModerationCog(bot))
