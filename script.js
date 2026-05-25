// ==========================================
// CONFIGURAÇÕES E URLS
// ==========================================
const API_URL = 'https://amz-studios-api.onrender.com';
const DISCORD_CLIENT_ID = '1479103284064026787';
const DISCORD_REDIRECT_PADRAO = 'https://muniz-amz.github.io/amz-studios/';

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
    
    const painel = document.getElementById('painel-loritta');
    painel.classList.remove('hidden');
    painel.classList.add('flex');
    
    document.getElementById('bot-landing').classList.remove('hidden');
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
}

function abrirListaServidores() {
    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    document.getElementById('lista-servidores').classList.remove('hidden');
    
    verificarAutenticacao();
}

function voltarAoInicioBot() {
    document.getElementById('painel-loritta').classList.add('hidden');
    document.getElementById('site-principal').classList.remove('hidden');
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
                localStorage.setItem('servidores_amz', JSON.stringify(dados.servidores));
                renderizarServidores(dados.servidores);
            } else {
                mostrarBotaoLogin(`Erro: ${dados.erro || "Não autorizado"}`);
            }
        } catch (erro) {
            console.error("Erro ao conectar na API:", erro);
            mostrarBotaoLogin("Erro ao conectar ao servidor de segurança.");
        }
    } else {
        const sessaoSalva = localStorage.getItem('servidores_amz');
        if (sessaoSalva) {
            renderizarServidores(JSON.parse(sessaoSalva));
        } else {
            mostrarBotaoLogin("Você precisa provar que é Administrador para acessar as configurações.");
        }
    }
}

function mostrarBotaoLogin(mensagem) {
    const container = document.getElementById('container-servidores');
    container.innerHTML = `
        <div class="text-center py-8 flex flex-col items-center justify-center w-full">
            <p class="text-white/60 text-xs mb-4 max-w-xs">${mensagem}</p>
            <div class="flex flex-col sm:flex-row gap-3">
                <a href="${obterDiscordLoginUrl()}" 
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
    const servidoresDemo = [
        { id: 'demo-celestial', nome: 'Celestial Trindade', icon_url: '' }
    ];

    localStorage.setItem('discord_token', 'demo-token');
    localStorage.setItem('servidores_amz', JSON.stringify(servidoresDemo));
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
                        <option value="1">24 Horas (1 dia)</option>
                        <option value="3">72 Horas (3 dias)</option>
                        <option value="7">168 Horas (7 dias)</option>
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

    if (token === 'demo-token') {
        renderizarSelectCanais(obterCanaisDemo());
        return;
    }

    if (!token) {
        select.innerHTML = '<option value="">Sessao expirada</option>';
        return;
    }

    try {
        const response = await fetch(`${API_URL}/api/servidores/${encodeURIComponent(serverId)}/canais`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const resultado = await response.json();

        if (response.ok && resultado.status === 'sucesso') {
            renderizarSelectCanais(resultado.canais || []);
            return;
        }

        select.innerHTML = `<option value="">${escaparHTML(resultado.mensagem || 'Erro ao carregar canais')}</option>`;
    } catch (erro) {
        console.error('Erro ao carregar canais:', erro);
        select.innerHTML = '<option value="">Erro ao conectar na API</option>';
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
                <a href="${obterDiscordLoginUrl()}" class="cleanup-state-link">
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
    const valor = String(dias);
    const mapa = {
        '1': '1 dia',
        '3': '3 dias',
        '7': '7 dias'
    };

    return mapa[valor] || `${valor} dias`;
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

        if (response.status === 401 || response.status === 403) {
            localStorage.removeItem('discord_token');
            renderizarEstadoLimpezas(resultado.mensagem || 'Sessao expirada. Entre novamente para carregar as limpezas salvas.', true);
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

        if(resultado.status === 'sucesso') {
            const limpezas = resultado.limpezas || [];
            salvarLimpezasCacheServidor(serverId, limpezas);
            renderizarLimpezasConfiguradas(limpezas);
            if(statusMsg) {
                statusMsg.innerText = "Limpeza salva e sincronizada com MongoDB!";
                statusMsg.className = "vm-status-message success";
            }
            alert('Limpeza salva com sucesso!');
        } else {
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
document.addEventListener("DOMContentLoaded", () => {
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('code')) {
        acessarTelaBot();
        document.getElementById('bot-landing').classList.add('hidden');
        document.getElementById('lista-servidores').classList.remove('hidden');
        
        verificarAutenticacao();
        
        window.history.replaceState({}, document.title, window.location.pathname);
    }
});
