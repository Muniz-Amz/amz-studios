# AMZ Clicker 3.3.1

Correção dos controles de iniciar, pausar e parar.

## Como usar

Feche o AMZ Clicker antigo e abra `AMZ-Clicker-v3.3.1.exe`. Os perfis continuam na mesma pasta de configurações.

| Tecla padrão | Durante a execução | Com o programa parado |
| --- | --- | --- |
| F6 | Para, inclusive durante a pausa ou a contagem inicial | Inicia |
| F7 | Pausa ou retoma | Não inicia uma execução |
| F8 | Para os cliques e a macro | Captura um ponto ou encerra a gravação |
| F12 | Parada de emergência | Cancela captura/teste e encerra gravação |

Basta pressionar: não é necessário soltar a tecla para o comando ser reconhecido. Segurar F6 ou F7 não fica alternando os estados.

F8 e F12 continuam parando a execução mesmo se os outros atalhos forem personalizados. Essas teclas são reservadas para parar; F8 também pode ser o atalho de captura. Perfis antigos que atribuíram F8/F12 a iniciar, pausar ou pular precisam ajustar esses atalhos.

## O que foi corrigido

- F8 agora para cliques e macros; antes ele só capturava posições ou encerrava gravações.
- Os controles reconhecem pressionamentos físicos separadamente das entradas geradas pela macro. Ctrl, Shift ou outras teclas seguradas não bloqueiam os atalhos padrão.
- Parar e pausar chegam diretamente ao motor, sem esperar a atualização visual.
- Uma parada cancela um início que ainda esteja na fila. A execução não reinicia por causa desse comando atrasado.
- O motor verifica novamente a parada após mover o cursor ou consultar uma condição, antes de enviar uma nova entrada.
- Teclas e botões pressionados são liberados na interrupção. Em uma gravação fiel, pausar encerra o bloco e libera suas entradas, como na versão anterior.

## Verificação

116 testes de dados/execução e 45 testes com a interface passaram. Cobrem repetição de teclas, atalhos personalizados, entradas sintéticas, início pendente, pausa, contagem inicial, cliques contínuos, liberação de teclas/botões e instalação/encerramento do monitor nativo do Windows. Os testes de execução usam entradas simuladas; não medem a resposta dentro de um jogo específico.

O executável 3.3.0 foi preservado. Abra a versão 3.3.1 para usar a correção.
