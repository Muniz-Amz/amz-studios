// ==========================================
// CONFIGURAÇÕES E URLS
// ==========================================
const API_URL = 'https://amz-studios-api.onrender.com';
const DISCORD_CLIENT_ID = '1479103284064026787';
const DISCORD_REDIRECT_PADRAO = 'https://muniz-amz.github.io/amz-studios/';
const DISCORD_LOGIN_SCOPES = 'identify guilds';
const DISCORD_BOT_INVITE_SCOPES = 'bot applications.commands';
const DISCORD_BOT_PERMISSIONS = '8';
const DISCORD_PERMISSION_ADMINISTRATOR = BigInt(0x8);
const DISCORD_PERMISSION_MANAGE_GUILD = BigInt(0x20);
const MAX_MINUTOS_LIMPEZA = 1440;
const ADMIN_TOKEN_KEY = 'amz_admin_token';
const SITE_THEME_KEY = 'amz_site_theme';
const SITE_THEMES = new Set(['dark', 'light']);
const SAVE_TRAY_SECTIONS = {
    setup: 'Limpeza',
    server: 'Avisos',
    role: 'Moderacao',
    audit: 'Auditoria',
    security: 'Seguranca',
    automations: 'Automacoes'
};
const MODELO_AVISOS_AMZ = {
    entrada_conteudo: '**Bem-vindo** {mention} **{server_upper}** ! Agora temos **{member_count} Membros.**',
    entrada_titulo: '',
    entrada_mensagem: '**Voce e o {member_count} a entrar no servidor!**',
    saida_conteudo: '**{user}** {leave_action} de **{server_upper}**. Agora temos **{member_count} Membros.**',
    saida_titulo: '',
    saida_mensagem: '**Registro:** {audit_action}\n**Responsavel:** {moderator_tag}\n**Motivo:** {leave_reason}'
};
const BOAS_VINDAS_PADRAO = {
    entrada_ativa: false,
    saida_ativa: false,
    canal_entrada_id: '',
    canal_entrada_nome: '',
    canal_saida_id: '',
    canal_saida_nome: '',
    entrada_conteudo: MODELO_AVISOS_AMZ.entrada_conteudo,
    entrada_titulo: MODELO_AVISOS_AMZ.entrada_titulo,
    entrada_mensagem: MODELO_AVISOS_AMZ.entrada_mensagem,
    entrada_imagem_url: '',
    entrada_cor: '#55ff88',
    entrada_mostrar_avatar: true,
    saida_conteudo: MODELO_AVISOS_AMZ.saida_conteudo,
    saida_titulo: MODELO_AVISOS_AMZ.saida_titulo,
    saida_mensagem: MODELO_AVISOS_AMZ.saida_mensagem,
    saida_imagem_url: '',
    saida_cor: '#ff6767',
    saida_mostrar_avatar: true
};
const VARIAVEIS_BOAS_VINDAS = ['{mention}', '{user}', '{username}', '{user_tag}', '{id}', '{server}', '{server_upper}', '{member_count}', '{member_number}', '{leave_action}', '{audit_action}', '{leave_reason}', '{moderator}', '{moderator_tag}'];
const voiceAuditLogTemplate = {
    eventType: '',
    responsibleUserId: '',
    responsibleUserName: '',
    affectedUserId: '',
    affectedUserName: '',
    sourceVoiceChannel: '',
    targetVoiceChannel: '',
    voiceChannel: '',
    action: '',
    dateTime: ''
};
const auditGroups = [
    { id: 'moderacao', title: 'Moderação', icon: 'ph-gavel' },
    { id: 'voz', title: 'Voz / Calls', icon: 'ph-speaker-high' },
    { id: 'mensagens', title: 'Mensagens', icon: 'ph-chat-circle-text' },
    { id: 'cargos', title: 'Cargos e permissões', icon: 'ph-identification-card' },
    { id: 'servidor', title: 'Servidor', icon: 'ph-tree-structure' },
    { id: 'seguranca', title: 'Segurança', icon: 'ph-shield-warning' },
    { id: 'bot_dashboard', title: 'Bot / Dashboard', icon: 'ph-robot' }
];
const auditEvents = [
    { id: 'banimentos', title: 'Banimentos', description: 'Registra quando um usuário é banido do servidor.', enabled: true, channelId: null, group: 'moderacao' },
    { id: 'expulsoes', title: 'Expulsões', description: 'Registra quando um usuário é expulso do servidor.', enabled: true, channelId: null, group: 'moderacao' },
    { id: 'advertencias', title: 'Advertências', description: 'Registra quando um usuário recebe uma advertência.', enabled: true, channelId: null, group: 'moderacao' },
    { id: 'silenciamentos', title: 'Silenciamentos', description: 'Registra quando um usuário é silenciado ou tem o silêncio removido.', enabled: true, channelId: null, group: 'moderacao' },
    { id: 'remocao_punicoes', title: 'Remoção de punições', description: 'Registra quando uma punição é removida de um usuário.', enabled: true, channelId: null, group: 'moderacao' },
    { id: 'voz_entrada', title: 'Usuário entrou em call', description: 'Registra quando um usuário entra em um canal de voz.', enabled: true, channelId: null, group: 'voz', logDetails: ['Usuário', 'Canal de voz', 'Data e horário'], logTemplate: { ...voiceAuditLogTemplate, eventType: 'voice_join' } },
    { id: 'voz_saida', title: 'Usuário saiu da call', description: 'Registra quando um usuário sai de um canal de voz.', enabled: true, channelId: null, group: 'voz', logDetails: ['Usuário', 'Canal de voz', 'Data e horário'], logTemplate: { ...voiceAuditLogTemplate, eventType: 'voice_leave' } },
    { id: 'voz_movido', title: 'Usuário movido de call', description: 'Registra quando um usuário é movido de um canal de voz para outro.', enabled: true, channelId: null, group: 'voz', logDetails: ['Quem moveu o usuário', 'Qual usuário foi movido', 'Canal de voz de origem', 'Canal de voz de destino', 'Data e horário da ação', 'ID do responsável', 'ID do usuário afetado'], logTemplate: { ...voiceAuditLogTemplate, eventType: 'voice_move' } },
    { id: 'voz_mute', title: 'Usuário mutado/desmutado na call', description: 'Registra quando um usuário é mutado ou desmutado em um canal de voz.', enabled: true, channelId: null, group: 'voz', logDetails: ['Quem executou a ação', 'Quem foi afetado', 'Canal de voz', 'Tipo da ação', 'Data e horário', 'ID do responsável', 'ID do usuário afetado'], logTemplate: { ...voiceAuditLogTemplate, eventType: 'voice_mute' } },
    { id: 'voz_deafen', title: 'Usuário ensurdecido/desensurdecido na call', description: 'Registra quando um usuário é ensurdecido ou desensurdecido em um canal de voz.', enabled: true, channelId: null, group: 'voz', logDetails: ['Quem executou a ação', 'Quem foi afetado', 'Canal de voz', 'Tipo da ação', 'Data e horário', 'ID do responsável', 'ID do usuário afetado'], logTemplate: { ...voiceAuditLogTemplate, eventType: 'voice_deafen' } },
    { id: 'voz_desconectado', title: 'Usuário desconectado da call', description: 'Registra quando um usuário é desconectado de um canal de voz por outra pessoa.', enabled: true, channelId: null, group: 'voz', logDetails: ['Quem desconectou', 'Quem foi desconectado', 'Canal de voz', 'Data e horário', 'ID do responsável', 'ID do usuário afetado'], logTemplate: { ...voiceAuditLogTemplate, eventType: 'voice_disconnect' } },
    { id: 'mensagem_apagada', title: 'Mensagem apagada', description: 'Registra quando uma mensagem é apagada.', enabled: true, channelId: null, group: 'mensagens' },
    { id: 'mensagem_editada', title: 'Mensagem editada', description: 'Registra quando uma mensagem é editada.', enabled: true, channelId: null, group: 'mensagens' },
    { id: 'mensagem_fixada', title: 'Mensagem fixada/desfixada', description: 'Registra quando uma mensagem é fixada ou desfixada.', enabled: true, channelId: null, group: 'mensagens' },
    { id: 'links_suspeitos_bloqueados', title: 'Links suspeitos bloqueados', description: 'Registra quando um link suspeito é bloqueado.', enabled: true, channelId: null, group: 'mensagens' },
    { id: 'spam_detectado', title: 'Spam detectado', description: 'Registra quando uma possível ação de spam é detectada.', enabled: true, channelId: null, group: 'mensagens' },
    { id: 'cargo_alterado', title: 'Cargo criado/editado/deletado', description: 'Registra alterações em cargos do servidor.', enabled: true, channelId: null, group: 'cargos' },
    { id: 'cargo_usuario', title: 'Cargo adicionado/removido de usuário', description: 'Registra quando cargos são adicionados ou removidos de usuários.', enabled: true, channelId: null, group: 'cargos' },
    { id: 'permissoes_alteradas', title: 'Permissões alteradas', description: 'Registra alterações em permissões do servidor.', enabled: true, channelId: null, group: 'cargos' },
    { id: 'canal_alterado', title: 'Canal criado/editado/deletado', description: 'Registra alterações em canais do servidor.', enabled: true, channelId: null, group: 'servidor' },
    { id: 'convite_alterado', title: 'Convite criado/deletado', description: 'Registra criação ou remoção de convites.', enabled: true, channelId: null, group: 'servidor' },
    { id: 'emoji_sticker_alterado', title: 'Emoji/sticker criado/editado/deletado', description: 'Registra alterações em emojis e stickers.', enabled: true, channelId: null, group: 'servidor' },
    { id: 'config_servidor_alterada', title: 'Alterações nas configurações do servidor', description: 'Registra mudanças nas configurações gerais do servidor.', enabled: true, channelId: null, group: 'servidor' },
    { id: 'raid_detectada', title: 'Raid detectada', description: 'Registra quando uma possível raid é detectada.', enabled: true, channelId: null, group: 'seguranca' },
    { id: 'lockdown_alterado', title: 'Lockdown ativado/desativado', description: 'Registra quando o modo lockdown é ativado ou desativado.', enabled: true, channelId: null, group: 'seguranca' },
    { id: 'bot_desconhecido_bloqueado', title: 'Bot desconhecido bloqueado', description: 'Registra quando um bot não autorizado é bloqueado.', enabled: true, channelId: null, group: 'seguranca' },
    { id: 'conta_suspeita_bloqueada', title: 'Conta suspeita bloqueada', description: 'Registra quando uma conta suspeita é bloqueada.', enabled: true, channelId: null, group: 'seguranca' },
    { id: 'comando_usado', title: 'Comando usado', description: 'Registra quando um comando do bot é usado.', enabled: true, channelId: null, group: 'bot_dashboard' },
    { id: 'erro_bot', title: 'Erro do bot', description: 'Registra erros internos do bot.', enabled: true, channelId: null, group: 'bot_dashboard' },
    { id: 'dashboard_config_alterada', title: 'Configuração alterada no dashboard', description: 'Registra alterações feitas no dashboard.', enabled: true, channelId: null, group: 'bot_dashboard' },
    { id: 'modulo_alterado', title: 'Módulo ativado/desativado', description: 'Registra quando um módulo é ativado ou desativado.', enabled: true, channelId: null, group: 'bot_dashboard' }
];
const securityOptions = [
    { id: 'antiRaid', title: 'Anti Raid', description: 'Anti raid e proteção contra abuso em massa.', enabled: false, type: 'section' }
];
const antiRaidSettings = [
    { id: 'enableAntiRaid', title: 'Ativar Anti Raid', description: 'Ativa ou desativa o sistema geral de Anti Raid.', enabled: false, type: 'toggle', value: false },
    { id: 'massJoinBlock', title: 'Bloquear entrada em massa', description: 'Bloqueia entrada de muitos usuários em um curto período.', enabled: true, type: 'threshold', value: 5, fields: [{ id: 'maxUsers', label: 'Número máximo de usuários', type: 'number', value: 5, min: 1, max: 100 }, { id: 'timeWindowSeconds', label: 'Tempo em segundos', type: 'number', value: 10, min: 1, max: 3600 }] },
    { id: 'accountMinAge', title: 'Idade mínima da conta', description: 'Bloqueia contas criadas recentemente.', enabled: true, type: 'number', value: 7, fields: [{ id: 'minimumDays', label: 'Número de dias mínimos', type: 'number', value: 7, min: 0, max: 3650 }] },
    { id: 'sensitivity', title: 'Sensibilidade', description: 'Define o nível de rigidez da proteção Anti Raid.', enabled: true, type: 'select', value: 'Média', fields: [{ id: 'level', label: 'Sensibilidade', type: 'select', value: 'Média', options: ['Baixa', 'Média', 'Alta', 'Extrema'] }] },
    { id: 'automaticAction', title: 'Ação automática', description: 'Define qual ação será executada automaticamente quando uma ameaça for detectada.', enabled: true, type: 'select', value: 'Apenas alertar', fields: [{ id: 'action', label: 'Ação automática', type: 'select', value: 'Apenas alertar', options: ['Apenas alertar', 'Silenciar', 'Expulsar', 'Banir', 'Colocar em verificação'] }] },
    { id: 'lockdownMode', title: 'Modo Lockdown', description: 'Trava o servidor automaticamente durante uma raid.', enabled: false, type: 'toggle', value: false },
    { id: 'lockdownTime', title: 'Tempo de Lockdown', description: 'Define por quanto tempo o servidor ficará em lockdown.', enabled: true, type: 'number', value: 10, fields: [{ id: 'minutes', label: 'Tempo em minutos', type: 'number', value: 10, min: 1, max: 1440 }] },
    { id: 'blockUnknownBots', title: 'Bloquear bots desconhecidos', description: 'Impede a entrada de bots não autorizados no servidor.', enabled: true, type: 'toggle', value: true },
    { id: 'notifyAdmins', title: 'Notificar administradores', description: 'Envia alerta para administradores quando uma raid for detectada.', enabled: true, type: 'toggle', value: true },
    { id: 'securityLogChannel', title: 'Canal de logs', description: 'Define o canal onde os alertas e logs de segurança serão enviados.', enabled: true, type: 'channel', value: '', fields: [{ id: 'channelId', label: 'Canal de logs', type: 'channel', value: '', hint: 'Canal que recebe alertas de raid, links suspeitos e ações automáticas.' }] },
    {
        id: 'suspiciousLinks',
        title: 'Bloquear links suspeitos',
        description: 'Detecta automaticamente phishing, scam, encurtadores suspeitos e dominios disfarçados.',
        enabled: true,
        type: 'links',
        value: 'Apagar e alertar',
        notes: ['Nao precisa preencher dominios para funcionar.', 'As listas abaixo sao opcionais: use apenas para liberar ou bloquear dominios especificos.'],
        fields: [
            { id: 'action', label: 'O que fazer quando detectar', type: 'select', value: 'Apagar e alertar', options: ['Apenas apagar mensagem', 'Apagar e alertar', 'Silenciar usuário', 'Expulsar usuário', 'Banir usuário'], hint: 'Ação aplicada quando o bot identificar um link suspeito.' },
            { id: 'whitelistDomains', label: 'Dominios sempre permitidos (opcional)', type: 'textarea-list', value: [], hint: 'Dominios confiaveis que nunca devem ser bloqueados, um por linha.' },
            { id: 'blacklistDomains', label: 'Dominios sempre bloqueados (opcional)', type: 'textarea-list', value: [], hint: 'Dominios que sempre devem ser bloqueados, um por linha.' }
        ]
    }
];
const automationSettings = [
    {
        id: 'autoRole',
        title: 'Auto cargo',
        description: 'Quando alguem entra no servidor, o bot adiciona o cargo escolhido automaticamente.',
        enabled: false,
        type: 'role',
        notes: ['Afeta apenas novos membros.', 'Se nenhum cargo for escolhido, nada sera aplicado.'],
        fields: [{ id: 'roleId', label: 'Cargo para novos membros', type: 'role', value: '', hint: 'Cargo que o bot tenta entregar assim que o membro entrar.' }]
    },
    {
        id: 'autoResponse',
        title: 'Auto resposta',
        description: 'Quando uma mensagem bater com uma auto resposta ligada, o bot responde no mesmo canal.',
        enabled: false,
        type: 'auto-response',
        notes: ['Cada regra pode ter canal, tipo de deteccao e cooldown proprios.', 'Canal vazio significa que a regra funciona em todos os canais.'],
        fields: []
    },
    {
        id: 'scheduledMessage',
        title: 'Mensagem agendada',
        description: 'Salva uma mensagem para ser enviada no canal escolhido no dia e horario definidos.',
        enabled: false,
        type: 'scheduled-message',
        notes: ['Use uma data futura.', 'O texto sera enviado exatamente para o canal selecionado.'],
        fields: [
            { id: 'channelId', label: 'Canal de envio', type: 'channel', value: '', hint: 'Canal onde a mensagem agendada sera publicada.' },
            { id: 'message', label: 'Mensagem que sera enviada', type: 'textarea', value: '', hint: 'Texto final que o bot deve publicar.' },
            { id: 'schedule', label: 'Dia e horario do envio', type: 'datetime-local', value: '', hint: 'Escolha pelo calendario para evitar data em formato errado.' }
        ]
    },
    {
        id: 'autoThread',
        title: 'Auto thread',
        description: 'Cria uma thread automaticamente em mensagens novas do canal configurado.',
        enabled: false,
        type: 'thread',
        notes: ['Use em canais de sugestoes, suporte ou midia.', 'O nome padrao pode usar um texto fixo para todas as threads.'],
        fields: [
            { id: 'channelId', label: 'Canal monitorado', type: 'channel', value: '', hint: 'Canal onde novas mensagens devem virar threads.' },
            { id: 'threadName', label: 'Nome padrao da thread', type: 'text', value: '', hint: 'Nome usado ao criar a thread automaticamente.' }
        ]
    },
    {
        id: 'commandChannelBlock',
        title: 'Bloqueio de comandos por canal',
        description: 'Bloqueia comandos nos canais escolhidos. Administradores e moderadores continuam liberados.',
        enabled: false,
        type: 'command-block',
        notes: ['Crie varias regras para canais diferentes.', 'Lista de comandos vazia bloqueia todos os comandos nos canais da regra.'],
        fields: []
    },
    {
        id: 'memberGoalNotice',
        title: 'Aviso por meta de membros',
        description: 'Envia um aviso quando o servidor atingir a quantidade de membros configurada.',
        enabled: false,
        type: 'member-goal',
        notes: ['A meta deve ser maior que o total atual de membros.', 'A mensagem vai para o canal de aviso escolhido.'],
        fields: [
            { id: 'memberCount', label: 'Meta de membros', type: 'number', value: 100, min: 1, max: 10000000, hint: 'Numero de membros que dispara o aviso.' },
            { id: 'channelId', label: 'Canal de aviso', type: 'channel', value: '', hint: 'Onde o bot deve publicar quando a meta for atingida.' },
            { id: 'message', label: 'Mensagem do aviso', type: 'textarea', value: '', hint: 'Texto que sera enviado ao bater a meta.' }
        ]
    }
];
const autoResponseDetectionTypes = [
    { value: 'contains', label: 'Contem a palavra' },
    { value: 'exact', label: 'Palavra exata' },
    { value: 'startsWith', label: 'Comeca com' },
    { value: 'endsWith', label: 'Termina com' }
];

function criarValoresCamposPadrao(campos = []) {
    return campos.reduce((valores, campo) => {
        valores[campo.id] = JSON.parse(JSON.stringify(campo.value ?? ''));
        return valores;
    }, {});
}

function criarAuditoriaPadrao() {
    return {
        enabled: false,
        defaultChannelId: '',
        defaultChannelName: '',
        lastEvent: '',
        events: auditEvents.map((evento) => ({
            id: evento.id,
            title: evento.title,
            description: evento.description,
            enabled: evento.enabled,
            channelId: evento.channelId || '',
            channelName: '',
            group: evento.group
        })),
        history: []
    };
}

function criarSegurancaPadrao() {
    return {
        options: securityOptions.map((opcao) => ({ ...opcao })),
        antiRaid: {
            lastThreat: '',
            totalAutomaticActions: 0,
            suspiciousLinksBlocked: 0,
            usersBlockedByRaid: 0,
            settings: antiRaidSettings.map((opcao) => ({
                id: opcao.id,
                title: opcao.title,
                description: opcao.description,
                enabled: opcao.enabled,
                type: opcao.type,
                value: JSON.parse(JSON.stringify(opcao.value ?? '')),
                values: criarValoresCamposPadrao(opcao.fields)
            }))
        }
    };
}

function criarAutomacoesPadrao() {
    return {
        lastExecution: '',
        options: automationSettings.map((opcao) => ({
            id: opcao.id,
            title: opcao.title,
            description: opcao.description,
            enabled: opcao.enabled,
            type: opcao.type,
            value: JSON.parse(JSON.stringify(opcao.value ?? '')),
            values: criarValoresCamposPadrao(opcao.fields)
        })),
        autoResponses: [],
        commandBlockRules: []
    };
}
const MODERACAO_PADRAO = {
    logs: {
        ativo: false,
        canal_mensagens_id: '',
        canal_mensagens_nome: '',
        canal_moderacao_id: '',
        canal_moderacao_nome: '',
        canal_servidor_id: '',
        canal_servidor_nome: '',
        canal_mensagens_deletadas_id: '',
        canal_mensagens_deletadas_nome: '',
        canal_mensagens_editadas_id: '',
        canal_mensagens_editadas_nome: '',
        canal_banimentos_id: '',
        canal_banimentos_nome: '',
        canal_desbanimentos_id: '',
        canal_desbanimentos_nome: '',
        canal_expulsoes_id: '',
        canal_expulsoes_nome: '',
        canal_castigos_id: '',
        canal_castigos_nome: '',
        canal_canais_id: '',
        canal_canais_nome: '',
        canal_cargos_id: '',
        canal_cargos_nome: '',
        mensagens_deletadas: true,
        mensagens_editadas: true,
        banimentos: true,
        desbanimentos: true,
        expulsoes: true,
        castigos: true,
        canais: true,
        cargos: true
    },
    auditoria: criarAuditoriaPadrao(),
    seguranca: criarSegurancaPadrao(),
    automacoes: criarAutomacoesPadrao()
};
let moderacaoAtual = JSON.parse(JSON.stringify(MODERACAO_PADRAO));
let automacaoRegraEditandoId = '';
let comandoBloqueioEditandoId = '';
let moderacaoServidorCarregadoId = '';
let moderacaoRecursosAtual = { canais: [], cargos: [] };
let saveTrayListenerAtivo = false;
let saveTrayToastTimer = null;
let saveTrayEstado = {
    dirty: false,
    saving: false,
    section: ''
};

function normalizarMinutosLimpeza(minutos) {
    const valor = Number.parseInt(minutos, 10);

    if (!Number.isFinite(valor)) return 1;

    return Math.min(Math.max(valor, 1), MAX_MINUTOS_LIMPEZA);
}

function normalizarDiasLimpeza(dias) {
    const valor = Number.parseInt(dias, 10);

    if (!Number.isFinite(valor)) return 1;

    return Math.min(Math.max(valor, 1), 14);
}

