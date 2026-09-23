# MusicAmz 1.2.1

Pacote `com.musicamz`, versionCode 16, Android 8/API 26 ou posterior.

## Alteração desta versão

Resposta mais clara ao tocar em **Baixar MP3**: o teclado fecha e a interface
mostra o progresso ou um aviso de erro, para evitar a impressão de que o botão
não iniciou nenhuma ação. Instale por cima da versão anterior, sem desinstalar,
para preservar os dados do aplicativo.

## Validação desta distribuição

- Build release com minificação R8 e lintVital, usando Gradle 8.6, JDK 17 e SDK 34.
- 12 testes JVM passaram: links, parser de cookies e reconhecimento de tentativas
  consecutivas que terminam com o mesmo erro.
- No emulador Android 14/x86_64, passaram 10 testes de interface e 4 de integração:
  colar/baixar, teclado/foco, clique duplicado, erro de início, timeout,
  restauração da tela, mensagens antigas, rolagem, cancelamento, conversão,
  rollback, áudio inválido e cookies cifrados/adulterados. Os dois testes
  opcionais de rede dessa suíte não foram habilitados.
- No APK release assinado, o teste externo acionou os botões Colar e Baixar MP3
  com o teclado aberto. Um link inválido produziu erro visível sem criar áudio;
  um vídeo público do YouTube foi baixado, convertido e inserido na biblioteca.
  O teclado fechou nos dois casos; capturas de tela conferidas visualmente.
- O APK release foi instalado sobre a versão 1.2.0; o marcador de dados anterior
  permaneceu salvo. Assinatura dos três APKs conferida e idêntica à versão anterior.
- APKs distribuídos para ARM64 e ARM32. Execução testada em Android 14/x86_64,
  não em celular físico ARM. A atualização online do extrator, sem alteração
  nesta versão, não foi repetida.

Os testes não garantem acesso a todos os vídeos ou contas. Bloqueios, expiração
de cookies e mudanças do YouTube continuam dependendo da plataforma.

## SHA-256 dos APKs publicados

| Arquivo | SHA-256 |
| --- | --- |
| MusicAmz-v1.2.1.apk (ARM64) | 9de0bc7489a31bc6acae36528c1d949164a24c5c74703b2461720239129c91b6 |
| MusicAmz-v1.2.1-armeabi-v7a.apk (ARM32) | 19fc78fc4ec50c7d693bc403870ac6cda4c14375dc5fd24f1af7098c08c60be6 |

O alias `MusicAmz.apk` contém o mesmo pacote ARM64 da versão 1.2.1, com hash
idêntico conferido antes da publicação.
