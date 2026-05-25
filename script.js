// ==========================================
// CONFIGURAÇÕES E URLS
// ==========================================
const API_URL = 'https://amz-studios-api.onrender.com';
const DISCORD_CLIENT_ID = '1479103284064026787';
const DISCORD_REDIRECT_PADRAO = 'https://muniz-amz.github.io/amz-studios/';
const MAX_DIAS_LIMPEZA_DISCORD = 14;
const ADMIN_TOKEN_KEY = 'amz_admin_token';

function normalizarDiasLimpeza(dias) {
    const valor = Number.parseInt(dias, 10);

    if (!Number.isFinite(valor)) return 1;

    return Math.min(Math.max(valor, 1), MAX_DIAS_LIMPEZA_DISCORD);
}

function gerarOpcoesDiasLimpeza() {
    return Array.from({ length: MAX_DIAS_LIMPEZA_DISCORD }, (_, indice) => {
        const dias = indice + 1;
        const horas = dias * 24;
        const rotuloDias = dias === 1 ? 'dia' : 'dias';

        return `<option value="${dias}">${horas} Horas (${dias} ${rotuloDias})</option>`;
    }).join('');
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
    url.searchParams.set('scope', 'identify guilds');
    return url.toString();
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
        title: 'Server Toggles',
        description: 'Ative ou desative recursos do servidor.'
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

    if (window.location.hash === '#dashboard') {
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
                    <select id="dias">
                        ${gerarOpcoesDiasLimpeza()}
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
        const dias = obterRotuloDias(limpeza.dias || '1');

        return `
            <div class="cleanup-item">
                <div class="cleanup-item-icon">
                    <i class="ph ph-broom"></i>
                </div>
                <div class="cleanup-item-body">
                    <strong>#${escaparHTML(canalNome.replace(/^#/, ''))}</strong>
                    <span>Excluir mensagens de ${escaparHTML(dias)} no canal configurado.</span>
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
    const diasSelect = document.getElementById('dias').value;
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
            dias: diasSelect,
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
        dias: diasSelect 
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

    if (window.location.hash === '#amz-admin') {
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

    if (window.location.hash === '#dashboard' || sessionStorage.getItem('amz_retorno_oauth') === 'servidores') {
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

function renderizarPermissoesAdmin(permissoes = {}) {
    const nomes = {
        administrador: 'Administrador',
        gerenciar_servidor: 'Gerenciar servidor',
        gerenciar_mensagens: 'Gerenciar mensagens',
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
                <summary>Limpezas (${escaparHTML(limpezas.length)})</summary>
                <div class="admin-detail-list">
                    ${limpezas.map(renderizarLimpezaAdmin).join('') || '<span>Nenhuma limpeza configurada.</span>'}
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
            <small>ID ${escaparHTML(canal.id)} | Ver: ${permissoes.ver ? 'sim' : 'nao'} | Enviar: ${permissoes.enviar ? 'sim' : 'nao'} | Limpar: ${permissoes.gerenciar_mensagens ? 'sim' : 'nao'}</small>
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

function renderizarLimpezaAdmin(limpeza) {
    return `
        <div class="admin-detail-row">
            <strong>#${escaparHTML(limpeza.canal_nome || limpeza.canal_id || 'canal')}</strong>
            <span>${escaparHTML(obterRotuloDias(limpeza.dias || 1))}</span>
            <small>ID ${escaparHTML(limpeza.canal_id || '--')}</small>
        </div>
    `;
}

window.addEventListener('hashchange', () => {
    if (window.location.hash === '#amz-admin') {
        abrirAreaAdmin();
    }
});

window.setInterval(carregarStatusPublico, 60000);
