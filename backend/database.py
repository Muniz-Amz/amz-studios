# backend/database.py
import os
import time
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient
from pymongo import ReturnDocument

MONGO_URI = os.getenv("MONGO_URI")
client = AsyncIOMotorClient(MONGO_URI)

db = client["AMZCore"]
collection = db["servidores"]
MAX_DIAS_LIMPEZA_DISCORD = 14
MAX_MINUTOS_LIMPEZA = 60
MAX_TITULO_AVISO = 240
MAX_CONTEUDO_AVISO = 1900
MAX_MENSAGEM_AVISO = 3800
MAX_URL_AVISO = 500
PADRAO_BOAS_VINDAS = {
    "entrada_ativa": False,
    "saida_ativa": False,
    "canal_entrada_id": "",
    "canal_entrada_nome": "",
    "canal_saida_id": "",
    "canal_saida_nome": "",
    "entrada_conteudo": "{mention}",
    "entrada_titulo": "Bem-vindo(a), {user}!",
    "entrada_mensagem": "{mention} entrou em {server}. Agora somos {member_count} membros.",
    "entrada_imagem_url": "",
    "entrada_cor": "#55ff88",
    "entrada_mostrar_avatar": True,
    "saida_conteudo": "",
    "saida_titulo": "{user} saiu do servidor",
    "saida_mensagem": "{user_tag} saiu de {server}. Agora somos {member_count} membros.",
    "saida_imagem_url": "",
    "saida_cor": "#ff6767",
    "saida_mostrar_avatar": True,
}


def _agora_iso():
    return datetime.now(timezone.utc).isoformat()


def _normalizar_dias(dias):
    try:
        valor = int(dias)
    except (TypeError, ValueError):
        valor = 1

    return str(min(max(valor, 1), MAX_DIAS_LIMPEZA_DISCORD))


def _normalizar_minutos(minutos):
    try:
        valor = int(minutos)
    except (TypeError, ValueError):
        valor = 1

    return str(min(max(valor, 1), MAX_MINUTOS_LIMPEZA))


def _normalizar_limpeza(dados):
    canal_id = str(dados.get("canal_id", "")).strip()
    canal_nome = str(dados.get("canal_nome") or dados.get("canal") or canal_id).strip()
    usa_minutos = dados.get("minutos") is not None or dados.get("unidade") == "minutos"

    if usa_minutos:
        tempo = _normalizar_minutos(dados.get("minutos", "1"))
        campo_tempo = {
            "minutos": tempo,
            "unidade": "minutos",
        }
    else:
        tempo = _normalizar_dias(dados.get("dias", "1"))
        campo_tempo = {
            "dias": tempo,
            "unidade": "dias",
        }

    return {
        "canal_id": canal_id,
        "canal_nome": canal_nome,
        **campo_tempo,
        "acao": "excluir_mensagens",
        "atualizado_em": _agora_iso(),
    }


def _normalizar_bool(valor):
    if isinstance(valor, bool):
        return valor

    if isinstance(valor, str):
        return valor.strip().lower() in ("1", "true", "sim", "yes", "on")

    return bool(valor)


def _limitar_texto(valor, limite, padrao=""):
    texto = str(valor if valor is not None else padrao).strip()
    return texto[:limite]


def _normalizar_cor(cor, padrao):
    texto = str(cor or padrao).strip()

    if not texto.startswith("#"):
        texto = f"#{texto}"

    texto = texto[:7]
    hexadecimais = "0123456789abcdefABCDEF"

    if len(texto) != 7 or any(char not in hexadecimais for char in texto[1:]):
        return padrao

    return texto.lower()


def _normalizar_url(valor):
    url = _limitar_texto(valor, MAX_URL_AVISO)

    if not url:
        return ""

    if url.startswith("http://") or url.startswith("https://"):
        return url

    return ""


