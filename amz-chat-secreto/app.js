import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const config = window.AMZ_SECRET_CHAT_CONFIG || {};
const accounts = normalizeAccounts(config.accounts || config.profiles);

const state = {
    selectedProfile: null,
    supabase: null,
    channel: null,
    roomId: "",
    mediaFile: null,
    onlineProfiles: new Set()
};

const elements = {
    login: document.querySelector("#secret-login"),
    chat: document.querySelector("#chat-app"),
    loginForm: document.querySelector("#login-form"),
    username: document.querySelector("#login-username"),
    password: document.querySelector("#login-password"),
    setupWarning: document.querySelector("#setup-warning"),
    activeProfile: document.querySelector("#active-profile"),
    presenceList: document.querySelector("#presence-list"),
    connectionState: document.querySelector("#connection-state"),
    connectionLabel: document.querySelector("#connection-label"),
    messages: document.querySelector("#messages"),
    messageForm: document.querySelector("#message-form"),
    messageText: document.querySelector("#message-text"),
    mediaInput: document.querySelector("#media-input"),
    attachmentPreview: document.querySelector("#attachment-preview"),
    leaveChat: document.querySelector("#leave-chat")
};

const isConfigured = Boolean(config.supabaseUrl && config.supabaseAnonKey);

function boot() {
    elements.setupWarning.hidden = isConfigured;
    elements.loginForm.addEventListener("submit", enterChat);
    elements.leaveChat.addEventListener("click", leaveChat);
    elements.messageForm.addEventListener("submit", sendMessage);
    elements.mediaInput.addEventListener("change", selectMedia);
    elements.messageText.addEventListener("input", autosizeComposer);
    renderPresence();
    restoreSession();
}

function normalizeAccounts(source) {
    const fallback = [
        {
            id: "perfil_1",
            username: "muniz",
            name: "Muniz",
            role: "Perfil principal",
            color: "#42b9ff",
            passwordHash: ""
        },
        {
            id: "perfil_2",
            username: "amigo",
            name: "Amigo",
            role: "Perfil convidado",
            color: "#30f27b",
            passwordHash: ""
        }
    ];

    if (!Array.isArray(source) || source.length < 1) {
        return fallback;
    }

    return source.map((account, index) => ({
        id: account.id || `perfil_${index + 1}`,
        username: normalizeUsername(account.username || account.name || `perfil${index + 1}`),
        name: account.name || account.username || `Perfil ${index + 1}`,
        role: account.role || "Participante",
        color: account.color || (index === 0 ? "#42b9ff" : "#30f27b"),
        passwordHash: account.passwordHash || ""
    }));
}

async function enterChat(event) {
    event.preventDefault();

    const username = normalizeUsername(elements.username.value);
    const password = elements.password.value;
    const account = accounts.find((item) => item.username === username);

    if (!account || !password) {
        showSetupMessage("Usuário ou senha inválidos.");
        return;
    }

    if (!account.passwordHash) {
        showSetupMessage("Este usuário ainda não tem senha configurada em config.js.");
        return;
    }

    const typedHash = await hashPassword(username, password);

    if (typedHash !== account.passwordHash) {
        showSetupMessage("Usuário ou senha inválidos.");
        return;
    }

    state.selectedProfile = account;
    state.roomId = await hashRoomPassword(password);
    saveSession(account.id, state.roomId);
    elements.password.value = "";
    window.location.hash = "conversa";
    startChat();
}

function restoreSession() {
    try {
        const session = JSON.parse(localStorage.getItem("amz-secret-session") || "null");

        if (!session || session.expiresAt < Date.now()) {
            localStorage.removeItem("amz-secret-session");
            return;
        }

        const account = accounts.find((item) => item.id === session.profileId);

        if (!account || !session.roomId) {
            localStorage.removeItem("amz-secret-session");
            return;
        }

        state.selectedProfile = account;
        state.roomId = session.roomId;
        window.location.hash = "conversa";
        startChat();
    } catch {
        localStorage.removeItem("amz-secret-session");
    }
}

function saveSession(profileId, roomId) {
    const sessionHours = Number(config.sessionHours || 8);
    localStorage.setItem("amz-secret-session", JSON.stringify({
        profileId,
        roomId,
        expiresAt: Date.now() + sessionHours * 60 * 60 * 1000
    }));
}

async function startChat() {
    if (!state.selectedProfile) {
        return;
    }

    elements.login.hidden = true;
    elements.chat.hidden = false;
    renderActiveProfile();
    renderPresence();

    if (!isConfigured) {
        setConnectionState("Config pendente", "error");
        renderEmpty("Login aceito. Para o bate-papo funcionar entre dois aparelhos, configure o Supabase em config.js e rode o arquivo supabase.sql.");
        return;
    }

    state.supabase = createClient(config.supabaseUrl, config.supabaseAnonKey, {
        global: {
            headers: {
                "x-amz-room-id": state.roomId
            }
        }
    });

    setConnectionState("Conectando", "loading");

    try {
        await loadMessages();
        subscribeRealtime();
        setConnectionState("Online", "ready");
    } catch (error) {
        console.error(error);
        setConnectionState("Erro", "error");
        renderEmpty(`Não consegui conectar agora. ${formatError(error)}`);
    }
}