function gerarOpcoesMinutosLimpeza() {
    const opcoes = [
        { unidade: 'minutos', valor: 30, rotulo: '30 minutos' },
        ...Array.from({ length: 23 }, (_, indice) => {
            const horas = indice + 1;
            return {
                unidade: 'minutos',
                valor: horas * 60,
                rotulo: `${horas} ${horas === 1 ? 'hora' : 'horas'}`
            };
        }),
        ...Array.from({ length: 14 }, (_, indice) => {
            const dias = indice + 1;
            return {
                unidade: 'dias',
                valor: dias,
                rotulo: `${dias} ${dias === 1 ? 'dia' : 'dias'}`
            };
        })
    ];

    return opcoes.map((opcao) => {
        const value = `${opcao.unidade}:${opcao.valor}`;
        return `<option value="${value}" data-unidade="${opcao.unidade}" data-valor="${opcao.valor}">${opcao.rotulo}</option>`;
    }).join('');
}

function obterTempoLimpezaSelecionado() {
    const select = document.getElementById('minutos');
    const option = select?.options[select.selectedIndex];
    const unidade = option?.dataset.unidade || 'minutos';
    const valor = Number.parseInt(option?.dataset.valor || select?.value || '30', 10);

    if (!Number.isFinite(valor)) {
        return { unidade: 'minutos', valor: 30 };
    }

    if (unidade === 'dias') {
        return { unidade: 'dias', valor: normalizarDiasLimpeza(valor) };
    }

    return { unidade: 'minutos', valor: normalizarMinutosLimpeza(valor) };
}

function obterRedirectUriDiscord() {
    const urlAtual = new URL(window.location.href);
    urlAtual.search = '';
    urlAtual.hash = '';

    if (urlAtual.hostname === 'muniz-amz.github.io' && urlAtual.pathname === '/amz-studios') {
        urlAtual.pathname = '/amz-studios/';
    }

    if (urlAtual.protocol === 'http:' || urlAtual.protocol === 'https:') {
        return urlAtual.href;
    }

    return DISCORD_REDIRECT_PADRAO;
}

function obterDiscordLoginUrl() {
    const url = new URL('https://discord.com/oauth2/authorize');
    url.searchParams.set('client_id', DISCORD_CLIENT_ID);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('redirect_uri', obterRedirectUriDiscord());
    url.searchParams.set('scope', DISCORD_LOGIN_SCOPES);
    return url.toString();
}

function obterDiscordBotInviteUrl() {
    const url = new URL('https://discord.com/oauth2/authorize');
    url.searchParams.set('client_id', DISCORD_CLIENT_ID);
    url.searchParams.set('permissions', DISCORD_BOT_PERMISSIONS);
    url.searchParams.set('integration_type', '0');
    url.searchParams.set('scope', DISCORD_BOT_INVITE_SCOPES);
    return url.toString();
}

function abrirConviteBot() {
    window.open(obterDiscordBotInviteUrl(), '_blank', 'noopener,noreferrer');
}

function prepararLoginDiscord() {
    sessionStorage.setItem('amz_retorno_oauth', 'servidores');
}

function obterServidoresDemo() {
    return [
        { id: 'demo-farm', nome: '[Grupin de farm]', icon_url: '', owner: false, permissions: '8' },
        { id: 'demo-yokai', nome: 'Yokai', icon_url: '', owner: false, permissions: '8' },
        { id: 'demo-celestial', nome: 'Celestial Trindade', icon_url: '', owner: true, permissions: '8' },
        { id: 'demo-ketto', nome: 'CELESTIAL//KETTO', icon_url: '', owner: false, permissions: '8' }
    ];
}

function obterServidoresCache() {
    try {
        return JSON.parse(localStorage.getItem('servidores_amz') || '[]');
    } catch (erro) {
        console.warn('Cache de servidores invalido:', erro);
        return [];
    }
}

function salvarServidoresCache(servidores = []) {
    localStorage.setItem('servidores_amz', JSON.stringify(servidores));
}

function limparSessaoDiscord() {
    localStorage.removeItem('discord_token');
    localStorage.removeItem('servidores_amz');
}

function estaEmArquivoLocal() {
    return window.location.protocol === 'file:' || ['localhost', '127.0.0.1'].includes(window.location.hostname);
}

const DASHBOARD_SECTIONS = {
    setup: {
        title: 'Limpeza',
        description: 'Exclusao automatica de mensagens por canal.'
    },
    server: {
        title: 'Entrada e Saida',
        description: 'Configure boas-vindas, saida, canal, mensagem e imagem/GIF.'
    },
    role: {
        title: 'Moderação',
        description: 'Logs, punicoes, auditoria e seguranca.'
    },
    audit: {
        title: 'Auditoria',
        description: 'Logs personalizados por canal.'
    },
    security: {
        title: 'Segurança',
        description: 'Anti raid e proteção.'
    },
    automations: {
        title: 'Automações',
        description: 'Ações automáticas do servidor.'
    }
};
const moderationLogEvents = [
    { path: 'logs.mensagens_deletadas', channelPath: 'logs.canal_mensagens_deletadas_id', namePath: 'logs.canal_mensagens_deletadas_nome', label: 'Mensagens deletadas', hint: 'Registra mensagens apagadas.' },
    { path: 'logs.mensagens_editadas', channelPath: 'logs.canal_mensagens_editadas_id', namePath: 'logs.canal_mensagens_editadas_nome', label: 'Mensagens editadas', hint: 'Registra mensagens alteradas.' },
    { path: 'logs.banimentos', channelPath: 'logs.canal_banimentos_id', namePath: 'logs.canal_banimentos_nome', label: 'Banimentos', hint: 'Registra membros banidos.' },
    { path: 'logs.desbanimentos', channelPath: 'logs.canal_desbanimentos_id', namePath: 'logs.canal_desbanimentos_nome', label: 'Desbanimentos', hint: 'Registra membros desbanidos.' },
    { path: 'logs.expulsoes', channelPath: 'logs.canal_expulsoes_id', namePath: 'logs.canal_expulsoes_nome', label: 'Expulsoes', hint: 'Registra membros expulsos.' },
    { path: 'logs.castigos', channelPath: 'logs.canal_castigos_id', namePath: 'logs.canal_castigos_nome', label: 'Castigos', hint: 'Registra castigos aplicados ou removidos.' },
    { path: 'logs.canais', channelPath: 'logs.canal_canais_id', namePath: 'logs.canal_canais_nome', label: 'Canais criados/deletados', hint: 'Registra alteracoes em canais.' },
    { path: 'logs.cargos', channelPath: 'logs.canal_cargos_id', namePath: 'logs.canal_cargos_nome', label: 'Cargos criados/deletados', hint: 'Registra alteracoes em cargos.' }
];
const MODERACAO_SECTIONS = {
    role: {
        label: 'Central de Moderacao',
        description: 'Configure quais eventos o bot deve registrar e para qual canal cada log sera enviado.',
        fields: [
            { type: 'toggle', path: 'logs.ativo', label: 'Ativar logs de moderacao', hint: 'Liga os registros automáticos do servidor.' },
            { type: 'channel', path: 'logs.canal_mensagens_id', namePath: 'logs.canal_mensagens_nome', label: 'Padrao mensagens', hint: 'Usado quando um log de mensagem nao tiver canal especifico.' },
            { type: 'channel', path: 'logs.canal_moderacao_id', namePath: 'logs.canal_moderacao_nome', label: 'Padrao punicoes', hint: 'Usado quando banimentos, expulsoes ou castigos nao tiverem canal especifico.' },
            { type: 'channel', path: 'logs.canal_servidor_id', namePath: 'logs.canal_servidor_nome', label: 'Padrao servidor', hint: 'Usado quando logs de canais ou cargos nao tiverem canal especifico.' },
            ...moderationLogEvents.map((evento) => ({
                ...evento,
                type: 'log-event',
                emptyLabel: 'Usar canal padrao'
            }))
        ]
    }
};

function escaparHTML(valor) {
    return String(valor ?? '').replace(/[&<>"']/g, (char) => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    }[char]));
}

function obterIconeServidor(servidor) {
    if (servidor.icon_url) return servidor.icon_url;
    if (servidor.icon && servidor.id) {
        const extensao = String(servidor.icon).startsWith('a_') ? 'gif' : 'png';
        return `https://cdn.discordapp.com/icons/${servidor.id}/${servidor.icon}.${extensao}?size=128`;
    }
    return '';
}

function obterIniciaisServidor(nome) {
    return String(nome || 'AMZ')
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((parte) => parte[0])
        .join('')
        .toUpperCase() || 'AMZ';
}

function renderizarAvatarServidor(nome, iconUrl, classe = 'server-avatar') {
    if (iconUrl) {
        return `<img class="${classe}" src="${escaparHTML(iconUrl)}" alt="${escaparHTML(nome)}">`;
    }

    return `<span class="${classe} fallback">${escaparHTML(obterIniciaisServidor(nome))}</span>`;
}

function obterPermissoesServidor(servidor = {}) {
    try {
        return BigInt(servidor.permissions ?? servidor.permissoes ?? 0);
    } catch (erro) {
        return BigInt(0);
    }
}

function obterRotuloPermissaoServidor(servidor = {}) {
    if (servidor.owner === true || servidor.dono === true) return 'Owner';

    const permissoes = obterPermissoesServidor(servidor);
    if ((permissoes & DISCORD_PERMISSION_ADMINISTRATOR) === DISCORD_PERMISSION_ADMINISTRATOR) return 'Administrador';
    if ((permissoes & DISCORD_PERMISSION_MANAGE_GUILD) === DISCORD_PERMISSION_MANAGE_GUILD) return 'Gerenciar servidor';

    return 'Administrador';
}

function hashAtualNormalizado() {
    return String(window.location.hash || '').toLowerCase();
}

function obterTemaSiteSalvo() {
    try {
        const tema = localStorage.getItem(SITE_THEME_KEY);
        return SITE_THEMES.has(tema) ? tema : 'dark';
    } catch {
        return 'dark';
    }
}

function aplicarTemaSite(tema, salvar = true) {
    const temaNormalizado = SITE_THEMES.has(tema) ? tema : 'dark';
    const proximoTema = temaNormalizado === 'dark' ? 'light' : 'dark';
    const rotulo = proximoTema === 'light' ? 'Claro' : 'Escuro';

    document.documentElement.dataset.siteTheme = temaNormalizado;
    document.body?.classList.toggle('site-theme-light', temaNormalizado === 'light');

    if (salvar) {
        try {
            localStorage.setItem(SITE_THEME_KEY, temaNormalizado);
        } catch {
            // Preferencia visual; se o navegador bloquear storage, seguimos com o tema atual.
        }
    }

    const botao = document.getElementById('site-theme-toggle');
    const icone = document.getElementById('site-theme-icon');
    const label = document.getElementById('site-theme-label');

    if (botao) {
        botao.setAttribute('aria-pressed', String(temaNormalizado === 'light'));
        botao.setAttribute('aria-label', `Ativar tema ${rotulo.toLowerCase()}`);
        botao.title = `Ativar tema ${rotulo.toLowerCase()}`;
    }

    if (icone) {
        icone.className = proximoTema === 'light' ? 'ph ph-sun' : 'ph ph-moon';
    }

    if (label) {
        label.innerText = rotulo;
    }
}

function alternarTemaSite() {
    const temaAtual = document.documentElement.dataset.siteTheme === 'light' ? 'light' : 'dark';
    aplicarTemaSite(temaAtual === 'dark' ? 'light' : 'dark');
}

function obterSecaoDashboardAtiva() {
    const ativa = document.querySelector('[data-dashboard-section].active');
    if (ativa?.dataset.dashboardSection) return ativa.dataset.dashboardSection;

    const painel = document.getElementById('dashboard-section-panel');
    if (painel?.querySelector('.audit-page')) return 'audit';
    if (painel?.querySelector('.security-page')) return 'security';
    if (painel?.querySelector('.automations-page')) return 'automations';
    if (painel?.querySelector('.welcome-config-panel')) return 'server';
    if (painel?.querySelector('.mod-config-panel')) return 'role';
    return 'setup';
}

function obterRotuloSecaoSalvamento(secao = '') {
    return SAVE_TRAY_SECTIONS[secao] || 'Configuracao';
}

function alternarBotoesBarraSalvamento(desativado) {
    document.querySelectorAll('#amz-save-tray button').forEach((botao) => {
        botao.disabled = Boolean(desativado);
    });
}

function marcarConfiguracaoAlterada(secao = obterSecaoDashboardAtiva()) {
    const tray = document.getElementById('amz-save-tray');
    const titulo = document.getElementById('amz-save-tray-title');

    if (!tray || saveTrayEstado.saving) return;

    saveTrayEstado = {
        dirty: true,
        saving: false,
        section: secao || obterSecaoDashboardAtiva()
    };

    if (titulo) {
        titulo.innerText = `Cuidado! Voce tem alteracoes em ${obterRotuloSecaoSalvamento(saveTrayEstado.section)} que nao foram salvas`;
    }

    tray.classList.remove('saving');
    tray.classList.add('visible');
    tray.setAttribute('aria-hidden', 'false');
    alternarBotoesBarraSalvamento(false);
}

function limparAlteracoesPendentes() {
    const tray = document.getElementById('amz-save-tray');

    saveTrayEstado = {
        dirty: false,
        saving: false,
        section: ''
    };

    if (tray) {
        tray.classList.remove('visible', 'saving');
        tray.setAttribute('aria-hidden', 'true');
    }

    alternarBotoesBarraSalvamento(false);
}

function prepararBarraSalvando() {
    const tray = document.getElementById('amz-save-tray');

    saveTrayEstado.saving = true;
    alternarBotoesBarraSalvamento(true);

    if (tray) {
        tray.classList.add('saving');
        tray.classList.remove('visible');
        tray.setAttribute('aria-hidden', 'true');
    }
}

function mostrarToastAmzSalvo() {
    const toast = document.getElementById('amz-saved-toast');
    if (!toast) return;

    window.clearTimeout(saveTrayToastTimer);
    toast.classList.add('visible');
    toast.setAttribute('aria-hidden', 'false');

    saveTrayToastTimer = window.setTimeout(() => {
        toast.classList.remove('visible');
        toast.setAttribute('aria-hidden', 'true');
    }, 2400);
}

function finalizarSalvamentoConfiguracao() {
    const tray = document.getElementById('amz-save-tray');
    const atrasarToast = tray?.classList.contains('saving') || tray?.classList.contains('visible');

    limparAlteracoesPendentes();

    window.setTimeout(mostrarToastAmzSalvo, atrasarToast ? 180 : 0);
}

async function salvarConfiguracaoPendente() {
    if (saveTrayEstado.saving) return false;

    const secao = saveTrayEstado.section || obterSecaoDashboardAtiva();
    const acoes = {
        setup: enviarConfiguracao,
        server: salvarBoasVindas,
        role: salvarModeracaoServidor,
        audit: salvarAuditoriaServidor,
        security: salvarSegurancaServidor,
        automations: salvarAutomacoesServidor
    };
    const acao = acoes[secao] || salvarModeracaoServidor;

    prepararBarraSalvando();

    try {
        const sucesso = await acao();
        if (sucesso) return true;
    } catch (erro) {
        console.error('Erro ao salvar configuracao pendente:', erro);
    }

    saveTrayEstado.saving = false;
    alternarBotoesBarraSalvamento(false);
    marcarConfiguracaoAlterada(secao);
    return false;
}

function redefinirAlteracoesPainel() {
    const secao = saveTrayEstado.section || obterSecaoDashboardAtiva();
    limparAlteracoesPendentes();
    selecionarSecaoDashboard(secao, { preservarAtual: false });
}

function configurarBarraSalvamento() {
    if (saveTrayListenerAtivo) return;
    saveTrayListenerAtivo = true;

    document.addEventListener('input', observarMudancaConfiguracao, true);
    document.addEventListener('change', observarMudancaConfiguracao, true);
}

function observarMudancaConfiguracao(evento) {
    const elemento = evento.target;

    if (!(elemento instanceof HTMLElement)) return;
    if (!elemento.matches('input, select, textarea')) return;
    if (!elemento.closest('#dashboard-section-panel')) return;
    if (elemento.closest('.amz-save-tray')) return;
    if (elemento.type === 'hidden' || elemento.disabled || elemento.dataset.saveIgnore === 'true') return;

    marcarConfiguracaoAlterada(obterSecaoDashboardAtiva());
}

// ==========================================
// FUNÇÕES DE NAVEGAÇÃO
// ==========================================

function acessarTelaBot() {
    document.getElementById('site-principal').classList.add('hidden');
    document.getElementById('admin-area')?.classList.add('hidden');
    
    const painel = document.getElementById('painel-loritta');
    painel.classList.remove('hidden');
    painel.classList.add('flex');
    
    document.getElementById('bot-landing').classList.remove('hidden');
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
}

function abrirPainelServidores() {
    document.getElementById('site-principal').classList.add('hidden');
    document.getElementById('admin-area')?.classList.add('hidden');

    const painel = document.getElementById('painel-loritta');
    painel.classList.remove('hidden');
    painel.classList.add('flex');

    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    document.getElementById('lista-servidores').classList.remove('hidden');
}

function navegarParaSecaoSite(secao) {
    const painel = document.getElementById('painel-loritta');
    const site = document.getElementById('site-principal');

    limparAlteracoesPendentes();
    painel.classList.add('hidden');
    painel.classList.remove('flex');
    document.getElementById('admin-area')?.classList.add('hidden');
    site.classList.remove('hidden');

    const destino = document.getElementById(secao);
    if (destino) {
        requestAnimationFrame(() => destino.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    }

    window.history.replaceState({}, document.title, `${window.location.pathname}#${secao}`);
}

function configurarNavegacaoTopo() {
    document.querySelectorAll('.site-nav-links a[href^="#"]').forEach((link) => {
        if (link.dataset.navConfigurada === 'true') return;

        link.dataset.navConfigurada = 'true';
        link.addEventListener('click', (evento) => {
            const secao = link.getAttribute('href')?.replace('#', '');

            if (!secao) return;

            evento.preventDefault();
            navegarParaSecaoSite(secao);
        });
    });
}

function abrirListaServidores() {
    abrirPainelServidores();
    
    verificarAutenticacao();
}

function voltarAoInicioBot() {
    const painel = document.getElementById('painel-loritta');
    limparAlteracoesPendentes();
    painel.classList.add('hidden');
    painel.classList.remove('flex');
    document.getElementById('site-principal').classList.remove('hidden');

    if (hashAtualNormalizado() === '#dashboard') {
        window.history.replaceState({}, document.title, window.location.pathname);
    }
}

// ==========================================
// SISTEMA DE SEGURANÇA E AUTENTICAÇÃO
// ==========================================

async function verificarAutenticacao() {
    const container = document.getElementById('container-servidores');
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');

    if (code) {
        container.innerHTML = '<p class="text-white/20 animate-pulse text-xs">Verificando suas permissões de Administrador...</p>';
        
        try {
            const response = await fetch(`${API_URL}/api/auth/callback`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    code: code,
                    redirect_uri: obterRedirectUriDiscord()
                })
            });

            const dados = await response.json();

            if (response.ok && dados.status === "sucesso") {
                // SALVANDO O TOKEN E A SESSÃO
                localStorage.setItem('discord_token', dados.access_token);
                salvarServidoresCache(dados.servidores || []);
                renderizarServidores(dados.servidores || []);
            } else {
                mostrarBotaoLogin(`Erro: ${dados.erro || "Não autorizado"}`);
            }
        } catch (erro) {
            console.error("Erro ao conectar na API:", erro);
            mostrarBotaoLogin("Erro ao conectar ao servidor de segurança.");
        }
    } else {
        await atualizarServidoresAutorizados();
        return;

        const sessaoSalva = localStorage.getItem('servidores_amz');
        if (sessaoSalva) {
            renderizarServidores(JSON.parse(sessaoSalva));
        } else {
            mostrarBotaoLogin("Você precisa provar que é Administrador para acessar as configurações.");
        }
    }
}

function mostrarAvisoListaServidores(mensagem) {
    const container = document.getElementById('container-servidores');
    if (!container) return;

    const aviso = document.createElement('p');
    aviso.className = 'text-white/35 text-[10px] uppercase tracking-wider pt-2';
    aviso.textContent = mensagem;
    container.appendChild(aviso);
}

