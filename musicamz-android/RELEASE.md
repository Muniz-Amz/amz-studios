# MusicAmz 1.2.2

Pacote `com.musicamz`, versionCode 17, Android 8/API 26 ou posterior.

## Alteração desta versão

Corrige a preparação dos componentes do download no primeiro uso. A minificação
R8 removia construtores de manipuladores ZIP do Commons Compress usados por
reflexão. A extração inicial falhava com `ExceptionInInitializerError` antes
do download, e a mensagem exibida indicava incorretamente incompatibilidade do
aparelho. O problema foi confirmado na versão anterior em um Moto G15 com
Android 15/ARM64. As regras de preservação desses componentes são corrigidas
nesta versão.

Mantém o retorno visual da versão 1.2.1: ao tocar em **Baixar MP3**, o teclado
fecha e a interface mostra o progresso ou um aviso de erro. Instale por cima
da versão anterior, sem desinstalar, para preservar os dados do aplicativo.

## Evidência anterior e limite dos testes

Na versão 1.2.1, os testes JVM, de interface e de integração passaram, e o APK
release baixou áudio em um emulador Android 14/x86_64. Porém, os componentes
do extrator já estavam preparados nesse ambiente; o teste não exercitou a
extração inicial que falhou no celular ARM64. Esses resultados anteriores não
validam a correção da versão 1.2.2.

## Validação desta distribuição

- Build release com R8 e lintVital concluído; 12 testes JVM passaram.
- O verificador `qa/check_zip_handlers.py` detectou a classe abstrata sem
  construtor no APK ARM64 antigo. No APK 1.2.2 assinado, os 13 manipuladores ZIP
  registrados preservam os construtores necessários em ARM64, ARM32 e x86_64.
- Emulador dedicado Android 14/x86_64: dados do aplicativo de testes apagados
  antes de instalar o APK release, sem Python/FFmpeg previamente extraídos.
  O fluxo Colar/Baixar preparou o motor do zero, baixou um vídeo público,
  converteu em MP3 e adicionou o áudio ao banco. Teclado e retorno visual conferidos.
- Moto G15 físico, Android 15/ARM64: a versão 1.2.1 reproduziu exatamente
  `ExceptionInInitializerError` ao inicializar o registro ZIP. Após instalar
  a 1.2.2, o mesmo diagnóstico passou. O link informado pelo usuário foi
  compartilhado para o aplicativo; o botão Baixar MP3 preparou o motor,
  baixou, converteu e adicionou uma faixa à biblioteca. Mensagem de conclusão
  e aumento da quantidade de faixas importadas confirmados no aparelho.
- Atualização instalada com `adb install -r` sobre a 1.2.1 no celular, sem
  desinstalar ou limpar dados. Nenhuma migração de banco foi alterada. Aplicativos
  auxiliares de diagnóstico removidos após os testes.
- Assinatura dos três APKs verificada, idêntica à versão anterior. ARM64 testado
  no celular e x86_64 no emulador; ARM32 passou pela verificação do pacote,
  mas não foi executado em aparelho físico nesta validação.
- Os dez testes de interface da 1.2.1 não foram repetidos: a interface não mudou
  nesta correção. O teste de ponta a ponta usou o APK final minificado.

Os testes não garantem acesso a todos os vídeos ou contas. Bloqueios, expiração
de cookies e mudanças do YouTube continuam dependendo da plataforma.

## SHA-256 dos APKs publicados

| Arquivo | SHA-256 |
| --- | --- |
| MusicAmz-v1.2.2.apk (ARM64) | af92e8941ca1c97ff0889a5fe5a19736698ce79815b76753376aafce5602c6e4 |
| MusicAmz-v1.2.2-armeabi-v7a.apk (ARM32) | 6bf75fffd9f0d8d3ad779a6645cce83d393d78e364d33b29cbbba501202d8555 |

O alias `MusicAmz.apk` contém o mesmo pacote ARM64 da versão 1.2.2, com hash
idêntico conferido antes da publicação.
