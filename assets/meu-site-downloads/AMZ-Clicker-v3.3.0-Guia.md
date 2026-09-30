# AMZ Clicker 3.3.0

## As cinco melhorias

1. **Gravação fiel.** Em Macro, clique em **Gravar** e use o aplicativo de destino. **Parar gravação**, F8 ou F12 encerra e acrescenta um bloco à sequência existente. Cada pressionamento e liberação fica registrado com seu tempo, incluindo teclas sobrepostas, arrastes e roda. Clique no bloco para conferir os tempos segurados. A duração desse bloco vem da gravação e não pode ser alterada pelo campo de duração. Só a macro fica ativada ao finalizar uma gravação.
2. **Arrastar e rolar.** Adicione uma etapa e escolha **Arrastar** ou **Rolar**. O editor permite capturar as posições em 3 segundos. Arrastar tem início, fim e botão; Rolar tem posição e quantidade de passos: positivo sobe, negativo desce. A duração da etapa controla o tempo total da ação.
3. **Aguardar imagem.** Importe um recorte PNG ou capture dois cantos da tela: 3 segundos para o primeiro e mais 3 para o segundo. Ajuste a semelhança mínima de 80% a 100%. A duração da etapa é o tempo limite; se a imagem não aparecer, a execução termina com o motivo em Diagnóstico. A imagem fica dentro do perfil, sem depender de um arquivo externo.
4. **Posições proporcionais.** Em Condições, selecione a janela, escolha **Proporcionais à janela** e clique em **Usar tamanho atual como referência**. Depois capture as posições. Os pontos acompanham a posição e o tamanho da área interna da janela, inclusive arrastes, roda, gravações e condições por cor.
5. **Trechos reutilizáveis.** Em Macro → **Trechos reutilizáveis**, dê um nome e selecione a primeira e a última etapa. Abra outro perfil e use **Inserir**. Uma cópia independente entra no final da macro; os grupos recebem identificadores novos e a inserção pode ser desfeita. Há busca por nome e remoção da biblioteca.

## Como testar a sequência

- **Simular só a macro** executa a sequência uma vez sem enviar entradas. Condições por imagem e cor são ignoradas na simulação; ela não confirma se o destino aceitará os comandos.
- Para executar de verdade, confira o destino, a quantidade de ciclos e a espera inicial em Execução.
- F6 inicia/para; F7 pausa; F8 captura posições e encerra a gravação em andamento; F9 pula uma etapa; F12 interrompe. Os atalhos configurados pelo usuário prevalecem.
- Uma gravação em reprodução encerra se perder a condição de destino ou receber uma pausa. Reinicie o bloco para reproduzir os tempos desde o começo. Parar, pular, falhas e perda de foco liberam as entradas que o bloco estava segurando.
- Se uma gravação ultrapassar o limite de cliques por segundo, ela encerra com diagnóstico, preservando os tempos em vez de alongar teclas já pressionadas.

## Limites e compatibilidade

- A gravação admite até 10 minutos e 20.000 eventos. Repetições automáticas do teclado não criam pressionamentos duplicados. Movimentos do mouse são registrados durante o arraste, com amostragem aproximada de até 125 Hz. Entradas no próprio Clicker e teclas reservadas aos atalhos são ignoradas. O Windows e o aplicativo de destino podem introduzir pequenas diferenças de tempo.
- A busca de imagem é local. Quando o perfil exige uma janela, busca sua área interna; caso contrário, usa as telas. O recorte precisa ter de 8 a 512 pixels por lado e até 512 KB. O reconhecimento compara cores e pixels no mesmo tamanho: zoom, tema, animações e mudanças visuais podem exigir um novo recorte. Ele aguarda a imagem e não clica nela automaticamente.
- Posições proporcionais escalam coordenadas; não reconhecem mudanças de layout. Se trocar o modo ou o tamanho de referência, recapture os pontos. A janela precisa estar visível e em foco para enviar entradas.
- Para inserir um trecho, o perfil aberto precisa usar o mesmo modo de coordenadas e, no modo proporcional, o mesmo tamanho de referência. A janela de destino continua sendo a do perfil aberto. Imagens e gravações são copiadas junto com o trecho.
- Perfis antigos, dos formatos 1 a 4, continuam abrindo. Ao salvar, passam ao formato 5, com limite de 5 MB. A cópia válida anterior continua em `.bak`; versões antigas do programa não abrem o formato 5.
- A biblioteca fica em `%USERPROFILE%\Documents\AmzClicker\trechos.json`, com até 100 trechos e 20 MB. Uma cópia anterior fica em `.bak`. Com `--config-dir`, a biblioteca acompanha a pasta escolhida.
- A versão 3.2.0 foi preservada. A versão 3.3.0 é um executável separado.

## Verificação desta entrega

94 testes automatizados de dados e execução e 36 testes com a interface Tk, usando perfis temporários e entradas simuladas. Cobrem tempos sobrepostos, liberação de entradas, interrupção, arraste, roda, imagens sintéticas, escala de coordenadas, migração de perfis e trechos com desfazer/refazer.

Essa verificação não mede compatibilidade ou precisão de tempo dentro de jogos específicos.