async function atualizarServidoresAutorizados() {
    const container = document.getElementById('container-servidores');
    const token = localStorage.getItem('discord_token');
    const cache = obterServidoresCache();

    if (token === 'demo-token') {
        renderizarServidores(cache.length ? cache : obterServidoresDemo());
        return;
    }

    if (!token) {
        mostrarBotaoLogin("Voce precisa entrar com o Discord para atualizar seus servidores.");
        return;
    }

    if (cache.length) {
        renderizarServidores(cache);
        mostrarAvisoListaServidores('Atualizando lista de servidores...');
    } else if (container) {
        container.innerHTML = '<p class="text-white/20 animate-pulse text-xs">Atualizando servidores que voce pode configurar...</p>';
    }

    try {
        const response = await fetch(`${API_URL}/api/servidores`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultado = await lerJsonResposta(response);

        if (response.ok && resultado.status === 'sucesso') {
            const servidores = resultado.servidores || [];
            salvarServidoresCache(servidores);
            renderizarServidores(servidores);
            return;
        }

        if (response.status === 401) {
            limparSessaoDiscord();
            mostrarBotaoLogin(resultado.mensagem || 'Sessao expirada. Entre novamente com o Discord.');
            return;
        }

        if (cache.length) {
            renderizarServidores(cache);
            mostrarAvisoListaServidores(resultado.mensagem || resultado.erro || 'Nao consegui atualizar agora. Mostrando a ultima lista carregada.');
            return;
        }

        mostrarBotaoLogin(resultado.mensagem || resultado.erro || 'Nao consegui atualizar seus servidores agora.');
    } catch (erro) {
        console.error('Erro ao atualizar servidores:', erro);

        if (cache.length) {
            renderizarServidores(cache);
            mostrarAvisoListaServidores('API indisponivel agora. Mostrando a ultima lista carregada.');
            return;
        }

        mostrarBotaoLogin('Erro ao conectar na API para atualizar os servidores.');
    }
}

function mostrarBotaoLogin(mensagem) {
    const container = document.getElementById('container-servidores');
    container.innerHTML = `
        <div class="text-center py-8 flex flex-col items-center justify-center w-full">
            <p class="text-white/60 text-xs mb-4 max-w-xs">${escaparHTML(mensagem)}</p>
            <div class="flex flex-col sm:flex-row gap-3">
                <a href="${obterDiscordLoginUrl()}" onclick="prepararLoginDiscord()"
                   class="bg-white text-black px-6 py-3 text-[11px] font-black uppercase tracking-wider transition-all hover:bg-black hover:text-white border border-white">
                    Entrar com o Discord
                </a>
                ${estaEmArquivoLocal() ? `
                    <button type="button" onclick="acessarDemoLocal()"
                            class="border border-white/20 text-white px-6 py-3 text-[11px] font-black uppercase tracking-wider transition-all hover:bg-white/10">
                        Testar Painel Local
                    </button>
                ` : ''}
            </div>
        </div>
    `;
}

function acessarDemoLocal() {
    const servidoresDemo = obterServidoresDemo();

    localStorage.setItem('discord_token', 'demo-token');
    salvarServidoresCache(servidoresDemo);
    renderizarServidores(servidoresDemo);
}

function obterServidorAtualId() {
    const painelSecao = document.getElementById('dashboard-section-panel');
    return painelSecao?.dataset.serverId || document.getElementById('vm-server-picker')?.dataset.serverId || '';
}

function fecharServidorDropdown() {
    const dropdown = document.getElementById('vm-server-dropdown');
    const botao = document.getElementById('vm-server-picker');

    if (dropdown) dropdown.classList.add('hidden');
    if (botao) botao.setAttribute('aria-expanded', 'false');
}

function abrirServidorDropdown() {
    const dropdown = document.getElementById('vm-server-dropdown');
    const botao = document.getElementById('vm-server-picker');
    const busca = document.getElementById('vm-server-search-input');

    if (!dropdown || !botao) return;

    renderizarMenuServidor(busca?.value || '');
    dropdown.classList.remove('hidden');
    botao.setAttribute('aria-expanded', 'true');
    requestAnimationFrame(() => busca?.focus());
}

function toggleServidorDropdown(evento) {
    evento?.preventDefault();
    evento?.stopPropagation();

    const dropdown = document.getElementById('vm-server-dropdown');
    if (!dropdown) return;

    if (dropdown.classList.contains('hidden')) {
        abrirServidorDropdown();
    } else {
        fecharServidorDropdown();
    }
}

function renderizarMenuServidor(filtro = '') {
    const lista = document.getElementById('vm-server-list');
    if (!lista) return;

    const termo = String(filtro || '').trim().toLowerCase();
    const servidores = obterServidoresCache();
    const atualId = obterServidorAtualId();
    const filtrados = servidores.filter((servidor) => String(servidor.nome || '').toLowerCase().includes(termo));

    if (!servidores.length) {
        lista.innerHTML = '<div class="vm-server-empty">Entre com o Discord para carregar seus servidores.</div>';
        return;
    }

    if (!filtrados.length) {
        lista.innerHTML = '<div class="vm-server-empty">Nenhum servidor encontrado.</div>';
        return;
    }

    lista.innerHTML = filtrados.map((servidor) => {
        const nomeServidor = String(servidor.nome || 'Servidor sem nome');
        const idServidor = String(servidor.id || '');
        const iconServidor = obterIconeServidor(servidor);
        const ativo = idServidor && idServidor === atualId;

        return `
            <button type="button"
                    class="vm-server-option ${ativo ? 'active' : ''}"
                    data-server-picker-item
                    data-server-id="${escaparHTML(idServidor)}"
                    data-server-name="${escaparHTML(nomeServidor)}"
                    data-server-icon="${escaparHTML(iconServidor)}">
                ${renderizarAvatarServidor(nomeServidor, iconServidor, 'vm-server-option-icon')}
                <span>
                    <strong>${escaparHTML(nomeServidor)}</strong>
                    <small>${escaparHTML(obterRotuloPermissaoServidor(servidor))}</small>
                </span>
                ${ativo ? '<i class="ph ph-check"></i>' : ''}
            </button>
        `;
    }).join('');

    lista.querySelectorAll('[data-server-picker-item]').forEach((botao) => {
        botao.addEventListener('click', () => {
            configurarServidor(botao.dataset.serverId, botao.dataset.serverName, botao.dataset.serverIcon);
        });
    });
}

function renderizarServidores(servidores) {
    const container = document.getElementById('container-servidores');
    
    if (servidores.length === 0) {
        container.innerHTML = '<p class="text-white/40 text-xs py-4">Você não é Administrador de nenhum servidor em comum.</p>';
        renderizarMenuServidor();
        return;
    }

    container.innerHTML = servidores.map(s => {
        const nomeServidor = String(s.nome || 'Servidor sem nome');
        const idServidor = String(s.id || '');
        const iconServidor = obterIconeServidor(s);

        return `
        <div class="server-config-card">
            <div class="server-config-name">
                ${renderizarAvatarServidor(nomeServidor, iconServidor)}
                <span>${escaparHTML(nomeServidor)}</span>
            </div>
            <button type="button"
                    class="server-config-button"
                    data-configurar-servidor
                    data-server-id="${escaparHTML(idServidor)}"
                    data-server-name="${escaparHTML(nomeServidor)}"
                    data-server-icon="${escaparHTML(iconServidor)}">
                Configurar
            </button>
        </div>
    `;
    }).join('');

    container.querySelectorAll('[data-configurar-servidor]').forEach((botao) => {
        botao.addEventListener('click', () => {
            configurarServidor(botao.dataset.serverId, botao.dataset.serverName, botao.dataset.serverIcon);
        });
    });

    renderizarMenuServidor();
}

// ==========================================
// CONFIGURAÇÕES DO SERVIDOR
// ==========================================
function configurarServidor(id, nome, iconUrl = '') {
    if (moderacaoServidorCarregadoId !== id) {
        moderacaoAtual = normalizarModeracaoLocal(clonarConfig(MODERACAO_PADRAO));
        moderacaoServidorCarregadoId = '';
        moderacaoRecursosAtual = { canais: [], cargos: [] };
        automacaoRegraEditandoId = '';
        comandoBloqueioEditandoId = '';
    }

    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.remove('hidden');
    document.getElementById('nome-servidor-atual').innerText = nome;
    atualizarServidorAtualNaSidebar(nome, iconUrl, id);
    fecharServidorDropdown();

    const nomeDashboard = document.getElementById('dashboard-server-name');
    if (nomeDashboard) nomeDashboard.innerText = nome;

    const painelSecao = document.getElementById('dashboard-section-panel');
    if (painelSecao) {
        painelSecao.dataset.serverId = id;
        painelSecao.dataset.serverName = nome;
        painelSecao.dataset.serverIcon = iconUrl;
    }

    selecionarSecaoDashboard('setup');
}

function atualizarServidorAtualNaSidebar(nome, iconUrl = '', serverId = '') {
    const nomeAtual = document.getElementById('nome-servidor-atual');
    const avatarAtual = document.getElementById('icone-servidor-atual');
    const botaoServidor = document.getElementById('vm-server-picker');

    if (nomeAtual) nomeAtual.innerText = nome;
    if (botaoServidor) {
        botaoServidor.dataset.serverId = serverId;
        botaoServidor.dataset.serverName = nome;
        botaoServidor.dataset.serverIcon = iconUrl;
    }

    if (!avatarAtual) return;

    if (iconUrl) {
        avatarAtual.innerHTML = `<img src="${escaparHTML(iconUrl)}" alt="${escaparHTML(nome)}">`;
    } else {
        avatarAtual.innerHTML = escaparHTML(obterIniciaisServidor(nome));
    }

    renderizarMenuServidor(document.getElementById('vm-server-search-input')?.value || '');
}

function selecionarSecaoDashboard(secao = 'setup', opcoes = {}) {
    if (opcoes.preservarAtual !== false) {
        preservarCamposDashboardAtual();
    }
    limparAlteracoesPendentes();
    const info = DASHBOARD_SECTIONS[secao] || DASHBOARD_SECTIONS.setup;

    document.querySelectorAll('[data-dashboard-section]').forEach((elemento) => {
        elemento.classList.toggle('active', elemento.dataset.dashboardSection === secao);
    });

    const painelSecao = document.getElementById('dashboard-section-panel');
    if (!painelSecao) return;

    const serverId = painelSecao.dataset.serverId || '';
    const serverName = painelSecao.dataset.serverName || document.getElementById('nome-servidor-atual')?.innerText || '---';

    if (secao === 'setup') {
        painelSecao.innerHTML = `
            <div class="vm-panel-heading">
                <span>Limpeza</span>
                <strong id="dashboard-server-name">${escaparHTML(serverName)}</strong>
            </div>

            <div class="vm-form-grid">
                <label>
                    <span>Canal do Discord</span>
                    <select id="canal_select" onchange="selecionarCanalLimpeza()">
                        <option value="">Carregando canais...</option>
                    </select>
                    <input type="hidden" id="canal_id">
                    <input type="hidden" id="canal_nome">
                </label>

                <label>
                    <span>Excluir mensagens apos</span>
                    <select id="minutos">
                        ${gerarOpcoesMinutosLimpeza()}
                    </select>
                </label>
            </div>

            <button type="button" onclick="enviarConfiguracao()" class="vm-save-button">
                <i class="ph ph-broom" id="icon-sync"></i>
                Salvar Limpeza
            </button>

            <div id="status_msg" class="vm-status-message hidden"></div>
            <div class="cleanup-list-panel">
                <div class="cleanup-list-heading">
                    <strong>Limpezas configuradas</strong>
                    <span>Canais com exclusao automatica</span>
                </div>
                <div id="limpezas-configuradas" class="cleanup-list-empty">
                    Carregando configuracoes...
                </div>
            </div>
        `;

        const canalInput = document.getElementById('canal_id');
        if (canalInput) {
            canalInput.value = '';
            canalInput.dataset.id = serverId;
        }
        carregarCanaisServidor();
        carregarLimpezasConfiguradas();
        return;
    }

    if (secao === 'server') {
        painelSecao.innerHTML = renderizarPainelBoasVindas(serverName);
        carregarBoasVindasServidor();
        return;
    }

    if (secao === 'audit') {
        painelSecao.innerHTML = AuditPage(serverName);
        carregarModeracaoServidor(secao);
        return;
    }

    if (secao === 'security') {
        painelSecao.innerHTML = SecurityPage(serverName);
        carregarModeracaoServidor(secao);
        return;
    }

    if (secao === 'automations') {
        automacaoRegraEditandoId = '';
        painelSecao.innerHTML = AutomationsPage(serverName);
        carregarModeracaoServidor(secao);
        return;
    }

    painelSecao.innerHTML = renderizarPainelModeracao(secao, serverName || info.title);
    carregarModeracaoServidor(secao);
}

function renderizarVariaveisBoasVindas() {
    return VARIAVEIS_BOAS_VINDAS.map((variavel) => `<code>${escaparHTML(variavel)}</code>`).join('');
}

function clonarConfig(valor) {
    return JSON.parse(JSON.stringify(valor));
}

function mesclarConfig(base, extra) {
    const saida = clonarConfig(base);

    Object.entries(extra || {}).forEach(([chave, valor]) => {
        if (valor && typeof valor === 'object' && !Array.isArray(valor) && saida[chave] && typeof saida[chave] === 'object' && !Array.isArray(saida[chave])) {
            saida[chave] = mesclarConfig(saida[chave], valor);
            return;
        }

        saida[chave] = valor;
    });

    return saida;
}

function obterValorPath(objeto, path) {
    return String(path || '').split('.').reduce((atual, parte) => atual?.[parte], objeto);
}

function definirValorPath(objeto, path, valor) {
    const partes = String(path || '').split('.');
    let atual = objeto;

    partes.slice(0, -1).forEach((parte) => {
        if (!atual[parte] || typeof atual[parte] !== 'object') atual[parte] = {};
        atual = atual[parte];
    });

    atual[partes[partes.length - 1]] = valor;
}

function idCampoModeracao(path) {
    return `mod_${String(path).replace(/[^a-zA-Z0-9]/g, '_')}`;
}

function listaParaTexto(valor) {
    return Array.isArray(valor) ? valor.join('\n') : String(valor || '');
}

function textoParaLista(valor) {
    return String(valor || '')
        .split(/\r?\n|,/)
        .map((item) => item.trim())
        .filter(Boolean);
}

function mapaPorId(lista = []) {
    return new Map((Array.isArray(lista) ? lista : []).map((item) => [item.id, item]));
}

function normalizarAuditoriaLocal(auditoria = {}) {
    const eventosSalvos = mapaPorId(auditoria.events || auditoria.eventos);

    return {
        enabled: Boolean(auditoria.enabled ?? auditoria.ativo),
        defaultChannelId: auditoria.defaultChannelId || auditoria.default_channel_id || auditoria.canal_padrao_id || '',
        defaultChannelName: auditoria.defaultChannelName || auditoria.default_channel_name || auditoria.canal_padrao_nome || '',
        lastEvent: auditoria.lastEvent || auditoria.last_event || auditoria.ultimo_evento || '',
        events: auditEvents.map((evento) => {
            const salvo = eventosSalvos.get(evento.id) || {};
            return {
                id: evento.id,
                title: evento.title,
                description: evento.description,
                enabled: Boolean(salvo.enabled ?? salvo.ativo ?? evento.enabled),
                channelId: salvo.channelId || salvo.channel_id || '',
                channelName: salvo.channelName || salvo.channel_name || '',
                group: evento.group
            };
        }),
        history: Array.isArray(auditoria.history || auditoria.historico)
            ? (auditoria.history || auditoria.historico).slice(0, 20)
            : []
    };
}

function normalizarOpcoesComCampos(opcoesBase, opcoesSalvas = []) {
    const salvas = mapaPorId(opcoesSalvas);

    return opcoesBase.map((opcao) => {
        const salva = salvas.get(opcao.id) || {};
        return {
            id: opcao.id,
            title: opcao.title,
            description: opcao.description,
            enabled: Boolean(salva.enabled ?? salva.ativo ?? opcao.enabled),
            type: opcao.type,
            value: salva.value ?? opcao.value ?? '',
            values: {
                ...criarValoresCamposPadrao(opcao.fields),
                ...(salva.values || {})
            }
        };
    });
}

function normalizarSegurancaLocal(seguranca = {}) {
    const antiRaid = seguranca.antiRaid || seguranca.anti_raid || {};

    return {
        options: normalizarOpcoesComCampos(securityOptions, seguranca.options),
        antiRaid: {
            lastThreat: antiRaid.lastThreat || antiRaid.last_threat || '',
            totalAutomaticActions: Number.parseInt(antiRaid.totalAutomaticActions ?? antiRaid.total_automatic_actions ?? 0, 10) || 0,
            suspiciousLinksBlocked: Number.parseInt(antiRaid.suspiciousLinksBlocked ?? antiRaid.suspicious_links_blocked ?? 0, 10) || 0,
            usersBlockedByRaid: Number.parseInt(antiRaid.usersBlockedByRaid ?? antiRaid.users_blocked_by_raid ?? 0, 10) || 0,
            settings: normalizarOpcoesComCampos(antiRaidSettings, antiRaid.settings)
        }
    };
}

function normalizarAutomacoesLocal(automacoes = {}) {
    return {
        lastExecution: automacoes.lastExecution || automacoes.last_execution || '',
        options: normalizarOpcoesComCampos(automationSettings, automacoes.options),
        autoResponses: Array.isArray(automacoes.autoResponses || automacoes.auto_responses)
            ? (automacoes.autoResponses || automacoes.auto_responses).map((regra) => ({
                id: regra.id || `auto-response-${Date.now()}`,
                enabled: Boolean(regra.enabled ?? regra.ativo),
                keyword: regra.keyword || regra.palavra_chave || '',
                response: regra.response || regra.resposta || '',
                channelId: regra.channelId || regra.channel_id || '',
                channelName: regra.channelName || regra.channel_name || '',
                detectionType: regra.detectionType || regra.detection_type || 'contains',
                cooldownSeconds: Number.parseInt(regra.cooldownSeconds ?? regra.cooldown_seconds ?? 30, 10) || 0,
                ignoreStaff: Boolean(regra.ignoreStaff ?? regra.ignore_staff),
                deleteAfterSeconds: Number.parseInt(regra.deleteAfterSeconds ?? regra.delete_after_seconds ?? 0, 10) || 0
            }))
            : [],
        commandBlockRules: Array.isArray(automacoes.commandBlockRules || automacoes.command_block_rules)
            ? (automacoes.commandBlockRules || automacoes.command_block_rules).map((regra) => ({
                id: regra.id || `command-block-${Date.now()}`,
                enabled: Boolean(regra.enabled ?? regra.ativo),
                channelIds: Array.isArray(regra.channelIds || regra.channel_ids) ? (regra.channelIds || regra.channel_ids).map(String) : [],
                channelNames: Array.isArray(regra.channelNames || regra.channel_names) ? (regra.channelNames || regra.channel_names).map(String) : [],
                commands: Array.isArray(regra.commands || regra.comandos) ? (regra.commands || regra.comandos).map(String) : []
            }))
            : []
    };
}

function normalizarModeracaoLocal(config = {}) {
    const normalizada = mesclarConfig(MODERACAO_PADRAO, config || {});
    delete normalizada.automod;
    delete normalizada.blacklist;
    delete normalizada.permissoes;
    delete normalizada.bot_profile;
    delete normalizada.interface;
    delete normalizada.profiles;
    normalizada.auditoria = normalizarAuditoriaLocal(normalizada.auditoria);
    normalizada.seguranca = normalizarSegurancaLocal(normalizada.seguranca);
    normalizada.automacoes = normalizarAutomacoesLocal(normalizada.automacoes);
    return normalizada;
}

function ToggleSwitch(id, checked = false, attrs = '') {
    return `
        <label class="toggle-switch">
            <input type="checkbox" id="${escaparHTML(id)}" ${attrs} ${checked ? 'checked' : ''}>
            <span></span>
        </label>
    `;
}

function ChannelSelect({ id, attrs = '', multiple = false } = {}) {
    return `
        <select id="${escaparHTML(id)}" ${attrs} ${multiple ? 'multiple' : ''}>
            <option value="">Carregando canais...</option>
        </select>
    `;
}

function RoleSelect({ id, attrs = '' } = {}) {
    return `
        <select id="${escaparHTML(id)}" ${attrs}>
            <option value="">Carregando cargos...</option>
        </select>
    `;
}

function preencherChannelSelect(select, canais = [], selecionado = '', permitirVazio = true) {
    if (!select) return;

    const valores = select.multiple
        ? new Set(Array.isArray(selecionado) ? selecionado.map(String) : textoParaLista(selecionado))
        : new Set([String(selecionado || '')]);

    const opcoes = [
        permitirVazio && !select.multiple ? '<option value="">Nao definido</option>' : '',
        ...canais.map((canal) => `<option value="${escaparHTML(canal.id)}" data-channel-name="${escaparHTML(canal.nome)}">#${escaparHTML(canal.nome)}${canal.categoria ? ` - ${escaparHTML(canal.categoria)}` : ''}</option>`)
    ].filter(Boolean);

    select.innerHTML = opcoes.length ? opcoes.join('') : '<option value="">Nenhum canal encontrado</option>';

    Array.from(select.options).forEach((option) => {
        option.selected = valores.has(option.value);
    });
}

function preencherRoleSelect(select, cargos = [], selecionado = '') {
    if (!select) return;

    select.innerHTML = [
        '<option value="">Nao definido</option>',
        ...cargos.map((cargo) => `<option value="${escaparHTML(cargo.id)}" data-role-name="${escaparHTML(cargo.nome)}">${escaparHTML(cargo.nome)}</option>`)
    ].join('');
    select.value = selecionado || '';
}

function obterNomeSelecionado(select, datasetKey) {
    const option = select?.options?.[select.selectedIndex];
    return option?.dataset?.[datasetKey] || '';
}

function obterNomesSelecionados(select, datasetKey) {
    return Array.from(select?.selectedOptions || [])
        .map((option) => option.dataset?.[datasetKey] || option.textContent.trim())
        .filter(Boolean);
}

function obterSetting(lista = [], id) {
    return (Array.isArray(lista) ? lista : []).find((item) => item.id === id) || {};
}

function valorCampoConfig(config, campo) {
    return config?.values?.[campo.id] ?? campo.value ?? '';
}

function renderizarCampoConfiguravel(escopo, opcaoId, campo) {
    const id = `${escopo}_${opcaoId}_${campo.id}`;
    const attrs = `data-${escopo}-field data-option-id="${escaparHTML(opcaoId)}" data-field-id="${escaparHTML(campo.id)}" data-field-type="${escaparHTML(campo.type)}"`;
    const hint = campo.hint ? `<small>${escaparHTML(campo.hint)}</small>` : '';

    if (campo.type === 'channel') {
        return `
            <label class="advanced-field">
                <span>${escaparHTML(campo.label)}</span>
                ${ChannelSelect({ id, attrs })}
                ${hint}
            </label>
        `;
    }

    if (campo.type === 'role') {
        return `
            <label class="advanced-field">
                <span>${escaparHTML(campo.label)}</span>
                ${RoleSelect({ id, attrs })}
                ${hint}
            </label>
        `;
    }

    if (campo.type === 'channel-multi') {
        return `
            <label class="advanced-field advanced-field-wide">
                <span>${escaparHTML(campo.label)}</span>
                ${ChannelSelect({ id, attrs, multiple: true })}
                ${hint}
            </label>
        `;
    }

    if (campo.type === 'textarea' || campo.type === 'textarea-list') {
        return `
            <label class="advanced-field advanced-field-wide">
                <span>${escaparHTML(campo.label)}</span>
                <textarea id="${escaparHTML(id)}" rows="4" ${attrs}></textarea>
                ${hint}
            </label>
        `;
    }

    if (campo.type === 'select') {
        return `
            <label class="advanced-field">
                <span>${escaparHTML(campo.label)}</span>
                <select id="${escaparHTML(id)}" ${attrs}>
                    ${(campo.options || []).map((opcao) => `<option value="${escaparHTML(opcao)}">${escaparHTML(opcao)}</option>`).join('')}
                </select>
                ${hint}
            </label>
        `;
    }

    const inputType = campo.type === 'number' ? 'number' : campo.type === 'datetime-local' ? 'datetime-local' : 'text';

    return `
        <label class="advanced-field">
            <span>${escaparHTML(campo.label)}</span>
            <input id="${escaparHTML(id)}" type="${inputType}" ${attrs} ${campo.min !== undefined ? `min="${escaparHTML(campo.min)}"` : ''} ${campo.max !== undefined ? `max="${escaparHTML(campo.max)}"` : ''}>
            ${hint}
        </label>
    `;
}

function preencherCampoConfiguravel(elemento, campo, valor, canais = [], cargos = []) {
    if (!elemento || !campo) return;

    if (campo.type === 'channel') {
        preencherChannelSelect(elemento, canais, valor);
        return;
    }

    if (campo.type === 'channel-multi') {
        preencherChannelSelect(elemento, canais, Array.isArray(valor) ? valor : textoParaLista(valor), false);
        return;
    }

    if (campo.type === 'role') {
        preencherRoleSelect(elemento, cargos, valor);
        return;
    }

    if (campo.type === 'textarea-list') {
        elemento.value = listaParaTexto(valor);
        return;
    }

    elemento.value = valor ?? '';
}

function coletarValorCampoConfiguravel(elemento) {
    const tipo = elemento.dataset.fieldType;

    if (tipo === 'channel-multi') {
        return Array.from(elemento.selectedOptions).map((option) => option.value).filter(Boolean);
    }

    if (tipo === 'textarea-list') {
        return textoParaLista(elemento.value);
    }

    if (tipo === 'number') {
        return Number.parseInt(elemento.value || '0', 10);
    }

    return elemento.value;
}

function AuditSummary() {
    const auditoria = normalizarAuditoriaLocal(moderacaoAtual.auditoria);
    const totalAtivos = auditoria.events.filter((evento) => evento.enabled).length;
    const canais = new Set([
        auditoria.defaultChannelId,
        ...auditoria.events.map((evento) => evento.channelId)
    ].filter(Boolean));
    const canalPadrao = auditoria.defaultChannelName || (auditoria.defaultChannelId ? `#${auditoria.defaultChannelId}` : 'Nao definido');

    return `
        <div class="summary-grid">
            <article><span>Status da auditoria</span><strong>${auditoria.enabled ? 'Ativada' : 'Desativada'}</strong></article>
            <article><span>Canal padrão</span><strong>${escaparHTML(canalPadrao)}</strong></article>
            <article><span>Eventos ativos</span><strong>${totalAtivos}</strong></article>
            <article><span>Último evento enviado</span><strong>${escaparHTML(auditoria.lastEvent || 'Nenhum evento enviado')}</strong></article>
            <article><span>Canais configurados</span><strong>${canais.size}</strong></article>
        </div>
    `;
}

function AuditEventConfig(evento) {
    const estado = obterSetting(moderacaoAtual.auditoria?.events, evento.id);
    const detalhes = evento.logDetails?.length
        ? `<ul class="audit-detail-list">${evento.logDetails.map((item) => `<li>${escaparHTML(item)}</li>`).join('')}</ul>`
        : '';

    return `
        <article class="audit-event-card" data-audit-event-card="${escaparHTML(evento.id)}">
            <div class="audit-event-main">
                <div>
                    <strong>${escaparHTML(evento.title)}</strong>
                    <p>${escaparHTML(evento.description)}</p>
                </div>
                ${ToggleSwitch(`audit_event_enabled_${evento.id}`, estado.enabled ?? evento.enabled, `data-audit-event-enabled data-event-id="${escaparHTML(evento.id)}"`)}
            </div>
            ${detalhes}
            <label class="advanced-field">
                <span>Canal do evento</span>
                ${ChannelSelect({ id: `audit_event_channel_${evento.id}`, attrs: `data-audit-event-channel data-event-id="${escaparHTML(evento.id)}"` })}
            </label>
        </article>
    `;
}

function AuditEventGroup(grupo) {
    const eventos = auditEvents.filter((evento) => evento.group === grupo.id);
    const regraVoz = grupo.id === 'voz'
        ? '<p class="audit-group-note">Todos os logs de voz devem mostrar responsável quando existir, usuário afetado, canal envolvido, data, horário e IDs relevantes.</p>'
        : '';

    return `
        <section class="audit-event-group">
            <div class="advanced-section-heading">
                <i class="ph ${escaparHTML(grupo.icon)}"></i>
                <div>
                    <strong>${escaparHTML(grupo.title)}</strong>
                    ${regraVoz}
                </div>
            </div>
            <div class="audit-event-grid">
                ${eventos.map(AuditEventConfig).join('')}
            </div>
        </section>
    `;
}

function AuditHistory() {
    const historico = normalizarAuditoriaLocal(moderacaoAtual.auditoria).history;

    if (!historico.length) {
        return '<div class="advanced-empty">Nenhum log enviado ainda.</div>';
    }

    return `
        <div class="audit-history-list">
            ${historico.map((item) => `
                <article class="audit-history-item">
                    <strong>${escaparHTML(item.eventType || item.tipo || 'Evento')}</strong>
                    <span>${escaparHTML(item.channelName || item.channel || item.canal || 'Canal nao informado')}</span>
                    <span>${escaparHTML(item.responsibleUser || item.user || item.usuario || 'Responsavel nao informado')}</span>
                    <span>${escaparHTML(item.dateTime || item.dataHora || item.data || '--')}</span>
                    <em class="${String(item.status || '').toLowerCase() === 'falhou' ? 'failed' : 'sent'}">${escaparHTML(item.status || 'enviado')}</em>
                </article>
            `).join('')}
        </div>
    `;
}

function AuditPage(serverName) {
    return `
        <div class="vm-panel-heading">
            <span>Auditoria</span>
            <strong>${escaparHTML(serverName)}</strong>
        </div>
        <div class="advanced-config-page audit-page">
            <div id="audit-summary">${AuditSummary()}</div>
            <div class="advanced-control-grid">
                <article class="advanced-control-card">
                    <div>
                        <strong>Ativar auditoria</strong>
                        <span>Quando estiver desativada, nenhum log de auditoria sera enviado.</span>
                    </div>
                    ${ToggleSwitch('audit_enabled', moderacaoAtual.auditoria?.enabled, 'data-audit-enabled')}
                </article>
                <article class="advanced-control-card">
                    <div>
                        <strong>Canal padrão</strong>
                        <span>Usado apenas quando o evento nao tiver canal especifico.</span>
                    </div>
                    ${ChannelSelect({ id: 'audit_default_channel', attrs: 'data-audit-default-channel' })}
                </article>
            </div>
            ${auditGroups.map(AuditEventGroup).join('')}
            <section class="audit-event-group">
                <div class="advanced-section-heading">
                    <i class="ph ph-clock-counter-clockwise"></i>
                    <div>
                        <strong>Histórico simples</strong>
                        <p>Últimos logs enviados pelo bot.</p>
                    </div>
                </div>
                <div id="audit-history">${AuditHistory()}</div>
            </section>
            <button type="button" onclick="salvarAuditoriaServidor()" class="vm-save-button">
                <i class="ph ph-clipboard-text" id="mod-save-icon"></i>
                Salvar Auditoria
            </button>
            <div id="mod_status_msg" class="vm-status-message hidden"></div>
        </div>
    `;
}

function SecuritySummary() {
    const seguranca = normalizarSegurancaLocal(moderacaoAtual.seguranca);
    const antiRaid = seguranca.antiRaid;
    const ativar = obterSetting(antiRaid.settings, 'enableAntiRaid');
    const sensibilidade = obterSetting(antiRaid.settings, 'sensitivity')?.values?.level || 'Média';

    return `
        <div class="summary-grid">
            <article><span>Status do Anti Raid</span><strong>${ativar.enabled ? 'Ativado' : 'Desativado'}</strong></article>
            <article><span>Nível de proteção</span><strong>${escaparHTML(sensibilidade)}</strong></article>
            <article><span>Última ameaça</span><strong>${escaparHTML(antiRaid.lastThreat || 'Nenhuma ameaça detectada')}</strong></article>
            <article><span>Ações automáticas</span><strong>${antiRaid.totalAutomaticActions}</strong></article>
            <article><span>Links bloqueados</span><strong>${antiRaid.suspiciousLinksBlocked}</strong></article>
            <article><span>Usuários bloqueados</span><strong>${antiRaid.usersBlockedByRaid}</strong></article>
        </div>
    `;
}

function SecurityOptionCard(opcao) {
    const estado = obterSetting(moderacaoAtual.seguranca?.antiRaid?.settings, opcao.id);
    const campos = (opcao.fields || []).map((campo) => renderizarCampoConfiguravel('security', opcao.id, campo)).join('');
    const notas = Array.isArray(opcao.notes) && opcao.notes.length
        ? `<ul class="security-option-notes">${opcao.notes.map((nota) => `<li>${escaparHTML(nota)}</li>`).join('')}</ul>`
        : '';

    return `
        <article class="security-option-card">
            <div class="advanced-card-heading">
                <div>
                    <strong>${escaparHTML(opcao.title)}</strong>
                    <p>${escaparHTML(opcao.description)}</p>
                    ${notas}
                </div>
                ${ToggleSwitch(`security_enabled_${opcao.id}`, estado.enabled ?? opcao.enabled, `data-security-enabled data-option-id="${escaparHTML(opcao.id)}"`)}
            </div>
            ${campos ? `<div class="advanced-field-grid">${campos}</div>` : ''}
        </article>
    `;
}

function AntiRaidSettings() {
    return `
        <section class="advanced-section">
            <div class="advanced-section-heading">
                <i class="ph ph-shield-warning"></i>
                <div>
                    <strong>Configurações Anti Raid</strong>
                    <p>Cada função abaixo tem seu próprio controle.</p>
                </div>
            </div>
            <div class="security-option-grid">
                ${antiRaidSettings.map(SecurityOptionCard).join('')}
            </div>
        </section>
    `;
}

function SecurityPage(serverName) {
    return `
        <div class="vm-panel-heading">
            <span>Segurança / Anti Raid</span>
            <strong>${escaparHTML(serverName)}</strong>
        </div>
        <div class="advanced-config-page security-page">
            <div id="security-summary">${SecuritySummary()}</div>
            ${AntiRaidSettings()}
            <button type="button" onclick="salvarSegurancaServidor()" class="vm-save-button">
                <i class="ph ph-shield-warning" id="mod-save-icon"></i>
                Salvar Segurança
            </button>
            <div id="mod_status_msg" class="vm-status-message hidden"></div>
        </div>
    `;
}

function AutomationSummary() {
    const automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    const ativas = automacoes.options.filter((opcao) => opcao.enabled).length;
    const regrasAtivas = automacoes.autoResponses.filter((regra) => regra.enabled).length;
    const bloqueiosAtivos = automacoes.commandBlockRules.filter((regra) => regra.enabled).length;

    return `
        <div class="summary-grid">
            <article><span>Automações ativas</span><strong>${ativas}</strong></article>
            <article><span>Regras de auto resposta</span><strong>${automacoes.autoResponses.length}</strong></article>
            <article><span>Auto respostas ativas</span><strong>${regrasAtivas}</strong></article>
            <article><span>Bloqueios de comandos</span><strong>${bloqueiosAtivos}</strong></article>
            <article><span>Última execução</span><strong>${escaparHTML(automacoes.lastExecution || 'Nenhuma automação executada')}</strong></article>
        </div>
    `;
}

function AutomationRuleEditor() {
    return `
        <div class="automation-rule-editor">
            <div class="advanced-section-heading">
                <i class="ph ph-chat-circle-dots"></i>
                <div>
                    <strong>Auto resposta por palavra-chave</strong>
                    <p>O bot le mensagens novas e responde quando o texto combinar com uma auto resposta ligada.</p>
                </div>
            </div>
            <div class="automation-rule-form">
                <label class="advanced-toggle-inline">
                    <span>
                        Ativar esta auto resposta
                        <small>Ligado: o bot responde. Desligado: a regra fica salva, mas nao funciona.</small>
                    </span>
                    ${ToggleSwitch('auto_response_rule_enabled', true)}
                </label>
                <label class="advanced-field">
                    <span>Palavra-chave</span>
                    <input id="auto_response_keyword" type="text" placeholder="arena">
                    <small>Texto que o bot vai procurar na mensagem do usuario.</small>
                </label>
                <label class="advanced-field advanced-field-wide">
                    <span>Resposta automática</span>
                    <textarea id="auto_response_response" rows="4" placeholder="A arena abre todos os dias às 20h! Use /arena para participar."></textarea>
                    <small>Mensagem que o bot envia quando a regra for acionada.</small>
                </label>
                <label class="advanced-field">
                    <span>Canal da regra</span>
                    ${ChannelSelect({ id: 'auto_response_channel' })}
                    <small>Vazio = funciona em todos os canais.</small>
                </label>
                <label class="advanced-field">
                    <span>Tipo de detecção</span>
                    <select id="auto_response_detection_type">
                        ${autoResponseDetectionTypes.map((tipo) => `<option value="${escaparHTML(tipo.value)}">${escaparHTML(tipo.label)}</option>`).join('')}
                    </select>
                    <small>Define como a palavra-chave sera comparada com a mensagem.</small>
                </label>
                <label class="advanced-field">
                    <span>Cooldown em segundos</span>
                    <input id="auto_response_cooldown" type="number" min="0" max="86400" value="30">
                    <small>Tempo minimo antes dessa regra responder de novo.</small>
                </label>
                <label class="advanced-field">
                    <span>Apagar resposta depois de</span>
                    <input id="auto_response_delete_after" type="number" min="0" max="86400" value="0">
                    <small>0 deixa a resposta no chat. Outro valor apaga apos esse tempo.</small>
                </label>
                <label class="advanced-toggle-inline">
                    <span>
                        Ignorar administradores/moderadores
                        <small>Administradores e moderadores nao acionam essa regra quando estiver ligado.</small>
                    </span>
                    ${ToggleSwitch('auto_response_ignore_staff', false)}
                </label>
                <div class="automation-rule-actions">
                    <button type="button" onclick="adicionarOuAtualizarRegraAutoResposta()" id="auto_response_save_rule">
                        <i class="ph ph-plus-circle"></i>
                        Adicionar nova regra
                    </button>
                    <button type="button" onclick="limparEditorAutoResposta()">
                        <i class="ph ph-eraser"></i>
                        Limpar
                    </button>
                </div>
            </div>
            <div class="automation-rule-list" id="auto-response-rules"></div>
        </div>
    `;
}

function CommandBlockRuleEditor() {
    return `
        <div class="automation-rule-editor">
            <div class="advanced-section-heading">
                <i class="ph ph-prohibit"></i>
                <div>
                    <strong>Regras de bloqueio</strong>
                    <p>Bloqueia comandos nos canais escolhidos; deixe comandos vazio para bloquear todos.</p>
                </div>
            </div>
            <div class="automation-rule-form">
                <label class="advanced-toggle-inline">
                    <span>
                        Ativar este bloqueio
                        <small>Ligado: comandos sao bloqueados nesses canais. Desligado: fica salvo, mas nao bloqueia.</small>
                    </span>
                    ${ToggleSwitch('command_block_rule_enabled', true)}
                </label>
                <label class="advanced-field advanced-field-wide">
                    <span>Canais bloqueados</span>
                    ${ChannelSelect({ id: 'command_block_channels', multiple: true })}
                    <small>Selecione um ou mais canais onde comandos devem ser bloqueados.</small>
                </label>
                <label class="advanced-field advanced-field-wide">
                    <span>Comandos bloqueados</span>
                    <textarea id="command_block_commands" rows="4" placeholder="/play&#10;/limpar&#10;Deixe vazio para bloquear todos os comandos nesses canais."></textarea>
                    <small>Um comando por linha. Vazio = todos os comandos nesses canais.</small>
                </label>
                <div class="automation-rule-actions">
                    <button type="button" onclick="adicionarOuAtualizarBloqueioComando()" id="command_block_save_rule">
                        <i class="ph ph-plus-circle"></i>
                        Adicionar bloqueio
                    </button>
                    <button type="button" onclick="limparEditorBloqueioComando()">
                        <i class="ph ph-eraser"></i>
                        Limpar
                    </button>
                </div>
            </div>
            <div class="automation-rule-list" id="command-block-rules"></div>
        </div>
    `;
}

function AutomationOptionCard(opcao) {
    const estado = obterSetting(moderacaoAtual.automacoes?.options, opcao.id);
    const campos = (opcao.fields || []).map((campo) => renderizarCampoConfiguravel('automation', opcao.id, campo)).join('');
    const cardAberto = ['auto-response', 'command-block'].includes(opcao.type);
    const notas = Array.isArray(opcao.notes) && opcao.notes.length
        ? `<ul class="automation-option-notes">${opcao.notes.map((nota) => `<li>${escaparHTML(nota)}</li>`).join('')}</ul>`
        : '';

    return `
        <article class="automation-option-card ${cardAberto ? 'automation-option-card-wide' : ''}">
            <div class="advanced-card-heading">
                <div>
                    <strong>${escaparHTML(opcao.title)}</strong>
                    <p>${escaparHTML(opcao.description)}</p>
                    ${notas}
                </div>
                ${ToggleSwitch(`automation_enabled_${opcao.id}`, estado.enabled ?? opcao.enabled, `data-automation-enabled data-option-id="${escaparHTML(opcao.id)}"`)}
            </div>
            ${opcao.type === 'auto-response' ? AutomationRuleEditor() : ''}
            ${opcao.type === 'command-block' ? CommandBlockRuleEditor() : ''}
            ${campos ? `<div class="advanced-field-grid">${campos}</div>` : ''}
        </article>
    `;
}

function AutomationsPage(serverName) {
    return `
        <div class="vm-panel-heading">
            <span>Automações</span>
            <strong>${escaparHTML(serverName)}</strong>
        </div>
        <div class="advanced-config-page automations-page">
            <div id="automation-summary">${AutomationSummary()}</div>
            <section class="advanced-section">
                <div class="advanced-section-heading">
                    <i class="ph ph-lightning"></i>
                    <div>
                        <strong>Ações automáticas do servidor</strong>
                        <p>Cada card controla uma automacao separada. Ative, preencha os campos e salve.</p>
                    </div>
                </div>
                <div class="automation-option-grid">
                    ${automationSettings.map(AutomationOptionCard).join('')}
                </div>
            </section>
            <button type="button" onclick="salvarAutomacoesServidor()" class="vm-save-button">
                <i class="ph ph-lightning" id="mod-save-icon"></i>
                Salvar Automações
            </button>
            <div id="mod_status_msg" class="vm-status-message hidden"></div>
        </div>
    `;
}

function preencherCamposAuditoria(canais = []) {
    moderacaoAtual.auditoria = normalizarAuditoriaLocal(moderacaoAtual.auditoria);
    const auditoria = moderacaoAtual.auditoria;

    const resumo = document.getElementById('audit-summary');
    if (resumo) resumo.innerHTML = AuditSummary();

    const toggle = document.getElementById('audit_enabled');
    if (toggle) toggle.checked = auditoria.enabled;

    preencherChannelSelect(document.getElementById('audit_default_channel'), canais, auditoria.defaultChannelId);

    auditoria.events.forEach((evento) => {
        const eventoToggle = document.getElementById(`audit_event_enabled_${evento.id}`);
        if (eventoToggle) eventoToggle.checked = evento.enabled;
        preencherChannelSelect(document.getElementById(`audit_event_channel_${evento.id}`), canais, evento.channelId);
    });

    const historico = document.getElementById('audit-history');
    if (historico) historico.innerHTML = AuditHistory();
}

function coletarCamposAuditoria() {
    moderacaoAtual.auditoria = normalizarAuditoriaLocal(moderacaoAtual.auditoria);
    const auditoria = moderacaoAtual.auditoria;
    const canalPadrao = document.getElementById('audit_default_channel');

    auditoria.enabled = Boolean(document.getElementById('audit_enabled')?.checked);
    auditoria.defaultChannelId = canalPadrao?.value || '';
    auditoria.defaultChannelName = obterNomeSelecionado(canalPadrao, 'channelName');

    auditoria.events = auditoria.events.map((evento) => {
        const select = document.getElementById(`audit_event_channel_${evento.id}`);
        return {
            ...evento,
            enabled: Boolean(document.getElementById(`audit_event_enabled_${evento.id}`)?.checked),
            channelId: select?.value || '',
            channelName: obterNomeSelecionado(select, 'channelName')
        };
    });
}

function preencherCamposSeguranca(canais = []) {
    moderacaoAtual.seguranca = normalizarSegurancaLocal(moderacaoAtual.seguranca);
    const resumo = document.getElementById('security-summary');
    if (resumo) resumo.innerHTML = SecuritySummary();

    antiRaidSettings.forEach((opcao) => {
        const estado = obterSetting(moderacaoAtual.seguranca.antiRaid.settings, opcao.id);
        const toggle = document.getElementById(`security_enabled_${opcao.id}`);
        if (toggle) toggle.checked = estado.enabled;

        (opcao.fields || []).forEach((campo) => {
            const elemento = document.getElementById(`security_${opcao.id}_${campo.id}`);
            preencherCampoConfiguravel(elemento, campo, valorCampoConfig(estado, campo), canais);
        });
    });
}

function coletarCamposSeguranca() {
    moderacaoAtual.seguranca = normalizarSegurancaLocal(moderacaoAtual.seguranca);
    moderacaoAtual.seguranca.antiRaid.settings = moderacaoAtual.seguranca.antiRaid.settings.map((estado) => {
        const base = antiRaidSettings.find((opcao) => opcao.id === estado.id) || {};
        const novo = {
            ...estado,
            enabled: Boolean(document.getElementById(`security_enabled_${estado.id}`)?.checked),
            values: { ...(estado.values || {}) }
        };

        (base.fields || []).forEach((campo) => {
            const elemento = document.getElementById(`security_${estado.id}_${campo.id}`);
            if (!elemento) return;
            novo.values[campo.id] = coletarValorCampoConfiguravel(elemento);
            if (campo.type === 'channel') novo.values[`${campo.id}Name`] = obterNomeSelecionado(elemento, 'channelName');
        });

        return novo;
    });
}

function preencherCamposAutomacoes(canais = [], cargos = []) {
    moderacaoAtual.automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    moderacaoRecursosAtual = { canais, cargos };
    const resumo = document.getElementById('automation-summary');
    if (resumo) resumo.innerHTML = AutomationSummary();

    automationSettings.forEach((opcao) => {
        const estado = obterSetting(moderacaoAtual.automacoes.options, opcao.id);
        const toggle = document.getElementById(`automation_enabled_${opcao.id}`);
        if (toggle) toggle.checked = estado.enabled;

        (opcao.fields || []).forEach((campo) => {
            const elemento = document.getElementById(`automation_${opcao.id}_${campo.id}`);
            preencherCampoConfiguravel(elemento, campo, valorCampoConfig(estado, campo), canais, cargos);
        });
    });

    preencherChannelSelect(document.getElementById('auto_response_channel'), canais, '');
    preencherChannelSelect(document.getElementById('command_block_channels'), canais, [], false);
    renderizarListaAutoRespostas();
    renderizarListaBloqueiosComando();
}

function preencherPainelConfiguracaoAtual(secao, canais = [], cargos = []) {
    if (secao === 'audit') {
        preencherCamposAuditoria(canais);
        return;
    }

    if (secao === 'security') {
        preencherCamposSeguranca(canais);
        return;
    }

    if (secao === 'automations') {
        preencherCamposAutomacoes(canais, cargos);
        return;
    }

    preencherCamposModeracao(canais);
}

function preservarCamposDashboardAtual() {
    const painelSecao = document.getElementById('dashboard-section-panel');
    if (!painelSecao) return;
    if (painelSecao.dataset.serverId && painelSecao.dataset.serverId !== moderacaoServidorCarregadoId) return;

    try {
        if (painelSecao.querySelector('.audit-page')) {
            coletarCamposAuditoria();
            return;
        }

        if (painelSecao.querySelector('.security-page')) {
            coletarCamposSeguranca();
            return;
        }

        if (painelSecao.querySelector('.automations-page')) {
            coletarCamposAutomacoes();
            return;
        }

        if (painelSecao.querySelector('.mod-config-panel')) {
            coletarCamposModeracao();
        }
    } catch (erro) {
        console.warn('Nao consegui preservar os campos atuais:', erro);
    }
}

function coletarCamposAutomacoes() {
    moderacaoAtual.automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    moderacaoAtual.automacoes.options = moderacaoAtual.automacoes.options.map((estado) => {
        const base = automationSettings.find((opcao) => opcao.id === estado.id) || {};
        const novo = {
            ...estado,
            enabled: Boolean(document.getElementById(`automation_enabled_${estado.id}`)?.checked),
            values: { ...(estado.values || {}) }
        };

        (base.fields || []).forEach((campo) => {
            const elemento = document.getElementById(`automation_${estado.id}_${campo.id}`);
            if (!elemento) return;
            novo.values[campo.id] = coletarValorCampoConfiguravel(elemento);
            if (campo.type === 'channel') novo.values[`${campo.id}Name`] = obterNomeSelecionado(elemento, 'channelName');
            if (campo.type === 'role') novo.values[`${campo.id}Name`] = obterNomeSelecionado(elemento, 'roleName');
        });

        return novo;
    });
}

function renderizarListaAutoRespostas() {
    const lista = document.getElementById('auto-response-rules');
    if (!lista) return;

    const regras = normalizarAutomacoesLocal(moderacaoAtual.automacoes).autoResponses;
    moderacaoAtual.automacoes.autoResponses = regras;

    if (!regras.length) {
        lista.innerHTML = '<div class="advanced-empty">Nenhuma regra de auto resposta cadastrada.</div>';
        return;
    }

    lista.innerHTML = regras.map((regra) => {
        const tipo = autoResponseDetectionTypes.find((item) => item.value === regra.detectionType)?.label || 'Contem a palavra';
        return `
            <article class="automation-rule-item">
                <div>
                    <strong>${escaparHTML(regra.keyword || 'Sem palavra-chave')}</strong>
                    <span>${escaparHTML(tipo)} · ${escaparHTML(regra.channelName || regra.channelId || 'Todos os canais')}</span>
                    <p>${escaparHTML(regra.response || 'Sem resposta configurada')}</p>
                </div>
                <em class="${regra.enabled ? 'sent' : 'failed'}">${regra.enabled ? 'ON' : 'OFF'}</em>
                <button type="button" onclick="editarRegraAutoResposta('${escaparHTML(regra.id)}')">
                    <i class="ph ph-pencil-simple"></i>
                    Editar
                </button>
                <button type="button" onclick="excluirRegraAutoResposta('${escaparHTML(regra.id)}')">
                    <i class="ph ph-trash"></i>
                    Excluir
                </button>
            </article>
        `;
    }).join('');
}

function obterDadosEditorAutoResposta() {
    const canal = document.getElementById('auto_response_channel');
    return {
        id: automacaoRegraEditandoId || `auto-response-${Date.now()}`,
        enabled: Boolean(document.getElementById('auto_response_rule_enabled')?.checked),
        keyword: document.getElementById('auto_response_keyword')?.value.trim() || '',
        response: document.getElementById('auto_response_response')?.value.trim() || '',
        channelId: canal?.value || '',
        channelName: obterNomeSelecionado(canal, 'channelName'),
        detectionType: document.getElementById('auto_response_detection_type')?.value || 'contains',
        cooldownSeconds: Number.parseInt(document.getElementById('auto_response_cooldown')?.value || '0', 10) || 0,
        ignoreStaff: Boolean(document.getElementById('auto_response_ignore_staff')?.checked),
        deleteAfterSeconds: Number.parseInt(document.getElementById('auto_response_delete_after')?.value || '0', 10) || 0
    };
}

function adicionarOuAtualizarRegraAutoResposta() {
    moderacaoAtual.automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    const regra = obterDadosEditorAutoResposta();

    if (!regra.keyword || !regra.response) {
        mostrarStatusModeracao('Informe palavra-chave e resposta para criar a regra.');
        return;
    }

    const indice = moderacaoAtual.automacoes.autoResponses.findIndex((item) => item.id === regra.id);
    if (indice >= 0) {
        moderacaoAtual.automacoes.autoResponses[indice] = regra;
    } else {
        moderacaoAtual.automacoes.autoResponses.push(regra);
    }

    ativarOpcaoAutomacao('autoResponse');

    automacaoRegraEditandoId = '';
    limparEditorAutoResposta(false);
    renderizarListaAutoRespostas();
    const resumo = document.getElementById('automation-summary');
    if (resumo) resumo.innerHTML = AutomationSummary();
    mostrarStatusModeracao('Regra pronta. Salve as automações para sincronizar.', 'success');
    marcarConfiguracaoAlterada('automations');
}

function editarRegraAutoResposta(id) {
    const regra = normalizarAutomacoesLocal(moderacaoAtual.automacoes).autoResponses.find((item) => item.id === id);
    if (!regra) return;

    automacaoRegraEditandoId = regra.id;
    const canal = document.getElementById('auto_response_channel');
    document.getElementById('auto_response_rule_enabled').checked = regra.enabled;
    document.getElementById('auto_response_keyword').value = regra.keyword;
    document.getElementById('auto_response_response').value = regra.response;
    if (canal) canal.value = regra.channelId || '';
    document.getElementById('auto_response_detection_type').value = regra.detectionType || 'contains';
    document.getElementById('auto_response_cooldown').value = regra.cooldownSeconds ?? 0;
    document.getElementById('auto_response_ignore_staff').checked = regra.ignoreStaff;
    document.getElementById('auto_response_delete_after').value = regra.deleteAfterSeconds ?? 0;
    const botao = document.getElementById('auto_response_save_rule');
    if (botao) botao.innerHTML = '<i class="ph ph-check-circle"></i> Salvar edição';
}

function excluirRegraAutoResposta(id) {
    if (!window.confirm('Excluir esta regra de auto resposta?')) return;
    moderacaoAtual.automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    moderacaoAtual.automacoes.autoResponses = moderacaoAtual.automacoes.autoResponses.filter((regra) => regra.id !== id);
    if (automacaoRegraEditandoId === id) automacaoRegraEditandoId = '';
    limparEditorAutoResposta(false);
    renderizarListaAutoRespostas();
    const resumo = document.getElementById('automation-summary');
    if (resumo) resumo.innerHTML = AutomationSummary();
    marcarConfiguracaoAlterada('automations');
}

function limparEditorAutoResposta(limparStatus = true) {
    automacaoRegraEditandoId = '';
    const campos = {
        auto_response_rule_enabled: true,
        auto_response_keyword: '',
        auto_response_response: '',
        auto_response_detection_type: 'contains',
        auto_response_cooldown: 30,
        auto_response_ignore_staff: false,
        auto_response_delete_after: 0
    };

    Object.entries(campos).forEach(([id, valor]) => {
        const elemento = document.getElementById(id);
        if (!elemento) return;
        if (elemento.type === 'checkbox') elemento.checked = Boolean(valor);
        else elemento.value = valor;
    });

    const canal = document.getElementById('auto_response_channel');
    if (canal) canal.value = '';

    const botao = document.getElementById('auto_response_save_rule');
    if (botao) botao.innerHTML = '<i class="ph ph-plus-circle"></i> Adicionar nova regra';
    if (limparStatus) mostrarStatusModeracao('Editor limpo.', 'success');
}

function ativarOpcaoAutomacao(opcaoId) {
    const toggle = document.getElementById(`automation_enabled_${opcaoId}`);
    if (toggle) toggle.checked = true;

    moderacaoAtual.automacoes.options = moderacaoAtual.automacoes.options.map((opcao) => (
        opcao.id === opcaoId ? { ...opcao, enabled: true } : opcao
    ));
}

function renderizarListaBloqueiosComando() {
    const lista = document.getElementById('command-block-rules');
    if (!lista) return;

    const regras = normalizarAutomacoesLocal(moderacaoAtual.automacoes).commandBlockRules;
    moderacaoAtual.automacoes.commandBlockRules = regras;

    if (!regras.length) {
        lista.innerHTML = '<div class="advanced-empty">Nenhum bloqueio de comandos cadastrado.</div>';
        return;
    }

    lista.innerHTML = regras.map((regra) => {
        const canais = regra.channelNames.length ? regra.channelNames.join(', ') : `${regra.channelIds.length} canal(is)`;
        const comandos = regra.commands.length ? regra.commands.join(', ') : 'Todos os comandos';

        return `
            <article class="automation-rule-item">
                <div>
                    <strong>${escaparHTML(canais || 'Sem canal')}</strong>
                    <span>${escaparHTML(comandos)}</span>
                </div>
                <em class="${regra.enabled ? 'sent' : 'failed'}">${regra.enabled ? 'ON' : 'OFF'}</em>
                <button type="button" onclick="editarBloqueioComando('${escaparHTML(regra.id)}')">
                    <i class="ph ph-pencil-simple"></i>
                    Editar
                </button>
                <button type="button" onclick="excluirBloqueioComando('${escaparHTML(regra.id)}')">
                    <i class="ph ph-trash"></i>
                    Excluir
                </button>
            </article>
        `;
    }).join('');
}

function obterDadosEditorBloqueioComando() {
    const canais = document.getElementById('command_block_channels');
    return {
        id: comandoBloqueioEditandoId || `command-block-${Date.now()}`,
        enabled: Boolean(document.getElementById('command_block_rule_enabled')?.checked),
        channelIds: Array.from(canais?.selectedOptions || []).map((option) => option.value).filter(Boolean),
        channelNames: obterNomesSelecionados(canais, 'channelName'),
        commands: textoParaLista(document.getElementById('command_block_commands')?.value || '')
    };
}

function adicionarOuAtualizarBloqueioComando() {
    moderacaoAtual.automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    const regra = obterDadosEditorBloqueioComando();

    if (!regra.channelIds.length) {
        mostrarStatusModeracao('Selecione pelo menos um canal para criar o bloqueio.');
        return;
    }

    const indice = moderacaoAtual.automacoes.commandBlockRules.findIndex((item) => item.id === regra.id);
    if (indice >= 0) {
        moderacaoAtual.automacoes.commandBlockRules[indice] = regra;
    } else {
        moderacaoAtual.automacoes.commandBlockRules.push(regra);
    }

    ativarOpcaoAutomacao('commandChannelBlock');
    comandoBloqueioEditandoId = '';
    limparEditorBloqueioComando(false);
    renderizarListaBloqueiosComando();
    const resumo = document.getElementById('automation-summary');
    if (resumo) resumo.innerHTML = AutomationSummary();
    mostrarStatusModeracao('Bloqueio pronto. Salve as automacoes para sincronizar.', 'success');
    marcarConfiguracaoAlterada('automations');
}

function editarBloqueioComando(id) {
    const regra = normalizarAutomacoesLocal(moderacaoAtual.automacoes).commandBlockRules.find((item) => item.id === id);
    if (!regra) return;

    comandoBloqueioEditandoId = regra.id;
    document.getElementById('command_block_rule_enabled').checked = regra.enabled;
    preencherChannelSelect(document.getElementById('command_block_channels'), moderacaoRecursosAtual.canais, regra.channelIds, false);
    document.getElementById('command_block_commands').value = listaParaTexto(regra.commands);

    const botao = document.getElementById('command_block_save_rule');
    if (botao) botao.innerHTML = '<i class="ph ph-check-circle"></i> Salvar ediÃ§Ã£o';
}

function excluirBloqueioComando(id) {
    if (!window.confirm('Excluir este bloqueio de comandos?')) return;
    moderacaoAtual.automacoes = normalizarAutomacoesLocal(moderacaoAtual.automacoes);
    moderacaoAtual.automacoes.commandBlockRules = moderacaoAtual.automacoes.commandBlockRules.filter((regra) => regra.id !== id);
    if (comandoBloqueioEditandoId === id) comandoBloqueioEditandoId = '';
    limparEditorBloqueioComando(false);
    renderizarListaBloqueiosComando();
    const resumo = document.getElementById('automation-summary');
    if (resumo) resumo.innerHTML = AutomationSummary();
    marcarConfiguracaoAlterada('automations');
}

function limparEditorBloqueioComando(limparStatus = true) {
    comandoBloqueioEditandoId = '';

    const ativo = document.getElementById('command_block_rule_enabled');
    if (ativo) ativo.checked = true;

    preencherChannelSelect(document.getElementById('command_block_channels'), moderacaoRecursosAtual.canais, [], false);

    const comandos = document.getElementById('command_block_commands');
    if (comandos) comandos.value = '';

    const botao = document.getElementById('command_block_save_rule');
    if (botao) botao.innerHTML = '<i class="ph ph-plus-circle"></i> Adicionar bloqueio';
    if (limparStatus) mostrarStatusModeracao('Editor limpo.', 'success');
}

async function salvarAuditoriaServidor() {
    coletarCamposAuditoria();
    return salvarModeracaoServidor();
}

async function salvarSegurancaServidor() {
    coletarCamposSeguranca();
    return salvarModeracaoServidor();
}

async function salvarAutomacoesServidor() {
    coletarCamposAutomacoes();
    return salvarModeracaoServidor();
}

function renderizarCampoModeracao(campo) {
    const id = idCampoModeracao(campo.path);
    const attrs = `id="${id}" data-mod-field data-path="${escaparHTML(campo.path)}" data-type="${escaparHTML(campo.type)}"${campo.namePath ? ` data-name-path="${escaparHTML(campo.namePath)}"` : ''}${campo.emptyLabel ? ` data-empty-label="${escaparHTML(campo.emptyLabel)}"` : ''}`;
    const hint = campo.hint ? `<small>${escaparHTML(campo.hint)}</small>` : '';

    if (campo.type === 'log-event') {
        const toggleAttrs = `id="${idCampoModeracao(campo.path)}" data-mod-field data-path="${escaparHTML(campo.path)}" data-type="toggle"`;
        const selectAttrs = `id="${idCampoModeracao(campo.channelPath)}" data-mod-field data-path="${escaparHTML(campo.channelPath)}" data-type="channel" data-name-path="${escaparHTML(campo.namePath)}" data-empty-label="${escaparHTML(campo.emptyLabel || 'Usar canal padrao')}"`;

        return `
            <article class="mod-field mod-log-event-card">
                <div class="mod-log-event-head">
                    <span>
                        <strong>Logar ${escaparHTML(campo.label)}</strong>
                        ${hint}
                    </span>
                    <input type="checkbox" ${toggleAttrs}>
                </div>
                <label class="mod-log-event-channel">
                    <span>Canal deste log</span>
                    <select ${selectAttrs}>
                        <option value="">Carregando canais...</option>
                    </select>
                </label>
            </article>
        `;
    }

    if (campo.type === 'toggle') {
        return `
            <label class="mod-toggle-row">
                <span>
                    <strong>${escaparHTML(campo.label)}</strong>
                    ${hint}
                </span>
                <input type="checkbox" ${attrs}>
            </label>
        `;
    }

    if (campo.type === 'textarea-list') {
        return `
            <label class="mod-field mod-field-wide">
                <span>${escaparHTML(campo.label)}</span>
                <textarea rows="6" ${attrs} placeholder="Um item por linha"></textarea>
                ${hint}
            </label>
        `;
    }

    if (campo.type === 'channel') {
        return `
            <label class="mod-field">
                <span>${escaparHTML(campo.label)}</span>
                <select ${attrs}>
                    <option value="">Carregando canais...</option>
                </select>
                ${hint}
            </label>
        `;
    }

    const inputType = campo.type === 'color' ? 'color' : campo.type === 'number' ? 'number' : 'text';
    const limites = campo.type === 'number' ? ` min="${campo.min || 0}" max="${campo.max || 999999}"` : '';

    return `
        <label class="mod-field">
            <span>${escaparHTML(campo.label)}</span>
            <input type="${inputType}" ${attrs}${limites} ${campo.disabled ? 'disabled' : ''}>
            ${hint}
        </label>
    `;
}

function renderizarPainelModeracao(secao, serverName) {
    const modulo = MODERACAO_SECTIONS[secao] || MODERACAO_SECTIONS.role;

    return `
        <div class="vm-panel-heading">
            <span>${escaparHTML(modulo.label)}</span>
            <strong>${escaparHTML(serverName)}</strong>
        </div>
        <div class="mod-config-panel" data-mod-section="${escaparHTML(secao)}">
            <div class="mod-intro">
                <strong>${escaparHTML(modulo.label)}</strong>
                <span>${escaparHTML(modulo.description)}</span>
            </div>
            <div class="mod-field-grid">
                ${modulo.fields.map(renderizarCampoModeracao).join('')}
            </div>
            <button type="button" onclick="salvarModeracaoServidor()" class="vm-save-button">
                <i class="ph ph-shield-check" id="mod-save-icon"></i>
                Salvar Configuracao
            </button>
            <div id="mod_status_msg" class="vm-status-message hidden"></div>
        </div>
    `;
}

function mostrarStatusModeracao(mensagem, tipo = 'error') {
    const statusMsg = document.getElementById('mod_status_msg');
    if (!statusMsg) return;
    statusMsg.innerText = mensagem;
    statusMsg.className = `vm-status-message ${tipo}`;
}

function preencherCamposModeracao(canais = []) {
    document.querySelectorAll('[data-mod-field]').forEach((campo) => {
        const path = campo.dataset.path;
        const tipo = campo.dataset.type;
        const valor = obterValorPath(moderacaoAtual, path);

        if (tipo === 'toggle') {
            campo.checked = Boolean(valor);
            return;
        }

        if (tipo === 'textarea-list') {
            campo.value = listaParaTexto(valor);
            return;
        }

        if (tipo === 'channel') {
            const rotuloVazio = campo.dataset.emptyLabel || 'Nao definido';
            campo.innerHTML = [
                `<option value="">${escaparHTML(rotuloVazio)}</option>`,
                ...canais.map((canal) => `<option value="${escaparHTML(canal.id)}" data-channel-name="${escaparHTML(canal.nome)}">#${escaparHTML(canal.nome)}${canal.categoria ? ` - ${escaparHTML(canal.categoria)}` : ''}</option>`)
            ].join('');
            campo.value = valor || '';
            return;
        }

        campo.value = valor ?? '';
    });
}

function coletarCamposModeracao() {
    document.querySelectorAll('[data-mod-field]').forEach((campo) => {
        const path = campo.dataset.path;
        const tipo = campo.dataset.type;
        let valor = campo.value;

        if (tipo === 'toggle') valor = campo.checked;
        if (tipo === 'number') valor = Number.parseInt(campo.value || '0', 10);
        if (tipo === 'textarea-list') valor = textoParaLista(campo.value);

        definirValorPath(moderacaoAtual, path, valor);

        if (tipo === 'channel' && campo.dataset.namePath) {
            const option = campo.options[campo.selectedIndex];
            definirValorPath(moderacaoAtual, campo.dataset.namePath, option?.dataset.channelName || '');
        }
    });
}

function chaveModeracaoDemo(serverId) {
    return `moderacao_demo_${serverId}`;
}

function chaveModeracaoCache(serverId) {
    return `moderacao_cache_${serverId}`;
}

function chaveModeracaoRecursosCache(serverId) {
    return `moderacao_recursos_cache_${serverId}`;
}

function obterModeracaoCache(serverId) {
    try {
        return JSON.parse(localStorage.getItem(chaveModeracaoCache(serverId)) || 'null');
    } catch {
        return null;
    }
}

function salvarModeracaoCache(serverId, config) {
    if (!serverId) return;
    localStorage.setItem(chaveModeracaoCache(serverId), JSON.stringify(normalizarModeracaoLocal(config)));
}

function obterRecursosModeracaoCache(serverId) {
    try {
        const recursos = JSON.parse(localStorage.getItem(chaveModeracaoRecursosCache(serverId)) || '{}');
        return {
            canais: Array.isArray(recursos.canais) ? recursos.canais : [],
            cargos: Array.isArray(recursos.cargos) ? recursos.cargos : []
        };
    } catch {
        return { canais: [], cargos: [] };
    }
}

function salvarRecursosModeracaoCache(serverId, canais = [], cargos = []) {
    if (!serverId) return;
    localStorage.setItem(chaveModeracaoRecursosCache(serverId), JSON.stringify({ canais, cargos }));
}

async function carregarModeracaoServidor(secao) {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverId = painelSecao?.dataset.serverId || '';
    const token = localStorage.getItem('discord_token');
    let canais = [];
    let cargos = [];

    if (!serverId) {
        mostrarStatusModeracao('Servidor nao identificado.');
        return;
    }

    if (moderacaoServidorCarregadoId === serverId) {
        preencherPainelConfiguracaoAtual(secao, moderacaoRecursosAtual.canais, moderacaoRecursosAtual.cargos);
        mostrarStatusModeracao('Configuracao pronta. Salve para sincronizar alteracoes.', 'success');
        return;
    }

    if (token === 'demo-token') {
        try {
            moderacaoAtual = normalizarModeracaoLocal(JSON.parse(localStorage.getItem(chaveModeracaoDemo(serverId)) || '{}'));
        } catch {
            moderacaoAtual = normalizarModeracaoLocal(clonarConfig(MODERACAO_PADRAO));
        }
        canais = obterCanaisDemo();
        cargos = obterCargosDemo();
        moderacaoServidorCarregadoId = serverId;
        moderacaoRecursosAtual = { canais, cargos };
        preencherPainelConfiguracaoAtual(secao, canais, cargos);
        mostrarStatusModeracao('Modo teste local. Configuracao salva no navegador.', 'success');
        return;
    }

    if (!token) {
        mostrarStatusModeracao('Sessao expirada. Entre novamente com o Discord.');
        return;
    }

    const configCache = obterModeracaoCache(serverId);
    const recursosCache = obterRecursosModeracaoCache(serverId);

    if (configCache) {
        moderacaoAtual = normalizarModeracaoLocal(configCache);
        moderacaoRecursosAtual = recursosCache;
        canais = recursosCache.canais;
        cargos = recursosCache.cargos;
        preencherPainelConfiguracaoAtual(secao, recursosCache.canais, recursosCache.cargos);
        mostrarStatusModeracao('Mostrando configuracao salva enquanto sincronizo com a API.', 'success');
    }

    try {
        const [responseConfig, responseCanais, responseCargos] = await Promise.all([
            fetch(`${API_URL}/api/config/${encodeURIComponent(serverId)}/moderacao`, {
                headers: { 'Authorization': `Bearer ${token}` }
            }),
            fetch(`${API_URL}/api/servidores/${encodeURIComponent(serverId)}/canais`, {
                headers: { 'Authorization': `Bearer ${token}` }
            }),
            fetch(`${API_URL}/api/servidores/${encodeURIComponent(serverId)}/cargos`, {
                headers: { 'Authorization': `Bearer ${token}` }
            })
        ]);
        const resultadoConfig = await lerJsonResposta(responseConfig);
        const resultadoCanais = await lerJsonResposta(responseCanais);
        const resultadoCargos = await lerJsonResposta(responseCargos);

        if (responseConfig.ok && resultadoConfig.status === 'sucesso') {
            moderacaoAtual = normalizarModeracaoLocal(resultadoConfig.moderacao || {});
            salvarModeracaoCache(serverId, moderacaoAtual);
        } else if (!configCache) {
            moderacaoAtual = normalizarModeracaoLocal(clonarConfig(MODERACAO_PADRAO));
        }

        if (responseCanais.ok && resultadoCanais.status === 'sucesso') {
            canais = resultadoCanais.canais || [];
        }

        if (responseCargos.ok && resultadoCargos.status === 'sucesso') {
            cargos = resultadoCargos.cargos || [];
        }

        moderacaoServidorCarregadoId = serverId;
        moderacaoRecursosAtual = { canais, cargos };
        salvarRecursosModeracaoCache(serverId, canais, cargos);
        preencherPainelConfiguracaoAtual(secao, canais, cargos);
        mostrarStatusModeracao('Configuracao carregada.', 'success');
    } catch (erro) {
        console.error('Erro ao carregar moderacao:', erro);
        if (configCache) {
            moderacaoServidorCarregadoId = serverId;
            mostrarStatusModeracao('API demorou. Mantive a ultima configuracao salva localmente.', 'success');
            return;
        }

        moderacaoAtual = normalizarModeracaoLocal(clonarConfig(MODERACAO_PADRAO));
        preencherPainelConfiguracaoAtual(secao, [], []);
        mostrarStatusModeracao('Erro ao conectar na API.');
    }
}

async function salvarModeracaoServidor() {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverId = painelSecao?.dataset.serverId || '';
    const serverName = painelSecao?.dataset.serverName || document.getElementById('nome-servidor-atual')?.innerText || '';
    const token = localStorage.getItem('discord_token');
    const icon = document.getElementById('mod-save-icon');
    const iconOriginalClass = icon?.className || '';

    coletarCamposModeracao();
    moderacaoAtual = normalizarModeracaoLocal(moderacaoAtual);
    moderacaoServidorCarregadoId = serverId;
    salvarModeracaoCache(serverId, moderacaoAtual);

    if (token === 'demo-token') {
        localStorage.setItem(chaveModeracaoDemo(serverId), JSON.stringify(moderacaoAtual));
        mostrarStatusModeracao('Configuracao salva no modo teste local.', 'success');
        finalizarSalvamentoConfiguracao();
        return true;
    }

    if (!token) {
        mostrarStatusModeracao('Sessao expirada. Entre novamente com o Discord.');
        return false;
    }

    try {
        if (icon) icon.className = 'ph ph-spinner-gap animate-spin';
        const response = await fetch(`${API_URL}/api/config/moderacao`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify({ id: serverId, nome: serverName, ...moderacaoAtual })
        });
        const resultado = await lerJsonResposta(response);

        if (response.ok && resultado.status === 'sucesso') {
            moderacaoAtual = normalizarModeracaoLocal(resultado.moderacao || moderacaoAtual);
            salvarModeracaoCache(serverId, moderacaoAtual);
            mostrarStatusModeracao('Moderacao salva e sincronizada com o bot.', 'success');
            finalizarSalvamentoConfiguracao();
            return true;
        }

        mostrarStatusModeracao(resultado.mensagem || resultado.erro || 'Nao foi possivel salvar a moderacao.');
        return false;
    } catch (erro) {
        console.error('Erro ao salvar moderacao:', erro);
        mostrarStatusModeracao('Erro ao conectar na API para salvar moderacao.');
        return false;
    } finally {
        if (icon && iconOriginalClass) icon.className = iconOriginalClass;
    }
}

