# Formato e limites de segurança — aplicativo 1.1.0

O diretório privado `files/vault-v1` contém metadados e conteúdo criptografados.
Não armazena senha, derivação da senha ou chave mestre em texto puro.
A chave existe em memória enquanto o cofre está aberto.

## Formato

- `vault.key`: magic AMZ1 (int big-endian), iterações (600000), salt aleatório
  de 16 bytes, IV de 12 bytes, chave mestre de 32 bytes envelopada por AES-GCM
  mais tag de 16 bytes. PBKDF2-HMAC-SHA256 / 256 bits deriva a chave de envelope.
  AAD `AMZ/key/1`. O parser aceita somente a contagem fixa desta versão.
- `index.enc`: IV de 12 bytes e AES-256-GCM, tag de 128 bits, chave mestre e
  AAD `AMZ/index/1`. DataOutputStream: versão e contagem int32; por entrada id,
  parent, name, MIME (writeUTF), folder (boolean), size/modified (int64), digest
  SHA-256 (32 bytes). O leitor aceita v1 e v2. A escrita usa v2 e acrescenta
  trashedAt (int64) e trashRoot (writeUTF) em cada entrada. A lixeira é cifrada.
- `recovery.key`, opcional: magic AMR1, IV de 12 bytes e chave mestre envelopada
  com AES-256-GCM (32 + 16 bytes), AAD `AMZ/recovery/1`. A chave aleatória tem
  256 bits, exibida como AMZ1 + 64 caracteres hexadecimais. O código puro não é
  persistido pelo cofre; o usuário pode salvá-lo explicitamente fora do app.
  Gerar outro código substitui apenas o envelope local.
- `backup.state`: IV de 12 bytes + AES-GCM com chave mestre e AAD
  `AMZ/backup-state/1`, contendo data int64 e SHA-256 de vault.key, index.enc
  e recovery.key (quando presente). São 68 bytes; não entra no backup.
  Gravado só após verificar o destino. Alterações reativam o lembrete; o app
  não monitora se o backup externo foi movido ou apagado.
- `<uuid>.bin`: magic AMF1, seguido de blocos de no máximo 1 MiB. Cada bloco
  armazena comprimento int32, IV aleatório de 12 bytes, ciphertext e tag de 16
  bytes. Chave por arquivo: HMAC-SHA256(master, `AMZ/file/1/<uuid>`).
  AAD `AMZ/chunk/1/<uuid>/<índice>/<comprimento>`. Um bloco vazio autenticado
  termina o arquivo. Tamanho, SHA-256, ordem dos blocos, marcador final e ausência
  de bytes adicionais são conferidos.
- Backup .amzcofre é ZIP sem compressão efetiva com os mesmos arquivos cifrados,
  incluindo lixeira e recovery.key quando ativada. Nomes originais não aparecem
  no ZIP. O importador aceita uma allowlist, rejeita duplicatas e traversal,
  limita a extração ao espaço disponível e verifica todos os arquivos antes
  de promover a pasta temporária. Nunca substitui um cofre existente.

## Escrita e remoção

Escrita de ciphertext e índice em temporário, fsync e rename. Importações passam
por leitura autenticada antes de entrar no índice. Antes de excluir o documento
original, a Activity sincroniza também o diretório do cofre e relê a origem para
conferir tamanho e SHA-256. Falhas conservam a origem ou uma cópia cifrada confirmada.
Não transforme erro de permissão para excluir em mensagem de sucesso.

Enviar à lixeira altera somente o índice. Não há expiração automática.
Restaurar resolve colisões sem sobrescrever entradas existentes. Exclusão definitiva
e retirada verificada atualizam o índice antes de apagar o ciphertext. Grupos já
excluídos separadamente permanecem independentes. Ciphertexts órfãos são limpos na
próxima abertura bem-sucedida. Nunca apague dados para resolver erro de senha ou
integridade. A v1.1 preserva conteúdos v1; apenas novas escritas de índice usam v2.

## Limites

- Tamanhos, quantidade de arquivos e existência do cofre podem ser inferidos por
  quem obtiver acesso à pasta privada. Não há ocultação de uso.
- Protege arquivos armazenados; não protege dispositivo comprometido,
  root/malware, captura física da tela ou senha fraca.
- Não há garantia de apagamento forense em flash. Outras cópias, lixeiras de
  provedores, sincronização de galeria e backups externos não são controlados.
- Prévias usam cache privado, limpo ao fechar a prévia, bloquear ou iniciar o app.
  Miniaturas ficam em LruCache na memória; a extração usa temporário privado
  apagado em finally. Bloqueio invalida trabalhos pendentes e descarta miniaturas.
  Encerramento abrupto pode deixar temporário até a próxima inicialização.
- Abrir em aplicativo externo concede leitura de cópia temporária; o receptor
  pode salvá-la. A concessão e o cache são removidos no retorno.
- Backup antigo mantém a senha e o envelope de recuperação que tinha ao ser salvo.
  Rotação do código não revoga backups antigos nem troca a chave mestre; quem
  já obteve essa chave de um backup continua tendo acesso criptográfico.
  Rotação do código não é remediação de comprometimento.
- Desinstalar ou limpar dados elimina o cofre. A recuperação exige backup externo
  e senha ou código previamente gerado e incluído naquele backup. Código sozinho
  não recria arquivos apagados. A chave de assinatura do APK não desbloqueia
  cofres, e a AMZ não recebe chaves nem arquivos.
- Testes funcionais e de integridade não substituem auditoria independente.

Referências oficiais:
[Android Cryptography](https://developer.android.com/privacy-and-security/cryptography),
[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
