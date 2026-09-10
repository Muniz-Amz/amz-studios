# Cofre AMZ — Android 1.2.0

Aplicativo nativo offline, Java, Android 8+ (API 26). ID permanente
`com.amzstudios.cofre`, versionCode 7.

## Recursos

- Duas senhas no mesmo campo de entrada. A senha atual abre o cofre principal;
  a senha alternativa abre um cofre independente, com a mesma tela e todos os
  recursos. Configuração em Opções do cofre → Senha alternativa, somente após
  entrar no principal. A configuração não move nem recriptografa arquivos atuais.
- Cada cofre tem chave mestre, índice, conteúdo, lixeira, recuperação e marcador
  de backup próprios. O backup inclui somente o cofre aberto. Nenhuma indicação
  de “falso” aparece ao entrar com a senha alternativa. Para trocar, bloqueie
  e digite a outra senha. Não há troca direta a partir do cofre alternativo.
- Senhas iguais são rejeitadas na configuração, alteração, recuperação e
  restauração. Senha inválida bloqueia ambos; nunca abre um cofre vazio como fallback.
- A recuperação seleciona somente o cofre correspondente ao código fornecido.
  Faça um backup e gere uma chave em cada cofre. Para restaurar os dois em outro
  aparelho, restaure primeiro o principal; dentro dele, abra Senha alternativa
  → Restaurar backup para restaurar o segundo em um destino vazio.
- Ao trocar de sessão, o app fecha o visualizador, invalida seletores antigos,
  limpa a seleção/busca e recria o carregador de miniaturas para o cofre aberto.
  Preferências de lista/grade são independentes.

O recurso separa o acesso dentro do app. Não garante ocultar a existência dos
dois cofres em uma análise técnica do aparelho. Veja SECURITY.md.

- Antes e depois de salvar o backup, a tela mostra quantos arquivos ativos e
  quantos arquivos da lixeira estão incluídos. Cofre vazio é identificado como
  backup somente da estrutura e dos dados de acesso. As contagens usam o índice
  no worker; não há leitura de fotos/vídeos nem consulta à galeria para o resumo.
- A cópia externa agora se chama “Copiar para fora (manter no cofre)”. Mensagens
  de retirada e cópia explicam sua participação nos próximos backups.

- Navegação Anterior/Próximo no visualizador de vídeos e áudios, com posição
  na sequência. Mantém a ordem da pasta ou busca exibida, separa vídeos de
  áudios e ignora pastas/lixeira. As pontas da sequência ficam desativadas.
  Não avança automaticamente ao terminar. A troca conserva o diálogo e
  aguarda a liberação do player/leitor anterior antes de criar outro.
  Toques rápidos durante a liberação escolhem apenas o último destino solicitado.

- Interface nativa em grafite e verde suave, ícones vetoriais, ações na base e
  feedback de toque. Sem biblioteca visual adicional, blur ou animação decorativa contínua.
- Toque abre arquivos diretamente; menu de três pontos mantém as ações. A seleção
  atualiza os controles sem reconstruir a tela ou perder a busca/posição da rolagem.
- Cartões reutilizados em lista/grade, miniaturas suspensas durante rolagem e
  reprodução, com decodificação em prioridade de fundo. Filtro/ordenação de
  catálogos acima de 500 entradas são feitos no worker, com descarte de resultados antigos.
- Fotos abertas por trechos autenticados com decodificação fora da interface e
  redução para até 2000 px por lado. PDFs renderizam uma página por vez no worker.

- Até dois cofres por instalação, senhas de 10+ caracteres, mudança de senha e pastas.
- Busca, renomear, seleção múltipla para mover, retirar, enviar à lixeira,
  restaurar e excluir definitivamente.
- Lista ou grade com miniaturas de fotos e vídeos compatíveis. Decodificação
  em fila separada e limitada, cache de 8 MiB e leitura autenticada por trechos,
  sem cópia temporária completa da foto ou vídeo.
- Lixeira criptografada sem expiração automática. Restauração mantém a estrutura,
  resolve nomes repetidos e devolve à raiz quando a pasta original não existe.