function renderActiveProfile() {
    elements.activeProfile.innerHTML = `
        <div class="profile-avatar" style="--profile-color:${escapeAttribute(state.selectedProfile.color)}">${escapeHtml(getInitials(state.selectedProfile.name))}</div>
        <strong>${escapeHtml(state.selectedProfile.name)}</strong>
        <span>@${escapeHtml(state.selectedProfile.username)} · ${escapeHtml(state.selectedProfile.role)}</span>
    `;
}

async function loadMessages() {
    const { data, error } = await state.supabase
        .from("secret_chat_messages")
        .select("*")
        .eq("room_id", state.roomId)
        .order("created_at", { ascending: true })
        .limit(120);

    if (error) {
        throw error;
    }

    elements.messages.innerHTML = "";

    if (!data.length) {
        renderEmpty("Nenhuma mensagem ainda. Manda a primeira e inaugura a sala.");
        return;
    }

    data.forEach(renderMessage);
    scrollToBottom();
}

function subscribeRealtime() {
    if (state.channel) {
        state.supabase.removeChannel(state.channel);
    }

    state.channel = state.supabase.channel(`amz-secret-chat:${state.roomId}`, {
        config: {
            broadcast: {
                self: false
            },
            presence: {
                key: state.selectedProfile.id
            }
        }
    });

    state.channel
        .on("broadcast", { event: "message" }, (payload) => {
            if (payload.payload?.room_id === state.roomId) {
                removeEmptyState();
                renderMessage(payload.payload);
                scrollToBottom();
            }
        })
        .on("presence", { event: "sync" }, () => {
            const presence = state.channel.presenceState();
            state.onlineProfiles = new Set(Object.keys(presence));
            renderPresence();
        })
        .subscribe(async (status) => {
            if (status === "SUBSCRIBED") {
                await state.channel.track({
                    name: state.selectedProfile.name,
                    online_at: new Date().toISOString()
                });
                setConnectionState("Online", "ready");
            }
        });
}

async function sendMessage(event) {
    event.preventDefault();

    if (!isConfigured || !state.supabase) {
        alert("Configure o Supabase para enviar mensagens de verdade.");
        return;
    }

    const body = elements.messageText.value.trim();

    if (!body && !state.mediaFile) {
        return;
    }

    elements.messageForm.classList.add("is-sending");

    try {
        const attachment = state.mediaFile ? await uploadMedia(state.mediaFile) : {};
        const { data, error } = await state.supabase
            .from("secret_chat_messages")
            .insert({
                room_id: state.roomId,
                profile_id: state.selectedProfile.id,
                profile_name: state.selectedProfile.name,
                body,
                attachment_url: attachment.url || null,
                attachment_path: attachment.path || null,
                attachment_type: attachment.type || null,
                attachment_name: attachment.name || null,
                attachment_size: attachment.size || null
            })
            .select()
            .single();

        if (error) {
            throw error;
        }

        removeEmptyState();
        renderMessage(data);
        scrollToBottom();
        await state.channel?.send({
            type: "broadcast",
            event: "message",
            payload: data
        });

        elements.messageText.value = "";
        clearMedia();
        autosizeComposer();
    } catch (error) {
        console.error(error);
        alert(`Não consegui enviar: ${formatError(error)}`);
    } finally {
        elements.messageForm.classList.remove("is-sending");
    }
}

async function uploadMedia(file) {
    const safeName = file.name.replace(/[^\w.-]+/g, "-").slice(-90);
    const path = `${state.roomId}/${Date.now()}-${crypto.randomUUID()}-${safeName}`;
    const { error } = await state.supabase.storage
        .from(config.bucketName || "secret-chat-media")
        .upload(path, file, {
            cacheControl: "3600",
            upsert: false,
            contentType: file.type
        });

    if (error) {
        throw error;
    }

    const { data } = state.supabase.storage
        .from(config.bucketName || "secret-chat-media")
        .getPublicUrl(path);

    return {
        url: data.publicUrl,
        path,
        type: file.type.startsWith("image/") ? "image" : "video",
        name: file.name,
        size: file.size
    };
}

