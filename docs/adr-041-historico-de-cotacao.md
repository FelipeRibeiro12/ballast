# ADR-041: Histórico de cotação para trás na F14.1

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A F14 (ADR-040) grava a cotação da CoinGecko daqui para frente. O patrimônio de
uma data anterior à primeira cotação do ativo fica sem preço, e a F18 vai
desenhar o patrimônio no tempo. O plano Demo só entrega 365 dias para trás e
recusa com 401 o pedido que passa disso.

## Decisão

### Pedido

- `/coins/{id}/market_chart/range` para nativo, com o mesmo id da F14, e
  `/coins/{plataforma}/contract/{contrato}/market_chart/range` para token. O
  segundo dispensa descobrir antes o id da moeda do token, que o backlog
  previa.
- Janela de 364 dias até agora, um dia de folga do limite do Demo. Com mais de
  90 dias a CoinGecko devolve um ponto por dia às 00:00 UTC.
- `vs_currency=usd` e `precision=full`; o preço vai direto para `BigDecimal`.
- 404 (`coin not found`) é ativo que a CoinGecko não conhece: lista vazia.
  Outro erro sobe como exceção.

### O que grava

- Só os pontos **antes da primeira cotação** daquele ativo para aquele dono,
  de qualquer fonte. A cotação digitada e a série do job continuam valendo dali
  para frente, sem ponto diário intercalado.
- `fonte = coingecko`, `moeda = USD`, com o `usuario_id` do dono do ativo. O
  conflito na PK é descartado.
- O mesmo filtro de preço da F14: 8 casas por `HALF_EVEN`, fora o que
  arredonda para zero e o que não cabe em `numeric(20,8)`.

### Uma vez por ativo

- `V17` cria `ativo_cripto.historico_em timestamptz`, nulo enquanto pendente.
  Os ativos que já existem entram na fila.
- Um pedido por `(rede, contrato)` serve a todos os donos pendentes. Os pontos
  e a marca de cada ativo gravam na mesma transação: se cair no meio, o ativo
  volta pendente sem buraco na série.
- Ativo que a CoinGecko não conhece também é marcado, para não pedir de novo a
  cada rodada. Ativo novo de outro usuário com o mesmo contrato faz um pedido
  novo, que é o que a cota permite sem catálogo global (ADR-035).
- Ativo não confiável não é pedido.

### Job

- Roda no `QuoteJob`, logo depois da cotação: o ativo novo já tem a cotação de
  agora e o histórico enche o que vem antes dela.
- Até 10 ativos por rodada, mais antigos primeiro. Com os até 6 pedidos da
  cotação são 16 no mesmo minuto, abaixo dos 30 do Demo. Cada ativo pede uma
  vez na vida, então a conta mensal da F14 não muda.
- Falha da fonte para a rodada; o que faltou fica para a próxima, 30 min
  depois. O log leva só o nome da exceção, como na F14.

## Justificativa

Encher só antes da primeira cotação mantém uma regra simples para a posição:
a última cotação até a data, sem decidir entre a digitada e a diária. A marca
no ativo é o menor estado que evita pedir o ano inteiro a cada 30 minutos.

## Alternativas descartadas

- **Descobrir o id da moeda por `/coins/{plataforma}/contract/{contrato}`**:
  um pedido a mais por token, e o endpoint de contrato já tem o histórico.
- **Gravar todos os pontos do ano, também depois da primeira cotação**: a
  cotação diária das 00:00 passaria na frente da digitada no dia seguinte.
- **Fonte nova (`coingecko_historico`)**: o dado é o mesmo preço do mesmo
  provedor, e o CHECK e a tela teriam um caso a mais sem ganho.
- **Inferir o pendente por "não há cotação antes de um ano"**: ativo que a
  CoinGecko não conhece seria pedido para sempre.

## Consequência

O patrimônio e a posição de datas do último ano passam a ter cotação. Antes de
um ano continua sem preço no plano Demo. A F18 pode desenhar a série sem
buscar nada na hora.
