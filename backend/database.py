# backend/database.py
import copy
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
MAX_MINUTOS_LIMPEZA = 1440
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
    "entrada_conteudo": "**Bem-vindo** {mention} **{server_upper}** ! Agora temos **{member_count} Membros.**",
    "entrada_titulo": "",
    "entrada_mensagem": "**Voce e o {member_count} a entrar no servidor!**",
    "entrada_imagem_url": "",
    "entrada_cor": "#55ff88",
    "entrada_mostrar_avatar": True,
    "saida_conteudo": "**{user}** {leave_action} de **{server_upper}**. Agora temos **{member_count} Membros.**",
    "saida_titulo": "",
    "saida_mensagem": "**Registro:** {audit_action}\n**Responsavel:** {moderator_tag}\n**Motivo:** {leave_reason}",
    "saida_imagem_url": "",
    "saida_cor": "#ff6767",
    "saida_mostrar_avatar": True,
}
AUDITORIA_EVENTOS_PADRAO = [
    {"id": "banimentos", "title": "Banimentos", "description": "Registra quando um usuario e banido do servidor.", "enabled": True, "group": "moderacao"},
    {"id": "expulsoes", "title": "Expulsoes", "description": "Registra quando um usuario e expulso do servidor.", "enabled": True, "group": "moderacao"},
    {"id": "advertencias", "title": "Advertencias", "description": "Registra quando um usuario recebe uma advertencia.", "enabled": True, "group": "moderacao"},
    {"id": "silenciamentos", "title": "Silenciamentos", "description": "Registra quando um usuario e silenciado ou tem o silencio removido.", "enabled": True, "group": "moderacao"},
    {"id": "remocao_punicoes", "title": "Remocao de punicoes", "description": "Registra quando uma punicao e removida de um usuario.", "enabled": True, "group": "moderacao"},
    {"id": "voz_entrada", "title": "Usuario entrou em call", "description": "Registra quando um usuario entra em um canal de voz.", "enabled": True, "group": "voz"},
    {"id": "voz_saida", "title": "Usuario saiu da call", "description": "Registra quando um usuario sai de um canal de voz.", "enabled": True, "group": "voz"},
    {"id": "voz_movido", "title": "Usuario movido de call", "description": "Registra quando um usuario e movido de um canal de voz para outro.", "enabled": True, "group": "voz"},
    {"id": "voz_mute", "title": "Usuario mutado/desmutado na call", "description": "Registra quando um usuario e mutado ou desmutado em um canal de voz.", "enabled": True, "group": "voz"},
    {"id": "voz_deafen", "title": "Usuario ensurdecido/desensurdecido na call", "description": "Registra quando um usuario e ensurdecido ou desensurdecido em um canal de voz.", "enabled": True, "group": "voz"},
    {"id": "voz_desconectado", "title": "Usuario desconectado da call", "description": "Registra quando um usuario e desconectado de um canal de voz por outra pessoa.", "enabled": True, "group": "voz"},
    {"id": "mensagem_apagada", "title": "Mensagem apagada", "description": "Registra quando uma mensagem e apagada.", "enabled": True, "group": "mensagens"},
    {"id": "mensagem_editada", "title": "Mensagem editada", "description": "Registra quando uma mensagem e editada.", "enabled": True, "group": "mensagens"},
    {"id": "mensagem_fixada", "title": "Mensagem fixada/desfixada", "description": "Registra quando uma mensagem e fixada ou desfixada.", "enabled": True, "group": "mensagens"},
    {"id": "links_suspeitos_bloqueados", "title": "Links suspeitos bloqueados", "description": "Registra quando um link suspeito e bloqueado.", "enabled": True, "group": "mensagens"},
    {"id": "spam_detectado", "title": "Spam detectado", "description": "Registra quando uma possivel acao de spam e detectada.", "enabled": True, "group": "mensagens"},
    {"id": "cargo_alterado", "title": "Cargo criado/editado/deletado", "description": "Registra alteracoes em cargos do servidor.", "enabled": True, "group": "cargos"},
    {"id": "cargo_usuario", "title": "Cargo adicionado/removido de usuario", "description": "Registra quando cargos sao adicionados ou removidos de usuarios.", "enabled": True, "group": "cargos"},
    {"id": "permissoes_alteradas", "title": "Permissoes alteradas", "description": "Registra alteracoes em permissoes do servidor.", "enabled": True, "group": "cargos"},
    {"id": "canal_alterado", "title": "Canal criado/editado/deletado", "description": "Registra alteracoes em canais do servidor.", "enabled": True, "group": "servidor"},
    {"id": "convite_alterado", "title": "Convite criado/deletado", "description": "Registra criacao ou remocao de convites.", "enabled": True, "group": "servidor"},
    {"id": "emoji_sticker_alterado", "title": "Emoji/sticker criado/editado/deletado", "description": "Registra alteracoes em emojis e stickers.", "enabled": True, "group": "servidor"},
    {"id": "config_servidor_alterada", "title": "Alteracoes nas configuracoes do servidor", "description": "Registra mudancas nas configuracoes gerais do servidor.", "enabled": True, "group": "servidor"},
    {"id": "raid_detectada", "title": "Raid detectada", "description": "Registra quando uma possivel raid e detectada.", "enabled": True, "group": "seguranca"},
    {"id": "lockdown_alterado", "title": "Lockdown ativado/desativado", "description": "Registra quando o modo lockdown e ativado ou desativado.", "enabled": True, "group": "seguranca"},
    {"id": "bot_desconhecido_bloqueado", "title": "Bot desconhecido bloqueado", "description": "Registra quando um bot nao autorizado e bloqueado.", "enabled": True, "group": "seguranca"},
    {"id": "conta_suspeita_bloqueada", "title": "Conta suspeita bloqueada", "description": "Registra quando uma conta suspeita e bloqueada.", "enabled": True, "group": "seguranca"},
    {"id": "comando_usado", "title": "Comando usado", "description": "Registra quando um comando do bot e usado.", "enabled": True, "group": "bot_dashboard"},
    {"id": "erro_bot", "title": "Erro do bot", "description": "Registra erros internos do bot.", "enabled": True, "group": "bot_dashboard"},
    {"id": "dashboard_config_alterada", "title": "Configuracao alterada no dashboard", "description": "Registra alteracoes feitas no dashboard.", "enabled": True, "group": "bot_dashboard"},
    {"id": "modulo_alterado", "title": "Modulo ativado/desativado", "description": "Registra quando um modulo e ativado ou desativado.", "enabled": True, "group": "bot_dashboard"},
]
SEGURANCA_ANTI_RAID_PADRAO = [
    {"id": "enableAntiRaid", "title": "Ativar Anti Raid", "description": "Ativa ou desativa o sistema geral de Anti Raid.", "enabled": False, "type": "toggle", "value": False, "values": {}},
    {"id": "massJoinBlock", "title": "Bloquear entrada em massa", "description": "Bloqueia entrada de muitos usuarios em um curto periodo.", "enabled": True, "type": "threshold", "value": 5, "values": {"maxUsers": 5, "timeWindowSeconds": 10}},
    {"id": "accountMinAge", "title": "Idade minima da conta", "description": "Bloqueia contas criadas recentemente.", "enabled": True, "type": "number", "value": 7, "values": {"minimumDays": 7}},
    {"id": "sensitivity", "title": "Sensibilidade", "description": "Define o nivel de rigidez da protecao Anti Raid.", "enabled": True, "type": "select", "value": "Média", "values": {"level": "Média"}},
    {"id": "automaticAction", "title": "Acao automatica", "description": "Define qual acao sera executada automaticamente quando uma ameaca for detectada.", "enabled": True, "type": "select", "value": "Apenas alertar", "values": {"action": "Apenas alertar"}},
    {"id": "lockdownMode", "title": "Modo Lockdown", "description": "Trava o servidor automaticamente durante uma raid.", "enabled": False, "type": "toggle", "value": False, "values": {}},
    {"id": "lockdownTime", "title": "Tempo de Lockdown", "description": "Define por quanto tempo o servidor ficara em lockdown.", "enabled": True, "type": "number", "value": 10, "values": {"minutes": 10}},
    {"id": "blockUnknownBots", "title": "Bloquear bots desconhecidos", "description": "Impede a entrada de bots nao autorizados no servidor.", "enabled": True, "type": "toggle", "value": True, "values": {}},
    {"id": "notifyAdmins", "title": "Notificar administradores", "description": "Envia alerta para administradores quando uma raid for detectada.", "enabled": True, "type": "toggle", "value": True, "values": {}},
    {"id": "securityLogChannel", "title": "Canal de logs", "description": "Define o canal onde os alertas e logs de seguranca serao enviados.", "enabled": True, "type": "channel", "value": "", "values": {"channelId": "", "channelIdName": ""}},
    {"id": "suspiciousLinks", "title": "Bloquear links suspeitos", "description": "Detecta automaticamente phishing, scam, encurtadores suspeitos e dominios disfarcados.", "enabled": True, "type": "links", "value": "Apagar e alertar", "values": {"action": "Apagar e alertar", "autoDetection": True, "whitelistDomains": [], "blacklistDomains": []}},
]
AUTOMACOES_PADRAO = [
    {"id": "autoRole", "title": "Auto cargo", "description": "Quando alguem entra no servidor, o bot adiciona o cargo escolhido automaticamente.", "enabled": False, "type": "role", "values": {"roleId": "", "roleIdName": ""}},
    {"id": "autoResponse", "title": "Auto resposta", "description": "Quando uma mensagem bater com uma regra ativa, o bot responde no mesmo canal.", "enabled": False, "type": "auto-response", "values": {}},
    {"id": "scheduledMessage", "title": "Mensagem agendada", "description": "Salva uma mensagem para ser enviada no canal escolhido no dia e horario definidos.", "enabled": False, "type": "scheduled-message", "values": {"channelId": "", "channelIdName": "", "message": "", "schedule": ""}},
    {"id": "autoThread", "title": "Auto thread", "description": "Cria uma thread automaticamente em mensagens novas do canal configurado.", "enabled": False, "type": "thread", "values": {"channelId": "", "channelIdName": "", "threadName": ""}},
    {"id": "commandChannelBlock", "title": "Bloqueio de comandos por canal", "description": "Bloqueia comandos nos canais escolhidos. Administradores e moderadores continuam liberados.", "enabled": False, "type": "command-block", "values": {}},
    {"id": "memberGoalNotice", "title": "Aviso por meta de membros", "description": "Envia um aviso quando o servidor atingir a quantidade de membros configurada.", "enabled": False, "type": "member-goal", "values": {"memberCount": 100, "channelId": "", "channelIdName": "", "message": ""}},
]
DETECCOES_AUTO_RESPOSTA = {"contains", "exact", "startsWith", "endsWith"}
LOG_EVENT_CHANNEL_FIELDS = (
    ("canal_mensagens_deletadas_id", "canal_mensagens_deletadas_nome"),
    ("canal_mensagens_editadas_id", "canal_mensagens_editadas_nome"),
    ("canal_banimentos_id", "canal_banimentos_nome"),
    ("canal_desbanimentos_id", "canal_desbanimentos_nome"),
    ("canal_expulsoes_id", "canal_expulsoes_nome"),
    ("canal_castigos_id", "canal_castigos_nome"),
    ("canal_canais_id", "canal_canais_nome"),
    ("canal_cargos_id", "canal_cargos_nome"),
)
PADRAO_MODERACAO = {
    "logs": {
        "ativo": False,
        "canal_mensagens_id": "",
        "canal_mensagens_nome": "",
        "canal_moderacao_id": "",
        "canal_moderacao_nome": "",
        "canal_servidor_id": "",
        "canal_servidor_nome": "",
        **{
            campo: ""
            for par_campos in LOG_EVENT_CHANNEL_FIELDS
            for campo in par_campos
        },
        "mensagens_deletadas": True,
        "mensagens_editadas": True,
        "banimentos": True,
        "desbanimentos": True,
        "expulsoes": True,
        "castigos": True,
        "canais": True,
        "cargos": True,
    },
    "automod": {
        "ativo": False,
        "bloquear_links": False,
        "bloquear_convites": True,
        "bloquear_palavras": True,
        "anti_spam": True,
        "anti_caps": False,
        "max_mencoes": 6,
        "max_caps_percent": 85,
        "castigo_minutos": 10,
        "apagar_mensagem": True,
        "avisar_usuario": True,
        "castigar_usuario": False,
    },
    "blacklist": {
        "palavras": [],
        "canais_ignorados": [],
        "cargos_imunes": [],
    },
    "permissoes": {
        "cargos_admin": [],
        "cargos_moderador": [],
        "permitir_ban": True,
        "permitir_expulsar": True,
        "permitir_castigar": True,
        "permitir_limpar": True,
    },
    "bot_profile": {
        "responder_mencao": True,
        "dashboard_url": "https://muniz-amz.github.io/amz-studios/#dashboard",
        "cor_principal": "#ffffff",
        "rodape": "AMZ Studios",
    },
    "interface": {
        "modo_compacto": False,
        "mostrar_ids": True,
        "mostrar_avancado": True,
    },
    "profiles": {
        "backup_automatico": False,
        "ultimo_backup": "",
    },
    "auditoria": {
        "enabled": False,
        "defaultChannelId": "",
        "defaultChannelName": "",
        "lastEvent": "",
        "events": [
            {**evento, "channelId": "", "channelName": ""}
            for evento in AUDITORIA_EVENTOS_PADRAO
        ],
        "history": [],
    },
    "seguranca": {
        "options": [
            {
                "id": "antiRaid",
                "title": "Anti Raid",
                "description": "Anti raid e protecao contra abuso em massa.",
                "enabled": False,
                "type": "section",
            }
        ],
        "antiRaid": {
            "lastThreat": "",
            "totalAutomaticActions": 0,
            "suspiciousLinksBlocked": 0,
            "usersBlockedByRaid": 0,
            "settings": copy.deepcopy(SEGURANCA_ANTI_RAID_PADRAO),
        },
    },
    "automacoes": {
        "lastExecution": "",
        "options": copy.deepcopy(AUTOMACOES_PADRAO),
        "autoResponses": [],
        "commandBlockRules": [],
    },
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


def _normalizar_canais_eventos_log(logs):
    canais = {}

    for campo_id, campo_nome in LOG_EVENT_CHANNEL_FIELDS:
        canais[campo_id] = _limitar_texto(logs.get(campo_id), 32)
        canais[campo_nome] = _limitar_texto(logs.get(campo_nome), 120)

    return canais


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


def _normalizar_int(valor, padrao, minimo, maximo):
    try:
        numero = int(valor)
    except (TypeError, ValueError):
        numero = padrao

    return min(max(numero, minimo), maximo)


def _normalizar_lista_texto(valor, limite=80, tamanho_item=80):
    if isinstance(valor, str):
        itens = valor.replace(",", "\n").splitlines()
    elif isinstance(valor, list):
        itens = valor
    else:
        itens = []

    normalizados = []
    vistos = set()

    for item in itens:
        texto = str(item).strip()
        if not texto:
            continue
        texto = texto[:tamanho_item]
        chave = texto.lower()
        if chave in vistos:
            continue
        vistos.add(chave)
        normalizados.append(texto)
        if len(normalizados) >= limite:
            break

    return normalizados


def _lista_por_id(valores):
    if not isinstance(valores, list):
        return {}

    return {
        str(item.get("id")): item
        for item in valores
        if isinstance(item, dict) and item.get("id")
    }


def _normalizar_auditoria(dados):
    auditoria = dados if isinstance(dados, dict) else {}
    eventos_salvos = _lista_por_id(auditoria.get("events") or auditoria.get("eventos"))
    historico = auditoria.get("history") or auditoria.get("historico") or []

    eventos = []
    for evento in AUDITORIA_EVENTOS_PADRAO:
        salvo = eventos_salvos.get(evento["id"], {})
        eventos.append({
            "id": evento["id"],
            "title": evento["title"],
            "description": evento["description"],
            "group": evento["group"],
            "enabled": _normalizar_bool(salvo.get("enabled", salvo.get("ativo", evento["enabled"]))),
            "channelId": _limitar_texto(salvo.get("channelId") or salvo.get("channel_id"), 32),
            "channelName": _limitar_texto(salvo.get("channelName") or salvo.get("channel_name"), 120),
        })

    historico_normalizado = []
    if isinstance(historico, list):
        for item in historico[:20]:
            if not isinstance(item, dict):
                continue
            historico_normalizado.append({
                "eventType": _limitar_texto(item.get("eventType") or item.get("tipo"), 120),
                "channelName": _limitar_texto(item.get("channelName") or item.get("channel") or item.get("canal"), 120),
                "responsibleUser": _limitar_texto(item.get("responsibleUser") or item.get("user") or item.get("usuario"), 120),
                "dateTime": _limitar_texto(item.get("dateTime") or item.get("dataHora") or item.get("data"), 80),
                "status": _limitar_texto(item.get("status"), 20, "enviado"),
            })

    return {
        "enabled": _normalizar_bool(auditoria.get("enabled", auditoria.get("ativo"))),
        "defaultChannelId": _limitar_texto(
            auditoria.get("defaultChannelId")
            or auditoria.get("default_channel_id")
            or auditoria.get("canal_padrao_id"),
            32,
        ),
        "defaultChannelName": _limitar_texto(
            auditoria.get("defaultChannelName")
            or auditoria.get("default_channel_name")
            or auditoria.get("canal_padrao_nome"),
            120,
        ),
        "lastEvent": _limitar_texto(
            auditoria.get("lastEvent")
            or auditoria.get("last_event")
            or auditoria.get("ultimo_evento"),
            120,
        ),
        "events": eventos,
        "history": historico_normalizado,
    }


def _normalizar_valor_generico(valor, padrao):
    if isinstance(padrao, bool):
        return _normalizar_bool(valor)

    if isinstance(padrao, int):
        return _normalizar_int(valor, padrao, 0, 10000000)

    if isinstance(padrao, list):
        return _normalizar_lista_texto(valor, 200, 120)

    return _limitar_texto(valor, 2000, str(padrao or ""))


def _normalizar_opcoes_configuraveis(defaults, salvos):
    salvos_por_id = _lista_por_id(salvos)
    opcoes = []

    for padrao in defaults:
        salvo = salvos_por_id.get(padrao["id"], {})
        valores_padrao = copy.deepcopy(padrao.get("values") or {})
        valores_salvos = salvo.get("values") if isinstance(salvo.get("values"), dict) else {}
        valores = {}

        for chave, valor_padrao in valores_padrao.items():
            valores[chave] = _normalizar_valor_generico(valores_salvos.get(chave, valor_padrao), valor_padrao)

        opcoes.append({
            "id": padrao["id"],
            "title": padrao.get("title", ""),
            "description": padrao.get("description", ""),
            "enabled": _normalizar_bool(salvo.get("enabled", salvo.get("ativo", padrao.get("enabled", False)))),
            "type": padrao.get("type", "toggle"),
            "value": _normalizar_valor_generico(salvo.get("value", padrao.get("value", "")), padrao.get("value", "")),
            "values": valores,
        })

    return opcoes


def _normalizar_seguranca(dados):
    seguranca = dados if isinstance(dados, dict) else {}
    anti_raid = seguranca.get("antiRaid") or seguranca.get("anti_raid") or {}

    return {
        "options": [
            {
                "id": "antiRaid",
                "title": "Anti Raid",
                "description": "Anti raid e protecao contra abuso em massa.",
                "enabled": _normalizar_bool((seguranca.get("options") or [{}])[0].get("enabled")) if isinstance(seguranca.get("options"), list) and seguranca.get("options") else False,
                "type": "section",
            }
        ],
        "antiRaid": {
            "lastThreat": _limitar_texto(anti_raid.get("lastThreat") or anti_raid.get("last_threat"), 120),
            "totalAutomaticActions": _normalizar_int(anti_raid.get("totalAutomaticActions") or anti_raid.get("total_automatic_actions"), 0, 0, 10000000),
            "suspiciousLinksBlocked": _normalizar_int(anti_raid.get("suspiciousLinksBlocked") or anti_raid.get("suspicious_links_blocked"), 0, 0, 10000000),
            "usersBlockedByRaid": _normalizar_int(anti_raid.get("usersBlockedByRaid") or anti_raid.get("users_blocked_by_raid"), 0, 0, 10000000),
            "settings": _normalizar_opcoes_configuraveis(
                SEGURANCA_ANTI_RAID_PADRAO,
                anti_raid.get("settings"),
            ),
        },
    }


def _normalizar_auto_respostas(valores):
    if not isinstance(valores, list):
        return []

    regras = []
    for indice, regra in enumerate(valores[:50]):
        if not isinstance(regra, dict):
            continue

        deteccao = regra.get("detectionType") or regra.get("detection_type") or "contains"
        if deteccao not in DETECCOES_AUTO_RESPOSTA:
            deteccao = "contains"

        regras.append({
            "id": _limitar_texto(regra.get("id"), 80, f"auto-response-{indice + 1}"),
            "enabled": _normalizar_bool(regra.get("enabled", regra.get("ativo", True))),
            "keyword": _limitar_texto(regra.get("keyword") or regra.get("palavra_chave"), 120),
            "response": _limitar_texto(regra.get("response") or regra.get("resposta"), 1900),
            "channelId": _limitar_texto(regra.get("channelId") or regra.get("channel_id"), 32),
            "channelName": _limitar_texto(regra.get("channelName") or regra.get("channel_name"), 120),
            "detectionType": deteccao,
            "cooldownSeconds": _normalizar_int(regra.get("cooldownSeconds") or regra.get("cooldown_seconds"), 30, 0, 86400),
            "ignoreStaff": _normalizar_bool(regra.get("ignoreStaff", regra.get("ignore_staff"))),
            "deleteAfterSeconds": _normalizar_int(regra.get("deleteAfterSeconds") or regra.get("delete_after_seconds"), 0, 0, 86400),
        })

    return [regra for regra in regras if regra["keyword"] and regra["response"]]


def _normalizar_bloqueios_comandos(valores):
    if not isinstance(valores, list):
        return []

    regras = []
    for indice, regra in enumerate(valores[:50]):
        if not isinstance(regra, dict):
            continue

        canais = _normalizar_lista_texto(regra.get("channelIds") or regra.get("channel_ids"), 100, 32)
        nomes = _normalizar_lista_texto(regra.get("channelNames") or regra.get("channel_names"), 100, 120)
        comandos = _normalizar_lista_texto(regra.get("commands") or regra.get("comandos"), 100, 80)

        if not canais:
            continue

        regras.append({
            "id": _limitar_texto(regra.get("id"), 80, f"command-block-{indice + 1}"),
            "enabled": _normalizar_bool(regra.get("enabled", regra.get("ativo", True))),
            "channelIds": canais,
            "channelNames": nomes,
            "commands": comandos,
        })

    return regras


def _normalizar_automacoes(dados):
    automacoes = dados if isinstance(dados, dict) else {}

    return {
        "lastExecution": _limitar_texto(automacoes.get("lastExecution") or automacoes.get("last_execution"), 120),
        "options": _normalizar_opcoes_configuraveis(AUTOMACOES_PADRAO, automacoes.get("options")),
        "autoResponses": _normalizar_auto_respostas(automacoes.get("autoResponses") or automacoes.get("auto_responses")),
        "commandBlockRules": _normalizar_bloqueios_comandos(automacoes.get("commandBlockRules") or automacoes.get("command_block_rules")),
    }


def _normalizar_moderacao(dados):
    dados = dados or {}
    logs = {**PADRAO_MODERACAO["logs"], **(dados.get("logs") or {})}
    automod = {**PADRAO_MODERACAO["automod"], **(dados.get("automod") or {})}
    blacklist = {**PADRAO_MODERACAO["blacklist"], **(dados.get("blacklist") or {})}
    permissoes = {**PADRAO_MODERACAO["permissoes"], **(dados.get("permissoes") or {})}
    bot_profile = {**PADRAO_MODERACAO["bot_profile"], **(dados.get("bot_profile") or {})}
    interface = {**PADRAO_MODERACAO["interface"], **(dados.get("interface") or {})}
    profiles = {**PADRAO_MODERACAO["profiles"], **(dados.get("profiles") or {})}

    return {
        "logs": {
            "ativo": _normalizar_bool(logs.get("ativo")),
            "canal_mensagens_id": _limitar_texto(logs.get("canal_mensagens_id"), 32),
            "canal_mensagens_nome": _limitar_texto(logs.get("canal_mensagens_nome"), 120),
            "canal_moderacao_id": _limitar_texto(logs.get("canal_moderacao_id"), 32),
            "canal_moderacao_nome": _limitar_texto(logs.get("canal_moderacao_nome"), 120),
            "canal_servidor_id": _limitar_texto(logs.get("canal_servidor_id"), 32),
            "canal_servidor_nome": _limitar_texto(logs.get("canal_servidor_nome"), 120),
            **_normalizar_canais_eventos_log(logs),
            "mensagens_deletadas": _normalizar_bool(logs.get("mensagens_deletadas")),
            "mensagens_editadas": _normalizar_bool(logs.get("mensagens_editadas")),
            "banimentos": _normalizar_bool(logs.get("banimentos")),
            "desbanimentos": _normalizar_bool(logs.get("desbanimentos")),
            "expulsoes": _normalizar_bool(logs.get("expulsoes")),
            "castigos": _normalizar_bool(logs.get("castigos")),
            "canais": _normalizar_bool(logs.get("canais")),
            "cargos": _normalizar_bool(logs.get("cargos")),
        },
        "automod": {
            "ativo": _normalizar_bool(automod.get("ativo")),
            "bloquear_links": _normalizar_bool(automod.get("bloquear_links")),
            "bloquear_convites": _normalizar_bool(automod.get("bloquear_convites")),
            "bloquear_palavras": _normalizar_bool(automod.get("bloquear_palavras")),
            "anti_spam": _normalizar_bool(automod.get("anti_spam")),
            "anti_caps": _normalizar_bool(automod.get("anti_caps")),
            "max_mencoes": _normalizar_int(automod.get("max_mencoes"), 6, 1, 50),
            "max_caps_percent": _normalizar_int(automod.get("max_caps_percent"), 85, 1, 100),
            "castigo_minutos": _normalizar_int(automod.get("castigo_minutos"), 10, 1, 10080),
            "apagar_mensagem": _normalizar_bool(automod.get("apagar_mensagem")),
            "avisar_usuario": _normalizar_bool(automod.get("avisar_usuario")),
            "castigar_usuario": _normalizar_bool(automod.get("castigar_usuario")),
        },
        "blacklist": {
            "palavras": _normalizar_lista_texto(blacklist.get("palavras"), 200, 80),
            "canais_ignorados": _normalizar_lista_texto(blacklist.get("canais_ignorados"), 100, 32),
            "cargos_imunes": _normalizar_lista_texto(blacklist.get("cargos_imunes"), 100, 32),
        },
        "permissoes": {
            "cargos_admin": _normalizar_lista_texto(permissoes.get("cargos_admin"), 100, 32),
            "cargos_moderador": _normalizar_lista_texto(permissoes.get("cargos_moderador"), 100, 32),
            "permitir_ban": _normalizar_bool(permissoes.get("permitir_ban")),
            "permitir_expulsar": _normalizar_bool(permissoes.get("permitir_expulsar")),
            "permitir_castigar": _normalizar_bool(permissoes.get("permitir_castigar")),
            "permitir_limpar": _normalizar_bool(permissoes.get("permitir_limpar")),
        },
        "bot_profile": {
            "responder_mencao": _normalizar_bool(bot_profile.get("responder_mencao")),
            "dashboard_url": _normalizar_url(bot_profile.get("dashboard_url")) or PADRAO_MODERACAO["bot_profile"]["dashboard_url"],
            "cor_principal": _normalizar_cor(bot_profile.get("cor_principal"), PADRAO_MODERACAO["bot_profile"]["cor_principal"]),
            "rodape": _limitar_texto(bot_profile.get("rodape"), 80, PADRAO_MODERACAO["bot_profile"]["rodape"]),
        },
        "interface": {
            "modo_compacto": _normalizar_bool(interface.get("modo_compacto")),
            "mostrar_ids": _normalizar_bool(interface.get("mostrar_ids")),
            "mostrar_avancado": _normalizar_bool(interface.get("mostrar_avancado")),
        },
        "profiles": {
            "backup_automatico": _normalizar_bool(profiles.get("backup_automatico")),
            "ultimo_backup": _limitar_texto(profiles.get("ultimo_backup"), 80),
        },
        "auditoria": _normalizar_auditoria(dados.get("auditoria")),
        "seguranca": _normalizar_seguranca(dados.get("seguranca")),
        "automacoes": _normalizar_automacoes(dados.get("automacoes")),
        "atualizado_em": dados.get("atualizado_em") or _agora_iso(),
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


async def salvar_moderacao(server_id, dados):
    """
    Salva configuracoes de moderacao, logs, automod, blacklist e permissoes.
    """
    server_id = str(server_id)
    configuracao = _normalizar_moderacao(dados)
    agora = _agora_iso()
    configuracao["atualizado_em"] = agora

    documento = await collection.find_one_and_update(
        {"id": server_id},
        {
            "$set": {
                "id": server_id,
                "nome": dados.get("nome", ""),
                "moderacao": configuracao,
                "atualizado_em": agora,
            }
        },
        upsert=True,
        return_document=ReturnDocument.AFTER,
        projection={"_id": 0, "moderacao": 1},
    )

    return _normalizar_moderacao((documento or {}).get("moderacao"))


async def buscar_moderacao(server_id):
    """
    Retorna a configuracao de moderacao de um servidor.
    """
    documento = await collection.find_one({"id": str(server_id)}, {"_id": 0, "moderacao": 1})
    return _normalizar_moderacao((documento or {}).get("moderacao"))


async def registrar_historico_auditoria(server_id, item):
    """
    Adiciona um item no historico simples de auditoria do servidor.
    """
    server_id = str(server_id)
    registro = {
        "eventType": _limitar_texto(item.get("eventType") or item.get("tipo"), 120),
        "channelName": _limitar_texto(item.get("channelName") or item.get("channel") or item.get("canal"), 120),
        "responsibleUser": _limitar_texto(item.get("responsibleUser") or item.get("user") or item.get("usuario"), 120),
        "dateTime": _limitar_texto(item.get("dateTime") or item.get("dataHora") or item.get("data"), 80, _agora_iso()),
        "status": _limitar_texto(item.get("status"), 20, "enviado"),
    }

    await collection.update_one(
        {"id": server_id},
        {
            "$set": {
                "id": server_id,
                "moderacao.auditoria.lastEvent": registro["eventType"],
                "atualizado_em": _agora_iso(),
            },
            "$push": {
                "moderacao.auditoria.history": {
                    "$each": [registro],
                    "$position": 0,
                    "$slice": 20,
                }
            },
        },
        upsert=True,
    )

    return registro


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
