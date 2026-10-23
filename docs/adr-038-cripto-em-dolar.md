# ADR-038: Cripto em dólar na F13.3

**Status:** aceito
**Data:** 2026-10-22

## Contexto

Na F13 (ADR-035) a cotação e o custo de compra e venda de cripto são em reais.
O dono pediu dólar: exchange e carteira mostram tudo em USD, e ele quer ver a
cotação em dólar, o valor em reais e a quantidade de tokens. Ele escolheu
digitar o custo em USD e converter para reais pela PTAX do Banco Central, a
mesma fonte que a renda fixa já usa (ADR-037).

A PTAX de venda é a série 1 do SGS, em reais por dólar, um valor por dia útil,
publicada no próprio dia. Antes de julho de 1994 a série está em cruzeiros.

## Decisão

### Moeda guardada

- `movimento_cripto` e `cotacao` ganham `moeda` (`BRL` ou `USD`), com `BRL`
  para o que já existe: o que foi gravado na F13 continua valendo como reais.
  Tudo que a API grava daqui em diante é `USD`. `movimento_cripto.custo` e
  `cotacao.preco` guardam o valor na moeda da linha.
- `indice.serie` ganha `dolar` (série 1), desde 01/07/1994, faixa plausível de
  R$ 0,50 a R$ 50,00. O job da renda fixa baixa as três séries.

### Conversão

- O dólar de uma data é a última PTAX guardada até ela, inclusive. Valor em
  USD vira reais multiplicado por ele; valor em BRL vira USD dividido por ele.
- Custo de movimento usa o dólar da data do movimento. Valor de posição usa o
  dólar da data da posição.
- Arredondamento uma vez, na saída, para centavos com `HALF_EVEN`, cada moeda
  calculada a partir do valor cheio, nunca uma moeda a partir da outra já
  arredondada.
- **Sem dólar guardado, só a moeda que precisaria de conversão some.** Com a
  cotação em USD não há valor em reais: a posição fica com o valor em dólar e
  entra em `semCotacao` do patrimônio. Com cotação antiga em reais o valor em
  reais vale e entra no total, e o em dólar fica nulo. O patrimônio nunca falha
  por falta de dólar (QA do PR #23).
- Enquanto a PTAX do dia não sai, um movimento de hoje usa a PTAX anterior, e o
  custo em reais muda quando a do dia chega.

### JSON

- `POST /movimentos-cripto` troca `custoCentavos` por `custoUsdCentavos`. A
  resposta traz `custoUsdCentavos` e `custoCentavos` (em reais pelo dólar da
  data, nulo sem dólar).
- `POST /ativos-cripto/{id}/cotacoes` troca `preco` por `precoUsd`. A resposta
  traz `precoUsd`.
- `GET /posicoes-cripto` traz por posição `quantidade`, `custoUsdCentavos`,
  `custoCentavos`, `cotacao: {precoUsd, momento}`, `valorUsdCentavos`,
  `valorCentavos` e, uma vez na resposta, `dolar: {valor, data}` (nulo sem
  dólar).
- `GET /patrimonio` mantém `criptoCentavos` em reais.

## Justificativa

Guardar a moeda por linha deixa os dados da F13 corretos sem converter nada na
migration, e a conversão acontece só na leitura, com o dólar da data.

## Alternativas descartadas

- **Converter as linhas da F13 para USD na migration**: precisaria do dólar de
  cada data dentro da migration, e erraria se ele faltasse.
- **Cotação de dólar digitada à mão**: o dono escolheu a PTAX automática.

## Consequência

Emenda o ADR-035 (custo e cotação em reais). `dados.md` registra a coluna
`moeda` e a série `dolar`.