def _normalizar_boas_vindas(dados):
    dados = dados or {}
    base = {**PADRAO_BOAS_VINDAS, **dados}

    return {
        "entrada_ativa": _normalizar_bool(base.get("entrada_ativa")),
        "saida_ativa": _normalizar_bool(base.get("saida_ativa")),
        "canal_entrada_id": _limitar_texto(base.get("canal_entrada_id"), 32),
        "canal_entrada_nome": _limitar_texto(base.get("canal_entrada_nome"), 120),
        "canal_saida_id": _limitar_texto(base.get("canal_saida_id"), 32),
        "canal_saida_nome": _limitar_texto(base.get("canal_saida_nome"), 120),
        "entrada_conteudo": _limitar_texto(base.get("entrada_conteudo"), MAX_CONTEUDO_AVISO),
        "entrada_titulo": _limitar_texto(base.get("entrada_titulo"), MAX_TITULO_AVISO, PADRAO_BOAS_VINDAS["entrada_titulo"]),
        "entrada_mensagem": _limitar_texto(base.get("entrada_mensagem"), MAX_MENSAGEM_AVISO, PADRAO_BOAS_VINDAS["entrada_mensagem"]),
        "entrada_imagem_url": _normalizar_url(base.get("entrada_imagem_url")),
        "entrada_cor": _normalizar_cor(base.get("entrada_cor"), PADRAO_BOAS_VINDAS["entrada_cor"]),
        "entrada_mostrar_avatar": _normalizar_bool(base.get("entrada_mostrar_avatar")),
        "saida_conteudo": _limitar_texto(base.get("saida_conteudo"), MAX_CONTEUDO_AVISO),
        "saida_titulo": _limitar_texto(base.get("saida_titulo"), MAX_TITULO_AVISO, PADRAO_BOAS_VINDAS["saida_titulo"]),
        "saida_mensagem": _limitar_texto(base.get("saida_mensagem"), MAX_MENSAGEM_AVISO, PADRAO_BOAS_VINDAS["saida_mensagem"]),
        "saida_imagem_url": _normalizar_url(base.get("saida_imagem_url")),
        "saida_cor": _normalizar_cor(base.get("saida_cor"), PADRAO_BOAS_VINDAS["saida_cor"]),
        "saida_mostrar_avatar": _normalizar_bool(base.get("saida_mostrar_avatar")),
        "atualizado_em": base.get("atualizado_em") or _agora_iso(),
    }


def _limpezas_do_documento(documento):
    if not documento:
        return []

    limpezas = documento.get("limpezas") or []

    if not limpezas and documento.get("canal_id"):
        limpezas = [_normalizar_limpeza(documento)]

    return limpezas


async def salvar_config(server_id, dados):
    """
    Salva ou atualiza dados gerais do servidor sem apagar limpezas existentes.
    """
    server_id = str(server_id)
    await collection.update_one(
        {"id": server_id},
        {
            "$set": {
                **dados,
                "id": server_id,
                "atualizado_em": _agora_iso(),
            }
        },
        upsert=True,
    )
    return True


async def buscar_config(server_id):
    """
    Busca as configuracoes de um servidor especifico no banco de dados.
    """
    return await collection.find_one({"id": str(server_id)})


async def salvar_limpeza(server_id, dados):
    """
    Salva uma limpeza de canal no documento do servidor.
    Se o canal ja existir, substitui a configuracao antiga pela nova.
    """
    server_id = str(server_id)
    limpeza = _normalizar_limpeza(dados)
    agora = _agora_iso()

    documento = await collection.find_one_and_update(
        {"id": server_id},
        [
            {
                "$set": {
                    "limpezas": {
                        "$concatArrays": [
                            {
                                "$filter": {
                                    "input": {"$ifNull": ["$limpezas", []]},
                                    "as": "limpeza",
                                    "cond": {"$ne": ["$$limpeza.canal_id", limpeza["canal_id"]]},
                                }
                            },
                            [limpeza],
                        ]
                    }
                }
            },
            {
                "$set": {
                    "id": server_id,
                    "nome": dados.get("nome", ""),
                    "atualizado_em": agora,
                }
            },
        ],
        upsert=True,
        return_document=ReturnDocument.AFTER,
        projection={"_id": 0},
    )

    return _limpezas_do_documento(documento)