- Chave de recuperação offline aleatória de 256 bits, gerada por opção do usuário.
  Código exibido uma vez, com salvamento explícito fora do app. Redefine a senha
  sem recriptografar arquivos. Backups antigos mantêm a recuperação anterior.
- Espaço do cofre, lixeira e volume calculado no worker e reutilizado pela interface.
- Áudio/vídeo via MediaDataSource, MediaPlayer em HandlerThread e preparação
  assíncrona, proporção original, pausa, busca e saltos de 10 segundos. Não cria um arquivo legível do vídeo no cache.
  A reprodução mantém a tela ativa e o bloqueio por inatividade fica suspenso
  enquanto a prévia está aberta; sair do aplicativo continua bloqueando o cofre.
- Transferências, backups e restauração com etapas, progresso e interrupção.
  A tela fica ativa; mantenha o app aberto. Não há serviço de transferência
  persistente nem retomada automática após o sistema encerrar o processo.
- Importação verifica espaço conhecido antes de começar e novamente a cada
  64 MiB, preservando uma margem de aproximadamente 32 MiB. O tamanho informado
  pelo provedor pode estar ausente ou incorreto; falhas conservam a origem.
- Lembrete de backup baseado em alterações do índice, senha e recuperação.
  Só é atualizado após fechar, reler e verificar o backup salvo.
- Importação múltipla pelo Storage Access Framework. Cada arquivo é criptografado,
  sincronizado em disco e autenticado antes de remover a origem. O original é
  relido e comparado por SHA-256. Se o DocumentsProvider negar a remoção, a cópia
  protegida é mantida e o app avisa que o original continua na origem.
- Retirada individual com ACTION_CREATE_DOCUMENT; múltipla com
  ACTION_OPEN_DOCUMENT_TREE, preservando subpastas. Colisões recebem sufixo.
  Uma pasta só sai do cofre depois de todos os seus arquivos serem verificados
  no destino. Falhas deixam o grupo de origem intacto e podem deixar cópias parciais.
- Visualização interna de imagens, textos (até 2 MiB), PDFs e formatos de
  áudio/vídeo reconhecidos pelo Android. Outros formatos usam aplicativo externo
  escolhido pelo usuário, após explicar o acesso à cópia descriptografada.
- Backup .amzcofre inclui arquivos, lixeira e envelope de recuperação quando
  ativado. Restaura com senha ou código correspondente ao backup, somente em uma
  instalação sem cofre, sem substituir dados existentes.
- Bloqueio ao sair e após dois minutos de inatividade. Operações em andamento
  terminam antes de limpar a chave. Seletores autorizados permitem retorno
  dentro do prazo. Sem senha, a seleção expirada não remove arquivos.
- FLAG_SECURE, sem permissão INTERNET, conta, anúncios ou telemetria.
- Backup e transferência automática de dados do Android desativados.

## Compilação

JDK 17, Gradle 8.6, Android SDK 34 / build-tools 34.0.0, AGP 8.3.2.
Dependências dos repositórios Google/Maven Central.

```powershell
gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.amzstudios.cofre.test/androidx.test.runner.AndroidJUnitRunner
```

`build-release.ps1` aceita os caminhos do Gradle e JDK. Executa assembleRelease,
lintRelease e testReleaseUnitTest, sem ignorar falhas.

## Assinatura e atualizações

A chave de produção fica fora do repositório, por padrão em
`$env:USERPROFILE/.android/cofre-amz-signing/cofre-amz-release.p12`.
A senha local é protegida pelo DPAPI do Windows no mesmo diretório.
**Preserve e faça backup seguro da chave e da senha.** O DPAPI só pode ser aberto
pelo usuário Windows que o criou. Guarde a senha separadamente em um gerenciador
seguro antes de migrar de computador. Nunca publique ou substitua essa chave.

SHA-256 do certificado:
`c6dcd70a40693f6e0b9c5dec9b9d8007827b43605ebd2f55455af2ddb41c746f`.

