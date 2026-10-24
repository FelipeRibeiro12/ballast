# ADR-047: Patrimônio no tempo na F18.1

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A F18 deixou o patrimônio no tempo para uma fatia própria (ADR-046). O
`GET /patrimonio?data=` já soma, numa data, o saldo das contas por caixa, a
cripto com a última cotação até o fim do dia e o líquido dos títulos de renda
fixa. A posição sem cotação fica fora do total e aparece em `semCotacao`.

## Decisão

### Regra

- Um ponto por mês, com o patrimônio do último dia do mês.
- O mês corrente vale **hoje**. Mês depois do corrente fica de fora: o
  patrimônio de uma data futura seria só o previsto.
- Cada ponto é o mesmo cálculo de `/patrimonio` naquela data. Nada de regra
  nova: o gráfico e a tela de Investimentos dão o mesmo número para o mesmo
  dia.
- A posição sem cotação na data fica fora do total, como em `/patrimonio`. O
  ponto diz quantas ficaram de fora, e a tela avisa. Não se inventa preço nem
  se zera o resto.
- Conta arquivada fica fora em todos os meses, como em `/patrimonio`. O
  arquivamento não tem data.

### Rota

- `GET /patrimonio/mensal?mes=AAAA-MM`, com o mês corrente como padrão. São
  doze meses terminando em `mes`.
- Cada ponto traz `mes`, `data`, `contasCentavos`, `criptoCentavos`,
  `rendaFixaCentavos`, `totalCentavos` e `posicoesSemCotacao`.
- Um ponto fora da faixa do JSON dá 400 `patrimônio fora do limite`, como
  `/patrimonio`.
- Mora no pacote de cripto, ao lado do `NetWorthService`, que é dele.

### Tela

- Terceiro cartão de Relatórios, com o mesmo mês da navegação. Tem leitura
  própria: se o relatório do mês falhar, o patrimônio ainda aparece.
- **Linha**, porque é mudança ao longo do tempo. Uma série só, na cor da
  marca, sem legenda: o título diz o que é.
- O número de destaque é o último total, com quanto mudou desde o primeiro
  mês.
- O eixo fica à direita, com zero, metade e topo redondos. Se algum mês fica
  negativo, entra o lado de baixo do zero.
- Uma faixa invisível por mês, mais larga que o ponto, escolhe o mês no
  detalhe. Funciona por toque, mouse e teclado (Enter ou espaço). O detalhe
  separa contas, cripto e renda fixa, e a tabela tem tudo.
- São doze meses, com um rótulo de mês a cada três: em 360px os doze rótulos
  encostariam.

## Justificativa

Reusar `netWorth(data)` mantém uma regra só de patrimônio. Doze chamadas por
leitura custam pouco no volume atual.

## Alternativas descartadas

- **Uma consulta SQL para a série inteira**: mais rápida, mas repetiria o
  custo médio, a conversão pela PTAX e a marcação de renda fixa num segundo
  lugar, e os dois poderiam divergir.
- **Colunas empilhadas por parte (contas, cripto, renda fixa)**: com saldo
  negativo de cartão a pilha fica ambígua. As partes estão no detalhe e na
  tabela.
- **Patrimônio diário**: trinta vezes mais leituras para um gráfico que mostra
  tendência.

## Consequência

O saldo das contas lê todo o histórico de lançamentos a cada ponto (dívida já
no backlog). Com doze pontos, isso pesa doze vezes. Se medir lento, a
leitura em série vira a primeira candidata à otimização daquela dívida.
