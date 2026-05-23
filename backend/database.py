# backend/database.py
import os
from motor.motor_asyncio import AsyncIOMotorClient

# Conecta ao MongoDB usando a variável de ambiente
MONGO_URI = os.getenv("MONGO_URI")
client = AsyncIOMotorClient(MONGO_URI)

# Seleciona o banco e a coleção
db = client["AMZCore"]
collection = db["servidores"]

async def salvar_config(server_id, dados):
    """
    Salva ou atualiza as configurações de um servidor.
    Se o servidor não existir no banco, ele cria um novo (upsert=True).
    """
    await collection.update_one(
        {"id": str(server_id)}, 
        {"$set": dados}, 
        upsert=True
    )
    return True

async def buscar_config(server_id):
    """
    Busca as configurações de um servidor específico no banco de dados.
    """
    return await collection.find_one({"id": str(server_id)})