function renderizarTemplateBoasVindas(tipo, titulo, descricao) {
    return `
        <section class="welcome-template-section">
            <div class="welcome-section-heading">
                <div>
                    <strong>${escaparHTML(titulo)}</strong>
                    <span>${escaparHTML(descricao)}</span>
                </div>
                <label class="welcome-avatar-toggle">
                    <input type="checkbox" id="welcome_${tipo}_mostrar_avatar" data-welcome-input>
                    <span>Avatar no embed</span>
                </label>
            </div>

            <div class="welcome-form-grid">
                <label>
                    <span>Texto antes do embed</span>
                    <input id="welcome_${tipo}_conteudo" data-welcome-input placeholder="{mention}">
                </label>
                <label>
                    <span>Cor do embed</span>
                    <input type="color" id="welcome_${tipo}_cor" data-welcome-input>
                </label>
                <label>
                    <span>Titulo</span>
                    <input id="welcome_${tipo}_titulo" data-welcome-input placeholder="Titulo do aviso">
                </label>
                <label>
                    <span>Imagem/GIF de fundo</span>
                    <input id="welcome_${tipo}_imagem_url" data-welcome-input placeholder="https://...">
                </label>
            </div>

            <label class="welcome-full-field">
                <span>Mensagem</span>
                <textarea id="welcome_${tipo}_mensagem" data-welcome-input rows="4" placeholder="Escreva a mensagem do aviso"></textarea>
            </label>
        </section>
    `;
}

