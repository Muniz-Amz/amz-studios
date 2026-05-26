def usuario_e_admin_ou_dono(guild, usuario):
    if not guild or not usuario:
        return False

    if usuario.id == guild.owner_id:
        return True

    return getattr(usuario.guild_permissions, "administrator", False)
