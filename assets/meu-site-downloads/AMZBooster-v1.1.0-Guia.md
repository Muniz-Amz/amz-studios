# AMZ Booster 1.1.0

Aplicativo independente para Windows 10/11 de 64 bits, em português. Abra `AMZBooster-v1.1.0.exe`; o executável inclui o Python e as dependências.

## O que faz

- **Visão geral:** CPU, memória RAM, espaço no disco do Windows e sugestões baseadas nas leituras atuais.
- **Jogos:** seleciona um plano de energia que já existe no computador e restaura o anterior ao encerrar a sessão ou fechar o Booster. Abre Modo de Jogo e as opções de gráficos do Windows.
- **Limpeza:** analisa arquivos antigos em `%LOCALAPPDATA%\Temp`. Relatórios de falhas em `%LOCALAPPDATA%\CrashDumps` são opcionais. Você seleciona os arquivos e confirma a remoção.
- **Programas:** CPU e RAM por processo, busca, filtros por consumo e ordenação por CPU, RAM ou nome. Exibe até 200 resultados depois dos filtros. CPU é normalizada pela capacidade total dos processadores lógicos e aparece depois de duas leituras.
- **Medir FPS:** captura quadros com PresentMon, calcula FPS médio, 1% baixo e P99 do tempo de quadros e desenha um gráfico. Também importa CSVs do PresentMon.
- **Histórico:** preserva os resultados de limpeza, sessões de energia e medições de FPS entre aberturas, com até 500 registros.
- **Ferramentas:** atalhos para inicialização, aplicativos, armazenamento e energia, além de relatório em texto.

## Limpeza

Por padrão, somente arquivos criados e modificados há mais de 7 dias são candidatos. É possível escolher 1 ou 30 dias. A lista começa sem seleção. Arquivos alterados desde a análise, inacessíveis, links, junções e pastas redirecionadas são ignorados. Pastas nunca são removidas. A análise tem limite de 5.000 arquivos por vez; a interface informa quando o limite foi atingido. Análise e limpeza podem ser canceladas.

A análise mostra contagem de itens e volume encontrado enquanto trabalha, sem estimar uma porcentagem antes de conhecer o total. Na remoção, a barra acompanha os arquivos processados. Clique nos cabeçalhos para ordenar por tamanho, data, nome ou pasta. **Ver motivos / detalhes** explica os itens ignorados. **Preservar selecionados** adiciona arquivos à lista de exclusões; em **Ferramentas**, também é possível excluir pastas inteiras da limpeza.

A remoção dos selecionados é permanente e não passa pela Lixeira. O resultado informa os bytes realmente removidos. O programa não faz limpeza automática na abertura.

## Sessão de jogo

O registro de recuperação fica em `%LOCALAPPDATA%\AMZBooster\power-session.json`, gravado antes de mudar a energia. Se houver encerramento inesperado, reabra o Booster e use **Encerrar e restaurar**. Se outro programa ou você trocar o plano durante a sessão, essa escolha é preservada. Somente uma instância normal pode abrir por sessão do Windows. Se a restauração falhar ao fechar, você pode tentar novamente, manter o aplicativo aberto ou fechar preservando o registro.

Em **Perfis por jogo**, salve nome, executável e plano de energia. Abra o jogo e clique em **Aplicar ao jogo aberto**. A comparação usa o caminho completo do executável; PID e horário de criação identificam os processos acompanhados. O plano é restaurado quando todos os processos daquela aplicação do perfil fecharem. Launchers devem apontar para o executável que permanece aberto durante o jogo. O Booster não inicia jogos nem aplica perfis automaticamente ao abrir.

Planos disponíveis variam por computador. Alto desempenho pode aumentar consumo e temperatura; melhorias de FPS dependem do hardware, jogo e gargalo. O Booster não altera serviços ou segurança do Windows e não encerra programas automaticamente. Leituras de CPU são instantâneas e a RAM exibida por processo é o conjunto de trabalho residente.

## FPS e estabilidade

Escolha **Medir FPS**, busque e selecione explicitamente o processo do jogo, escolha 30, 60 ou 120 segundos e inicie a medição. Volte ao jogo enquanto ele renderiza. A captura usa o PresentMon 2.6.0 x64 oficial, sem rastreamento de entrada do teclado/mouse. Em contas sem permissão de coleta ETW, o Windows exige executar o Booster como administrador. O aplicativo informa a condição, sem alterar permissões nem elevar automaticamente.

As estatísticas usam `MsBetweenPresents` do CSV `--v1_metrics`: FPS médio = 1000 / média dos intervalos; 1% baixo = 1000 / média dos 1% maiores intervalos; P99 = percentil 99 por posição arredondada para cima. São necessários pelo menos 30 intervalos válidos. Quando há mais de uma cadeia de apresentação, é usada aquela com mais amostras, sem somar quadros de diferentes cadeias. Na importação sem processo selecionado, também é escolhido o par processo/cadeia com mais amostras.

Essas métricas medem apresentações do aplicativo, não garantem a mesma taxa efetivamente exibida no monitor e não incluem uma análise de quadros gerados. O gráfico agrupa amostras em até 240 médias para visualização; as estatísticas usam todos os intervalos válidos. CSVs em UTF-8 e UTF-16 são aceitos, até 100 MB ou um milhão de linhas. Capturas e registros ficam em `%LOCALAPPDATA%\AMZBooster\captures`. Cancelar interrompe somente a sessão de captura criada pelo Booster. Use condições semelhantes no jogo ao comparar resultados.

## Histórico, bandeja e preferências

As preferências, exclusões, perfis e últimos 500 resultados ficam em `%LOCALAPPDATA%\AMZBooster\preferences.json`, com gravação atômica. Um arquivo inválido é preservado e o aplicativo informa o problema antes de qualquer tentativa de substituição.

Em **Ferramentas**, escolha o intervalo de atualização quando minimizado (15, 20, 30 ou 60 segundos), pause o diagnóstico ou use **Ir para bandeja**. A janela visível atualiza a cada 4 segundos. O acompanhamento do jogo continua a cada 2 segundos mesmo com o diagnóstico pausado. Na bandeja, use **Abrir AMZ Booster** ou **Encerrar e restaurar energia**. O botão X continua encerrando o aplicativo e restaurando a energia.

## Compilar e verificar

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
.\build.ps1
```

`build.ps1` roda os testes de lógica e de interface antes de gerar `dist\AMZBooster.exe` e seu SHA-256. Testes usam pastas temporárias e um controlador fictício de energia. `AMZBooster.exe --preview` permite conferir a interface com remoção de arquivos, mudança de energia, captura ao vivo de FPS e abertura de ferramentas externas desativadas.

O diretório `vendor` contém o binário oficial do PresentMon e sua licença. SHA-256 do PresentMon 2.6.0 x64: `b2a706bc6ad475749e3b7e3409263aa1e6906d45bdcf993f6dbc0f660188f1af` (conferido com o digest do lançamento oficial).

## Referências técnicas

- [Páginas de configurações do Windows](https://learn.microsoft.com/en-us/windows/apps/develop/launch/launch-settings)
- [Planos de energia e powercfg](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/powercfg-command-line-options)
- [Métricas de processos e recursos com psutil](https://psutil.readthedocs.io/stable/)
- [Documentação do PresentMon](https://github.com/GameTechDev/PresentMon/blob/v2.6.0/README-ConsoleApplication.md)
- [Lançamento oficial do PresentMon 2.6.0](https://github.com/GameTechDev/PresentMon/releases/tag/v2.6.0)
- [Integração da bandeja com pystray](https://pystray.readthedocs.io/en/latest/usage.html)