function renderizarPainelBoasVindas(serverName) {
    return `
        <div class="vm-panel-heading">
            <span>Entrada e Saida</span>
            <strong>${escaparHTML(serverName)}</strong>
        </div>

        <div class="welcome-config-panel">
            <div class="welcome-intro">
                <div>
                    <strong>Boas-vindas e despedida</strong>
                    <span>Escolha canais, textos, cores e imagens/GIFs para quando membros entram ou saem.</span>
                </div>
                <div class="welcome-vars">
                    ${renderizarVariaveisBoasVindas()}
                </div>
                <button type="button" class="welcome-model-button" onclick="aplicarModeloBoasVindas()">
                    <i class="ph ph-sparkle"></i>
                    Aplicar modelo do print
                </button>
            </div>

            <div class="welcome-toggle-grid">
                <label class="welcome-toggle-row">
                    <span>
                        <strong>Aviso de entrada</strong>
                        <small>Envia quando alguem entra no servidor.</small>
                    </span>
                    <input type="checkbox" id="welcome_entrada_ativa" data-welcome-input>
                </label>
                <label class="welcome-toggle-row">
                    <span>
                        <strong>Aviso de saida</strong>
                        <small>Envia quando alguem sai do servidor.</small>
                    </span>
                    <input type="checkbox" id="welcome_saida_ativa" data-welcome-input>
                </label>
            </div>

            <div class="welcome-channel-grid">
                <label>
                    <span>Canal de entrada</span>
                    <select id="welcome_canal_entrada"></select>
                </label>
                <label>
                    <span>Canal de saida</span>
                    <select id="welcome_canal_saida"></select>
                </label>
            </div>

            ${renderizarTemplateBoasVindas('entrada', 'Mensagem de entrada', 'Use para receber novos membros com nome, mencao e imagem.')}
            ${renderizarTemplateBoasVindas('saida', 'Mensagem de saida', 'Use para avisar a equipe ou o chat quando alguem sair.')}

            <div class="welcome-preview-grid">
                <article class="welcome-preview" id="welcome_preview_entrada">
                    <span>Preview entrada</span>
                    <strong id="welcome_preview_entrada_titulo"></strong>
                    <p id="welcome_preview_entrada_mensagem"></p>
                    <small id="welcome_preview_entrada_conteudo"></small>
                </article>
                <article class="welcome-preview" id="welcome_preview_saida">
                    <span>Preview saida</span>
                    <strong id="welcome_preview_saida_titulo"></strong>
                    <p id="welcome_preview_saida_mensagem"></p>
                    <small id="welcome_preview_saida_conteudo"></small>
                </article>
            </div>

            <button type="button" onclick="salvarBoasVindas()" class="vm-save-button">
                <i class="ph ph-floppy-disk" id="welcome-save-icon"></i>
                Salvar avisos
            </button>

            <div id="welcome_status_msg" class="vm-status-message hidden"></div>
        </div>
    `;
}

