// ==========================================
// CONFIGURAÇÕES E URLS
// ==========================================
const API_URL = 'https://amz-studios-api.onrender.com';
const DISCORD_CLIENT_ID = '1479103284064026787';
const DISCORD_REDIRECT_PADRAO = 'https://muniz-amz.github.io/amz-studios/';
const DISCORD_LOGIN_SCOPES = 'identify guilds';
const DISCORD_BOT_INVITE_SCOPES = 'bot applications.commands';
const DISCORD_BOT_PERMISSIONS = '8';
const MAX_MINUTOS_LIMPEZA = 1440;
const ADMIN_TOKEN_KEY = 'amz_admin_token';
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
        { id: 'demo-celestial', nome: 'Celestial Trindade', icon_url: '' }
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
    return window.location.protocol === 'file:';
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
        title: 'Role Toggles',
        description: 'Controle recursos por cargo.'
    },
    misc: {
        title: 'Misc',
        description: 'Opcoes extras do servidor.'
    },
    bot: {
        title: 'Bot Profile',
        description: 'Ajustes de aparencia e comportamento.'
    },
    interface: {
        title: 'Interface',
        description: 'Preferencias visuais do painel.'
    },
    blacklist: {
        title: 'Blacklisted Words',
        description: 'Filtro de palavras bloqueadas.'
    },
    global: {
        title: 'Global Profile',
        description: 'Preferencias pessoais da conta.'
    },
    profiles: {
        title: 'Server Profiles',
        description: 'Coming Soon'
    },
    upgrade: {
        title: 'Upgrade',
        description: 'Recursos premium do AMZ Bot.'
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

function hashAtualNormalizado() {
    return String(window.location.hash || '').toLowerCase();
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

function renderizarServidores(servidores) {
    const container = document.getElementById('container-servidores');
    
    if (servidores.length === 0) {
        container.innerHTML = '<p class="text-white/40 text-xs py-4">Você não é Administrador de nenhum servidor em comum.</p>';
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
}

// ==========================================
// CONFIGURAÇÕES DO SERVIDOR
// ==========================================
function configurarServidor(id, nome, iconUrl = '') {
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.remove('hidden');
    document.getElementById('nome-servidor-atual').innerText = nome;
    atualizarServidorAtualNaSidebar(nome, iconUrl);

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

function atualizarServidorAtualNaSidebar(nome, iconUrl = '') {
    const nomeAtual = document.getElementById('nome-servidor-atual');
    const avatarAtual = document.getElementById('icone-servidor-atual');

    if (nomeAtual) nomeAtual.innerText = nome;
    if (!avatarAtual) return;

    if (iconUrl) {
        avatarAtual.innerHTML = `<img src="${escaparHTML(iconUrl)}" alt="${escaparHTML(nome)}">`;
    } else {
        avatarAtual.innerHTML = escaparHTML(obterIniciaisServidor(nome));
    }
}

function selecionarSecaoDashboard(secao = 'setup') {
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

    painelSecao.innerHTML = `
        <div class="vm-panel-heading">
            <span>${escaparHTML(info.title)}</span>
            <strong>${escaparHTML(serverName)}</strong>
        </div>
        <div class="vm-placeholder-panel">
            <p>${escaparHTML(info.description)}</p>
            <div class="vm-toggle-list">
                <label class="vm-toggle-row">
                    <span>
                        <strong>Status</strong>
                        <small>Modulo em preparacao</small>
                    </span>
                    <input type="checkbox" disabled>
                </label>
                <label class="vm-toggle-row">
                    <span>
                        <strong>Permissao</strong>
                        <small>Aguardando integracao</small>
                    </span>
                    <input type="checkbox" disabled>
                </label>
            </div>
        </div>
    `;
}

function renderizarVariaveisBoasVindas() {
    return VARIAVEIS_BOAS_VINDAS.map((variavel) => `<code>${escaparHTML(variavel)}</code>`).join('');
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
        return;
    }

    if (!token) {
        mostrarStatusBoasVindas('Sessao expirada. Entre novamente com o Discord.');
        alert('Sessao expirada. Por favor, logue novamente.');
        return;
    }

    if (token === 'demo-token') {
        const salva = salvarBoasVindasDemo(config.id, config);
        preencherFormularioBoasVindas(salva);
        mostrarStatusBoasVindas('Avisos salvos no modo teste local.', 'success');
        return;
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
            alert('Avisos salvos com sucesso!');
            return;
        }

        if (response.status === 401) {
            limparSessaoDiscord();
        }

        const mensagem = resultado.mensagem || resultado.erro || 'Nao foi possivel salvar os avisos.';
        mostrarStatusBoasVindas(mensagem);
        alert(mensagem);
    } catch (erro) {
        console.error('Erro ao salvar avisos:', erro);
        if (icon) icon.classList.remove('animate-spin');
        mostrarStatusBoasVindas('Erro ao conectar na API.');
        alert('Erro ao conectar na API.');
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
        return;
    }

    if (!token) {
        alert('Sessão expirada. Por favor, logue novamente.');
        return;
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
        return;
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
            alert('Limpeza salva com sucesso!');
        } else {
            if (response.status === 401) {
                limparSessaoDiscord();
            }

            if(statusMsg) {
                statusMsg.innerText = resultado.mensagem || resultado.erro || "Resposta invalida do servidor.";
                statusMsg.className = "vm-status-message error";
            }
            alert('Erro de permissão: ' + (resultado.mensagem || resultado.erro || 'Resposta inválida do servidor.'));
        }
    } catch (e) {
        console.error('Erro ao salvar:', e);
        if (iconSync) iconSync.classList.remove('animate-spin');
        if(statusMsg) {
            statusMsg.innerText = "Erro ao conectar na API.";
            statusMsg.className = "vm-status-message error";
        }
        alert('Erro ao conectar na API.');
    }
}

// ==========================================
// INICIALIZAÇÃO
// ==========================================
function inicializarAplicacao() {
    configurarNavegacaoTopo();
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
        <article class="admin-server-card">
            <div class="admin-server-head">
                <div class="admin-server-avatar">${avatar}</div>
                <div>
                    <strong>${escaparHTML(servidor.nome)}</strong>
                    <span>ID ${escaparHTML(servidor.id)}</span>
                </div>
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

window.setInterval(carregarStatusPublico, 60000);
