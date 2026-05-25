# backend/database.py
import os
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient
from pymongo import ReturnDocument

MONGO_URI = os.getenv("MONGO_URI")
client = AsyncIOMotorClient(MONGO_URI)

db = client["AMZCore"]
collection = db["servidores"]


def _agora_iso():
    return datetime.now(timezone.utc).isoformat()


def _normalizar_limpeza(dados):
    canal_id = str(dados.get("canal_id", "")).strip()
    canal_nome = str(dados.get("canal_nome") or dados.get("canal") or canal_id).strip()
    dias = str(dados.get("dias", "1")).strip()

    return {
        "canal_id": canal_id,
        "canal_nome": canal_nome,
        "dias": dias,
        "acao": "excluir_mensagens",
        "atualizado_em": _agora_iso(),
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
            },
            "$set": {"atualizado_em": _agora_iso()},
        },
    )

    return await buscar_limpezas(server_id)