function normalizarCorHex(cor, padrao) {
    const texto = String(cor || padrao || '#ffffff').trim();
    const valor = texto.startsWith('#') ? texto : `#${texto}`;

    return /^#[0-9a-fA-F]{6}$/.test(valor) ? valor.toLowerCase() : padrao;
}

function normalizarConfigBoasVindas(config = {}) {
    return {
        ...BOAS_VINDAS_PADRAO,
        ...config,
        entrada_cor: normalizarCorHex(config.entrada_cor, BOAS_VINDAS_PADRAO.entrada_cor),
        saida_cor: normalizarCorHex(config.saida_cor, BOAS_VINDAS_PADRAO.saida_cor)
    };
}

function obterChaveBoasVindasDemo(serverId) {
    return `boas_vindas_demo_${serverId}`;
}

function obterBoasVindasDemo(serverId) {
    try {
        return normalizarConfigBoasVindas(JSON.parse(localStorage.getItem(obterChaveBoasVindasDemo(serverId)) || '{}'));
    } catch (erro) {
        console.warn('Cache de boas-vindas invalido:', erro);
        return normalizarConfigBoasVindas();
    }
}

function salvarBoasVindasDemo(serverId, config) {
    const normalizada = normalizarConfigBoasVindas(config);
    localStorage.setItem(obterChaveBoasVindasDemo(serverId), JSON.stringify(normalizada));
    return normalizada;
}

function mostrarStatusBoasVindas(mensagem, tipo = 'error') {
    const statusMsg = document.getElementById('welcome_status_msg');
    if (!statusMsg) return;

    statusMsg.innerText = mensagem;
    statusMsg.className = `vm-status-message ${tipo}`;
}

function renderizarEstadoSelectBoasVindas(selectId, mensagem) {
    const select = document.getElementById(selectId);
    if (!select) return;

    select.disabled = true;
    select.innerHTML = `<option value="">${escaparHTML(mensagem)}</option>`;
}

function renderizarSelectBoasVindas(selectId, canais = [], selecionado = '') {
    const select = document.getElementById(selectId);
    if (!select) return;

    const selecionadoTexto = String(selecionado || '');
    select.disabled = !canais.length;

    if (!canais.length) {
        renderizarEstadoSelectBoasVindas(selectId, 'Nenhum canal de texto encontrado');
        return;
    }

    const existeSelecionado = canais.some((canal) => String(canal.id) === selecionadoTexto);
    const opcaoSalva = selecionadoTexto && !existeSelecionado
        ? `<option value="${escaparHTML(selecionadoTexto)}">Canal salvo nao encontrado</option>`
        : '';

    select.innerHTML = [
        '<option value="">Selecione um canal</option>',
        opcaoSalva,
        ...canais.map((canal) => `
            <option value="${escaparHTML(canal.id)}" data-channel-name="${escaparHTML(canal.nome)}">
                #${escaparHTML(canal.nome)}${canal.categoria ? ` - ${escaparHTML(canal.categoria)}` : ''}
            </option>
        `)
    ].join('');

    select.value = selecionadoTexto;
}

function preencherFormularioBoasVindas(config = {}) {
    const dados = normalizarConfigBoasVindas(config);
    const camposTexto = [
        'entrada_conteudo',
        'entrada_titulo',
        'entrada_mensagem',
        'entrada_imagem_url',
        'entrada_cor',
        'saida_conteudo',
        'saida_titulo',
        'saida_mensagem',
        'saida_imagem_url',
        'saida_cor'
    ];

    document.getElementById('welcome_entrada_ativa').checked = Boolean(dados.entrada_ativa);
    document.getElementById('welcome_saida_ativa').checked = Boolean(dados.saida_ativa);
    document.getElementById('welcome_entrada_mostrar_avatar').checked = Boolean(dados.entrada_mostrar_avatar);
    document.getElementById('welcome_saida_mostrar_avatar').checked = Boolean(dados.saida_mostrar_avatar);

    camposTexto.forEach((campo) => {
        const elemento = document.getElementById(`welcome_${campo}`);
        if (elemento) elemento.value = dados[campo] ?? '';
    });

    const canalEntrada = document.getElementById('welcome_canal_entrada');
    const canalSaida = document.getElementById('welcome_canal_saida');
    if (canalEntrada) canalEntrada.value = dados.canal_entrada_id || '';
    if (canalSaida) canalSaida.value = dados.canal_saida_id || '';

    atualizarPreviewBoasVindas();
}

function aplicarModeloBoasVindas() {
    const atual = obterDadosFormularioBoasVindas();

    preencherFormularioBoasVindas({
        ...atual,
        ...MODELO_AVISOS_AMZ,
        entrada_ativa: atual.entrada_ativa,
        saida_ativa: atual.saida_ativa,
        canal_entrada_id: atual.canal_entrada_id,
        canal_entrada_nome: atual.canal_entrada_nome,
        canal_saida_id: atual.canal_saida_id,
        canal_saida_nome: atual.canal_saida_nome
    });
    mostrarStatusBoasVindas('Modelo do print aplicado. Salve para enviar assim no Discord.', 'success');
    marcarConfiguracaoAlterada('server');
}

function formatarPreviewBoasVindas(texto) {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverName = painelSecao?.dataset.serverName || document.getElementById('nome-servidor-atual')?.innerText || 'Seu servidor';
    const valores = {
        '{mention}': '@Usuario',
        '{user}': 'Usuario',
        '{username}': 'usuario',
        '{user_tag}': 'usuario#0000',
        '{id}': '1234567890',
        '{server}': serverName,
        '{server_upper}': serverName.toUpperCase(),
        '{member_count}': '100',
        '{member_number}': '100',
        '{leave_action}': 'foi expulso',
        '{audit_action}': 'Expulsao',
        '{leave_reason}': 'Teste do painel',
        '{moderator}': 'Administrador',
        '{moderator_tag}': 'admin#0000'
    };

    return Object.entries(valores).reduce((resultado, [chave, valor]) => {
        return resultado.replaceAll(chave, valor);
    }, String(texto || ''));
}

function atualizarPreviewBoasVindas() {
    ['entrada', 'saida'].forEach((tipo) => {
        const preview = document.getElementById(`welcome_preview_${tipo}`);
        const titulo = document.getElementById(`welcome_preview_${tipo}_titulo`);
        const mensagem = document.getElementById(`welcome_preview_${tipo}_mensagem`);
        const conteudo = document.getElementById(`welcome_preview_${tipo}_conteudo`);
        const cor = document.getElementById(`welcome_${tipo}_cor`)?.value || BOAS_VINDAS_PADRAO[`${tipo}_cor`];

        if (preview) preview.style.borderColor = normalizarCorHex(cor, BOAS_VINDAS_PADRAO[`${tipo}_cor`]);
        if (titulo) titulo.innerText = formatarPreviewBoasVindas(document.getElementById(`welcome_${tipo}_titulo`)?.value);
        if (mensagem) mensagem.innerText = formatarPreviewBoasVindas(document.getElementById(`welcome_${tipo}_mensagem`)?.value);
        if (conteudo) conteudo.innerText = formatarPreviewBoasVindas(document.getElementById(`welcome_${tipo}_conteudo`)?.value);
    });
}

function conectarEventosBoasVindas() {
    document.querySelectorAll('[data-welcome-input]').forEach((elemento) => {
        elemento.addEventListener('input', atualizarPreviewBoasVindas);
        elemento.addEventListener('change', atualizarPreviewBoasVindas);
    });
}

