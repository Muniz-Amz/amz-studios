// Configuração da API
const API_URL = 'https://SUA-API-NO-RENDER.onrender.com';

// Funções de Navegação
function acessarTelaBot() {
    document.getElementById('site-principal').classList.add('hidden');
    document.getElementById('painel-loritta').classList.remove('hidden');
    document.getElementById('painel-loritta').classList.add('flex');
    carregarServidores();
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

// Carregamento de dados
async function carregarServidores() {
    const container = document.getElementById('container-servidores');
    container.innerHTML = '<p class="text-white/20">Carregando...</p>';
    
    try {
        const response = await fetch(`${API_URL}/servidores`);
        const data = await response.json();
        
        container.innerHTML = data.map(s => `
            <div class="bw-border p-6 bg-black flex justify-between items-center">
                <h3 class="font-bold">${s.nome}</h3>
                <button onclick="configurarServidor('${s.id}', '${s.nome}')" 
                        class="bg-white text-black px-4 py-2 text-[10px] font-black uppercase">Configurar</button>
            </div>
        `).join('');
    } catch (e) {
        container.innerHTML = '<p class="text-red-500">Erro ao carregar.</p>';
    }
}

function configurarServidor(id, nome) {
    document.getElementById('lista-servidores').classList.add('hidden');
    document.getElementById('config-limpeza').classList.remove('hidden');
    document.getElementById('nome-servidor-atual').innerText = nome;
    document.getElementById('canal_id').dataset.id = id;
}