A v1.2 lê índices v1 e v2, mantendo `files/vault-v1`, applicationId e assinatura.
A primeira alteração grava o índice v2 com os campos da lixeira; o conteúdo e a
chave mestre são preservados. Backups v1 podem ser restaurados. Cofres alterados
na v1.1 não devem ser abertos na v1.0. Instale por cima, sem limpar dados ou
desinstalar. Aumente versionCode/versionName em futuras versões.

Distribuição: `../../assets/meu-site-downloads/` a partir deste diretório,
junto do SHA-256. Atualize a página do cofre e o link na raiz do site.

## Validação

Nove testes JVM de sessões exercitam o isolamento entre senhas, arquivos,
lixeira, backup e recuperação; recusam colisões de senha; verificam reinício,
restauração lado a lado, interrupção e preservação do cofre anterior. Quatro testes Android
exercitam configuração pela tela, entrada pelas duas senhas, importação e backup
no alternativo, seleção expirada após troca e restauração/recuperação do segundo.
Uma regressão com 600 metadados bloqueia o filtro em segundo plano e confirma que nenhuma linha da sessão anterior permanece visível enquanto ele aguarda. Os demais testes verificam os recursos já existentes.

41 testes JVM cobrem criptografia por blocos, senha errada, adulteração,
truncamento, bytes extras, hierarquia, ciclos, backup/restauração, zip traversal,
importação interrompida, erro de destino e alterações na origem. Incluem migração
de um backup produzido pelo código original v1, lixeira aninhada, restauração com
colisões, movimentos múltiplos atômicos, recuperação errada/de outro cofre,
rotação do código, restauração por recuperação e marcador de backup adulterado.
As regressões de backup conferem o ZIP e restauram o resultado depois de retirar
arquivos: itens retirados, purgados, órfãos e externos ficam de fora; cópias
mantidas no cofre e lixeira permanecem. Um backup antigo preserva seu retrato
anterior e um novo backup do cofre vazio restaura somente estrutura e recuperação.

Quinze testes instrumentados no emulador Android 14 em modo avião exercitam
criptografia Android, criação e bloqueio, transferência real via DocumentsProvider,
miniaturas PNG/MP4, grade/lista, seleção, lixeira/restauração/exclusão, recuperação
pela tela, backup verificado e retirada em árvore com colisões e destino inválido.
Incluem reprodução por trechos, resposta da UI, ausência de cópia de vídeo no
cache, controle de bloqueio durante reprodução e interrupção imediata da importação.
Incluem também reciclagem sem miniatura residual, seleção sem reconstrução da
Activity, busca/rolagem com 5.000 entradas de metadados, imagem sem cópia no cache,
proporção horizontal/vertical de vídeo e navegação em PDF de duas páginas.
A navegação testa ordem, limites, isolamento de pasta/lixeira/tipos, busca com
vídeo único, toques rápidos, liberação do leitor anterior, retorno à mesma lista
e bloqueio com reprodução aberta. Há também regressão para fechar a Activity
durante uma operação pendente, sem tentar remover uma janela já destruída.
As regressões de retirada individual e em árvore salvam e restauram um novo
backup pelo fluxo real da Activity, verificam as contagens e a ausência dos
arquivos retirados. A cópia externa preservada continua presente e legível.
O provedor e os arquivos sintéticos de teste não entram no APK de produção.
Imagens de QA são geradas apenas com dados fictícios dos testes.

A validação de distribuição confere assinatura v2 e ausência de permissão de
internet e do provedor de teste. Na v1.1.0, a atualização do APK de produção foi verificada
sobre a versão 1.0.0 com um cofre sintético, preservando senha e arquivo.
Não houve auditoria criptográfica independente nem testes em todos os fabricantes;
não anunciar proteção absoluta. Veja [SECURITY.md](SECURITY.md).

Consulte [PERFORMANCE.md](PERFORMANCE.md) para o ensaio real de 3 GiB com heap
limitado a 96 MiB, seu comando de reprodução e o que ainda não foi validado.