async function carregarBoasVindasServidor() {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverId = painelSecao?.dataset.serverId || '';
    const token = localStorage.getItem('discord_token');
    let config = normalizarConfigBoasVindas();

    renderizarEstadoSelectBoasVindas('welcome_canal_entrada', 'Carregando canais...');
    renderizarEstadoSelectBoasVindas('welcome_canal_saida', 'Carregando canais...');
    preencherFormularioBoasVindas(config);
    conectarEventosBoasVindas();

    if (!serverId) {
        renderizarEstadoSelectBoasVindas('welcome_canal_entrada', 'Servidor nao identificado');
        renderizarEstadoSelectBoasVindas('welcome_canal_saida', 'Servidor nao identificado');
        mostrarStatusBoasVindas('Servidor nao identificado.');
        return;
    }

    if (token === 'demo-token') {
        const canais = obterCanaisDemo();
        config = obterBoasVindasDemo(serverId);
        renderizarSelectBoasVindas('welcome_canal_entrada', canais, config.canal_entrada_id);
        renderizarSelectBoasVindas('welcome_canal_saida', canais, config.canal_saida_id);
        preencherFormularioBoasVindas(config);
        mostrarStatusBoasVindas('Modo teste local. As mensagens nao sao enviadas no Discord.', 'success');
        return;
    }

    if (!token) {
        renderizarEstadoSelectBoasVindas('welcome_canal_entrada', 'Entre novamente com o Discord');
        renderizarEstadoSelectBoasVindas('welcome_canal_saida', 'Entre novamente com o Discord');
        mostrarStatusBoasVindas('Sessao expirada. Entre novamente com o Discord.');
        return;
    }

    try {
        const responseConfig = await fetch(`${API_URL}/api/config/${encodeURIComponent(serverId)}/boas-vindas`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultadoConfig = await lerJsonResposta(responseConfig);

        if (responseConfig.ok && resultadoConfig.status === 'sucesso') {
            config = normalizarConfigBoasVindas(resultadoConfig.boas_vindas || {});
        } else if (responseConfig.status === 401) {
            limparSessaoDiscord();
            mostrarStatusBoasVindas(resultadoConfig.mensagem || 'Sessao expirada. Entre novamente com o Discord.');
            return;
        } else if (responseConfig.status === 403) {
            mostrarStatusBoasVindas(resultadoConfig.mensagem || 'Acesso negado para este servidor.');
            return;
        } else {
            mostrarStatusBoasVindas(resultadoConfig.mensagem || resultadoConfig.erro || 'Nao foi possivel carregar os avisos.');
        }

        const responseCanais = await fetch(`${API_URL}/api/servidores/${encodeURIComponent(serverId)}/canais`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultadoCanais = await lerJsonResposta(responseCanais);

        if (responseCanais.ok && resultadoCanais.status === 'sucesso') {
            const canais = resultadoCanais.canais || [];
            renderizarSelectBoasVindas('welcome_canal_entrada', canais, config.canal_entrada_id);
            renderizarSelectBoasVindas('welcome_canal_saida', canais, config.canal_saida_id);
            preencherFormularioBoasVindas(config);
            if (!canais.length) {
                mostrarStatusBoasVindas('Nenhum canal de texto foi encontrado. Confira se o bot esta no servidor e consegue ver os canais.');
                return;
            }
            mostrarStatusBoasVindas('Avisos carregados.', 'success');
            return;
        }

        if (responseCanais.status === 401) {
            limparSessaoDiscord();
        }

        preencherFormularioBoasVindas(config);
        renderizarEstadoSelectBoasVindas('welcome_canal_entrada', 'Canais indisponiveis');
        renderizarEstadoSelectBoasVindas('welcome_canal_saida', 'Canais indisponiveis');
        mostrarStatusBoasVindas(resultadoCanais.mensagem || resultadoCanais.erro || 'Nao foi possivel carregar os canais.');
    } catch (erro) {
        console.error('Erro ao carregar avisos:', erro);
        renderizarEstadoSelectBoasVindas('welcome_canal_entrada', 'Erro ao carregar canais');
        renderizarEstadoSelectBoasVindas('welcome_canal_saida', 'Erro ao carregar canais');
        mostrarStatusBoasVindas('Erro ao conectar na API para carregar os avisos.');
    }
}

function opcaoSelecionadaBoasVindas(selectId) {
    const select = document.getElementById(selectId);
    const option = select?.options[select.selectedIndex];

    return {
        id: select?.value || '',
        nome: option?.dataset.channelName || ''
    };
}

function obterDadosFormularioBoasVindas() {
    const entrada = opcaoSelecionadaBoasVindas('welcome_canal_entrada');
    const saida = opcaoSelecionadaBoasVindas('welcome_canal_saida');
    const valor = (id) => document.getElementById(id)?.value?.trim() || '';
    const marcado = (id) => Boolean(document.getElementById(id)?.checked);

    return {
        id: document.getElementById('dashboard-section-panel')?.dataset.serverId || '',
        nome: document.getElementById('nome-servidor-atual')?.innerText || '',
        entrada_ativa: marcado('welcome_entrada_ativa'),
        saida_ativa: marcado('welcome_saida_ativa'),
        canal_entrada_id: entrada.id,
        canal_entrada_nome: entrada.nome,
        canal_saida_id: saida.id,
        canal_saida_nome: saida.nome,
        entrada_conteudo: valor('welcome_entrada_conteudo'),
        entrada_titulo: valor('welcome_entrada_titulo'),
        entrada_mensagem: valor('welcome_entrada_mensagem'),
        entrada_imagem_url: valor('welcome_entrada_imagem_url'),
        entrada_cor: valor('welcome_entrada_cor') || BOAS_VINDAS_PADRAO.entrada_cor,
        entrada_mostrar_avatar: marcado('welcome_entrada_mostrar_avatar'),
        saida_conteudo: valor('welcome_saida_conteudo'),
        saida_titulo: valor('welcome_saida_titulo'),
        saida_mensagem: valor('welcome_saida_mensagem'),
        saida_imagem_url: valor('welcome_saida_imagem_url'),
        saida_cor: valor('welcome_saida_cor') || BOAS_VINDAS_PADRAO.saida_cor,
        saida_mostrar_avatar: marcado('welcome_saida_mostrar_avatar')
    };
}

function urlBoasVindasValida(url) {
    if (!url) return true;

    try {
        const parsed = new URL(url);
        return parsed.protocol === 'http:' || parsed.protocol === 'https:';
    } catch {
        return false;
    }
}

function validarBoasVindas(config) {
    if (config.entrada_ativa && !config.canal_entrada_id) {
        return 'Selecione o canal do aviso de entrada.';
    }

    if (config.saida_ativa && !config.canal_saida_id) {
        return 'Selecione o canal do aviso de saida.';
    }

    if (!urlBoasVindasValida(config.entrada_imagem_url)) {
        return 'A imagem/GIF de entrada precisa ser um link http ou https.';
    }

    if (!urlBoasVindasValida(config.saida_imagem_url)) {
        return 'A imagem/GIF de saida precisa ser um link http ou https.';
    }

    return null;
}

async function salvarBoasVindas() {
    const config = obterDadosFormularioBoasVindas();
    const erroValidacao = validarBoasVindas(config);
    const token = localStorage.getItem('discord_token');
    const icon = document.getElementById('welcome-save-icon');

    if (erroValidacao) {
        mostrarStatusBoasVindas(erroValidacao);
        alert(erroValidacao);
        return false;
    }

    if (!token) {
        mostrarStatusBoasVindas('Sessao expirada. Entre novamente com o Discord.');
        alert('Sessao expirada. Por favor, logue novamente.');
        return false;
    }

    if (token === 'demo-token') {
        const salva = salvarBoasVindasDemo(config.id, config);
        preencherFormularioBoasVindas(salva);
        mostrarStatusBoasVindas('Avisos salvos no modo teste local.', 'success');
        finalizarSalvamentoConfiguracao();
        return true;
    }

    try {
        if (icon) icon.classList.add('animate-spin');

        const response = await fetch(`${API_URL}/api/config/boas-vindas`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify(config)
        });
        const resultado = await lerJsonResposta(response);

        if (icon) icon.classList.remove('animate-spin');

        if (response.ok && resultado.status === 'sucesso') {
            preencherFormularioBoasVindas(resultado.boas_vindas || config);
            mostrarStatusBoasVindas('Avisos salvos e sincronizados com o bot.', 'success');
            finalizarSalvamentoConfiguracao();
            return true;
        }

        if (response.status === 401) {
            limparSessaoDiscord();
        }

        const mensagem = resultado.mensagem || resultado.erro || 'Nao foi possivel salvar os avisos.';
        mostrarStatusBoasVindas(mensagem);
        alert(mensagem);
        return false;
    } catch (erro) {
        console.error('Erro ao salvar avisos:', erro);
        if (icon) icon.classList.remove('animate-spin');
        mostrarStatusBoasVindas('Erro ao conectar na API.');
        alert('Erro ao conectar na API.');
        return false;
    }
}

function obterCanaisDemo() {
    return [
        { id: '111111111111111111', nome: 'geral', categoria: 'Comunidade' },
        { id: '222222222222222222', nome: 'anuncios', categoria: 'Informacoes' },
        { id: '333333333333333333', nome: 'regras', categoria: 'Informacoes' },
        { id: '444444444444444444', nome: 'chat-denuncia', categoria: 'Suporte' }
    ];
}

function obterCargosDemo() {
    return [
        { id: '555555555555555555', nome: 'Membro' },
        { id: '666666666666666666', nome: 'Verificado' },
        { id: '777777777777777777', nome: 'Moderador' },
        { id: '888888888888888888', nome: 'Admin' }
    ];
}

function renderizarSelectCanais(canais = []) {
    const select = document.getElementById('canal_select');
    const canalId = document.getElementById('canal_id');
    const canalNome = document.getElementById('canal_nome');

    if (!select) return;
    select.disabled = false;

    if (!canais.length) {
        select.innerHTML = '<option value="">Nenhum canal de texto encontrado</option>';
        if (canalId) canalId.value = '';
        if (canalNome) canalNome.value = '';
        return;
    }

    select.innerHTML = [
        '<option value="">Selecione um canal</option>',
        ...canais.map((canal) => `
            <option value="${escaparHTML(canal.id)}" data-channel-name="${escaparHTML(canal.nome)}">
                #${escaparHTML(canal.nome)}${canal.categoria ? ` - ${escaparHTML(canal.categoria)}` : ''}
            </option>
        `)
    ].join('');

    selecionarCanalLimpeza();
}

function renderizarErroCanais(mensagem, incluirLogin = false) {
    const select = document.getElementById('canal_select');
    const canalId = document.getElementById('canal_id');
    const canalNome = document.getElementById('canal_nome');

    if (select) {
        select.disabled = true;
        select.innerHTML = '<option value="">Canais indisponiveis no momento</option>';
    }

    if (canalId) canalId.value = '';
    if (canalNome) canalNome.value = '';

    mostrarStatusLimpeza(mensagem, 'error');

    if (incluirLogin) {
        renderizarEstadoLimpezas(mensagem, true);
    }
}

function selecionarCanalLimpeza() {
    const select = document.getElementById('canal_select');
    const canalId = document.getElementById('canal_id');
    const canalNome = document.getElementById('canal_nome');

    if (!select || !canalId || !canalNome) return;

    const option = select.options[select.selectedIndex];
    canalId.value = select.value;
    canalNome.value = option?.dataset.channelName || '';
}

async function carregarCanaisServidor() {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverId = painelSecao?.dataset.serverId || '';
    const token = localStorage.getItem('discord_token');
    const select = document.getElementById('canal_select');

    if (!serverId || !select) return;

    select.innerHTML = '<option value="">Carregando canais...</option>';
    select.disabled = true;

    if (token === 'demo-token') {
        renderizarSelectCanais(obterCanaisDemo());
        return;
    }

    if (!token) {
        renderizarErroCanais('Sessao expirada. Entre novamente com o Discord.', true);
        return;
    }

    try {
        const response = await fetch(`${API_URL}/api/servidores/${encodeURIComponent(serverId)}/canais`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultado = await lerJsonResposta(response);

        if (response.ok && resultado.status === 'sucesso') {
            renderizarSelectCanais(resultado.canais || []);
            return;
        }

        if (response.status === 401) {
            limparSessaoDiscord();
            renderizarErroCanais(resultado.mensagem || 'Sessao expirada. Entre novamente com o Discord.', true);
            return;
        }

        renderizarErroCanais(resultado.mensagem || resultado.erro || 'Nao foi possivel carregar os canais.');
    } catch (erro) {
        console.error('Erro ao carregar canais:', erro);
        renderizarErroCanais('Erro ao conectar na API para carregar os canais.');
    }
}

function obterChaveLimpezasDemo(serverId) {
    return `limpezas_demo_${serverId}`;
}

function obterLimpezasDemo(serverId) {
    return JSON.parse(localStorage.getItem(obterChaveLimpezasDemo(serverId)) || '[]');
}

function salvarLimpezaDemo(serverId, limpeza) {
    const limpezas = obterLimpezasDemo(serverId).filter((item) => item.canal_id !== limpeza.canal_id);
    limpezas.push(limpeza);
    localStorage.setItem(obterChaveLimpezasDemo(serverId), JSON.stringify(limpezas));
    return limpezas;
}

function removerLimpezaDemo(serverId, canalId) {
    const limpezas = obterLimpezasDemo(serverId).filter((item) => item.canal_id !== canalId);
    localStorage.setItem(obterChaveLimpezasDemo(serverId), JSON.stringify(limpezas));
    return limpezas;
}

function obterChaveCacheLimpezas(serverId) {
    return `limpezas_servidor_${serverId}`;
}

function obterLimpezasCacheServidor(serverId) {
    try {
        return JSON.parse(localStorage.getItem(obterChaveCacheLimpezas(serverId)) || '[]');
    } catch (erro) {
        console.warn('Cache de limpezas invalido:', erro);
        return [];
    }
}

function salvarLimpezasCacheServidor(serverId, limpezas = []) {
    localStorage.setItem(obterChaveCacheLimpezas(serverId), JSON.stringify(limpezas));
}

function mostrarStatusLimpeza(mensagem, tipo = 'error') {
    const statusMsg = document.getElementById('status_msg');
    if (!statusMsg) return;

    statusMsg.innerText = mensagem;
    statusMsg.className = `vm-status-message ${tipo}`;
}

function renderizarEstadoLimpezas(mensagem, incluirLogin = false) {
    const container = document.getElementById('limpezas-configuradas');
    if (!container) return;

    container.className = 'cleanup-list-empty';
    container.innerHTML = `
        <div class="cleanup-state">
            <span>${escaparHTML(mensagem)}</span>
            ${incluirLogin ? `
                <a href="${obterDiscordLoginUrl()}" onclick="prepararLoginDiscord()" class="cleanup-state-link">
                    Entrar novamente
                </a>
            ` : ''}
        </div>
    `;
}

async function lerJsonResposta(response) {
    try {
        return await response.json();
    } catch {
        return {};
    }
}

function obterRotuloDias(dias) {
    const valor = normalizarDiasLimpeza(dias);
    return `${valor} ${valor === 1 ? 'dia' : 'dias'}`;
}

function obterRotuloMinutos(minutos) {
    const valor = normalizarMinutosLimpeza(minutos);

    if (valor >= 1440) {
        return '1 dia';
    }

    if (valor >= 60 && valor % 60 === 0) {
        const horas = valor / 60;
        return `${horas} ${horas === 1 ? 'hora' : 'horas'}`;
    }

    if (valor > 60) {
        const horas = Math.floor(valor / 60);
        const minutosRestantes = valor % 60;
        return `${horas}h ${minutosRestantes}min`;
    }

    return `${valor} ${valor === 1 ? 'minuto' : 'minutos'}`;
}

function obterRotuloTempoLimpeza(limpeza = {}) {
    if (limpeza.unidade === 'minutos' || limpeza.minutos !== undefined) {
        return obterRotuloMinutos(limpeza.minutos || 1);
    }

    return obterRotuloDias(limpeza.dias || 1);
}

function renderizarLimpezasConfiguradas(limpezas = []) {
    const container = document.getElementById('limpezas-configuradas');
    if (!container) return;

    if (!limpezas.length) {
        container.className = 'cleanup-list-empty';
        container.innerHTML = 'Nenhuma limpeza configurada.';
        return;
    }

    container.className = 'cleanup-list';
    container.innerHTML = limpezas.map((limpeza) => {
        const canalId = String(limpeza.canal_id || '');
        const canalNome = String(limpeza.canal_nome || canalId || 'canal');
        const tempo = obterRotuloTempoLimpeza(limpeza);

        return `
            <div class="cleanup-item">
                <div class="cleanup-item-icon">
                    <i class="ph ph-broom"></i>
                </div>
                <div class="cleanup-item-body">
                    <strong>#${escaparHTML(canalNome.replace(/^#/, ''))}</strong>
                    <span>Excluir mensagens com mais de ${escaparHTML(tempo)} no canal configurado.</span>
                    <small>ID: ${escaparHTML(canalId)}</small>
                </div>
                <button type="button" data-remover-limpeza="${escaparHTML(canalId)}">
                    Remover
                </button>
            </div>
        `;
    }).join('');

    container.querySelectorAll('[data-remover-limpeza]').forEach((botao) => {
        botao.addEventListener('click', () => {
            removerLimpezaConfigurada(botao.dataset.removerLimpeza);
        });
    });
}

async function carregarLimpezasConfiguradas() {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverId = painelSecao?.dataset.serverId || '';
    const token = localStorage.getItem('discord_token');

    if (!serverId) {
        renderizarEstadoLimpezas('Servidor nao identificado.');
        return;
    }

    renderizarEstadoLimpezas('Carregando limpezas salvas...');

    if (token === 'demo-token') {
        renderizarLimpezasConfiguradas(obterLimpezasDemo(serverId));
        return;
    }

    if (!token) {
        renderizarEstadoLimpezas('Entre com o Discord para carregar as limpezas salvas.', true);
        return;
    }

    try {
        const response = await fetch(`${API_URL}/api/config/${encodeURIComponent(serverId)}/limpezas`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultado = await lerJsonResposta(response);

        if (response.ok && resultado.status === 'sucesso') {
            const limpezas = resultado.limpezas || [];
            salvarLimpezasCacheServidor(serverId, limpezas);
            renderizarLimpezasConfiguradas(limpezas);
            return;
        }

        if (response.status === 401) {
            limparSessaoDiscord();
            renderizarEstadoLimpezas(resultado.mensagem || 'Sessao expirada. Entre novamente para carregar as limpezas salvas.', true);
            return;
        }

        if (response.status === 403) {
            renderizarEstadoLimpezas(resultado.mensagem || 'Acesso negado para este servidor.');
            mostrarStatusLimpeza('Atualize a lista de servidores ou entre novamente se sua permissao mudou.');
            return;
        }

        const cache = obterLimpezasCacheServidor(serverId);
        if (cache.length) {
            renderizarLimpezasConfiguradas(cache);
            mostrarStatusLimpeza('Nao consegui atualizar agora. Mostrando a ultima lista carregada neste navegador.');
            return;
        }

        renderizarEstadoLimpezas(resultado.mensagem || resultado.erro || 'Nao foi possivel carregar as limpezas salvas.');
    } catch (erro) {
        console.error('Erro ao carregar limpezas:', erro);
        const cache = obterLimpezasCacheServidor(serverId);

        if (cache.length) {
            renderizarLimpezasConfiguradas(cache);
            mostrarStatusLimpeza('API indisponivel agora. Mostrando a ultima lista carregada neste navegador.');
            return;
        }

        renderizarEstadoLimpezas('Erro ao conectar na API para carregar as limpezas salvas.');
    }
}

async function removerLimpezaConfigurada(canalId) {
    const painelSecao = document.getElementById('dashboard-section-panel');
    const serverId = painelSecao?.dataset.serverId || '';
    const token = localStorage.getItem('discord_token');
    const statusMsg = document.getElementById('status_msg');

    if (!serverId || !canalId) return;

    if (token === 'demo-token') {
        const limpezas = removerLimpezaDemo(serverId, canalId);
        renderizarLimpezasConfiguradas(limpezas);
        if (statusMsg) {
            statusMsg.innerText = 'Limpeza removida no modo teste local.';
            statusMsg.className = 'vm-status-message success';
        }
        return;
    }

    if (!token) {
        alert('Sessão expirada. Por favor, logue novamente.');
        return;
    }

    try {
        const response = await fetch(`${API_URL}/api/config/${encodeURIComponent(serverId)}/limpezas/${encodeURIComponent(canalId)}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultado = await lerJsonResposta(response);

        if (response.ok && resultado.status === 'sucesso') {
            const limpezas = resultado.limpezas || [];
            salvarLimpezasCacheServidor(serverId, limpezas);
            renderizarLimpezasConfiguradas(limpezas);
            if (statusMsg) {
                statusMsg.innerText = 'Limpeza removida e banco atualizado.';
                statusMsg.className = 'vm-status-message success';
            }
            return;
        }

        if (response.status === 401) {
            limparSessaoDiscord();
            alert(resultado.mensagem || 'Sessao expirada. Entre novamente com o Discord.');
            return;
        }

        alert(resultado.mensagem || resultado.erro || 'Erro ao remover limpeza.');
    } catch (erro) {
        console.error('Erro ao remover limpeza:', erro);
        alert('Erro ao conectar na API.');
    }
}

async function enviarConfiguracao() {
    const serverId = document.getElementById('canal_id').dataset.id;
    const canalInput = document.getElementById('canal_id').value;
    const canalNomeInput = document.getElementById('canal_nome')?.value || canalInput;
    const tempoLimpeza = obterTempoLimpezaSelecionado();
    const statusMsg = document.getElementById('status_msg');
    const iconSync = document.getElementById('icon-sync');
    
    // PEGANDO O TOKEN SALVO
    const token = localStorage.getItem('discord_token');

    if (!canalInput) {
        alert('Por favor, selecione um canal.');
        return false;
    }

    if (!token) {
        alert('Sessão expirada. Por favor, logue novamente.');
        return false;
    }

    if (token === 'demo-token') {
        const limpezas = salvarLimpezaDemo(serverId, {
            canal_id: canalInput,
            canal_nome: canalNomeInput,
            ...(tempoLimpeza.unidade === 'dias'
                ? { dias: String(tempoLimpeza.valor), unidade: 'dias' }
                : { minutos: String(tempoLimpeza.valor), unidade: 'minutos' }),
            acao: 'excluir_mensagens',
            atualizado_em: new Date().toISOString()
        });
        renderizarLimpezasConfiguradas(limpezas);
        if(statusMsg) {
            statusMsg.innerText = "Limpeza salva no modo teste local.";
            statusMsg.className = "vm-status-message success";
        }
        finalizarSalvamentoConfiguracao();
        return true;
    }

    const dados = {
        id: serverId,
        nome: document.getElementById('nome-servidor-atual').innerText,
        canal_id: canalInput,
        canal_nome: canalNomeInput,
        unidade: tempoLimpeza.unidade,
        ...(tempoLimpeza.unidade === 'dias'
            ? { dias: String(tempoLimpeza.valor) }
            : { minutos: String(tempoLimpeza.valor) })
    };

    try {
        if(iconSync) iconSync.classList.add('animate-spin');
        
        // ENVIANDO O TOKEN NO CABEÇALHO (AUTHORIZATION)
        const response = await fetch(`${API_URL}/api/config/limpezas`, {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}` 
            },
            body: JSON.stringify(dados)
        });
        
        const resultado = await lerJsonResposta(response);
        
        if (iconSync) iconSync.classList.remove('animate-spin');

        if(response.ok && resultado.status === 'sucesso') {
            const limpezas = resultado.limpezas || [];
            salvarLimpezasCacheServidor(serverId, limpezas);
            renderizarLimpezasConfiguradas(limpezas);
            if(statusMsg) {
                statusMsg.innerText = "Limpeza salva e sincronizada com MongoDB!";
                statusMsg.className = "vm-status-message success";
            }
            finalizarSalvamentoConfiguracao();
            return true;
        } else {
            if (response.status === 401) {
                limparSessaoDiscord();
            }

            if(statusMsg) {
                statusMsg.innerText = resultado.mensagem || resultado.erro || "Resposta invalida do servidor.";
                statusMsg.className = "vm-status-message error";
            }
            alert('Erro de permissão: ' + (resultado.mensagem || resultado.erro || 'Resposta inválida do servidor.'));
            return false;
        }
    } catch (e) {
        console.error('Erro ao salvar:', e);
        if (iconSync) iconSync.classList.remove('animate-spin');
        if(statusMsg) {
            statusMsg.innerText = "Erro ao conectar na API.";
            statusMsg.className = "vm-status-message error";
        }
        alert('Erro ao conectar na API.');
        return false;
    }
}

// ==========================================
// INICIALIZAÇÃO
// ==========================================
function inicializarAplicacao() {
    aplicarTemaSite(obterTemaSiteSalvo(), false);
    configurarNavegacaoTopo();
    configurarBarraSalvamento();
    carregarStatusPublico();

    const urlParams = new URLSearchParams(window.location.search);

    if (hashAtualNormalizado() === '#amz-admin') {
        abrirAreaAdmin();
        return;
    }

    if (urlParams.get('code')) {
        abrirPainelServidores();
        verificarAutenticacao();

        sessionStorage.removeItem('amz_retorno_oauth');
        window.history.replaceState({}, document.title, `${window.location.pathname}#dashboard`);
        return;
    }

    if (hashAtualNormalizado() === '#dashboard' || sessionStorage.getItem('amz_retorno_oauth') === 'servidores') {
        sessionStorage.removeItem('amz_retorno_oauth');
        abrirListaServidores();
    }
}

if (document.readyState === 'loading') {
    document.addEventListener("DOMContentLoaded", inicializarAplicacao);
} else {
    inicializarAplicacao();
}

// ==========================================
// STATUS PUBLICO E AREA ADM ESCONDIDA
// ==========================================
function formatarDataHora(valor) {
    if (!valor) return '--';

    try {
        return new Intl.DateTimeFormat('pt-BR', {
            dateStyle: 'short',
            timeStyle: 'short'
        }).format(new Date(valor));
    } catch {
        return '--';
    }
}

function formatarDuracao(segundos) {
    const total = Number(segundos);

    if (!Number.isFinite(total) || total < 0) return '--';

    const dias = Math.floor(total / 86400);
    const horas = Math.floor((total % 86400) / 3600);
    const minutos = Math.floor((total % 3600) / 60);

    if (dias > 0) return `${dias}d ${horas}h`;
    if (horas > 0) return `${horas}h ${minutos}m`;
    return `${Math.max(minutos, 1)}m`;
}

function definirStatusPublico(dados = null, erro = false) {
    const strip = document.getElementById('bot-status-publico');
    const dot = document.getElementById('bot-status-dot');
    const label = document.getElementById('bot-status-label');
    const servidores = document.getElementById('bot-status-servidores');
    const sync = document.getElementById('bot-status-sync');

    if (!strip || !dot || !label || !servidores || !sync) return;

    const online = Boolean(dados?.online) && !erro;
    strip.classList.toggle('online', online);
    strip.classList.toggle('offline', !online);
    dot.classList.toggle('online', online);

    label.innerText = online ? 'Bot online' : 'Bot offline';
    servidores.innerText = `Servidores: ${online ? dados.servidores ?? 0 : '--'}`;
    sync.innerText = `Ultima sincronizacao: ${formatarDataHora(dados?.ultima_sincronizacao_em)}`;
}

async function carregarStatusPublico() {
    try {
        const response = await fetch(`${API_URL}/api/status`);
        const dados = await lerJsonResposta(response);

        if (response.ok && dados.status === 'sucesso') {
            definirStatusPublico(dados);
            return;
        }

        definirStatusPublico(null, true);
    } catch (erro) {
        console.warn('Nao foi possivel carregar status publico do bot:', erro);
        definirStatusPublico(null, true);
    }
}

function obterAdminToken() {
    return localStorage.getItem(ADMIN_TOKEN_KEY);
}

function salvarAdminToken(token) {
    localStorage.setItem(ADMIN_TOKEN_KEY, token);
}

function limparAdminToken() {
    localStorage.removeItem(ADMIN_TOKEN_KEY);
}

function mostrarStatusLoginAdmin(mensagem, tipo = 'error') {
    const status = document.getElementById('admin-login-status');

    if (!status) return;

    status.innerText = mensagem;
    status.className = tipo;
}

function abrirAreaAdmin() {
    limparAlteracoesPendentes();
    document.getElementById('site-principal')?.classList.add('hidden');

    const painel = document.getElementById('painel-loritta');
    painel?.classList.add('hidden');
    painel?.classList.remove('flex');

    document.getElementById('admin-area')?.classList.remove('hidden');

    if (obterAdminToken()) {
        carregarStatusAdmin();
    } else {
        document.getElementById('admin-login-panel')?.classList.remove('hidden');
        document.getElementById('admin-dashboard')?.classList.add('hidden');
    }
}

function fecharAreaAdmin() {
    document.getElementById('admin-area')?.classList.add('hidden');
    document.getElementById('site-principal')?.classList.remove('hidden');
    window.history.replaceState({}, document.title, window.location.pathname);
}

function sairAreaAdmin() {
    limparAdminToken();
    document.getElementById('admin-dashboard')?.classList.add('hidden');
    document.getElementById('admin-login-panel')?.classList.remove('hidden');
    mostrarStatusLoginAdmin('Sessao ADM encerrada.', 'success');
}

async function entrarAreaAdmin(evento) {
    evento.preventDefault();

    const input = document.getElementById('admin-password');
    const senha = input?.value || '';

    if (!senha) {
        mostrarStatusLoginAdmin('Digite a senha ADM.');
        return;
    }

    mostrarStatusLoginAdmin('Verificando acesso...', 'loading');

    try {
        const response = await fetch(`${API_URL}/api/admin/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ senha })
        });
        const dados = await lerJsonResposta(response);

        if (response.ok && dados.status === 'sucesso' && dados.token) {
            salvarAdminToken(dados.token);
            if (input) input.value = '';
            mostrarStatusLoginAdmin('Acesso liberado.', 'success');
            await carregarStatusAdmin();
            return;
        }

        mostrarStatusLoginAdmin(dados.mensagem || dados.erro || 'Nao foi possivel entrar.');
    } catch (erro) {
        console.error('Erro ao entrar na area ADM:', erro);
        mostrarStatusLoginAdmin('Erro ao conectar na API.');
    }
}

async function carregarStatusAdmin() {
    const token = obterAdminToken();

    if (!token) {
        document.getElementById('admin-login-panel')?.classList.remove('hidden');
        document.getElementById('admin-dashboard')?.classList.add('hidden');
        return;
    }

    document.getElementById('admin-login-panel')?.classList.add('hidden');
    document.getElementById('admin-dashboard')?.classList.remove('hidden');

    const lista = document.getElementById('admin-server-list');
    const resumo = document.getElementById('admin-summary-grid');

    if (lista) lista.innerHTML = '<div class="admin-loading">Carregando servidores...</div>';
    if (resumo) resumo.innerHTML = '';

    try {
        const response = await fetch(`${API_URL}/api/admin/status`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const dados = await lerJsonResposta(response);

        if (response.ok && dados.status === 'sucesso') {
            renderizarAdminDashboard(dados);
            return;
        }

        if (response.status === 401) {
            limparAdminToken();
            document.getElementById('admin-dashboard')?.classList.add('hidden');
            document.getElementById('admin-login-panel')?.classList.remove('hidden');
        }

        mostrarStatusLoginAdmin(dados.mensagem || dados.erro || 'Nao foi possivel carregar ADM.');
    } catch (erro) {
        console.error('Erro ao carregar status ADM:', erro);
        if (lista) lista.innerHTML = '<div class="admin-loading">Erro ao conectar na API.</div>';
    }
}

function renderizarAdminDashboard(dados) {
    const titulo = document.getElementById('admin-bot-title');
    const resumo = document.getElementById('admin-summary-grid');
    const sistema = document.getElementById('admin-system-panel');
    const lista = document.getElementById('admin-server-list');
    const servidores = Array.isArray(dados.servidores) ? dados.servidores : [];

    if (titulo) {
        titulo.innerText = dados.bot?.display || dados.bot?.nome || 'AMZ Bot';
    }

    if (resumo) {
        resumo.innerHTML = [
            criarCardResumoAdmin('Status', dados.online ? 'Online' : 'Offline', dados.online ? 'Conectado ao Discord' : 'Sem conexao ativa'),
            criarCardResumoAdmin('Servidores', servidores.length, 'Onde o bot esta conectado'),
            criarCardResumoAdmin('Latencia', dados.latencia_ms ? `${dados.latencia_ms} ms` : '--', 'Ping do bot'),
            criarCardResumoAdmin('Uptime', formatarDuracao(dados.online_ha_segundos), 'Tempo online'),
            criarCardResumoAdmin('Slash Sync', dados.comandos_slash_sincronizados ?? 0, 'Servidores sincronizados'),
            criarCardResumoAdmin('Ultima sync', formatarDataHora(dados.ultima_sincronizacao_em), 'Comandos e ready')
        ].join('');
    }

    if (sistema) {
        sistema.innerHTML = renderizarSistemaAdmin(dados.sistema || {});
    }

    if (!lista) return;

    if (!servidores.length) {
        lista.innerHTML = '<div class="admin-loading">Nenhum servidor conectado ao bot.</div>';
        return;
    }

    lista.innerHTML = servidores.map(renderizarServidorAdmin).join('');
}

function criarCardResumoAdmin(titulo, valor, detalhe) {
    return `
        <article class="admin-summary-card">
            <span>${escaparHTML(titulo)}</span>
            <strong>${escaparHTML(valor)}</strong>
            <small>${escaparHTML(detalhe)}</small>
        </article>
    `;
}

function simNao(valor) {
    return valor ? 'Sim' : 'Nao';
}

function valorAdmin(valor) {
    if (valor === true) return 'Sim';
    if (valor === false) return 'Nao';
    if (valor === null || valor === undefined || valor === '') return '--';
    return String(valor);
}

function renderizarLinhaSistema(label, valor, destaque = false) {
    return `
        <div class="admin-system-row ${destaque ? 'featured' : ''}">
            <span>${escaparHTML(label)}</span>
            <strong>${escaparHTML(valorAdmin(valor))}</strong>
        </div>
    `;
}

function renderizarVariaveisSistema(configuracoes = {}) {
    return Object.entries(configuracoes).map(([nome, configurada]) => `
        <span class="admin-env-chip ${configurada ? 'ok' : 'no'}">
            ${escaparHTML(nome)}: ${configurada ? 'ok' : 'faltando'}
        </span>
    `).join('');
}

function renderizarSistemaAdmin(sistema = {}) {
    const api = sistema.api || {};
    const render = sistema.render || {};
    const banco = sistema.banco || {};
    const botSistema = sistema.bot || {};
    const configuracoes = sistema.configuracoes || {};
    const ultimoDocumento = banco.ultimo_documento || {};

    return `
        <article class="admin-system-card">
            <div class="admin-system-heading">
                <div>
                    <span>Sistema</span>
                    <strong>Bot, Render e banco de dados</strong>
                </div>
            </div>

            <div class="admin-system-grid">
                <section>
                    <h3>API</h3>
                    ${renderizarLinhaSistema('Status', api.online ? 'Online' : 'Offline', true)}
                    ${renderizarLinhaSistema('Uptime', formatarDuracao(api.uptime_segundos))}
                    ${renderizarLinhaSistema('Python', api.python)}
                    ${renderizarLinhaSistema('Plataforma', api.plataforma)}
                    ${renderizarLinhaSistema('PID', api.processo_id)}
                </section>

                <section>
                    <h3>Render</h3>
                    ${renderizarLinhaSistema('Ambiente', render.ambiente, true)}
                    ${renderizarLinhaSistema('Servico', render.servico_nome || render.servico_id)}
                    ${renderizarLinhaSistema('URL publica', render.url_externa)}
                    ${renderizarLinhaSistema('Branch', render.git_branch)}
                    ${renderizarLinhaSistema('Commit', render.git_commit ? String(render.git_commit).slice(0, 12) : '--')}
                    ${renderizarLinhaSistema('Deploy hook', simNao(render.deploy_hook_configurado))}
                </section>

                <section>
                    <h3>MongoDB</h3>
                    ${renderizarLinhaSistema('Status', banco.online ? 'Online' : 'Offline', true)}
                    ${renderizarLinhaSistema('Ping', banco.ping_ms ? `${banco.ping_ms} ms` : '--')}
                    ${renderizarLinhaSistema('Database', banco.database)}
                    ${renderizarLinhaSistema('Collection', banco.collection)}
                    ${renderizarLinhaSistema('Documentos', banco.documentos)}
                    ${renderizarLinhaSistema('Com limpeza', banco.documentos_com_limpeza)}
                    ${renderizarLinhaSistema('Ultimo update', formatarDataHora(ultimoDocumento.atualizado_em))}
                </section>

                <section>
                    <h3>Bot</h3>
                    ${renderizarLinhaSistema('Prefixo', botSistema.prefixo)}
                    ${renderizarLinhaSistema('Cogs', Array.isArray(botSistema.cogs) ? botSistema.cogs.join(', ') : '--')}
                    ${renderizarLinhaSistema('Comandos prefixo', botSistema.total_comandos_prefixo)}
                    ${renderizarLinhaSistema('Comandos slash', botSistema.total_comandos_slash)}
                    ${renderizarLinhaSistema('Guilds sync', botSistema.slash_guilds_sincronizadas)}
                    ${renderizarLinhaSistema('Intent members', simNao(botSistema.intents?.members))}
                    ${renderizarLinhaSistema('Membros aprox.', botSistema.totais?.membros_aproximados)}
                </section>
            </div>

            <details class="admin-system-details">
                <summary>Variaveis configuradas no Render</summary>
                <div class="admin-env-grid">
                    ${renderizarVariaveisSistema(configuracoes)}
                </div>
            </details>

            ${banco.erro ? `
                <div class="admin-system-error">
                    MongoDB: ${escaparHTML(banco.erro)}
                </div>
            ` : ''}
        </article>
    `;
}

function renderizarPermissoesAdmin(permissoes = {}) {
    const nomes = {
        administrador: 'Administrador',
        gerenciar_servidor: 'Gerenciar servidor',
        gerenciar_mensagens: 'Gerenciar mensagens',
        banir_membros: 'Banir membros',
        expulsar_membros: 'Expulsar membros',
        castigar_membros: 'Castigar membros',
        ver_canais: 'Ver canais',
        enviar_mensagens: 'Enviar mensagens',
        ler_historico: 'Ler historico',
        gerenciar_cargos: 'Gerenciar cargos',
        ver_auditoria: 'Ver auditoria'
    };

    return Object.entries(nomes).map(([chave, nome]) => `
        <span class="admin-permission ${permissoes[chave] ? 'ok' : 'no'}">
            ${escaparHTML(nome)}
        </span>
    `).join('');
}

function renderizarServidorAdmin(servidor) {
    const avatar = servidor.icone_url
        ? `<img src="${escaparHTML(servidor.icone_url)}" alt="${escaparHTML(servidor.nome)}">`
        : `<span>${escaparHTML(obterIniciaisServidor(servidor.nome))}</span>`;

    const canais = Array.isArray(servidor.canais) ? servidor.canais : [];
    const cargos = Array.isArray(servidor.cargos) ? servidor.cargos : [];
    const limpezas = Array.isArray(servidor.limpezas_configuradas) ? servidor.limpezas_configuradas : [];
    const boasVindas = servidor.boas_vindas_config || {};
    const avisosAtivos = Number(Boolean(boasVindas.entrada_ativa)) + Number(Boolean(boasVindas.saida_ativa));
    const features = Array.isArray(servidor.features) && servidor.features.length
        ? servidor.features.join(', ')
        : 'Nenhuma feature especial';

    return `
        <article class="admin-server-card" data-admin-server-id="${escaparHTML(servidor.id)}" data-admin-server-name="${escaparHTML(servidor.nome)}">
            <div class="admin-server-head">
                <div class="admin-server-avatar">${avatar}</div>
                <div>
                    <strong>${escaparHTML(servidor.nome)}</strong>
                    <span>ID ${escaparHTML(servidor.id)}</span>
                </div>
            </div>

            <div class="admin-server-actions">
                <button type="button" class="admin-danger-button" onclick="sairServidorBotAdmin('${escaparHTML(servidor.id)}')">
                    <i class="ph ph-sign-out"></i>
                    Sair do servidor
                </button>
            </div>

            <div class="admin-server-metrics">
                <span>Membros <strong>${escaparHTML(servidor.membros ?? '--')}</strong></span>
                <span>Canais <strong>${escaparHTML(servidor.contagens?.canais ?? canais.length)}</strong></span>
                <span>Cargos <strong>${escaparHTML(servidor.contagens?.cargos ?? cargos.length)}</strong></span>
                <span>Limpezas <strong>${escaparHTML(limpezas.length)}</strong></span>
                <span>Avisos <strong>${escaparHTML(avisosAtivos)}</strong></span>
            </div>

            <div class="admin-server-meta">
                <span>Dono: ${escaparHTML(servidor.dono_nome || servidor.dono_id || '--')}</span>
                <span>Criado: ${escaparHTML(formatarDataHora(servidor.criado_em))}</span>
                <span>Bot entrou: ${escaparHTML(formatarDataHora(servidor.bot_entrou_em))}</span>
                <span>Boost tier: ${escaparHTML(servidor.premium_tier ?? 0)} / boosts: ${escaparHTML(servidor.boosts ?? 0)}</span>
                <span>Features: ${escaparHTML(features)}</span>
            </div>

            <div class="admin-permission-grid">
                ${renderizarPermissoesAdmin(servidor.permissoes_bot)}
            </div>

            <details class="admin-details">
                <summary>Canais (${escaparHTML(canais.length)})</summary>
                <div class="admin-detail-list">
                    ${canais.map(renderizarCanalAdmin).join('') || '<span>Nenhum canal encontrado.</span>'}
                </div>
            </details>

            <details class="admin-details">
                <summary>Cargos (${escaparHTML(cargos.length)})</summary>
                <div class="admin-detail-list">
                    ${cargos.map(renderizarCargoAdmin).join('') || '<span>Nenhum cargo encontrado.</span>'}
                </div>
            </details>

            <details class="admin-details">
                <summary>Membros (${escaparHTML(servidor.membros ?? '--')})</summary>
                <div class="admin-members-toolbar">
                    <button type="button" onclick="carregarMembrosAdmin('${escaparHTML(servidor.id)}')">
                        <i class="ph ph-users-three"></i>
                        Carregar membros
                    </button>
                    <small>Acoes exigem permissao do bot e cargo acima do membro.</small>
                </div>
                <div class="admin-member-list" id="admin-members-${escaparHTML(servidor.id)}">
                    <span class="admin-member-empty">Clique para carregar a lista de membros.</span>
                </div>
            </details>

            <details class="admin-details">
                <summary>Limpezas (${escaparHTML(limpezas.length)})</summary>
                <div class="admin-detail-list">
                    ${limpezas.map(renderizarLimpezaAdmin).join('') || '<span>Nenhuma limpeza configurada.</span>'}
                </div>
            </details>

            <details class="admin-details">
                <summary>Avisos de entrada e saida</summary>
                <div class="admin-detail-list">
                    ${renderizarBoasVindasAdmin(boasVindas)}
                </div>
            </details>
        </article>
    `;
}

async function sairServidorBotAdmin(serverId) {
    const token = obterAdminToken();
    const card = document.querySelector(`[data-admin-server-id="${serverId}"]`);
    const nomeServidor = card?.dataset.adminServerName || serverId;

    if (!token) return;

    const confirmar = window.confirm(`Fazer o bot sair de "${nomeServidor}"? Esta acao remove o bot do servidor imediatamente.`);
    if (!confirmar) return;

    const digitado = window.prompt(`Digite exatamente o nome do servidor para confirmar:\n${nomeServidor}`);
    if (digitado === null) return;

    if (digitado !== nomeServidor) {
        alert('Confirmacao cancelada. O nome digitado nao confere.');
        return;
    }

    const motivo = window.prompt('Motivo interno da saida:', 'Solicitado pelo painel ADM AMZ.');
    if (motivo === null) return;

    try {
        const response = await fetch(`${API_URL}/api/admin/servidores/${encodeURIComponent(serverId)}/leave`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify({
                confirmar: digitado,
                motivo
            })
        });
        const dados = await lerJsonResposta(response);

        if (response.ok && dados.status === 'sucesso') {
            alert(dados.mensagem || 'Bot saiu do servidor.');
            await carregarStatusAdmin();
            return;
        }

        if (response.status === 401) {
            limparAdminToken();
            sairAreaAdmin();
            return;
        }

        alert(dados.mensagem || dados.erro || 'Nao foi possivel fazer o bot sair do servidor.');
    } catch (erro) {
        console.error('Erro ao sair do servidor:', erro);
        alert('Erro ao conectar na API para sair do servidor.');
    }
}

function renderizarCanalAdmin(canal) {
    const permissoes = canal.permissoes_bot || {};

    return `
        <div class="admin-detail-row">
            <strong>#${escaparHTML(canal.nome)}</strong>
            <span>${escaparHTML(canal.tipo)}${canal.categoria ? ` / ${escaparHTML(canal.categoria)}` : ''}</span>
            <small>ID ${escaparHTML(canal.id)} | Ver: ${permissoes.ver ? 'sim' : 'nao'} | Enviar: ${permissoes.enviar ? 'sim' : 'nao'} | Embeds: ${permissoes.enviar_embeds ? 'sim' : 'nao'} | Limpar: ${permissoes.gerenciar_mensagens ? 'sim' : 'nao'}</small>
        </div>
    `;
}

function renderizarCargoAdmin(cargo) {
    return `
        <div class="admin-detail-row">
            <strong>${escaparHTML(cargo.nome)}</strong>
            <span>Posicao ${escaparHTML(cargo.posicao)} / membros ${escaparHTML(cargo.membros)}</span>
            <small>ID ${escaparHTML(cargo.id)} | Cor ${escaparHTML(cargo.cor)} | Gerenciado: ${cargo.gerenciado ? 'sim' : 'nao'}</small>
        </div>
    `;
}

async function carregarMembrosAdmin(serverId) {
    const container = document.getElementById(`admin-members-${serverId}`);
    const token = obterAdminToken();

    if (!container || !token) return;

    container.innerHTML = '<span class="admin-member-empty">Carregando membros...</span>';

    try {
        const response = await fetch(`${API_URL}/api/admin/servidores/${encodeURIComponent(serverId)}/membros`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const dados = await lerJsonResposta(response);

        if (response.ok && dados.status === 'sucesso') {
            const membros = Array.isArray(dados.membros) ? dados.membros : [];
            const aviso = dados.aviso ? `<div class="admin-member-warning">${escaparHTML(dados.aviso)}</div>` : '';
            const meta = `
                <div class="admin-member-meta">
                    <span>Origem: ${escaparHTML(dados.origem || '--')}</span>
                    <span>Mostrando: ${escaparHTML(membros.length)}</span>
                    <span>Total servidor: ${escaparHTML(dados.total_servidor ?? '--')}</span>
                    <span>Total cache: ${escaparHTML(dados.total_cache ?? '--')}</span>
                </div>
            `;

            container.innerHTML = `
                ${aviso}
                ${meta}
                ${membros.map((membro) => renderizarMembroAdmin(serverId, membro)).join('') || '<span class="admin-member-empty">Nenhum membro encontrado.</span>'}
            `;
            return;
        }

        if (response.status === 401) {
            limparAdminToken();
            sairAreaAdmin();
            return;
        }

        container.innerHTML = `<span class="admin-member-empty">${escaparHTML(dados.mensagem || dados.erro || 'Nao foi possivel carregar membros.')}</span>`;
    } catch (erro) {
        console.error('Erro ao carregar membros:', erro);
        container.innerHTML = '<span class="admin-member-empty">Erro ao conectar na API.</span>';
    }
}

function renderizarMembroAdmin(serverId, membro) {
    const avatar = membro.avatar_url
        ? `<img src="${escaparHTML(membro.avatar_url)}" alt="${escaparHTML(membro.display || membro.nome)}">`
        : `<span>${escaparHTML(obterIniciaisServidor(membro.display || membro.nome))}</span>`;
    const cargos = Array.isArray(membro.cargos) && membro.cargos.length
        ? membro.cargos.slice(0, 6).map((cargo) => `<span>${escaparHTML(cargo.nome)}</span>`).join('')
        : '<span>Sem cargos</span>';
    const podeBanir = Boolean(membro.moderacao?.pode_banir);
    const podeExpulsar = Boolean(membro.moderacao?.pode_expulsar);
    const podeCastigar = Boolean(membro.moderacao?.pode_castigar);
    const motivoBanir = membro.moderacao?.motivo_bloqueio || 'Indisponivel';
    const motivoExpulsar = membro.moderacao?.motivo_expulsar || 'Indisponivel';
    const motivoCastigar = membro.moderacao?.motivo_castigar || 'Indisponivel';

    return `
        <div class="admin-member-row" data-member-id="${escaparHTML(membro.id)}" data-member-name="${escaparHTML(membro.display || membro.nome)}">
            <div class="admin-member-avatar">${avatar}</div>
            <div class="admin-member-main">
                <strong>${escaparHTML(membro.display || membro.nome)}</strong>
                <span>${escaparHTML(membro.tag || membro.nome)} ${membro.bot ? '/ BOT' : '/ USER'}</span>
                <small>ID ${escaparHTML(membro.id)} | Entrou: ${escaparHTML(formatarDataHora(membro.entrou_em))} | Criado: ${escaparHTML(formatarDataHora(membro.criado_em))}</small>
                <div class="admin-member-roles">${cargos}</div>
            </div>
            <div class="admin-member-actions">
                <button type="button"
                        class="admin-action-button timeout"
                        ${podeCastigar ? '' : 'disabled'}
                        title="${escaparHTML(motivoCastigar)}"
                        onclick="castigarMembroAdmin('${escaparHTML(serverId)}', '${escaparHTML(membro.id)}')">
                    Castigar
                </button>
                <button type="button"
                        class="admin-action-button kick"
                        ${podeExpulsar ? '' : 'disabled'}
                        title="${escaparHTML(motivoExpulsar)}"
                        onclick="expulsarMembroAdmin('${escaparHTML(serverId)}', '${escaparHTML(membro.id)}')">
                    Expulsar
                </button>
                <button type="button"
                        class="admin-action-button ban"
                        ${podeBanir ? '' : 'disabled'}
                        title="${escaparHTML(motivoBanir)}"
                        onclick="banirMembroAdmin('${escaparHTML(serverId)}', '${escaparHTML(membro.id)}')">
                    Banir
                </button>
            </div>
        </div>
    `;
}

async function executarAcaoMembroAdmin(serverId, userId, acao, config) {
    const token = obterAdminToken();
    const row = document.querySelector(`[data-member-id="${userId}"]`);
    const nome = row?.dataset.memberName || userId;

    if (!token) return;

    const confirmar = window.confirm(`${config.confirmacao} ${nome}? Essa acao e imediata.`);

    if (!confirmar) return;

    const body = {};
    const motivo = window.prompt(config.motivoLabel, config.motivoPadrao);

    if (motivo === null) return;

    body.motivo = motivo;

    if (config.pedirMinutos) {
        const minutosTexto = window.prompt('Tempo do castigo em minutos (1 a 10080):', '60');

        if (minutosTexto === null) return;

        const minutos = Number.parseInt(minutosTexto, 10);
        if (!Number.isFinite(minutos) || minutos < 1 || minutos > 10080) {
            alert('Informe um tempo entre 1 e 10080 minutos.');
            return;
        }

        body.minutos = minutos;
    }

    try {
        const response = await fetch(`${API_URL}/api/admin/servidores/${encodeURIComponent(serverId)}/membros/${encodeURIComponent(userId)}/${acao}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify(body)
        });
        const dados = await lerJsonResposta(response);

        if (response.ok && dados.status === 'sucesso') {
            alert(dados.mensagem || config.sucesso);
            await carregarMembrosAdmin(serverId);
            return;
        }

        alert(dados.mensagem || dados.erro || config.erro);
    } catch (erro) {
        console.error(`Erro ao executar ${acao}:`, erro);
        alert(`Erro ao conectar na API para ${config.verbo}.`);
    }
}

