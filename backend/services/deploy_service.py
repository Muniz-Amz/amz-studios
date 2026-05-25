import os

import requests

RENDER_DEPLOY_HOOK_URL = os.getenv("RENDER_DEPLOY_HOOK_URL", "").strip()
DEPLOY_TIMEOUT_SEGUNDOS = int(os.getenv("AMZ_DEPLOY_TIMEOUT_SECONDS", "15"))
DEPLOY_ALLOWED_USER_IDS = {
    user_id.strip()
    for user_id in os.getenv("AMZ_DEPLOY_ALLOWED_USER_IDS", "").replace(",", " ").split()
    if user_id.strip()
}


def usuario_pode_deploy(ctx):
    if str(ctx.author.id) in DEPLOY_ALLOWED_USER_IDS:
        return True

    if ctx.guild and ctx.guild.owner_id == ctx.author.id:
        return True

    return False


def usuario_pode_deploy_interaction(interaction):
    if str(interaction.user.id) in DEPLOY_ALLOWED_USER_IDS:
        return True

    if interaction.guild and interaction.guild.owner_id == interaction.user.id:
        return True

    return False


def deploy_configurado():
    return bool(RENDER_DEPLOY_HOOK_URL)


def disparar_deploy_render():
    response = requests.post(RENDER_DEPLOY_HOOK_URL, timeout=DEPLOY_TIMEOUT_SEGUNDOS)
    response.raise_for_status()
    return response.status_code
