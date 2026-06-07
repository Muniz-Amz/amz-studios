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
    onlineProfiles: new Set(),
    presenceReady: false,
    notifiedPresenceAt: new Map(),
    titleAlertTimer: null,
    originalTitle: document.title,
    audioContext: null,
    lastRenderedDateKey: "",
    lastRenderedProfileId: "",
    lastRenderedGroupKey: "",
    autoFollowMessages: true,
    unreadMessages: 0,
    callPromptNode: null,
    call: {
        peer: null,
        localStream: null,
        remoteStream: null,
        id: "",
        mode: "",
        status: "idle",
        incomingOffer: null,
        targetProfileId: "",
        pendingCandidates: [],
        facingMode: "user"
    }
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
    conversation: document.querySelector(".conversation"),
    connectionState: document.querySelector("#connection-state"),
    connectionLabel: document.querySelector("#connection-label"),
    conversationAvatar: document.querySelector("#conversation-avatar"),
    chatTitle: document.querySelector("#chat-title"),
    chatSubtitle: document.querySelector("#chat-subtitle"),
    messages: document.querySelector("#messages"),
    scrollBottom: document.querySelector("#scroll-bottom"),
    messageForm: document.querySelector("#message-form"),
    messageText: document.querySelector("#message-text"),
    sendButton: document.querySelector(".send-button"),
    mediaInput: document.querySelector("#media-input"),
    attachmentPreview: document.querySelector("#attachment-preview"),
    leaveChat: document.querySelector("#leave-chat"),
    startVoiceCall: document.querySelector("#start-voice-call"),
    startVideoCall: document.querySelector("#start-video-call"),
    switchCamera: document.querySelector("#switch-camera"),
    endCall: document.querySelector("#end-call"),
    acceptCall: document.querySelector("#accept-call"),
    declineCall: document.querySelector("#decline-call"),
    callPanel: document.querySelector("#call-panel"),
    callStatus: document.querySelector("#call-status"),
    incomingCallActions: document.querySelector("#incoming-call-actions"),
    callVideos: document.querySelector(".call-videos"),
    localVideo: document.querySelector("#local-video"),
    remoteVideo: document.querySelector("#remote-video")
};

const isConfigured = Boolean(config.supabaseUrl && config.supabaseAnonKey);
const messageRetentionHours = Math.max(1, Number(config.messageRetentionHours || 24));
const messageHistoryLimit = Math.max(1, Number(config.messageHistoryLimit || 30));
const encryptionEnabled = config.encryptionEnabled !== false;
const encryptionIterations = Math.max(100000, Number(config.encryptionIterations || 250000));
const encryptedTextPrefix = "amzenc:v1";
const encryptedMediaMagic = new TextEncoder().encode("AMZENC1");
const encryptionSalt = `${config.roomSalt || "amz-secret"}:e2ee:v1`;
const rtcConfig = {
    iceServers: Array.isArray(config.rtcIceServers) && config.rtcIceServers.length
        ? config.rtcIceServers
        : [
            { urls: "stun:stun.l.google.com:19302" },
            { urls: "stun:global.stun.twilio.com:3478" }
        ]
};

async function boot() {
    showLogin();
    elements.setupWarning.hidden = isConfigured;
    elements.loginForm.addEventListener("submit", enterChat);
    elements.leaveChat.addEventListener("click", leaveChat);
    elements.messageForm.addEventListener("submit", sendMessage);
    elements.mediaInput.addEventListener("change", selectMedia);
    elements.messages.addEventListener("click", saveMediaFromMessage);
    elements.messages.addEventListener("click", handleMessageCallAction);
    elements.messages.addEventListener("scroll", handleMessagesScroll);
    elements.scrollBottom.addEventListener("click", () => scrollToBottom({ behavior: "smooth", force: true }));
    elements.messageText.addEventListener("input", autosizeComposer);
    elements.messageText.addEventListener("keydown", handleComposerKeydown);
    elements.startVoiceCall.addEventListener("click", () => startOutgoingCall("voice"));
    elements.startVideoCall.addEventListener("click", () => startOutgoingCall("video"));
    elements.switchCamera.addEventListener("click", switchCamera);
    elements.endCall.addEventListener("click", () => endActiveCall("Encerrando chamada.", true));
    elements.acceptCall.addEventListener("click", acceptIncomingCall);
    elements.declineCall.addEventListener("click", declineIncomingCall);
    window.addEventListener("hashchange", guardConversationRoute);
    window.addEventListener("beforeunload", () => endActiveCall("Saindo da chamada.", true));
    window.addEventListener("resize", updateViewportHeight);
    window.visualViewport?.addEventListener("resize", updateViewportHeight);
    window.visualViewport?.addEventListener("scroll", updateViewportHeight);
    if ("ResizeObserver" in window) {
        new ResizeObserver(updateComposerHeight).observe(elements.messageForm);
    }
    updateViewportHeight();
    updateComposerHeight();
    updateComposerHint();
    updateComposerState();
    renderPresence();
    await restoreSession();
    guardConversationRoute();
}