function banirMembroAdmin(serverId, userId) {
    return executarAcaoMembroAdmin(serverId, userId, 'ban', {
        confirmacao: 'Banir',
        motivoLabel: 'Motivo do banimento:',
        motivoPadrao: 'Banido pelo painel ADM AMZ.',
        sucesso: 'Membro banido.',
        erro: 'Nao foi possivel banir o membro.',
        verbo: 'banir'
    });
}

function expulsarMembroAdmin(serverId, userId) {
    return executarAcaoMembroAdmin(serverId, userId, 'kick', {
        confirmacao: 'Expulsar',
        motivoLabel: 'Motivo da expulsao:',
        motivoPadrao: 'Expulso pelo painel ADM AMZ.',
        sucesso: 'Membro expulso.',
        erro: 'Nao foi possivel expulsar o membro.',
        verbo: 'expulsar'
    });
}

function castigarMembroAdmin(serverId, userId) {
    return executarAcaoMembroAdmin(serverId, userId, 'timeout', {
        confirmacao: 'Castigar',
        motivoLabel: 'Motivo do castigo:',
        motivoPadrao: 'Castigo aplicado pelo painel ADM AMZ.',
        sucesso: 'Castigo aplicado.',
        erro: 'Nao foi possivel castigar o membro.',
        verbo: 'castigar',
        pedirMinutos: true
    });
}

function renderizarBoasVindasAdmin(config = {}) {
    const entrada = config.entrada_ativa
        ? `Ativo em #${config.canal_entrada_nome || config.canal_entrada_id || 'canal'}`
        : 'Desativado';
    const saida = config.saida_ativa
        ? `Ativo em #${config.canal_saida_nome || config.canal_saida_id || 'canal'}`
        : 'Desativado';

    return `
        <div class="admin-detail-row">
            <strong>Entrada</strong>
            <span>${escaparHTML(entrada)}</span>
            <small>Titulo: ${escaparHTML(config.entrada_titulo || '--')}</small>
        </div>
        <div class="admin-detail-row">
            <strong>Saida</strong>
            <span>${escaparHTML(saida)}</span>
            <small>Titulo: ${escaparHTML(config.saida_titulo || '--')}</small>
        </div>
    `;
}

function renderizarLimpezaAdmin(limpeza) {
    return `
        <div class="admin-detail-row">
            <strong>#${escaparHTML(limpeza.canal_nome || limpeza.canal_id || 'canal')}</strong>
            <span>${escaparHTML(obterRotuloTempoLimpeza(limpeza))}</span>
            <small>ID ${escaparHTML(limpeza.canal_id || '--')}</small>
        </div>
    `;
}

window.addEventListener('hashchange', () => {
    if (hashAtualNormalizado() === '#amz-admin') {
        abrirAreaAdmin();
    }
});

document.addEventListener('click', (evento) => {
    if (!evento.target.closest('.vm-sidebar-group')) {
        fecharServidorDropdown();
    }
});

document.addEventListener('keydown', (evento) => {
    if (evento.key === 'Escape') {
        fecharServidorDropdown();
    }
});

window.setInterval(carregarStatusPublico, 60000);
