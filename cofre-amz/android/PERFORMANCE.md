# Desempenho — 1.1.2

## Interface na v1.1.2

- A seleção mantém a mesma GridView, consulta e posição de rolagem. Células são
  recicladas com tipos distintos para lista, grade e estado vazio. A imagem e
  a associação anterior são limpas quando a célula passa a representar outro arquivo.
- Durante a rolagem, a fila pendente de miniaturas é descartada. Quando a lista
  para, só as células visíveis são vinculadas novamente. O executor de miniaturas
  tem prioridade de fundo; mídia em reprodução impede novas miniaturas.
- Filtro e ordenação com mais de 500 metadados passam para o worker; um número
  de geração impede aplicar resultados de uma consulta ou tela antiga.
- Toque direto abre vídeos, sem menu intermediário. SurfaceView preserva a
  proporção original. Preparação/leitura continuam assíncronas.
- Fotos não são exportadas para arquivo temporário. Decodificação por trechos
  ocorre fora da interface, com amostragem até 2000 px por lado (até cerca de
  16 MB de bitmap ARGB). PDF mantém uma página exibida, até 1600 × 2400 px;
  abertura/renderização/fechamento nativos ficam no executor do visualizador.
- O tema usa fontes do Android, caminhos vetoriais e formas sólidas; nenhuma
  dependência visual adicional ou animação decorativa contínua foi adicionada.

## Validação da interface

Em emulador Android 14, modo avião, os oito testes instrumentados passaram
na compilação final em 134,589 s. A preparação do MP4 sintético pequeno após
um toque levou 1.263 ms; não é medição de vídeo de 2–3 GB. Vinte rodadas de
navegação/espera da interface sobre uma lista de 5.000 metadados levaram
1.981 ms; isso não mede FPS nem representa 5.000 arquivos gravados no cofre.
Os testes verificam reciclagem sem imagem residual, manutenção da seleção,
busca e posição de rolagem, imagem sem arquivo temporário, proporções de vídeo
e navegação em PDF, além dos fluxos de transferência, lixeira e recuperação.
Trinta testes JVM passaram. Lint de produção: zero erros.

APK de produção: 1.206.572 bytes, sem novas bibliotecas visuais, com o mesmo
identificador e certificado das versões anteriores.

## Base de transferência e mídia da v1.1.1

O formato de conteúdo continua AMF1, em blocos autenticados de até 1 MiB.
Não existe carregamento do cofre inteiro ou de um vídeo inteiro na memória.

- RandomReader usa posições long, valida cabeçalho, comprimento total e tag final,
  autentica cada bloco solicitado e mantém apenas um bloco de 1 MiB em cache.
- Miniaturas usam um executor independente com um trabalho ativo e até 12 na fila,
  no máximo oito referências fracas por entrada e cache de imagens de 8 MiB.
  Vídeos têm orçamento de leitura de 64 MiB por miniatura e as novas leituras
  deixam de ser aceitas após oito segundos. Esse prazo não interrompe uma chamada
  nativa que já esteja decodificando; ele não é garantia de tempo máximo de codec.
  Mídias que excedem o orçamento ou falham mostram o ícone do tipo de arquivo.
- MediaPlayer, abertura do leitor e leitura de dados executam fora da thread da
  Activity. A preparação é assíncrona e não precisa de 2–3 GB de cache temporário
  para assistir a um vídeo desse tamanho. Os codecs dependem do Android/aparelho.
- Contagens de filhos e informações de armazenamento são calculadas uma vez por
  operação no worker; busca e seleção não varrem o disco nem recalculam o backup.
- Backup, restauração e verificações completas continuam sequenciais e podem
  demorar com 100 GB. Há indicação de etapa/bytes, tela ativa e interrupção.
  Itens que já terminaram permanecem transferidos; os restantes são preservados.
  Interrupção pode deixar cópias parciais no destino, sem marcar backup completo.

## Ensaio de volume realizado

Ensaio realizado na v1.1.1, com o mesmo VaultEngine preservado na v1.1.2.
Em Windows / JDK 17, tools/VolumeProbe.java gerou e processou **3.221.225.489 bytes**
(3 GiB + 17), sem manter o original na memória ou em outro arquivo. Importação com
releitura autenticada, exportação integral com SHA-256 e leituras aleatórias perto
de 2 GiB e do final passaram com **-Xmx96m** (100.663.296 bytes de heap máximo).

Nesse computador, importação + verificação levaram 48,28 s e o ensaio inteiro,
67,13 s. Esses tempos **não são previsão para um celular**. O heap limita a memória
Java do processo do ensaio; não mede a memória total do aplicativo Android nem o
cache de disco do sistema operacional. Arquivos temporários do ensaio foram apagados.

Reprodução a partir deste diretório (JDK 17):

```powershell
javac -encoding UTF-8 -d probe-classes app/src/main/java/com/amzstudios/cofre/VaultEngine.java tools/VolumeProbe.java
java -Xms32m -Xmx96m -cp probe-classes com.amzstudios.cofre.VolumeProbe
```

O ensaio usa armazenamento real; requer mais de 3 GiB livres. Testes JVM cobrem
adulteração, cancelamento, fechamento, limites de blocos e cálculo para 100 GiB.
O cálculo de 100 GiB **não equivale a um ensaio físico com 100 GiB armazenados**.
Testes em emulador Android 14 usam mídia sintética pequena para validar decodificação,
reprodução, controles, resposta da Activity e ausência de cópia de vídeo no cache.

## Limites práticos

Ainda não foi testado um cofre físico de 100 GB nem o celular do usuário. É preciso
validar o modelo, versão do Android, formatos, quantidade de arquivos, espaço livre,
temperatura e duração das operações antes de afirmar capacidade nesse cenário.
Nenhuma versão deve ser anunciada como imune a travamentos.

A importação sequencial precisa de espaço para o maior arquivo em processamento
mais margem: primeiro grava/confere a cópia cifrada, depois remove a origem.
Backup exige aproximadamente o tamanho de todo o cofre no destino. A lixeira
continua ocupando espaço até exclusão definitiva. Mantenha o aplicativo aberto
em operações longas; encerramento pelo Android não tem retomada automática.

Referências de implementação:
[MediaDataSource](https://developer.android.com/reference/android/media/MediaDataSource),
[MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer),
[Interface responsiva](https://developer.android.com/topic/performance/anrs/keep-your-app-responsive).
