// Configuração da API
const API_URL = 'https://amz-studios-api.onrender.com';

// URL oficial do OAuth2 que você gerou no portal do Discord
const DISCORD_LOGIN_URL = "https://discord.com/oauth2/authorize?client_id=1479103284064026787&response_type=code&redirect_uri=https%3A%2F%2Fmuniz-amz.github.io%2Famz-studios.github.io%2F&scope=identify+guilds"; 

// Funções de Navegação
function acessarTelaBot() {
    document.getElementById('site-principal').classList.add('hidden');
    document.getElementById('painel-loritta').classList.remove('hidden');
    document.getElementById('painel-loritta').classList.add('flex');
    
    // Em vez de carregar direto, vamos verificar a autenticação primeiro
    verificarAutenticacao();
}

function voltarAoInicioBot() {
    document.getElementById('painel-loritta').classList.add('hidden');
    document.getElementById('site-principal').classList.remove('hidden');
}

function abrirListaServidores() {
    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    document.getElementById('lista-servidores').classList.remove('hidden');
}

// ==========================================
// SISTEMA DE SEGURANÇA E AUTENTICAÇÃO
// ==========================================

// Esta função roda assim que o usuário clica para ver o painel do Bot
async function verificarAutenticacao() {
    const container = document.getElementById('container-servidores');
    
    // 1. Verifica se o Discord acabou de redirecionar com o ?code=... na URL
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');

    if (code) {
        // Limpa a URL do navegador para remover o código visualmente e ficar elegante
        window.history.replaceState({}, document.title, window.location.pathname);
        
        container.innerHTML = '<p class="text-white/20 animate-pulse">Verificando suas permissões de Administrador...</p>';
        
        try {
            // Envia o código para o seu app.py validar no Render
            const response = await fetch(`${API_URL}/api/auth/callback`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ code: code })
            });

            const dados = await response.json();

            if (response.ok && dados.status === "sucesso") {
                // Guarda os servidores autorizados no navegador temporariamente para usar depois
                window.servidoresAutenticados = dados.servidores;
                
                // Abre a lista e renderiza os servidores seguros
                abrirListaServidores();
                renderizarServidores(dados.servidores);
            } else {
                mostrarBotaoLogin(`Erro: ${dados.erro || "Não autorizado"}`);
            }
        } catch (erro) {
            console.error("Erro ao conectar na API:", erro);
            mostrarBotaoLogin("Erro ao conectar ao servidor de segurança.");
        }
    } else if (window.servidoresAutenticados) {
        // Se o usuário já logou nesta sessão, apenas abre a lista de novo
        abrirListaServidores();
        renderizarServidores(window.servidoresAutenticados);
    } else {
        // Se não tem código e não está logado, bloqueia a tela e pede login
        mostrarBotaoLogin("Você precisa provar que é Administrador para acessar as configurações.");
    }
}

// Renderiza a tela de bloqueio com o botão preto e branco padrão AMZ Studios
function mostrarBotaoLogin(mensagem) {
    const container = document.getElementById('container-servidores');
    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    document.getElementById('lista-servidores').classList.remove('hidden');

    container.innerHTML = `
        <div class="text-center py-8 flex flex-col items-center justify-center w-full">
            <p class="text-white/60 text-xs mb-4 max-w-xs">${mensagem}</p>
            <a href="${DISCORD_LOGIN_URL}" 
               class="bg-white text-black px-6 py-3 text-[11px] font-black uppercase tracking-wider transition-all hover:bg-black hover:text-white border border-white">
                Entrar com o Discord
            </a>
        </div>
    `;
}

// Desenha na tela apenas os servidores onde o usuário é ADMIN de verdade
function renderizarServidores(servidores) {
    const container = document.getElementById('container-servidores');
    
    if (servidores.length === 0) {
        container.innerHTML = '<p class="text-white/40 text-xs py-4">Você não é Administrador de nenhum servidor em comum com o Celestial Bot.</p>';
        return;
    }

    container.innerHTML = servidores.map(s => `
        <div class="bw-border p-6 bg-black flex justify-between items-center w-full">
            <h3 class="font-bold text-white">${s.nome}</h3>
            <button onclick="configurarServidor('${s.id}', '${s.nome}')" 
                    class="bg-white text-black px-4 py-2 text-[10px] font-black uppercase hover:bg-black hover:text-white border border-white transition-colors">
                Configurar
            </button>
        </div>
    `).join('');
}

// ==========================================
// CONFIGURAÇÕES DO SERVIDOR (SUA LÓGICA ATUAL)
// ==========================================
function configurarServidor(id, nome) {
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.remove('hidden');
    document.getElementById('nome-servidor-atual').innerText = nome;
    document.getElementById('canal_id').dataset.id = id;
}

async function salvarConfiguracoes() {
    const serverId = document.getElementById('canal_id').dataset.id; 
    const dados = {
        id: serverId,
        nome: document.getElementById('nome-servidor-atual').innerText,
        dias: document.getElementById('input-dias').value 
    };

    try {
        const response = await fetch(`${API_URL}/api/config`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(dados)
        });
        
        const resultado = await response.json();
        if(resultado.status === 'sucesso') {
            alert('Configurações salvas com sucesso!');
        }
    } catch (e) {
        console.error('Erro ao salvar:', e);
        alert('Erro ao salvar configurações.');
    }
}

// ESSENCIAL: Se o cara voltar com o ?code= na URL, o site já abre a tela do bot direto
if (window.location.search.includes('code=')) {
    acessarTelaBot();
}