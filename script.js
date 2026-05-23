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
    
    // TRAZ DE VOLTA A SUA PÁGINA DO AMZ BOT (Apresentação com os 3 botões)
    document.getElementById('bot-landing').classList.remove('hidden');
    
    // Garante que as telas internas de configuração começam estritamente escondidas
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
}

// 2. Quando clica no botão roxo/customizado "Painel de Controle" dentro da tela do AMZ BOT
function abrirListaServidores() {
    // Esconde a apresentação do bot que você acabou de ver
    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    
    // Mostra a tela escura "//SERVIDORES_VINCULADOS"
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
// SISTEMA DE SEGURANÇA E AUTENTICAÇÃO
// ==========================================

async function verificarAutenticacao() {
    const container = document.getElementById('container-servidores');
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');

    // Se o usuário acabou de voltar da autorização do Discord
    if (code) {
        // Limpa o "?code=..." da URL na mesma hora para não quebrar o F5 do navegador
        window.history.replaceState({}, document.title, window.location.pathname);
        
        container.innerHTML = '<p class="text-white/20 animate-pulse">Verificando suas permissões de Administrador...</p>';
        
        try {
            const response = await fetch(`${API_URL}/api/auth/callback`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ code: code })
            });

            const dados = await response.json();

            if (response.ok && dados.status === "sucesso") {
                window.servidoresAutenticados = dados.servidores;
                renderizarServidores(dados.servidores);
            } else {
                mostrarBotaoLogin(`Erro: ${dados.erro || "Não autorizado"}`);
            }
        } catch (erro) {
            console.error("Erro ao conectar na API:", erro);
            mostrarBotaoLogin("Erro ao conectar ao servidor de segurança.");
        }
    } else if (window.servidoresAutenticados) {
        // Se já fez login nessa sessão, mostra os servidores direto
        renderizarServidores(window.servidoresAutenticados);
    } else {
        // Se não está logado, joga o botão de autenticação dentro do container de servidores ativos
        mostrarBotaoLogin("Você precisa provar que é Administrador para acessar as configurações.");
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

// Se o usuário acabar de voltar do Discord com o token, o sistema pula a introdução e joga ele direto na lista de servidores validados
document.addEventListener("DOMContentLoaded", () => {
    if (window.location.search.includes('code=')) {
        acessarTelaBot();
        abrirListaServidores();
    }
});