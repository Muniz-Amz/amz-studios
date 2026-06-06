import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const config = window.AMZ_SECRET_CHAT_CONFIG || {};
const profiles = Array.isArray(config.profiles) && config.profiles.length === 2
    ? config.profiles
    : [
        { id: "perfil_1", name: "Perfil 1", role: "Principal", color: "#42b9ff" },
        { id: "perfil_2", name: "Perfil 2", role: "Convidado", color: "#30f27b" }
    ];

const state = {
    selectedProfile: profiles[0],
    supabase: null,
    channel: null,
    roomId: "",
    mediaFile: null,
    onlineProfiles: new Set()
};

const elements = {
    login: document.querySelector("#secret-login"),
    chat: document.querySelector("#chat-app"),
    profileGrid: document.querySelector("#profile-grid"),
    roomCode: document.querySelector("#room-code"),
    enterChat: document.querySelector("#enter-chat"),
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
    renderProfiles();
    renderPresence();
    elements.setupWarning.hidden = isConfigured;
    elements.enterChat.addEventListener("click", enterChat);
    elements.leaveChat.addEventListener("click", leaveChat);
    elements.messageForm.addEventListener("submit", sendMessage);
    elements.mediaInput.addEventListener("change", selectMedia);
    elements.messageText.addEventListener("input", autosizeComposer);
    elements.roomCode.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            enterChat();
        }
    });

    const savedProfile = localStorage.getItem("amz-secret-profile");
    const savedRoom = localStorage.getItem("amz-secret-room");
    const profile = profiles.find((item) => item.id === savedProfile);

    if (profile && savedRoom && isConfigured) {
        state.selectedProfile = profile;
        state.roomId = savedRoom;
        startChat();
    }
}

function renderProfiles() {
    elements.profileGrid.innerHTML = profiles.map((profile) => `
        <button class="profile-card ${profile.id === state.selectedProfile.id ? "active" : ""}" type="button" data-profile="${escapeHtml(profile.id)}" style="--profile-color:${escapeAttribute(profile.color)}">
            <span class="profile-avatar">${escapeHtml(getInitials(profile.name))}</span>
            <strong>${escapeHtml(profile.name)}</strong>
            <small>${escapeHtml(profile.role)}</small>
        </button>
    `).join("");

    elements.profileGrid.querySelectorAll(".profile-card").forEach((button) => {
        button.addEventListener("click", () => {
            state.selectedProfile = profiles.find((profile) => profile.id === button.dataset.profile) || profiles[0];
            renderProfiles();
        });
    });
}

async function enterChat() {
    if (!isConfigured) {
        elements.setupWarning.hidden = false;
        return;
    }

    const roomCode = elements.roomCode.value.trim();

    if (roomCode.length < 6) {
        showSetupMessage("Use um código com pelo menos 6 caracteres.");
        return;
    }

    state.roomId = await hashRoomCode(roomCode);
    localStorage.setItem("amz-secret-profile", state.selectedProfile.id);
    localStorage.setItem("amz-secret-room", state.roomId);
    elements.roomCode.value = "";
    startChat();
}

async function startChat() {
    state.supabase = createClient(config.supabaseUrl, config.supabaseAnonKey, {
        global: {
            headers: {
                "x-amz-room-id": state.roomId
            }
        }
    });
    elements.login.hidden = true;
    elements.chat.hidden = false;
    renderActiveProfile();
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
        <span>${escapeHtml(state.selectedProfile.role)}</span>
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
    elements.presenceList.innerHTML = profiles.map((profile) => {
        const online = state.onlineProfiles.has(profile.id) || profile.id === state.selectedProfile.id;
        return `
            <div class="presence-item ${online ? "online" : ""}">
                <span>${escapeHtml(profile.name)}</span>
                <span class="presence-dot" title="${online ? "Online" : "Offline"}"></span>
            </div>
        `;
    }).join("");
}

function leaveChat() {
    localStorage.removeItem("amz-secret-profile");
    localStorage.removeItem("amz-secret-room");
    if (state.channel && state.supabase) {
        state.supabase.removeChannel(state.channel);
    }
    state.channel = null;
    state.roomId = "";
    state.onlineProfiles = new Set();
    elements.chat.hidden = true;
    elements.login.hidden = false;
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

async function hashRoomCode(roomCode) {
    const source = `${config.roomSalt || "amz-secret"}:${roomCode}`;
    const bytes = new TextEncoder().encode(source);
    const hash = await crypto.subtle.digest("SHA-256", bytes);
    return [...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
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
