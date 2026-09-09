# Cofre AMZ — Android

Aplicativo nativo offline, Java, Android 8+ (API 26). ID permanente:
`com.amzstudios.cofre`. Versão inicial 1.0.0, versionCode 1.

## O que está implementado

- Um cofre por instalação, senha de 10+ caracteres e mudança de senha.
- Pastas/subpastas, busca global, nomes sem colisões, renomear, mover e excluir.
- Importação múltipla pelo Storage Access Framework do Android. Cada arquivo é
  criptografado, sincronizado em disco e autenticado antes de remover a origem.
  O original é lido novamente e comparado por SHA-256 para detectar mudanças.
  Se o DocumentsProvider negar a remoção, a cópia protegida é mantida e o app
  informa explicitamente que o original continua no local de origem.
- Retirada e cópia para um destino escolhido com ACTION_CREATE_DOCUMENT.
  A retirada só exclui a entrada do cofre após fechar, reler e verificar o destino.
- Visualização interna de imagens, textos (até 2 MiB), PDFs e formatos de
  áudio/vídeo reconhecidos pelo Android. Outros formatos usam um aplicativo
  escolhido pelo usuário, após explicar o acesso à cópia descriptografada.
- Backup .amzcofre criptografado e restauração verificada em uma instalação
  sem cofre, sem substituir um cofre existente.
- Bloqueio ao sair e após dois minutos de inatividade; operações em andamento
  terminam antes de limpar a chave. Seletores autorizados permitem retorno
  dentro do prazo. Sem senha, a seleção expirada não remove arquivos.
- FLAG_SECURE nas telas, sem permissão INTERNET, sem conta, anúncios ou telemetria.
- Backup automático do Android e transferência automática dos dados desativados.

## Compilação e validação

Requisitos: JDK 17, Gradle 8.6, Android SDK 34 / build-tools 34.0.0.
AGP 8.3.2. Dependências baixadas dos repositórios Google/Maven Central.

```powershell
gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.amzstudios.cofre.test/androidx.test.runner.AndroidJUnitRunner
```

`build-release.ps1` aceita os caminhos do Gradle e do JDK. Ele executa
assembleRelease, lintRelease e testReleaseUnitTest, sem ignorar falhas.

### Assinatura e atualizações

A chave de produção é guardada fora do repositório, por padrão em
`$env:USERPROFILE/.android/cofre-amz-signing/cofre-amz-release.p12`.
A senha local fica protegida pelo DPAPI do Windows no mesmo diretório.
**Preserve e faça backup seguro da chave e de sua senha.** O arquivo DPAPI só
pode ser aberto pelo usuário Windows que o criou; guarde a senha separadamente
em um gerenciador seguro antes de migrar de computador.

Não publique esse diretório. Nunca substitua a chave ao atualizar o APK.
Fingerprint SHA-256 do certificado inicial:
`c6dcd70a40693f6e0b9c5dec9b9d8007827b43605ebd2f55455af2ddb41c746f`.

Incremente versionCode/versionName para cada versão. Preserve o applicationId,
a assinatura e o formato existente do cofre. Instale atualizações com os dados
mantidos; nunca instrua a desinstalar para atualizar. Futuras migrações precisam
ler o formato anterior e preservar backup antes de gravar qualquer conversão.

O APK de distribuição fica em `../../assets/meu-site-downloads/` a partir de
`cofre-amz/android/`, junto de seu SHA-256. Atualize a página e o link na raiz do site.

## Testes da primeira versão

11 testes JVM: arquivos vazios e grandes em blocos, reabertura, senha errada,
adulteração de conteúdo e índice, truncamento, bytes adicionais, alteração de
senha, hierarquia de pastas, ciclos, backup/restauração, zip traversal, importação
interrompida, erro de destino e detecção de mudanças na origem.

Dois testes instrumentados em Android 14, em modo avião: engine com o provedor
criptográfico Android e um teste da Activity que cria o cofre, transfere via
DocumentsProvider, verifica a exclusão da origem, devolve para o destino e
bloqueia. O provedor de teste existe somente em src/debug e não vai no APK
de produção. Imagens de QA são geradas apenas pelo teste com dados fictícios.

Validação de assinatura APK v2 e ausência de permissão de internet no APK.
O APK de produção também foi instalado no Android 14 em modo avião e usado
com o seletor real do Android: um TXT saiu de Downloads, abriu no visualizador
interno e voltou a Downloads com SHA-256 idêntico. Uma reinstalação por cima
do APK preservou a senha e o arquivo do cofre durante essa verificação.
O aplicativo não passou por auditoria criptográfica independente nem testes em
todos os fabricantes de aparelhos; não anunciar proteção absoluta.

Veja [SECURITY.md](SECURITY.md) antes de alterar criptografia ou armazenamento.
