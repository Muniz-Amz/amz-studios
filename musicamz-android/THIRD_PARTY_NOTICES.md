# Componentes de terceiros

Este arquivo registra os componentes usados na importação local de áudio e
preserva referências às suas licenças e fontes. Os componentes mantêm os direitos
e avisos de seus respectivos autores; a presença deles não representa endosso
ao MusicAmz. As bibliotecas upstream não foram modificadas nesta integração.

## youtubedl-android 0.18.1

Artefatos Maven utilizados:

- `io.github.junkfood02.youtubedl-android:library:0.18.1`
- `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`
- `io.github.junkfood02.youtubedl-android:common:0.18.1` (dependência transitiva)

Os POMs desses três artefatos declaram **GNU General Public License, versão 3**
e identificam `yausername/youtubedl-android` como repositório de origem. O grupo
Maven com o nome JunkFood02 não altera essa referência de origem.

- Autor/mantenedor creditado nos POMs: yausername e colaboradores do projeto.
- [Fonte da versão 0.18.1](https://github.com/yausername/youtubedl-android/tree/0.18.1),
  commit `d725d5c9a18c3a99a13ee0308bf78275dc310760`.
- [Arquivo de código dessa versão](https://github.com/yausername/youtubedl-android/archive/refs/tags/0.18.1.tar.gz).
- [Licença upstream da versão](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE).
- Cópia integral da licença preservada neste diretório: [COPYING](COPYING).
- Metadados publicados no Maven Central:
  [library](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1.pom),
  [ffmpeg](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/ffmpeg/0.18.1/ffmpeg-0.18.1.pom) e
  [common](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/common/0.18.1/common-0.18.1.pom).

O APK inclui em `app/src/main/res/raw/ytdlp` o executável Python oficial
`yt-dlp` da versão [2026.08.19](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19),
substituindo o recurso antigo do AAR sem alterar o wrapper. SHA-256 verificado:
`1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6`.
O código correspondente está na [tag 2026.08.19](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19).

O upstream também credita
[youtubedl-java, de sapher](https://github.com/sapher/youtubedl-java),
no qual a adaptação para Android se baseia. A GPL inclui os avisos de ausência
de garantia e as condições de cópia, modificação e redistribuição; consulte o
texto completo em COPYING.

## Executáveis e bibliotecas nativas empacotados

A versão Android inclui componentes nativos de outros projetos. A licença GPL
do wrapper não substitui os avisos próprios desses componentes.

| Componente | Origem e referências |
| --- | --- |
| yt-dlp | [Fonte e licença](https://github.com/yt-dlp/yt-dlp#license). O código principal usa Unlicense; os artefatos empacotados podem incluir componentes com outras licenças. |
| FFmpeg / ffprobe | [Fonte oficial](https://ffmpeg.org/download.html#get-sources), [licenças e configuração de build](https://ffmpeg.org/legal.html). A licença dos binários depende dos componentes habilitados; não se presume LGPL para todo o pacote. |
| Python | [Fonte CPython](https://github.com/python/cpython) e [histórico de licenças Python](https://docs.python.org/3/license.html). |
| QuickJS | [Fonte e licença MIT do projeto](https://bellard.org/quickjs/). O pacote Android fornece `libqjs.so`. |
| Pacotes Android derivados do Termux | [Receitas e patches upstream](https://github.com/termux/termux-packages). |

As instruções fornecidas pelo youtubedl-android para compilar os componentes
nativos estão preservadas no upstream da mesma versão:
[BUILD_FFMPEG.md](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md)
e [BUILD_PYTHON.md](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md).
Elas descrevem receitas Termux e incluem referências históricas; os números de
versão mostrados nos exemplos não identificam necessariamente todos os binários
presentes no AAR 0.18.1.

O recurso **Atualizar extrator** pode substituir o yt-dlp instalado no aparelho
por uma versão mais recente do upstream. Essa atualização não recompila o APK
e não altera a versão fixa dos wrappers Maven.

## Outras dependências Android

As versões diretas estão declaradas em `gradle/libs.versions.toml` e
`app/build.gradle.kts`. O grafo resolvido pelo Gradle também inclui dependências
transitivas, cada qual com seus avisos próprios.

- [AndroidX / Jetpack, incluindo Compose, Media3 e Room](https://android.googlesource.com/platform/frameworks/support/): Apache License 2.0.
- [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt): Apache License 2.0.
- [Coil](https://github.com/coil-kt/coil/blob/main/LICENSE.txt): Apache License 2.0.
- [Jackson](https://github.com/FasterXML/jackson-databind): Apache License 2.0.
- [Apache Commons IO](https://commons.apache.org/proper/commons-io/) e
  [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/): Apache License 2.0.
- As bibliotecas de testes são declaradas separadamente no Gradle e não fazem
  parte do APK release quando utilizadas apenas por testes.

## Fonte desta integração

A implementação Kotlin, recursos, testes e configuração de build do MusicAmz
estão em
[`musicamz-android` no repositório AMZ Studios](https://github.com/Muniz-Amz/amz-studios/tree/main/musicamz-android).
Consulte o commit que acompanha o APK para obter a versão correspondente.
O [README](README.md) descreve como compilar. Este arquivo de avisos não substitui
as licenças upstream nem representa uma auditoria completa de todos os binários
transitivos; mantenha os avisos e as fontes correspondentes ao redistribuí-los.
