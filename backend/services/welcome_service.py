import discord

from database import buscar_boas_vindas


TIPOS_AVISO = {
    "entrada": {
        "tipo": "entrada",
        "ativo": "entrada_ativa",
        "canal_id": "canal_entrada_id",
        "conteudo": "entrada_conteudo",
        "titulo": "entrada_titulo",
        "mensagem": "entrada_mensagem",
        "imagem_url": "entrada_imagem_url",
        "cor": "entrada_cor",
        "mostrar_avatar": "entrada_mostrar_avatar",
        "cor_padrao": "#55ff88",
    },
    "saida": {
        "tipo": "saida",
        "ativo": "saida_ativa",
        "canal_id": "canal_saida_id",
        "conteudo": "saida_conteudo",
        "titulo": "saida_titulo",
        "mensagem": "saida_mensagem",
        "imagem_url": "saida_imagem_url",
        "cor": "saida_cor",
        "mostrar_avatar": "saida_mostrar_avatar",
        "cor_padrao": "#ff6767",
    },
}


def url_http_valida(url):
    return isinstance(url, str) and (url.startswith("http://") or url.startswith("https://"))


def cor_embed(cor, padrao):
    texto = str(cor or padrao).strip().lstrip("#")

    try:
        return discord.Color(int(texto, 16))
    except (TypeError, ValueError):
        return discord.Color(int(padrao.lstrip("#"), 16))


def formatar_variaveis(template, member, contexto=None):
    guild = member.guild
    member_count = str(guild.member_count or len(guild.members))
    contexto = contexto or {}
    valores = {
        "{user}": member.display_name,
        "{username}": member.name,
        "{user_tag}": str(member),
        "{mention}": member.mention,
        "{id}": str(member.id),
        "{server}": guild.name,
        "{server_upper}": guild.name.upper(),
        "{member_count}": member_count,
        "{member_number}": member_count,
        "{leave_action}": contexto.get("leave_action", "saiu do servidor"),
        "{leave_reason}": contexto.get("leave_reason", "Sem motivo registrado"),
        "{moderator}": contexto.get("moderator", "Sistema"),
        "{moderator_tag}": contexto.get("moderator_tag", "Sistema"),
        "{audit_action}": contexto.get("audit_action", "Saida voluntaria"),
    }

    texto = str(template or "")

    for chave, valor in valores.items():
        texto = texto.replace(chave, valor)

    return texto.strip()


async def obter_canal_texto(guild, canal_id):
    try:
        canal_id_int = int(canal_id)
    except (TypeError, ValueError):
        return None

    canal = guild.get_channel(canal_id_int)

    if canal is None:
        try:
            canal = await guild.fetch_channel(canal_id_int)
        except (discord.Forbidden, discord.HTTPException, discord.NotFound):
            return None

    return canal if isinstance(canal, discord.TextChannel) else None


def montar_embed(member, config, campos, contexto=None):
    titulo = formatar_variaveis(config.get(campos["titulo"]), member, contexto)
    mensagem = formatar_variaveis(config.get(campos["mensagem"]), member, contexto)
    imagem_url = config.get(campos["imagem_url"], "")
    contexto = contexto or {}

    embed = discord.Embed(
        title=titulo or None,
        description=mensagem or None,
        color=cor_embed(config.get(campos["cor"]), campos["cor_padrao"]),
    )

    if config.get(campos["mostrar_avatar"], True):
        embed.set_thumbnail(url=str(member.display_avatar.url))

    if url_http_valida(imagem_url):
        embed.set_image(url=imagem_url)

    if campos.get("tipo") == "saida" and contexto:
        embed.add_field(
            name="Registro de saida",
            value=(
                f"Acao: {contexto.get('audit_action', 'Saida voluntaria')}\n"
                f"Responsavel: {contexto.get('moderator_tag', 'Sistema')}\n"
                f"Motivo: {contexto.get('leave_reason', 'Sem motivo registrado')}"
            )[:1024],
            inline=False,
        )

    embed.set_footer(text=f"ID: {member.id}")
    return embed


def montar_fallback_texto(conteudo, embed, imagem_url):
    partes = [conteudo]

    if embed.title:
        partes.append(embed.title)

    if embed.description:
        partes.append(embed.description)

    if url_http_valida(imagem_url):
        partes.append(imagem_url)

    return "\n".join(parte for parte in partes if parte).strip()[:1900]


def resultado_aviso(detalhado, ok, mensagem):
    return (ok, mensagem) if detalhado else ok


async def enviar_aviso_membro(member, tipo, detalhado=False, contexto=None):
    campos = TIPOS_AVISO.get(tipo)

    if not campos or not member.guild:
        return resultado_aviso(detalhado, False, "Tipo de aviso invalido ou membro sem servidor.")

    config = await buscar_boas_vindas(str(member.guild.id))

    if not config.get(campos["ativo"]):
        return resultado_aviso(detalhado, False, f"Aviso de {tipo} esta desativado no painel.")

    canal_id = config.get(campos["canal_id"])

    if not canal_id:
        return resultado_aviso(detalhado, False, f"Nenhum canal de {tipo} foi salvo no painel.")

    canal = await obter_canal_texto(member.guild, canal_id)

    if not canal:
        return resultado_aviso(detalhado, False, f"Canal de {tipo} nao encontrado ou nao e canal de texto.")

    if not member.guild.me:
        return resultado_aviso(detalhado, False, "Nao consegui identificar o cargo do bot neste servidor.")

    permissoes = canal.permissions_for(member.guild.me)

    if not permissoes.view_channel or not permissoes.send_messages:
        return resultado_aviso(
            detalhado,
            False,
            f"O bot precisa de `Ver canal` e `Enviar mensagens` em {canal.mention}.",
        )

    conteudo = formatar_variaveis(config.get(campos["conteudo"]), member, contexto)
    embed = montar_embed(member, config, campos, contexto)
    allowed_mentions = discord.AllowedMentions(users=True, roles=False, everyone=False)

    try:
        if permissoes.embed_links:
            await canal.send(content=conteudo or None, embed=embed, allowed_mentions=allowed_mentions)
        else:
            texto = montar_fallback_texto(conteudo, embed, config.get(campos["imagem_url"], ""))
            if not texto:
                texto = f"{member.display_name} - {member.guild.name}"
            await canal.send(content=texto or None, allowed_mentions=allowed_mentions)
        aviso_embed = "" if permissoes.embed_links else " Sem `Embed Links`, enviei como texto simples."
        return resultado_aviso(detalhado, True, f"Aviso de {tipo} enviado em {canal.mention}.{aviso_embed}")
    except discord.HTTPException as erro:
        print(f"[WELCOME] Falha ao enviar aviso de {tipo} em {member.guild.id}: {erro}")
        return resultado_aviso(detalhado, False, f"Discord recusou o envio do aviso: `{erro}`")