function selectMedia(event) {
    const file = event.target.files?.[0];

    if (!file) {
        clearMedia();
        return;
    }

    const isImage = file.type.startsWith("image/");
    const isVideo = file.type.startsWith("video/");

    if (!isImage && !isVideo) {
        alert("Escolha apenas imagem ou vídeo.");
        clearMedia();
        return;
    }

    const maxMb = isImage ? Number(config.maxImageMb || 10) : Number(config.maxVideoMb || 60);

    if (file.size > maxMb * 1024 * 1024) {
        alert(`Esse arquivo é grande demais. Limite: ${maxMb}MB.`);
        clearMedia();
        return;
    }

    state.mediaFile = file;
    elements.attachmentPreview.hidden = false;
    elements.attachmentPreview.innerHTML = `
        <strong>${isImage ? "Imagem" : "Vídeo"} pronto para envio:</strong>
        ${escapeHtml(file.name)}
        <button type="button" id="clear-media">remover</button>
    `;
    document.querySelector("#clear-media").addEventListener("click", clearMedia);
}

function clearMedia() {
    state.mediaFile = null;
    elements.mediaInput.value = "";
    elements.attachmentPreview.hidden = true;
    elements.attachmentPreview.innerHTML = "";
}

function renderMessage(message) {
    const isOwn = message.profile_id === state.selectedProfile.id;
    const media = renderMedia(message);
    const node = document.createElement("article");
    node.className = `message ${isOwn ? "own" : ""}`;
    node.innerHTML = `
        <header class="message-head">
            <span>${escapeHtml(message.profile_name || "Perfil")}</span>
            <time>${formatTime(message.created_at)}</time>
        </header>
        ${message.body ? `<div class="message-body">${escapeHtml(message.body)}</div>` : ""}
        ${media}
    `;
    removeEmptyState();
    elements.messages.appendChild(node);
}

function renderMedia(message) {
    if (!message.attachment_url) {
        return "";
    }

    if (message.attachment_type === "image") {
        return `<img class="message-media" src="${escapeAttribute(message.attachment_url)}" alt="${escapeAttribute(message.attachment_name || "Imagem enviada")}" loading="lazy">`;
    }

    if (message.attachment_type === "video") {
        return `<video class="message-media" src="${escapeAttribute(message.attachment_url)}" controls playsinline preload="metadata"></video>`;
    }

    return `
        <a class="file-link" href="${escapeAttribute(message.attachment_url)}" target="_blank" rel="noopener">
            <i class="ph ph-download-simple"></i>
            ${escapeHtml(message.attachment_name || "Abrir arquivo")}
        </a>
    `;
}

function renderPresence() {
    elements.presenceList.innerHTML = accounts.map((account) => {
        const online = state.onlineProfiles.has(account.id) || account.id === state.selectedProfile?.id;
        return `
            <div class="presence-item ${online ? "online" : ""}">
                <span>${escapeHtml(account.name)}</span>
                <span class="presence-dot" title="${online ? "Online" : "Offline"}"></span>
            </div>
        `;
    }).join("");
}

function leaveChat() {
    localStorage.removeItem("amz-secret-session");
    if (state.channel && state.supabase) {
        state.supabase.removeChannel(state.channel);
    }
    state.selectedProfile = null;
    state.channel = null;
    state.supabase = null;
    state.roomId = "";
    state.onlineProfiles = new Set();
    elements.chat.hidden = true;
    elements.login.hidden = false;
    window.location.hash = "login";
    renderPresence();
}

function renderEmpty(text) {
    elements.messages.innerHTML = `<div class="empty-state">${escapeHtml(text)}</div>`;
}

function removeEmptyState() {
    elements.messages.querySelector(".empty-state")?.remove();
}

function setConnectionState(text, mode) {
    elements.connectionState.classList.remove("ready", "error");
    if (mode === "ready") {
        elements.connectionState.classList.add("ready");
    }
    if (mode === "error") {
        elements.connectionState.classList.add("error");
    }
    elements.connectionLabel.textContent = text;
}

function showSetupMessage(text) {
    elements.setupWarning.hidden = false;
    elements.setupWarning.textContent = text;
}

function autosizeComposer() {
    elements.messageText.style.height = "auto";
    elements.messageText.style.height = `${elements.messageText.scrollHeight}px`;
}

function scrollToBottom() {
    elements.messages.scrollTop = elements.messages.scrollHeight;
}

async function hashPassword(username, password) {
    return sha256(`${config.roomSalt || "amz-secret"}:${username}:${password}`);
}

async function hashRoomPassword(password) {
    return sha256(`${config.roomSalt || "amz-secret"}:${password}`);
}

async function sha256(source) {
    const bytes = new TextEncoder().encode(source);
    const hash = await crypto.subtle.digest("SHA-256", bytes);
    return [...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function normalizeUsername(value) {
    return String(value || "").trim().toLowerCase();
}

function getInitials(name) {
    return name
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((part) => part[0])
        .join("")
        .toUpperCase();
}

function formatTime(value) {
    if (!value) {
        return "";
    }
    return new Intl.DateTimeFormat("pt-BR", {
        hour: "2-digit",
        minute: "2-digit"
    }).format(new Date(value));
}

function formatError(error) {
    return error?.message || "erro desconhecido";
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function escapeAttribute(value) {
    return escapeHtml(value).replaceAll("`", "&#096;");
}

boot();
