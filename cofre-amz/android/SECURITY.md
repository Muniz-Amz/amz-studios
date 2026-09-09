# Formato e limites de segurança — v1

O diretório privado `files/vault-v1` contém somente metadados e conteúdo
criptografados. Não armazenamos senha, derivação da senha ou chave mestre em
texto puro. A chave existe em memória enquanto o cofre está aberto.

## Formato

- `vault.key`: magic AMZ1 (int big-endian), iterações (600000), salt aleatório
  de 16 bytes, IV de 12 bytes, chave mestre de 32 bytes envelopada por AES-GCM
  mais tag de 16 bytes. PBKDF2-HMAC-SHA256 / 256 bits deriva a chave de envelope.
  AAD: `AMZ/key/1`. O parser aceita somente a contagem fixa desta versão.
- `index.enc`: IV aleatório de 12 bytes e AES-256-GCM com tag de 128 bits,
  chave mestre e AAD `AMZ/index/1`. O texto interno usa DataOutputStream:
  versão e contagem int32, depois por entrada id, parent, name, MIME (writeUTF),
  folder (boolean), size/modified (int64) e SHA-256 (32 bytes).
- `<uuid>.bin`: magic AMF1, seguido de blocos de no máximo 1 MiB. Cada bloco
  armazena comprimento int32, IV aleatório de 12 bytes, ciphertext e tag de 16
  bytes. Chave por arquivo: HMAC-SHA256(master, `AMZ/file/1/<uuid>`).
  AAD por bloco: `AMZ/chunk/1/<uuid>/<índice>/<comprimento>`.
  Um bloco vazio autenticado termina o arquivo. O tamanho, SHA-256, ordem dos
  blocos, marcador final e ausência de bytes adicionais são conferidos.
- Backup .amzcofre é um ZIP sem compressão efetiva contendo esses mesmos
  arquivos criptografados. Nomes originais não aparecem no ZIP. O importador
  aceita uma allowlist estrita de nomes, rejeita duplicatas e traversal,
  limita a extração ao espaço disponível e verifica todos os arquivos antes
  de promover a pasta temporária. Restauração nunca substitui um cofre existente.

## Escrita e remoção

Escrita de ciphertext e índice em arquivo temporário, fsync e rename.
Importações passam por leitura autenticada antes de entrar no índice. Antes da
exclusão do documento original, a Activity sincroniza também o diretório do
cofre. Falhas conservam a origem ou uma cópia criptografada já confirmada.
Não transforme um erro de permissão para excluir em uma mensagem de sucesso.

Remoções internas atualizam o índice antes de apagar o ciphertext. Arquivos
criptografados órfãos são limpos na próxima abertura bem-sucedida. Não apague
dados para resolver erro de senha ou de integridade.

## Limites

- Os tamanhos, a quantidade de arquivos e a existência do cofre podem ser
  inferidos por quem obtiver acesso à pasta privada. Não há ocultação de uso.
- O mecanismo protege arquivos armazenados; não protege um dispositivo
  comprometido, root/malware, captura física da tela ou senha fraca.
- Não há garantia de apagamento forense em memória flash. Outras cópias, lixeira
  de provedores, sincronização de galeria e backups externos não são controlados.
- As prévias internas usam temporariamente o cache privado; ele é limpo no
  fechamento da prévia, bloqueio ou próxima inicialização. Encerramento abrupto
  pode deixar uma prévia no cache até a próxima abertura.
- Abertura em aplicativo externo concede leitura de uma cópia temporária; o
  aplicativo receptor pode salvá-la. A concessão e o cache são removidos no retorno.
- Backup antigo continua usando a senha antiga, mesmo depois de trocar a senha.
- Desinstalação ou limpeza de dados elimina o cofre. Recuperação depende de um
  backup externo e da senha correta. A chave de assinatura do APK não desbloqueia
  os cofres, e a AMZ não recebe chaves nem arquivos.
- Testes funcionais e de integridade não substituem uma auditoria independente.

Referências oficiais:
[Android Cryptography](https://developer.android.com/privacy-and-security/cryptography),
[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