function normalizeAccounts(source) {
    const fallback = [
        {
            id: "perfil_1",
            username: "usuario1",
            displayHandle: "usuario1",
            name: "Usuário 1",
            role: "Perfil principal",
            color: "#42b9ff",
            passwordHash: ""
        },
        {
            id: "perfil_2",
            username: "usuario2",
            displayHandle: "usuario2",
            name: "Usuário 2",
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
        displayHandle: normalizeUsername(account.displayHandle || account.handle || account.username || account.name || `perfil${index + 1}`),
        aliases: Array.isArray(account.aliases) ? account.aliases.map(normalizeUsername).filter(Boolean) : [],
        passwordHashUsername: normalizeUsername(account.passwordHashUsername || account.username || account.name || `perfil${index + 1}`),
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
    const account = accounts.find((item) => item.username === username || item.aliases.includes(username));

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

    const typedHash = await hashPassword(account.passwordHashUsername || account.username, password);

    if (typedHash !== account.passwordHash) {
        showSetupMessage("Usuário ou senha inválidos.");
        return;
    }

    state.selectedProfile = account;
    state.encryptionKey = encryptionEnabled ? await deriveEncryptionKey(privateKey) : null;
    state.roomId = encryptionEnabled ? await hashRoomSecret(privateKey) : await hashRoomSecret(password);
    saveSession(account.id, state.roomId);
    requestNotificationPermission();
    primeNotificationAudio();
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
    renderConversationHeader();

    if (!isConfigured) {
        setConnectionState("Config pendente", "error");
        renderEmpty(`Login aceito. Para o bate-papo funcionar entre dois aparelhos, configure o Supabase em config.js e rode o arquivo supabase.sql. As conversas expiram em ${messageRetentionHours}h e o chat mantém as ${messageHistoryLimit} últimas.`);
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
        setConnectionState("Conectado", "ready");
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
        <span>@${escapeHtml(state.selectedProfile.displayHandle)} · ${escapeHtml(state.selectedProfile.role)}</span>
    `;
}

function renderConversationHeader() {
    if (!state.selectedProfile) {
        elements.conversationAvatar.textContent = "AMZ";
        elements.chatTitle.textContent = "Bate-papo privado";
        elements.chatSubtitle.textContent = getRetentionLabel();
        return;
    }

    const target = getRemoteAccount();

    elements.conversationAvatar.textContent = target ? getInitials(target.name) : "AMZ";
    elements.chatTitle.textContent = target ? target.name : "Bate-papo privado";
    elements.chatSubtitle.textContent = target
        ? getRetentionLabel()
        : getRetentionLabel();
}

function updateComposerHint() {
    elements.messageText.placeholder = "Escreva uma mensagem... Enter envia · Shift+Enter pula linha";
    elements.messageText.title = "Enter envia. Shift+Enter pula linha.";
}

async function loadMessages() {
    const { data, error } = await state.supabase
        .from("secret_chat_messages")
        .select("*")
        .eq("room_id", state.roomId)
        .gte("created_at", getRetentionCutoffIso())
        .order("created_at", { ascending: false })
        .limit(messageHistoryLimit);

    if (error) {
        throw error;
    }

    resetMessageRenderState();
    elements.messages.innerHTML = "";

    if (!data.length) {
        renderEmpty(`Nenhuma mensagem recente. As conversas somem depois de ${messageRetentionHours}h e apenas as ${messageHistoryLimit} últimas ficam no chat.`);
        return;
    }

    state.autoFollowMessages = true;
    state.unreadMessages = 0;

    for (const message of data.slice().reverse()) {
        await renderMessage(message);
    }
    scrollToBottom({ force: true });
}

function subscribeRealtime() {
    if (state.channel) {
        state.supabase.removeChannel(state.channel);
    }

    state.presenceReady = false;
    state.notifiedPresenceAt.clear();

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
                trimVisibleMessages();
            }
        })
        .on("broadcast", { event: "call-signal" }, async (payload) => {
            await handleCallSignal(payload.payload);
        })
        .on("presence", { event: "sync" }, () => {
            const presence = state.channel.presenceState();
            const previousProfiles = new Set(state.onlineProfiles);
            const nextProfiles = new Set(Object.keys(presence));
            state.onlineProfiles = nextProfiles;
            renderPresence();
            renderConversationHeader();
            notifyNewOnlineParticipants(previousProfiles, nextProfiles);
        })
        .subscribe(async (status) => {
            if (status === "SUBSCRIBED") {
                await state.channel.track({
                    name: state.selectedProfile.name,
                    online_at: new Date().toISOString()
                });
                setConnectionState("Conectado", "ready");
            }
        });
}

async function startOutgoingCall(mode) {
    if (!isConfigured || !state.channel) {
        alert("Entre no chat conectado para iniciar uma chamada.");
        return;
    }

    if (state.call.status !== "idle") {
        alert("Ja existe uma chamada em andamento.");
        return;
    }

    const target = getRemoteAccount();

    if (!target) {
        alert("Nao encontrei outro participante para chamar.");
        return;
    }

    if (!state.onlineProfiles.has(target.id)) {
        alert(`${target.name} precisa estar online para receber a chamada.`);
        return;
    }

    state.call.id = crypto.randomUUID();
    state.call.mode = mode;
    state.call.status = "calling";
    state.call.targetProfileId = target.id;
    state.call.pendingCandidates = [];
    updateCallUi(`Chamando ${target.name} por ${mode === "video" ? "video" : "voz"}...`);

    try {
        await prepareLocalStream(mode);
        updateCallUi(`Chamando ${target.name} por ${mode === "video" ? "video" : "voz"}...`);
        const peer = createPeerConnection();
        addLocalTracks(peer);
        const offer = await peer.createOffer();
        await peer.setLocalDescription(offer);

        await sendCallSignal({
            signal: "offer",
            call_id: state.call.id,
            mode,
            target_profile_id: target.id,
            description: serializeDescription(peer.localDescription)
        });
    } catch (error) {
        console.error(error);
        await endActiveCall(`Nao consegui iniciar a chamada: ${formatError(error)}`, false);
    }
}

async function acceptIncomingCall() {
    const offer = state.call.incomingOffer;

    if (!offer) {
        return;
    }

    state.call.status = "connecting";
    removeCallPrompt();
    stopTitleAlert();
    updateCallUi("Aceitando chamada...");

    try {
        await prepareLocalStream(offer.mode);
        updateCallUi("Aceitando chamada...");
        const peer = createPeerConnection();
        addLocalTracks(peer);
        await peer.setRemoteDescription(new RTCSessionDescription(offer.description));
        await flushPendingCandidates();

        const answer = await peer.createAnswer();
        await peer.setLocalDescription(answer);

        await sendCallSignal({
            signal: "answer",
            call_id: state.call.id,
            target_profile_id: state.call.targetProfileId,
            description: serializeDescription(peer.localDescription)
        });

        state.call.status = "active";
        updateCallUi("Conectado");
    } catch (error) {
        console.error(error);
        await sendCallSignal({
            signal: "reject",
            call_id: state.call.id,
            target_profile_id: state.call.targetProfileId,
            reason: formatError(error)
        });
        await endActiveCall(`Nao consegui aceitar: ${formatError(error)}`, false);
    }
}

async function declineIncomingCall() {
    if (state.call.status !== "incoming") {
        return;
    }

    removeCallPrompt();
    stopTitleAlert();
    await sendCallSignal({
        signal: "reject",
        call_id: state.call.id,
        target_profile_id: state.call.targetProfileId
    });
    await endActiveCall("Chamada recusada.", false);
}

async function handleMessageCallAction(event) {
    const action = event.target.closest("[data-call-action]");

    if (!action) {
        return;
    }

    event.preventDefault();
    const card = action.closest("[data-call-card]");

    if (!card || card.dataset.callId !== state.call.id || state.call.status !== "incoming") {
        return;
    }

    card.classList.add("is-processing");

    if (action.dataset.callAction === "accept") {
        await acceptIncomingCall();
        return;
    }

    if (action.dataset.callAction === "decline") {
        await declineIncomingCall();
    }
}

async function handleCallSignal(payload) {
    if (!payload || payload.room_id !== state.roomId || !state.selectedProfile) {
        return;
    }

    if (payload.from_profile_id === state.selectedProfile.id) {
        return;
    }

    if (payload.target_profile_id && payload.target_profile_id !== state.selectedProfile.id) {
        return;
    }

    if (payload.signal === "offer") {
        await receiveCallOffer(payload);
        return;
    }

    if (payload.call_id !== state.call.id) {
        return;
    }

    if (payload.signal === "answer" && state.call.peer) {
        await state.call.peer.setRemoteDescription(new RTCSessionDescription(payload.description));
        await flushPendingCandidates();
        state.call.status = "active";
        updateCallUi("Conectado");
        return;
    }

    if (payload.signal === "ice") {
        await addRemoteIceCandidate(payload.candidate);
        return;
    }

    if (payload.signal === "reject") {
        await endActiveCall("Chamada recusada.", false);
        return;
    }

    if (payload.signal === "busy") {
        await endActiveCall("A outra pessoa ja esta em chamada.", false);
        return;
    }

    if (payload.signal === "hangup") {
        await endActiveCall("Chamada encerrada pela outra pessoa.", false);
    }
}

async function receiveCallOffer(payload) {
    if (state.call.status !== "idle") {
        await sendCallSignal({
            signal: "busy",
            call_id: payload.call_id,
            target_profile_id: payload.from_profile_id
        });
        return;
    }

    state.call.id = payload.call_id;
    state.call.mode = payload.mode || "voice";
    state.call.status = "incoming";
    state.call.incomingOffer = payload;
    state.call.targetProfileId = payload.from_profile_id;
    state.call.pendingCandidates = [];
    updateCallUi(`${payload.from_name || "Alguem"} esta ligando por ${state.call.mode === "video" ? "video" : "voz"}.`);
    renderIncomingCallPrompt(payload);
    playStrongNotificationSound();
    vibrateDevice();
    flashPageTitle("Chamada recebida");
}

function renderIncomingCallPrompt(payload) {
    removeCallPrompt();
    removeEmptyState();

    const callerName = getMessageAuthorName({
        profile_id: payload.from_profile_id,
        profile_name: payload.from_name
    });
    const mode = payload.mode === "video" ? "video" : "voice";
    const modeLabel = mode === "video" ? "video" : "voz";
    const node = document.createElement("article");

    node.className = "message call-message";
    node.dataset.callCard = "incoming";
    node.dataset.callId = payload.call_id || "";
    node.innerHTML = `
        <header class="message-head">
            <span>Chamada recebida</span>
            <time>${formatTime(payload.created_at)}</time>
        </header>
        <div class="call-message-body">
            <div class="call-message-icon">
                <i class="ph ${mode === "video" ? "ph-video-camera" : "ph-phone-call"}"></i>
            </div>
            <div>
                <strong>${escapeHtml(callerName)} está ligando</strong>
                <span>Chamada privada de ${modeLabel}. Aceite para liberar ${mode === "video" ? "câmera e microfone" : "microfone"}.</span>
            </div>
        </div>
        <div class="call-message-actions">
            <button class="call-message-button accept" type="button" data-call-action="accept">
                <i class="ph ph-phone-call"></i>
                Aceitar
            </button>
            <button class="call-message-button decline" type="button" data-call-action="decline">
                <i class="ph ph-x"></i>
                Recusar
            </button>
        </div>
    `;

    state.callPromptNode = node;
    elements.messages.appendChild(node);
    scrollToBottom({ force: true });
}

function removeCallPrompt() {
    state.callPromptNode?.remove();
    state.callPromptNode = null;
}

async function prepareLocalStream(mode) {
    if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error("este navegador nao liberou camera/microfone");
    }

    stopLocalStream();
    state.call.localStream = await navigator.mediaDevices.getUserMedia(getMediaConstraints(mode));
    elements.localVideo.srcObject = state.call.localStream;
    updateCallMediaVisibility();
    await elements.localVideo.play().catch(() => {});
}

function getMediaConstraints(mode, audio = true) {
    return {
        audio,
        video: mode === "video"
            ? {
                facingMode: {
                    ideal: state.call.facingMode || "user"
                }
            }
            : false
    };
}

async function switchCamera() {
    if (state.call.mode !== "video" || !state.call.localStream) {
        return;
    }

    const previousFacingMode = state.call.facingMode || "user";
    const nextFacingMode = previousFacingMode === "user" ? "environment" : "user";
    const oldVideoTrack = state.call.localStream.getVideoTracks()[0];

    elements.switchCamera.disabled = true;
    state.call.facingMode = nextFacingMode;
    updateCallUi(nextFacingMode === "environment" ? "Câmera traseira" : "Câmera frontal");

    try {
        const cameraStream = await navigator.mediaDevices.getUserMedia(getMediaConstraints("video", false));
        const newVideoTrack = cameraStream.getVideoTracks()[0];

        if (!newVideoTrack) {
            throw new Error("nenhuma camera encontrada");
        }

        const sender = state.call.peer?.getSenders?.().find((item) => item.track?.kind === "video");

        if (sender) {
            await sender.replaceTrack(newVideoTrack);
        }

        if (oldVideoTrack) {
            state.call.localStream.removeTrack(oldVideoTrack);
            oldVideoTrack.stop();
        }

        state.call.localStream.addTrack(newVideoTrack);
        elements.localVideo.srcObject = state.call.localStream;
        updateCallMediaVisibility();
        await elements.localVideo.play().catch(() => {});
        updateCallUi(nextFacingMode === "environment" ? "Câmera traseira ativa" : "Câmera frontal ativa");
    } catch (error) {
        state.call.facingMode = previousFacingMode;
        console.warn("Não consegui alternar câmera:", formatError(error));
        updateCallUi("Não consegui alternar a câmera.");
    } finally {
        elements.switchCamera.disabled = false;
    }
}

function createPeerConnection() {
    const peer = new RTCPeerConnection(rtcConfig);
    state.call.peer = peer;
    state.call.remoteStream = new MediaStream();
    elements.remoteVideo.srcObject = state.call.remoteStream;
    updateCallMediaVisibility();

    peer.onicecandidate = (event) => {
        if (event.candidate) {
            sendCallSignal({
                signal: "ice",
                call_id: state.call.id,
                target_profile_id: state.call.targetProfileId,
                candidate: event.candidate.toJSON()
            });
        }
    };

    peer.ontrack = (event) => {
        for (const track of event.streams?.[0]?.getTracks?.() || [event.track]) {
            if (!state.call.remoteStream.getTracks().some((item) => item.id === track.id)) {
                state.call.remoteStream.addTrack(track);
            }
        }
        elements.remoteVideo.srcObject = state.call.remoteStream;
        updateCallMediaVisibility();
        elements.remoteVideo.play().catch(() => {});
    };

    peer.onconnectionstatechange = () => {
        if (peer.connectionState === "connected") {
            state.call.status = "active";
            updateCallUi("Conectado");
        }

        if (["failed", "disconnected"].includes(peer.connectionState)) {
            updateCallUi("Conexao instavel. Tentando manter a chamada...");
        }

        if (peer.connectionState === "closed") {
            endActiveCall("Chamada finalizada.", false);
        }
    };

    return peer;
}

function addLocalTracks(peer) {
    for (const track of state.call.localStream.getTracks()) {
        peer.addTrack(track, state.call.localStream);
    }
}

async function addRemoteIceCandidate(candidate) {
    if (!candidate) {
        return;
    }

    if (!state.call.peer || !state.call.peer.remoteDescription) {
        state.call.pendingCandidates.push(candidate);
        return;
    }

    try {
        await state.call.peer.addIceCandidate(new RTCIceCandidate(candidate));
    } catch (error) {
        console.warn("Candidato ICE ignorado:", formatError(error));
    }
}

async function flushPendingCandidates() {
    const candidates = state.call.pendingCandidates.splice(0);

    for (const candidate of candidates) {
        await addRemoteIceCandidate(candidate);
    }
}

async function sendCallSignal(payload) {
    if (!state.channel || !state.selectedProfile) {
        return;
    }

    await state.channel.send({
        type: "broadcast",
        event: "call-signal",
        payload: {
            room_id: state.roomId,
            from_profile_id: state.selectedProfile.id,
            from_name: state.selectedProfile.name,
            created_at: new Date().toISOString(),
            ...payload
        }
    });
}

async function endActiveCall(statusText = "Chamada encerrada.", notifyRemote = true) {
    const hadCall = state.call.status !== "idle";
    const callId = state.call.id;
    const targetProfileId = state.call.targetProfileId;

    removeCallPrompt();

    if (notifyRemote && hadCall && callId && targetProfileId) {
        await sendCallSignal({
            signal: "hangup",
            call_id: callId,
            target_profile_id: targetProfileId
        }).catch(() => {});
    }

    closePeerConnection();
    stopLocalStream();
    stopRemoteStream();
    resetCallState();

    if (hadCall) {
        showCallNotice(statusText);
    }
}

function closePeerConnection() {
    if (state.call.peer) {
        state.call.peer.onicecandidate = null;
        state.call.peer.ontrack = null;
        state.call.peer.onconnectionstatechange = null;
        state.call.peer.close();
    }
}

function stopLocalStream() {
    if (state.call.localStream) {
        for (const track of state.call.localStream.getTracks()) {
            track.stop();
        }
    }
    elements.localVideo.srcObject = null;
    updateCallMediaVisibility();
}

function stopRemoteStream() {
    if (state.call.remoteStream) {
        for (const track of state.call.remoteStream.getTracks()) {
            track.stop();
        }
    }
    elements.remoteVideo.srcObject = null;
    updateCallMediaVisibility();
}

function resetCallState() {
    removeCallPrompt();
    state.call = {
        peer: null,
        localStream: null,
        remoteStream: null,
        id: "",
        mode: "",
        status: "idle",
        incomingOffer: null,
        targetProfileId: "",
        pendingCandidates: [],
        facingMode: "user"
    };
    updateCallUi();
}

function updateCallUi(statusText = "") {
    const isIdle = state.call.status === "idle";
    const isVideoCall = state.call.mode === "video";
    const canSwitchCamera = isVideoCall && !["idle", "incoming"].includes(state.call.status);

    elements.callPanel.hidden = isIdle;
    elements.conversation.classList.toggle("has-call-panel", !isIdle);
    elements.incomingCallActions.hidden = true;
    elements.endCall.hidden = isIdle || state.call.status === "incoming";
    elements.switchCamera.hidden = !canSwitchCamera;
    elements.switchCamera.disabled = !canSwitchCamera || !state.call.localStream;
    elements.switchCamera.title = state.call.facingMode === "environment"
        ? "Alternar para câmera frontal"
        : "Alternar para câmera traseira";
    elements.switchCamera.setAttribute("aria-label", elements.switchCamera.title);
    elements.startVoiceCall.disabled = !isIdle;
    elements.startVideoCall.disabled = !isIdle;
    elements.callPanel.classList.toggle("voice-only", !isVideoCall);
    updateCallMediaVisibility();

    if (statusText) {
        elements.callStatus.textContent = statusText;
    }
}

function updateCallMediaVisibility() {
    const hasLocalVideo = Boolean(state.call.localStream?.getVideoTracks().some((track) => track.readyState !== "ended"));
    const hasRemoteVideo = Boolean(state.call.remoteStream?.getVideoTracks().some((track) => track.readyState !== "ended"));
    const hasVideoFeed = state.call.mode === "video" && (hasLocalVideo || hasRemoteVideo);

    elements.callPanel.classList.toggle("has-video-feed", hasVideoFeed);
    elements.callPanel.classList.toggle("has-local-video", hasLocalVideo);
    elements.callPanel.classList.toggle("has-remote-video", hasRemoteVideo);
}

function showCallNotice(text) {
    elements.callPanel.hidden = false;
    elements.incomingCallActions.hidden = true;
    elements.endCall.hidden = true;
    elements.callStatus.textContent = text;
    window.setTimeout(() => {
        if (state.call.status === "idle") {
            elements.callPanel.hidden = true;
        }
    }, 2600);
}

function getRemoteAccount() {
    return accounts.find((account) => account.id !== state.selectedProfile?.id);
}

function serializeDescription(description) {
    return {
        type: description.type,
        sdp: description.sdp
    };
}

async function cleanupExpiredMessages() {
    try {
        const { data: expiredPaths } = await state.supabase.rpc("get_expired_secret_chat_media_paths");

        if (Array.isArray(expiredPaths) && expiredPaths.length > 0) {
            const { error: mediaCleanupError } = await state.supabase.storage
                .from(config.bucketName || "secret-chat-media")
                .remove(expiredPaths);

            if (mediaCleanupError) {
                throw mediaCleanupError;
            }

            await state.supabase.rpc("clear_secret_chat_media_cleanup_paths", {
                media_paths: expiredPaths
            });
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
    elements.messageForm.dataset.status = state.mediaFile ? "Criptografando e enviando midia..." : "Enviando mensagem...";
    elements.sendButton.disabled = true;

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
        trimVisibleMessages();
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
        delete elements.messageForm.dataset.status;
        updateComposerState();
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
    elements.messageText.focus();
    updateComposerState();
}

function clearMedia() {
    state.mediaFile = null;
    elements.mediaInput.value = "";
    elements.attachmentPreview.hidden = true;
    elements.attachmentPreview.innerHTML = "";
    updateComposerState();
}

async function renderMessage(message) {
    if (isExpiredMessage(message)) {
        return;
    }

    const isOwn = message.profile_id === state.selectedProfile.id;
    const shouldStickToBottom = isOwn || state.autoFollowMessages || isNearMessageBottom(260);
    const authorName = getMessageAuthorName(message);
    const body = await decryptMessageBody(message.body);
    const media = renderMedia(message);
    const grouped = isGroupedWithPreviousMessage(message);
    const node = document.createElement("article");
    node.className = `message ${isOwn ? "own" : ""} ${grouped ? "grouped" : ""}`.trim();
    node.dataset.messageId = message.id || "";
    node.setAttribute("aria-label", `${authorName} às ${formatTime(message.created_at)}`);
    node.innerHTML = `
        ${grouped
            ? `<time class="message-time-inline">${formatTime(message.created_at)}</time>`
            : `<header class="message-head">
                <span>${escapeHtml(authorName)}</span>
                <time>${formatTime(message.created_at)}</time>
            </header>`}
        ${body ? `<div class="message-body">${escapeHtml(body)}</div>` : ""}
        ${media}
    `;
    removeEmptyState();
    renderDateSeparatorIfNeeded(message.created_at);
    elements.messages.appendChild(node);
    markMessageAsRendered(message);
    hydrateMessageMedia(node, message);

    if (shouldStickToBottom) {
        scrollToBottom({ force: true });
    } else {
        state.unreadMessages += 1;
        updateComposerState();
    }
}

function trimVisibleMessages() {
    const messageNodes = [...elements.messages.querySelectorAll("article.message")];
    const overflow = messageNodes.length - messageHistoryLimit;

    if (overflow <= 0) {
        return;
    }

    const shouldStickToBottom = state.autoFollowMessages || isNearMessageBottom(260);

    for (const node of messageNodes.slice(0, overflow)) {
        node.remove();
    }

    removeOrphanDateSeparators();

    if (shouldStickToBottom) {
        scrollToBottom({ force: true });
    }
}

function removeOrphanDateSeparators() {
    const separators = [...elements.messages.querySelectorAll(".message-date-separator")];

    for (const separator of separators) {
        let sibling = separator.nextElementSibling;
        let hasMessageInGroup = false;

        while (sibling && !sibling.classList.contains("message-date-separator")) {
            if (sibling.matches("article.message")) {
                hasMessageInGroup = true;
                break;
            }

            sibling = sibling.nextElementSibling;
        }

        if (!hasMessageInGroup) {
            separator.remove();
        }
    }
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

    const shouldStickToBottom = state.autoFollowMessages || isNearMessageBottom(260);
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

        if (shouldStickToBottom) {
            requestAnimationFrame(() => scrollToBottom({ force: true }));
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

function resetMessageRenderState() {
    state.lastRenderedDateKey = "";
    state.lastRenderedProfileId = "";
    state.lastRenderedGroupKey = "";
}

function renderDateSeparatorIfNeeded(value) {
    const dateKey = getMessageDateKey(value);

    if (!dateKey || dateKey === state.lastRenderedDateKey) {
        return;
    }

    const separator = document.createElement("div");
    separator.className = "message-date-separator";
    separator.textContent = formatMessageDate(value);
    elements.messages.appendChild(separator);
    state.lastRenderedDateKey = dateKey;
    state.lastRenderedProfileId = "";
    state.lastRenderedGroupKey = "";
}

function isGroupedWithPreviousMessage(message) {
    const profileId = message.profile_id || "";
    const groupKey = getMessageGroupKey(message.created_at);

    return Boolean(
        profileId
        && groupKey
        && profileId === state.lastRenderedProfileId
        && groupKey === state.lastRenderedGroupKey
    );
}

function markMessageAsRendered(message) {
    state.lastRenderedProfileId = message.profile_id || "";
    state.lastRenderedGroupKey = getMessageGroupKey(message.created_at);
}

function getMessageAuthorName(message) {
    return accounts.find((account) => account.id === message.profile_id)?.name || message.profile_name || "Perfil";
}

async function leaveChat() {
    await endActiveCall("Saindo da chamada.", true);
    stopTitleAlert();
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
    state.presenceReady = false;
    state.notifiedPresenceAt.clear();
    showLogin();
    window.location.hash = "login";
    renderPresence();
}

function showLogin() {
    elements.chat.hidden = true;
    elements.login.hidden = false;
    elements.activeProfile.innerHTML = "";
    resetMessageRenderState();
    elements.messages.innerHTML = "";
}

function showChat() {
    elements.login.hidden = true;
    elements.chat.hidden = false;
}

function renderEmpty(text) {
    resetMessageRenderState();
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
    updateComposerState();
    updateComposerHeight();
}

function handleMessagesScroll() {
    const nearBottom = isNearMessageBottom(220);
    state.autoFollowMessages = nearBottom;

    if (nearBottom) {
        state.unreadMessages = 0;
    }

    updateComposerState();
}

function scrollToBottom(options = {}) {
    const force = options.force !== false;
    const behavior = options.behavior || "auto";
    const top = elements.messages.scrollHeight;

    if (typeof elements.messages.scrollTo === "function") {
        elements.messages.scrollTo({ top, behavior });
    } else {
        elements.messages.scrollTop = top;
    }

    if (force) {
        state.autoFollowMessages = true;
        state.unreadMessages = 0;
    }

    updateComposerState();
}

function isNearMessageBottom(threshold = 180) {
    return elements.messages.scrollHeight - elements.messages.scrollTop - elements.messages.clientHeight <= threshold;
}

function updateComposerState() {
    const awayFromBottom = !isNearMessageBottom(260);
    const hasText = Boolean(elements.messageText.value.trim());
    const canSend = (hasText || Boolean(state.mediaFile)) && !elements.messageForm.classList.contains("is-sending");
    elements.messageForm.classList.toggle("has-attachment", Boolean(state.mediaFile));
    elements.messageForm.classList.toggle("has-text", hasText);
    elements.messageForm.classList.toggle("is-away-from-bottom", awayFromBottom);
    elements.sendButton.disabled = !canSend;
    elements.scrollBottom.hidden = !awayFromBottom;
    elements.scrollBottom.dataset.unread = state.unreadMessages > 0 ? String(Math.min(state.unreadMessages, 99)) : "";
    elements.scrollBottom.title = state.unreadMessages > 0
        ? `${state.unreadMessages} mensagem nova. Ir para o fim.`
        : "Ir para o fim da conversa";
    elements.scrollBottom.setAttribute("aria-label", elements.scrollBottom.title);
    updateComposerHeight();
}

function handleComposerKeydown(event) {
    if (event.key !== "Enter" || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey || event.isComposing) {
        return;
    }

    event.preventDefault();

    if (elements.messageForm.classList.contains("is-sending")) {
        return;
    }

    if (!elements.messageText.value.trim() && !state.mediaFile) {
        return;
    }

    if (typeof elements.messageForm.requestSubmit === "function") {
        elements.messageForm.requestSubmit();
    } else {
        elements.messageForm.dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
    }
}

function updateViewportHeight() {
    const viewportHeight = window.visualViewport?.height || window.innerHeight;
    document.documentElement.style.setProperty("--chat-vh", `${Math.max(320, viewportHeight)}px`);
}

function updateComposerHeight() {
    const composerHeight = elements.messageForm?.offsetHeight || 76;
    document.documentElement.style.setProperty("--composer-height", `${Math.max(58, composerHeight)}px`);
}

function requestNotificationPermission() {
    if (!("Notification" in window) || Notification.permission !== "default") {
        return;
    }

    Notification.requestPermission().catch(() => {});
}

function primeNotificationAudio() {
    try {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return;

        state.audioContext ||= new AudioContextClass();
        if (state.audioContext.state === "suspended") {
            state.audioContext.resume().catch(() => {});
        }
    } catch {
        state.audioContext = null;
    }
}

function notifyNewOnlineParticipants(previousProfiles, nextProfiles) {
    if (!state.selectedProfile) return;

    if (!state.presenceReady) {
        state.presenceReady = true;
        return;
    }

    for (const account of accounts) {
        if (account.id === state.selectedProfile.id) continue;
        if (!nextProfiles.has(account.id) || previousProfiles.has(account.id)) continue;
        notifyParticipantEntered(account);
    }
}

function notifyParticipantEntered(account) {
    const now = Date.now();
    const lastNotification = state.notifiedPresenceAt.get(account.id) || 0;

    if (now - lastNotification < 30000) {
        return;
    }

    state.notifiedPresenceAt.set(account.id, now);

    const title = "AMZ Chat Privado";
    const body = `${account.name} entrou no bate-papo privado.`;

    showBrowserNotification(title, body, account);
    playStrongNotificationSound();
    vibrateDevice();
    flashPageTitle(`${account.name} entrou no chat`);
}

function showBrowserNotification(title, body, account) {
    if (!("Notification" in window)) return;

    if (Notification.permission === "default") {
        requestNotificationPermission();
        return;
    }

    if (Notification.permission !== "granted") {
        return;
    }

    try {
        const notification = new Notification(title, {
            body,
            icon: "../assets/logo.png",
            badge: "../assets/logo.png",
            tag: `amz-chat-presence-${account.id}`,
            renotify: true,
            requireInteraction: true,
            silent: false
        });

        notification.onclick = () => {
            window.focus();
            notification.close();
            stopTitleAlert();
        };
    } catch {}
}

function playStrongNotificationSound() {
    try {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return;

        state.audioContext ||= new AudioContextClass();
        const audioContext = state.audioContext;

        if (audioContext.state === "suspended") {
            audioContext.resume().catch(() => {});
        }

        const startTime = audioContext.currentTime + 0.04;
        const frequencies = [880, 660, 980];

        frequencies.forEach((frequency, index) => {
            const oscillator = audioContext.createOscillator();
            const gain = audioContext.createGain();
            const toneStart = startTime + index * 0.24;
            const toneEnd = toneStart + 0.16;

            oscillator.type = "sine";
            oscillator.frequency.setValueAtTime(frequency, toneStart);
            gain.gain.setValueAtTime(0.0001, toneStart);
            gain.gain.exponentialRampToValueAtTime(0.28, toneStart + 0.025);
            gain.gain.exponentialRampToValueAtTime(0.0001, toneEnd);

            oscillator.connect(gain);
            gain.connect(audioContext.destination);
            oscillator.start(toneStart);
            oscillator.stop(toneEnd + 0.02);
        });
    } catch {}
}

function vibrateDevice() {
    if (!navigator.vibrate) return;
    navigator.vibrate([250, 100, 250, 100, 350]);
}

function flashPageTitle(text) {
    stopTitleAlert();

    let visible = false;
    let ticks = 0;

    state.titleAlertTimer = window.setInterval(() => {
        document.title = visible ? state.originalTitle : `● ${text}`;
        visible = !visible;
        ticks += 1;

        if (ticks >= 16) {
            stopTitleAlert();
        }
    }, 650);
}

function stopTitleAlert() {
    if (state.titleAlertTimer) {
        window.clearInterval(state.titleAlertTimer);
        state.titleAlertTimer = null;
    }

    document.title = state.originalTitle;
}

function getRetentionCutoffIso() {
    return new Date(Date.now() - messageRetentionHours * 60 * 60 * 1000).toISOString();
}

function getRetentionLabel() {
    return `Mensagens somem em ${messageRetentionHours}h · últimas ${messageHistoryLimit}`;
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

function getMessageDateKey(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return "";
    }

    return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function getMessageGroupKey(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return "";
    }

    return String(Math.floor(date.getTime() / (5 * 60 * 1000)));
}

function formatMessageDate(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);
    const today = new Date();
    const yesterday = new Date();
    yesterday.setDate(today.getDate() - 1);

    if (getMessageDateKey(value) === getMessageDateKey(today)) {
        return "Hoje";
    }

    if (getMessageDateKey(value) === getMessageDateKey(yesterday)) {
        return "Ontem";
    }

    return new Intl.DateTimeFormat("pt-BR", {
        weekday: "short",
        day: "2-digit",
        month: "2-digit"
    }).format(date);
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
