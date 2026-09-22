# MusicAmz 1.2.0

Pacote `com.musicamz`, versionCode 15, Android 8/API 26 ou posterior.

## Validação desta distribuição

- Build release com minificação e lint, usando Gradle 8.6, JDK 17 e SDK 34.
- 11 testes JVM passaram: normalização de links e importação de cookies.
- No emulador Android 14/x86_64: conversão real de WAV gerado para MP3,
  rollback quando o banco falha, rejeição de áudio inválido, cookies cifrados
  e rejeição de adulteração, cancelamento e limpeza de temporários.
- Atualização online do extrator e download de áudio de um vídeo público do
  YouTube concluídos pelo serviço Android, sem cookies de conta.
- APK release instalado sobre a versão 1.1.7, preservando um marcador de dados.
  O próprio pacote release baixou e importou áudio usando o extrator embutido,
  sem atualização manual. Checksum do extrator conferido dentro do APK.
- Assinatura idêntica à versão 1.1.7. As arquiteturas ARM são distribuídas em
  pacotes separados; o teste de execução foi realizado em x86_64, não em celular físico.

Os testes não garantem acesso a todos os vídeos ou contas. Bloqueios, expiração
de cookies e mudanças do YouTube continuam dependendo da plataforma.

## SHA-256 dos APKs publicados

| Arquivo | SHA-256 |
| --- | --- |
| MusicAmz-v1.2.0.apk (ARM64) | c0a1c3abcdd9ffb3bb184ae5fd032c3f38b569f155b014f6d0fb6b6fa8ab353b |
| MusicAmz-v1.2.0-armeabi-v7a.apk (ARM32) | 03d354cb6fec8f4f7f3025c08770c6822dd5f36d31b62502dad33bb29126b05e |

O alias `MusicAmz.apk` contém o mesmo pacote ARM64 da versão 1.2.0.