async def buscar_limpezas(server_id):
    """
    Retorna todas as configuracoes de limpeza salvas para um servidor.
    """
    documento = await collection.find_one({"id": str(server_id)}, {"_id": 0})
    return _limpezas_do_documento(documento)


async def salvar_boas_vindas(server_id, dados):
    """
    Salva os avisos de entrada e saida do servidor.
    """
    server_id = str(server_id)
    configuracao = _normalizar_boas_vindas(dados)
    agora = _agora_iso()
    configuracao["atualizado_em"] = agora

    documento = await collection.find_one_and_update(
        {"id": server_id},
        {
            "$set": {
                "id": server_id,
                "nome": dados.get("nome", ""),
                "boas_vindas": configuracao,
                "atualizado_em": agora,
            }
        },
        upsert=True,
        return_document=ReturnDocument.AFTER,
        projection={"_id": 0, "boas_vindas": 1},
    )

    return _normalizar_boas_vindas((documento or {}).get("boas_vindas"))


async def buscar_boas_vindas(server_id):
    """
    Retorna a configuracao de avisos de entrada e saida de um servidor.
    """
    documento = await collection.find_one({"id": str(server_id)}, {"_id": 0, "boas_vindas": 1})
    return _normalizar_boas_vindas((documento or {}).get("boas_vindas"))


async def buscar_todas_limpezas():
    """
    Retorna todas as limpezas configuradas em todos os servidores.
    """
    cursor = collection.find(
        {
            "$or": [
                {"limpezas.0": {"$exists": True}},
                {"canal_id": {"$exists": True, "$ne": ""}},
            ]
        },
        {
            "_id": 0,
            "id": 1,
            "nome": 1,
            "limpezas": 1,
            "canal_id": 1,
            "canal_nome": 1,
            "dias": 1,
            "minutos": 1,
            "unidade": 1,
        },
    )

    servidores = []

    async for documento in cursor:
        limpezas = _limpezas_do_documento(documento)

        if limpezas:
            servidores.append({
                "id": str(documento.get("id", "")),
                "nome": documento.get("nome", ""),
                "limpezas": limpezas,
            })

    return servidores


async def remover_limpeza(server_id, canal_id):
    """
    Remove a configuracao de limpeza de um canal.
    """
    server_id = str(server_id)
    canal_id = str(canal_id)

    await collection.update_one(
        {"id": server_id},
        {
            "$pull": {"limpezas": {"canal_id": canal_id}},
            "$unset": {
                "canal_id": "",
                "canal_nome": "",
                "dias": "",
                "minutos": "",
                "unidade": "",
            },
            "$set": {"atualizado_em": _agora_iso()},
        },
    )

    return await buscar_limpezas(server_id)


async def status_banco_dados():
    """
    Retorna informacoes seguras de saude do MongoDB para o painel ADM.
    """
    inicio = time.perf_counter()

    try:
        await client.admin.command("ping")
        ping_ms = round((time.perf_counter() - inicio) * 1000)
        total_documentos = await collection.count_documents({})
        total_com_limpeza = await collection.count_documents(
            {
                "$or": [
                    {"limpezas.0": {"$exists": True}},
                    {"canal_id": {"$exists": True, "$ne": ""}},
                ]
            }
        )
        ultimo_documento = await collection.find_one(
            {},
            {"_id": 0, "id": 1, "nome": 1, "atualizado_em": 1},
            sort=[("atualizado_em", -1)],
        )
        indices = await collection.index_information()

        return {
            "online": True,
            "ping_ms": ping_ms,
            "database": db.name,
            "collection": collection.name,
            "mongo_uri_configurada": bool(MONGO_URI),
            "documentos": total_documentos,
            "documentos_com_limpeza": total_com_limpeza,
            "indices": list(indices.keys()),
            "ultimo_documento": ultimo_documento,
            "erro": None,
        }
    except Exception as erro:
        return {
            "online": False,
            "ping_ms": None,
            "database": db.name,
            "collection": collection.name,
            "mongo_uri_configurada": bool(MONGO_URI),
            "documentos": None,
            "documentos_com_limpeza": None,
            "indices": [],
            "ultimo_documento": None,
            "erro": str(erro),
        }
