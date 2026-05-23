// Configuração da API
const API_URL = 'https://amz-studios-api.onrender.com';

// URL oficial do OAuth2 padrão (com a barra normal no final)
const DISCORD_LOGIN_URL = "https://discord.com/oauth2/authorize?client_id=1479103284064026787&response_type=code&redirect_uri=https%3A%2F%2Fmuniz-amz.github.io%2Famz-studios%2F&scope=identify+guilds";

// ==========================================
// FUNÇÕES DE NAVEGAÇÃO
// ==========================================

function acessarTelaBot() {
    // Esconde a página principal do Hub
    document.getElementById('site-principal').classList.add('hidden');
    
    // Mostra o container do painel
    const painel = document.getElementById('painel-loritta');
    painel.classList.remove('hidden');
    painel.classList.add('flex');
    
    // Mostra SEMPRE a tela de introdução primeiro (para exibir o botão "Me Adicione")
    document.getElementById('bot-landing').classList.remove('hidden');
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
}

function abrirListaServidores() {
    document.getElementById('bot-landing').classList.add('hidden');
    document.getElementById('config-limpeza').classList.add('hidden');
    document.getElementById('lista-servidores').classList.remove('hidden');
    
    // Dispara a segurança para conferir o login do administrador
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
                body: JSON.stringify({ code: code })
            });

            const dados = await response.json();

            if (response.ok && dados.status === "sucesso") {
                // Salva a sessão no navegador para persistir o login
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
        // Se não tem código na URL, tenta usar o que já está salvo localmente
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
        <div class="bw-border p-6 bg-black flex justify-between items-center w-full border border-white/10">
            <h3 class="font-bold text-white text-sm">${s.nome}</h3>
            <button onclick="configurarServidor('${s.id}', '${s.nome}')" 
                    class="bg-white text-black px-4 py-2 text-[10px] font-black uppercase hover:bg-black hover:text-white border border-white transition-colors">
                Configurar
            </button>
        </div>
    `).join('');
}

// ==========================================
// CONFIGURAÇÕES DO SERVIDOR (SINCRONIZADO COM INDEX.HTML)
// ==========================================
function configurarServidor(id, nome) {
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.remove('hidden');
    document.getElementById('nome-servidor-atual').innerText = nome;
    document.getElementById('canal_id').value = ""; 
    document.getElementById('canal_id').dataset.id = id;
}

async function enviarConfiguracao() {
    const serverId = document.getElementById('canal_id').dataset.id;
    const canalInput = document.getElementById('canal_id').value;
    const diasSelect = document.getElementById('dias').value; // ID correto do select no HTML
    const statusMsg = document.getElementById('status_msg');
    const iconSync = document.getElementById('icon-sync');

    if (!canalInput) {
        alert('Por favor, insira o ID do canal do Discord.');
        return;
    }

    const dados = {
        id: serverId,
        nome: document.getElementById('nome-servidor-atual').innerText,
        canal_id: canalInput,
        dias: diasSelect 
    };

    try {
        if(iconSync) iconSync.classList.add('animate-spin');
        
        const response = await fetch(`${API_URL}/api/config`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(dados)
        });
        
        const resultado = await response.json();
        
        if (iconSync) iconSync.classList.remove('animate-spin');

        if(resultado.status === 'sucesso') {
            if(statusMsg) {
                statusMsg.innerText = "Sincronizado com MongoDB com sucesso!";
                statusMsg.className = "text-[10px] uppercase tracking-widest text-center mt-4 text-green-500 font-black block";
            }
            alert('Configurações salvas com sucesso!');
        } else {
            alert('Erro ao salvar: ' + (resultado.erro || 'Resposta inválida do servidor.'));
        }
    } catch (e) {
        console.error('Erro ao salvar:', e);
        if (iconSync) iconSync.classList.remove('animate-spin');
        alert('Erro ao conectar na API para salvar configurações.');
    }
}

// ==========================================
// INTERCEPTADOR DE REDIRECIONAMENTO PADRÃO
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('code')) {
        acessarTelaBot();
        document.getElementById('bot-landing').classList.add('hidden');
        document.getElementById('lista-servidores').classList.remove('hidden');
        
        verificarAutenticacao();
        
        // Limpa a URL de forma limpa (?code=...) removendo o lixo visual da barra
        window.history.replaceState({}, document.title, window.location.pathname);
    }
});