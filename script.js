// Configuração da API
const API_URL = 'https://amz-studios-api.onrender.com';

// URL oficial do OAuth2 apontando para a raiz do seu GitHub Pages
const DISCORD_LOGIN_URL = "https://discord.com/oauth2/authorize?client_id=1479103284064026787&response_type=code&redirect_uri=https%3A%2F%2Fmuniz-amz.github.io%2Famz-studios.github.io%2F&scope=identify+guilds"; 

// ==========================================
// FUNÇÕES DE NAVEGAÇÃO CORRIGIDAS
// ==========================================

// 1. Quando clica em "Acessar Painel do Bot" na Home do Hub
function acessarTelaBot() {
    // Esconde a página principal do Hub
    document.getElementById('site-principal').classList.add('hidden');
    
    // Mostra o container do painel
    const painel = document.getElementById('painel-loritta');
    painel.classList.remove('hidden');
    painel.classList.add('flex');
    
    // Verifica se o usuário já possui um login salvo no navegador
    const sessaoSalva = localStorage.getItem('servidores_amz');
    
    if (sessaoSalva) {
        // Se já está logado, pula a introdução azul e vai direto para a lista de servidores
        document.getElementById('bot-landing').classList.add('hidden');
        document.getElementById('config-limpeza').classList.add('hidden');
        document.getElementById('lista-servidores').classList.remove('hidden');
        renderizarServidores(JSON.parse(sessaoSalva));
    } else {
        // Se não está logado, mostra a sua tela do AMZ BOT (Apresentação com os 3 botões)
        document.getElementById('bot-landing').classList.remove('hidden');
        document.getElementById('lista-servidores').classList.add('hidden');
        document.getElementById('config-limpeza').classList.add('hidden');
    }
}

// 2. Quando clica no botão "Painel de Controle" dentro da tela do AMZ BOT
function abrirListaServidores() {
    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    document.getElementById('lista-servidores').classList.remove('hidden');
    
    // Dispara a segurança para conferir o login do administrador
    verificarAutenticacao();
}

// 3. Voltar para a Home do Hub
function voltarAoInicioBot() {
    document.getElementById('painel-loritta').classList.add('hidden');
    document.getElementById('site-principal').classList.remove('hidden');
}

// ==========================================
// SISTEMA DE SEGURANÇA E AUTENTICAÇÃO (ESTILO LORITTA)
// ==========================================

async function verificarAutenticacao() {
    const container = document.getElementById('container-servidores');
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');

    // Se o usuário acabou de voltar da autorização do Discord com o código na URL
    if (code) {
        container.innerHTML = '<p class="text-white/20 animate-pulse">Verificando suas permissões de Administrador...</p>';
        
        try {
            const response = await fetch(`${API_URL}/api/auth/callback`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ code: code })
            });

            const dados = await response.json();

            if (response.ok && dados.status === "sucesso") {
                // SALVA A SESSÃO NO NAVEGADOR: É isso que faz o login persistir
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
        // Se não possui código na URL, tenta buscar o que está salvo no navegador
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
            <a href="${DISCORD_LOGIN_URL}" 
               class="bg-white text-black px-6 py-3 text-[11px] font-black uppercase tracking-wider transition-all hover:bg-black hover:text-white border border-white">
                Entrar com o Discord
            </a>
        </div>
    `;
}

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
// CONFIGURAÇÕES DO SERVIDOR
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

// ==========================================
// INTERCEPTADOR DE REDIRECIONAMENTO (MOMENTO DO RETORNO)
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('code')) {
        // 1. Abre os containers corretos do painel visualmente
        acessarTelaBot();
        document.getElementById('bot-landing').classList.add('hidden');
        document.getElementById('lista-servidores').classList.remove('hidden');
        
        // 2. Faz a chamada de segurança para ler o código
        verificarAutenticacao();
        
        // 3. Limpa imediatamente o "?code=..." da URL para evitar bugs de F5
        window.history.replaceState({}, document.title, window.location.pathname);
    }
});