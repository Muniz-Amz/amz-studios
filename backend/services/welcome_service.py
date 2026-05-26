import discord

from database import buscar_boas_vindas


TIPOS_AVISO = {
    "entrada": {
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


def formatar_variaveis(template, member):
    guild = member.guild
    valores = {
        "{user}": member.display_name,
        "{username}": member.name,
        "{user_tag}": str(member),
        "{mention}": member.mention,
        "{id}": str(member.id),
        "{server}": guild.name,
        "{member_count}": str(guild.member_count or len(guild.members)),
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


def montar_embed(member, config, campos):
    titulo = formatar_variaveis(config.get(campos["titulo"]), member)
    mensagem = formatar_variaveis(config.get(campos["mensagem"]), member)
    imagem_url = config.get(campos["imagem_url"], "")

    embed = discord.Embed(
        title=titulo or None,
        description=mensagem or None,
        color=cor_embed(config.get(campos["cor"]), campos["cor_padrao"]),
    )

    if config.get(campos["mostrar_avatar"], True):
        embed.set_thumbnail(url=str(member.display_avatar.url))

    if url_http_valida(imagem_url):
        embed.set_image(url=imagem_url)

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


async def enviar_aviso_membro(member, tipo):
    campos = TIPOS_AVISO.get(tipo)

    if not campos or not member.guild:
        return False

    config = await buscar_boas_vindas(str(member.guild.id))

    if not config.get(campos["ativo"]):
        return False

    canal = await obter_canal_texto(member.guild, config.get(campos["canal_id"]))

    if not canal or not member.guild.me:
        return False

    permissoes = canal.permissions_for(member.guild.me)

    if not permissoes.view_channel or not permissoes.send_messages:
        return False

    conteudo = formatar_variaveis(config.get(campos["conteudo"]), member)
    embed = montar_embed(member, config, campos)
    allowed_mentions = discord.AllowedMentions(users=True, roles=False, everyone=False)

    try:
        if permissoes.embed_links:
            await canal.send(content=conteudo or None, embed=embed, allowed_mentions=allowed_mentions)
        else:
            texto = montar_fallback_texto(conteudo, embed, config.get(campos["imagem_url"], ""))
            if not texto:
                texto = f"{member.display_name} - {member.guild.name}"
            await canal.send(content=texto or None, allowed_mentions=allowed_mentions)
        return True
    except discord.HTTPException as erro:
        print(f"[WELCOME] Falha ao enviar aviso de {tipo} em {member.guild.id}: {erro}")
        return False
