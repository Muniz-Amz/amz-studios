import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const config = window.AMZ_SECRET_CHAT_CONFIG || {};
const accounts = normalizeAccounts(config.accounts || config.profiles);

const state = {
    selectedProfile: null,
    supabase: null,
    channel: null,
    roomId: "",
    encryptionKey: null,
    mediaFile: null,
    onlineProfiles: new Set()
};

const elements = {
    login: document.querySelector("#secret-login"),
    chat: document.querySelector("#chat-app"),
    loginForm: document.querySelector("#login-form"),
    username: document.querySelector("#login-username"),
    password: document.querySelector("#login-password"),
    privateKey: document.querySelector("#login-private-key"),
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
const messageRetentionHours = Math.max(1, Number(config.messageRetentionHours || 24));
const encryptionEnabled = config.encryptionEnabled !== false;
const encryptionIterations = Math.max(100000, Number(config.encryptionIterations || 250000));
const encryptedTextPrefix = "amzenc:v1";
const encryptedMediaMagic = new TextEncoder().encode("AMZENC1");
const encryptionSalt = `${config.roomSalt || "amz-secret"}:e2ee:v1`;

async function boot() {
    showLogin();
    elements.setupWarning.hidden = isConfigured;
    elements.loginForm.addEventListener("submit", enterChat);
    elements.leaveChat.addEventListener("click", leaveChat);
    elements.messageForm.addEventListener("submit", sendMessage);
    elements.mediaInput.addEventListener("change", selectMedia);
    elements.messages.addEventListener("click", saveMediaFromMessage);
    elements.messageText.addEventListener("input", autosizeComposer);
    window.addEventListener("hashchange", guardConversationRoute);
    renderPresence();
    await restoreSession();
    guardConversationRoute();
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
    const privateKey = elements.privateKey.value.trim();
    const account = accounts.find((item) => item.username === username);

    if (!account || !password) {
        showSetupMessage("Usuário ou senha inválidos.");
        return;
    }

    if (encryptionEnabled && privateKey.length < 16) {
        showSetupMessage("Use uma chave privada da conversa com pelo menos 16 caracteres.");
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
    state.encryptionKey = encryptionEnabled ? await deriveEncryptionKey(privateKey) : null;
    state.roomId = encryptionEnabled ? await hashRoomSecret(privateKey) : await hashRoomSecret(password);
    saveSession(account.id, state.roomId);
    if (encryptionEnabled) {
        sessionStorage.setItem("amz-secret-e2ee-key", privateKey);
    }
    elements.password.value = "";
    elements.privateKey.value = "";
    window.location.hash = "conversa";
    startChat();
}

async function restoreSession() {
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

        const privateKey = sessionStorage.getItem("amz-secret-e2ee-key");

        if (encryptionEnabled && !privateKey) {
            localStorage.removeItem("amz-secret-session");
            return;
        }

        state.selectedProfile = account;
        state.roomId = session.roomId;
        state.encryptionKey = encryptionEnabled ? await deriveEncryptionKey(privateKey) : null;
        window.location.hash = "conversa";
        startChat();
    } catch {
        localStorage.removeItem("amz-secret-session");
        sessionStorage.removeItem("amz-secret-e2ee-key");
    }
}

function guardConversationRoute() {
    if (window.location.hash === "#conversa" && !state.selectedProfile) {
        showLogin();
        window.location.hash = "login";
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
        showLogin();
        return;
    }

    if (encryptionEnabled && !state.encryptionKey) {
        showLogin();
        showSetupMessage("Digite a chave privada da conversa para descriptografar as mensagens.");
        return;
    }

    showChat();
    renderActiveProfile();
    renderPresence();

    if (!isConfigured) {
        setConnectionState("Config pendente", "error");
        renderEmpty(`Login aceito. Para o bate-papo funcionar entre dois aparelhos, configure o Supabase em config.js e rode o arquivo supabase.sql. As conversas expiram em ${messageRetentionHours}h.`);
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
        await cleanupExpiredMessages();
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
        .gte("created_at", getRetentionCutoffIso())
        .order("created_at", { ascending: true })
        .limit(120);

    if (error) {
        throw error;
    }

    elements.messages.innerHTML = "";

    if (!data.length) {
        renderEmpty(`Nenhuma mensagem recente. As conversas somem depois de ${messageRetentionHours}h.`);
        return;
    }

    for (const message of data) {
        await renderMessage(message);
    }
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
        .on("broadcast", { event: "message" }, async (payload) => {
            if (payload.payload?.room_id === state.roomId && !isExpiredMessage(payload.payload)) {
                removeEmptyState();
                await renderMessage(payload.payload);
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

async function cleanupExpiredMessages() {
    try {
        const { data: expiredPaths } = await state.supabase.rpc("get_expired_secret_chat_media_paths");

        if (Array.isArray(expiredPaths) && expiredPaths.length > 0) {
            await state.supabase.storage
                .from(config.bucketName || "secret-chat-media")
                .remove(expiredPaths);
        }

        await state.supabase.rpc("cleanup_secret_chat_messages");
    } catch (error) {
        console.warn("Limpeza automática indisponível:", formatError(error));
    }
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
        const encryptedBody = body ? await encryptText(body) : null;
        const attachment = state.mediaFile ? await uploadMedia(state.mediaFile) : {};
        const { data, error } = await state.supabase
            .from("secret_chat_messages")
            .insert({
                room_id: state.roomId,
                profile_id: state.selectedProfile.id,
                profile_name: state.selectedProfile.name,
                body: encryptedBody,
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
        await renderMessage(data);
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
    const encryptedFile = encryptionEnabled
        ? await encryptFile(file)
        : {
            blob: file,
            contentType: file.type,
            suffix: ""
        };
    const path = `${state.roomId}/${Date.now()}-${crypto.randomUUID()}-${safeName}${encryptedFile.suffix}`;
    const { error } = await state.supabase.storage
        .from(config.bucketName || "secret-chat-media")
        .upload(path, encryptedFile.blob, {
            cacheControl: "3600",
            upsert: false,
            contentType: encryptedFile.contentType
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

async function renderMessage(message) {
    if (isExpiredMessage(message)) {
        return;
    }

    const isOwn = message.profile_id === state.selectedProfile.id;
    const body = await decryptMessageBody(message.body);
    const media = renderMedia(message);
    const node = document.createElement("article");
    node.className = `message ${isOwn ? "own" : ""}`;
    node.innerHTML = `
        <header class="message-head">
            <span>${escapeHtml(message.profile_name || "Perfil")}</span>
            <time>${formatTime(message.created_at)}</time>
        </header>
        ${body ? `<div class="message-body">${escapeHtml(body)}</div>` : ""}
        ${media}
    `;
    removeEmptyState();
    elements.messages.appendChild(node);
    hydrateMessageMedia(node, message);
}

function renderMedia(message) {
    if (!message.attachment_url) {
        return "";
    }

    const saveAction = renderSaveMediaAction(message, true);
    const placeholder = `<div class="message-media-placeholder" data-media-placeholder>Descriptografando mídia...</div>`;

    if (message.attachment_type === "image") {
        return `
            ${placeholder}
            ${saveAction}
        `;
    }

    if (message.attachment_type === "video") {
        return `
            ${placeholder}
            ${saveAction}
        `;
    }

    return `
        <a class="file-link" href="${escapeAttribute(message.attachment_url)}" target="_blank" rel="noopener">
            <i class="ph ph-download-simple"></i>
            ${escapeHtml(message.attachment_name || "Abrir arquivo")}
        </a>
        ${saveAction}
    `;
}

function renderSaveMediaAction(message, hidden = false) {
    const fileName = message.attachment_name || `amz-chat-${message.attachment_type || "midia"}-${message.id || Date.now()}`;

    return `
        <div class="media-actions">
            <a class="media-save" href="${escapeAttribute(message.attachment_url)}" download="${escapeAttribute(fileName)}" data-save-media data-url="${escapeAttribute(message.attachment_url)}" data-name="${escapeAttribute(fileName)}" target="_blank" rel="noopener" ${hidden ? "hidden" : ""}>
                <i class="ph ph-download-simple"></i>
                Salvar mídia
            </a>
        </div>
    `;
}

async function hydrateMessageMedia(node, message) {
    if (!message.attachment_url || !["image", "video"].includes(message.attachment_type)) {
        return;
    }

    const placeholder = node.querySelector("[data-media-placeholder]");
    const saveAction = node.querySelector("[data-save-media]");

    if (!placeholder) {
        return;
    }

    try {
        const response = await fetch(message.attachment_url, { mode: "cors" });

        if (!response.ok) {
            throw new Error("mídia indisponível");
        }

        const encryptedBlob = await response.blob();
        const mediaBlob = encryptionEnabled
            ? await decryptMediaBlob(encryptedBlob, message)
            : encryptedBlob;
        const objectUrl = URL.createObjectURL(mediaBlob);

        if (message.attachment_type === "image") {
            placeholder.outerHTML = `<img class="message-media" src="${escapeAttribute(objectUrl)}" alt="${escapeAttribute(message.attachment_name || "Imagem enviada")}" loading="lazy">`;
        } else {
            placeholder.outerHTML = `<video class="message-media" src="${escapeAttribute(objectUrl)}" controls playsinline preload="metadata"></video>`;
        }

        if (saveAction) {
            saveAction.hidden = false;
            saveAction.href = objectUrl;
            saveAction.dataset.url = objectUrl;
        }
    } catch (error) {
        console.warn("Não consegui descriptografar mídia:", formatError(error));
        placeholder.textContent = "Não consegui abrir esta mídia. Verifique se a chave privada está correta.";
        placeholder.classList.add("error");
    }
}

async function saveMediaFromMessage(event) {
    const action = event.target.closest("[data-save-media]");

    if (!action) {
        return;
    }

    event.preventDefault();
    const url = action.dataset.url;
    const fileName = action.dataset.name || "amz-chat-midia";
    action.classList.add("is-saving");

    try {
        const response = await fetch(url, { mode: "cors" });

        if (!response.ok) {
            throw new Error("download indisponível");
        }

        const blob = await response.blob();
        const objectUrl = URL.createObjectURL(blob);
        const downloadLink = document.createElement("a");
        downloadLink.href = objectUrl;
        downloadLink.download = fileName;
        document.body.appendChild(downloadLink);
        downloadLink.click();
        downloadLink.remove();
        setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (error) {
        console.warn("Fallback de salvamento:", formatError(error));
        window.open(url, "_blank", "noopener");
    } finally {
        action.classList.remove("is-saving");
    }
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
    sessionStorage.removeItem("amz-secret-e2ee-key");
    if (state.channel && state.supabase) {
        state.supabase.removeChannel(state.channel);
    }
    state.selectedProfile = null;
    state.encryptionKey = null;
    state.channel = null;
    state.supabase = null;
    state.roomId = "";
    state.onlineProfiles = new Set();
    showLogin();
    window.location.hash = "login";
    renderPresence();
}

function showLogin() {
    elements.chat.hidden = true;
    elements.login.hidden = false;
    elements.activeProfile.innerHTML = "";
    elements.messages.innerHTML = "";
}

function showChat() {
    elements.login.hidden = true;
    elements.chat.hidden = false;
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

function getRetentionCutoffIso() {
    return new Date(Date.now() - messageRetentionHours * 60 * 60 * 1000).toISOString();
}

function isExpiredMessage(message) {
    if (!message?.created_at) {
        return false;
    }

    return new Date(message.created_at).getTime() < Date.now() - messageRetentionHours * 60 * 60 * 1000;
}

async function deriveEncryptionKey(secret) {
    const keyMaterial = await crypto.subtle.importKey(
        "raw",
        new TextEncoder().encode(secret),
        "PBKDF2",
        false,
        ["deriveKey"]
    );

    return crypto.subtle.deriveKey(
        {
            name: "PBKDF2",
            salt: new TextEncoder().encode(encryptionSalt),
            iterations: encryptionIterations,
            hash: "SHA-256"
        },
        keyMaterial,
        {
            name: "AES-GCM",
            length: 256
        },
        false,
        ["encrypt", "decrypt"]
    );
}

async function encryptText(text) {
    if (!encryptionEnabled || !state.encryptionKey) {
        return text;
    }

    const encrypted = await encryptBytes(new TextEncoder().encode(text));
    return [
        encryptedTextPrefix,
        bytesToBase64(encrypted.iv),
        bytesToBase64(encrypted.ciphertext)
    ].join(":");
}

async function decryptMessageBody(body) {
    if (!body) {
        return "";
    }

    if (!encryptionEnabled) {
        return body;
    }

    if (!body.startsWith(`${encryptedTextPrefix}:`)) {
        return "[mensagem antiga sem criptografia]";
    }

    try {
        const [, , ivBase64, ciphertextBase64] = body.split(":");
        const plaintext = await decryptBytes(
            base64ToBytes(ivBase64),
            base64ToBytes(ciphertextBase64)
        );
        return new TextDecoder().decode(plaintext);
    } catch {
        return "[não consegui descriptografar esta mensagem]";
    }
}

async function encryptFile(file) {
    const plainBytes = new Uint8Array(await file.arrayBuffer());
    const encrypted = await encryptBytes(plainBytes);
    const payload = concatBytes(encryptedMediaMagic, encrypted.iv, encrypted.ciphertext);

    return {
        blob: new Blob([payload], { type: "application/octet-stream" }),
        contentType: "application/octet-stream",
        suffix: ".amzenc"
    };
}

async function decryptMediaBlob(blob, message) {
    const payload = new Uint8Array(await blob.arrayBuffer());
    const magic = payload.slice(0, encryptedMediaMagic.length);
    const hasMagic = bytesToBase64(magic) === bytesToBase64(encryptedMediaMagic);

    if (!hasMagic) {
        throw new Error("mídia sem criptografia");
    }

    const ivStart = encryptedMediaMagic.length;
    const ivEnd = ivStart + 12;
    const iv = payload.slice(ivStart, ivEnd);
    const ciphertext = payload.slice(ivEnd);
    const plainBytes = await decryptBytes(iv, ciphertext);

    return new Blob([plainBytes], {
        type: guessMimeType(message.attachment_name, message.attachment_type)
    });
}

async function encryptBytes(plainBytes) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
        {
            name: "AES-GCM",
            iv
        },
        state.encryptionKey,
        plainBytes
    ));

    return { iv, ciphertext };
}

async function decryptBytes(iv, ciphertext) {
    return new Uint8Array(await crypto.subtle.decrypt(
        {
            name: "AES-GCM",
            iv
        },
        state.encryptionKey,
        ciphertext
    ));
}

function concatBytes(...chunks) {
    const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
    const output = new Uint8Array(total);
    let offset = 0;

    for (const chunk of chunks) {
        output.set(chunk, offset);
        offset += chunk.length;
    }

    return output;
}

function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;

    for (let index = 0; index < bytes.length; index += chunkSize) {
        binary += String.fromCharCode(...bytes.slice(index, index + chunkSize));
    }

    return btoa(binary);
}

function base64ToBytes(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);

    for (let index = 0; index < binary.length; index += 1) {
        bytes[index] = binary.charCodeAt(index);
    }

    return bytes;
}

function guessMimeType(fileName = "", type = "") {
    const extension = fileName.split(".").pop()?.toLowerCase();
    const mimeTypes = {
        jpg: "image/jpeg",
        jpeg: "image/jpeg",
        png: "image/png",
        webp: "image/webp",
        gif: "image/gif",
        mp4: "video/mp4",
        webm: "video/webm",
        mov: "video/quicktime"
    };

    return mimeTypes[extension] || (type === "image" ? "image/png" : "video/mp4");
}

async function hashPassword(username, password) {
    return sha256(`${config.roomSalt || "amz-secret"}:${username}:${password}`);
}

async function hashRoomSecret(secret) {
    return sha256(`${config.roomSalt || "amz-secret"}:${secret}`);
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
