# AMZ PDF 1.0.0 — Android

Leitor de PDF em português, para Android 8.0 ou mais recente. Funciona offline com arquivos disponíveis no aparelho; não tem anúncios nem permissão de acesso à Internet. Provedores de arquivos na nuvem podem precisar de conexão para entregar o documento ao seletor do Android.

## Instalar e usar

1. Transfira `AMZ-PDF-v1.0.0.apk` para o celular e abra-o para instalar. Se o Android solicitar, autorize a instalação desse APK pelo aplicativo usado para abri-lo. Esta é uma instalação direta, fora da Play Store.
2. Abra **AMZ PDF** e toque em **Abrir PDF**. Escolha o documento no seletor de arquivos do Android.
3. Use **Anterior**, **Próxima** ou a barra de páginas. Toque no número da página para ir diretamente a outra.
4. Amplie com dois dedos, dê dois toques para ampliar/ajustar ou use os botões de zoom. Arraste para explorar a página ampliada.
5. Toque em **Marcar** para guardar a página. O botão **Marcadores** lista as páginas guardadas no documento.
6. Volte à **Biblioteca** para abrir outro PDF. Os quinze documentos recentes guardam a última página lida e até cem marcadores por documento. Segure um item dos recentes para remover sua referência; o PDF original permanece intacto.

O botão **Noite** inverte as cores da página na tela, inclusive imagens. **Dia** restaura as cores originais. O modo escolhido é lembrado.

## Alcance desta versão

- PDFs de até 100 MB, uma página por vez, com zoom de até 5× e renderização limitada a quatro milhões de pixels por página. O limite controla memória; ampliações extremas podem perder nitidez.
- O aplicativo pode receber um PDF por **Abrir com** ou pelo compartilhamento de outro aplicativo, desde que ele forneça um endereço `content://` com acesso de leitura.
- PDFs protegidos por senha ou formatos de proteção não suportados exibem uma mensagem; não há desbloqueio de senha nesta versão.
- É um leitor: não edita, assina, preenche formulários, faz OCR, pesquisa texto ou sincroniza documentos. A navegação é por páginas, sem rolagem contínua entre páginas.
- Arquivos movidos, excluídos ou fornecidos por aplicativos que concedem acesso temporário podem precisar ser selecionados novamente.
- A compatibilidade depende do renderizador de PDF do Android instalado. Os testes em emulador não substituem a validação no modelo específico do celular.

## Arquivos e privacidade

O leitor pede acesso somente ao documento escolhido no seletor. Não solicita acesso geral aos arquivos, contatos, câmera ou localização. Não possui servidor, conta ou telemetria própria. O serviço de renderização usa um processo isolado, sem permissões do aplicativo, e recebe apenas um descritor de leitura do PDF.

Durante a abertura, o conteúdo é copiado temporariamente para o cache privado para permitir leitura aleatória de arquivos fornecidos como fluxo. Essa cópia é removida após a abertura pelo renderizador; ela não é uma cópia permanente de biblioteca. O original não é alterado. Android e o aplicativo que fornece o arquivo mantêm seus próprios comportamentos de nuvem e armazenamento.

Histórico, posições e marcadores ficam nas preferências locais do aplicativo. Não há backup automático desses dados. Limpar os dados ou desinstalar o app remove o histórico e os marcadores do leitor, sem excluir os PDFs originais. As permissões persistentes que deixam de ser usadas pela lista de recentes são liberadas.
