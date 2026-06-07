window.AMZ_SECRET_CHAT_CONFIG = {
    supabaseUrl: "https://hymspkeuhtajznjypscb.supabase.co",
    supabaseAnonKey: "sb_publishable_jirjANipi8EZTHXYip8C5A_XZ80KPtH",
    bucketName: "secret-chat-media",
    roomSalt: "amz-studios-private-chat",
    sessionHours: 8,
    messageRetentionHours: 24,
    messageHistoryLimit: 30,
    encryptionEnabled: true,
    encryptionIterations: 250000,
    maxImageMb: 10,
    maxVideoMb: 60,
    rtcIceServers: [
        { urls: "stun:stun.l.google.com:19302" },
        { urls: "stun:global.stun.twilio.com:3478" }
    ],
    accounts: [
        {
            id: "perfil_1",
            username: "usuario1",
            aliases: ["muniz"],
            passwordHashUsername: "muniz",
            displayHandle: "usuario1",
            name: "Usuário 1",
            role: "Perfil principal",
            color: "#42b9ff",
            passwordHash: "39f729ef2d3c3b404bb19b7fb2a6bba72f8250363b9fc13d03c647474cd18196"
        },
        {
            id: "perfil_2",
            username: "usuario2",
            aliases: ["monteiro"],
            passwordHashUsername: "monteiro",
            displayHandle: "usuario2",
            name: "Usuário 2",
            role: "Perfil convidado",
            color: "#30f27b",
            passwordHash: "0819bdd4d7e68afa5a611b6f88392e1cba2d9993eed3d7f2998c8a04558037b5"
        }
    ]
};
