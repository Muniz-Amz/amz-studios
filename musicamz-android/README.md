# MusicAmz

Player Android para músicas do aparelho, com importação de áudio do YouTube
diretamente no celular. Versão 1.2.1, código de versão Android 16.

## Recursos

- Biblioteca do MediaStore, busca, favoritos, histórico e playlists.
- Fila persistente, incluindo ordem, repetição, aleatório e posição atual.
- Reprodução em segundo plano, timer, equalizador e controles de áudio.
- Importação de arquivos de áudio e extração compatível de áudio de vídeos locais.
- Importação de um link do YouTube como MP3, com progresso, cancelamento e
  inclusão do arquivo concluído na biblioteca.
- Recebimento de links pelo menu Compartilhar do Android.
- Cookies opcionais do próprio usuário e atualização manual do extrator.

## Importar áudio de um link

Na área de importação do aplicativo, cole um link de vídeo do YouTube e toque em
**Baixar MP3**. Também é possível compartilhar o link do YouTube para o MusicAmz.
Receber ou colar um link não inicia o download automaticamente. Ao tocar em
**Baixar MP3**, o teclado fecha e a tela mostra o progresso ou um aviso de erro.
A versão 1.2.1 melhora essa resposta para que o resultado do toque fique visível.

Para atualizar, instale o novo APK por cima da versão anterior, sem desinstalar
o MusicAmz, para preservar os dados do aplicativo.

O aplicativo usa a conexão do aparelho para baixar e converter o áudio. Não
envia o download para servidores AMZ, Render ou Hugging Face. A conexão com o
YouTube é necessária para baixar; depois, a faixa pode ser reproduzida offline.
O processamento utiliza bateria, armazenamento temporário e capacidade do
celular. O primeiro uso também prepara os componentes locais.

A importação aceita um vídeo por vez; links de canal ou playlist não importam
uma coleção inteira. O áudio concluído é validado antes de entrar na biblioteca.
Arquivos vazios, inválidos ou acima de 500 MB são rejeitados. Os MP3 importados
ficam na área privada do MusicAmz: não aparecem automaticamente na pasta pública
Downloads e são removidos se os dados do aplicativo forem apagados ou ele for
desinstalado.

O YouTube pode exigir login, recusar solicitações ou mudar a forma de entregar
áudio. Vídeos indisponíveis, privados sem acesso, restritos ou protegidos podem
falhar. Cookies não garantem sucesso. Se uma alteração do YouTube interromper o
recurso, use **Cookies e atualização → Atualizar extrator** e tente novamente.
A atualização consulta a distribuição upstream do extrator pela internet;
problemas em outros componentes podem exigir uma nova versão do APK.
Use o recurso com conteúdo que você tenha autorização para baixar.

## Cookies opcionais

O aplicativo funciona sem importar cookies quando o vídeo pode ser acessado
anonimamente. Se for necessário usar a sua sessão, abra **Cookies e atualização**,
escolha **Importar cookies.txt** e selecione seu arquivo no formato Netscape,
codificado em UTF-8 e com no máximo 1 MB. O importador conserva apenas cookies dos
domínios do YouTube aceitos. Ative **Usar meus cookies neste download** quando
quiser utilizá-los.

Os cookies armazenados pelo MusicAmz são criptografados com AES-GCM e uma chave
do Android Keystore, fora da área de backup. Durante uma tarefa autorizada, uma
cópia descriptografada é disponibilizada ao extrator em uma pasta privada
temporária. O MusicAmz não envia esses cookies à infraestrutura AMZ. Eles são
usados na comunicação com o YouTube e podem expirar ou ser revogados.

**Apagar cookies do aplicativo** remove a cópia armazenada pelo MusicAmz. O
arquivo original escolhido no seletor de documentos continua onde você o salvou;
apague-o também se não quiser mantê-lo. Cookies representam uma sessão de conta:
não os compartilhe nem os publique em relatórios de erro. A documentação do
[yt-dlp sobre cookies do YouTube](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies)
explica a exportação e as limitações dessa sessão.

## Dados locais e backup

Playlists, favoritos e configurações ficam no aparelho. O backup JSON guarda os
dados da biblioteca, mas não carrega os arquivos de áudio importados. Ao importar,
o MusicAmz cria playlists novas para não substituir playlists já existentes.
Os cookies não fazem parte desse backup. Mantenha uma cópia separada dos arquivos
de áudio importantes antes de limpar dados ou desinstalar o aplicativo.

## Compilação no Windows

O Android Gradle Plugin não compila projetos em caminhos com caracteres especiais.
Use uma cópia do projeto em um caminho simples, por exemplo:

`C:\Trabalhos\AMZ\MusicAmz`

Requisitos: JDK 17, Gradle 8.6, Android SDK Platform 34 e Build Tools 34.0.0.
O projeto usa Android Gradle Plugin 8.3.2, Kotlin 1.9.23 e minSdk 26 (Android 8).
Google Maven e Maven Central fornecem as dependências declaradas nos arquivos
Gradle. A primeira compilação precisa de internet.

Configure `JAVA_HOME` e `ANDROID_SDK_ROOT` para suas instalações. Com Gradle 8.6
no PATH, execute na raiz deste diretório:

```powershell
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug
gradle --no-daemon --max-workers=2 :app:assembleRelease
```

Os APKs de testes ficam em `app/build/outputs/apk/debug/`. Os APKs release
ficam em `app/build/outputs/apk/release/`, separados por arquitetura
(`arm64-v8a`, `armeabi-v7a`, `x86_64`), e requerem alinhamento e assinatura antes
da distribuição. Para testes instrumentados,
inicie um emulador ou conecte um aparelho e execute:

```powershell
gradle --no-daemon --max-workers=2 :app:connectedDebugAndroidTest
```

Para atualizar uma instalação existente preservando os dados, mantenha o pacote
`com.musicamz`, aumente o `versionCode` e assine com a mesma chave da versão
instalada. Não coloque keystores, senhas, cookies, `local.properties`, caches,
arquivos `build/` ou dumps de memória no repositório.

## Código e componentes

O código desta versão é distribuído junto ao site no diretório
[`musicamz-android`](https://github.com/Muniz-Amz/amz-studios/tree/main/musicamz-android).
As versões das dependências estão em `app/build.gradle.kts` e
`gradle/libs.versions.toml`. O download local utiliza
`io.github.junkfood02.youtubedl-android:library:0.18.1` e `ffmpeg:0.18.1`.

Esta versão do aplicativo e seu código-fonte são distribuídos sob a GNU GPL v3.

Os avisos, licenças e fontes upstream estão em
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). A cópia da GNU GPL versão 3
distribuída com youtubedl-android está preservada em [COPYING](COPYING).
