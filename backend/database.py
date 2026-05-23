# backend/database.py
from motor.motor_asyncio import AsyncIOMotorClient
import os

# Conecta ao MongoDB
client = AsyncIOMotorClient(os.getenv("MONGO_URI"))
db = client["AMZCore"]
collection = db["servidores"]

async def salvar_config(server_id, dados):
    # Upsert: se existir, atualiza; se não, cria.
    await collection.update_one(
        {"id": server_id}, 
        {"$set": dados}, 
        upsert=True
    )

async def buscar_config(server_id):
    return await collection.find_one({"id": server_id})