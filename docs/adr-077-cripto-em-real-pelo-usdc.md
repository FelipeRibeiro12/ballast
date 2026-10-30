# ADR-077: Cripto em real pela cotação do USDC

**Status:** aceito
**Data:** 2026-10-30

## Contexto

O ADR-038 converte dólar em real pela PTAX do Banco Central. A PTAX só existe em
dia útil, e a cripto negocia 24 horas por dia, sete dias por semana. No sábado e
no domingo o valor em reais de uma posição ficava parado na PTAX de sexta, mesmo
com o token mexendo. O dono pediu para converter tudo que é cripto pela cotação
do próprio token, o USDC em reais (USDC/BRL), e para mostrar cada conta cripto
em reais e em dólar.

## Decisão

Emenda o ADR-038 só no que diz respeito à cripto. A PTAX segue na renda fixa e
no IPCA (ADR-037), sem mudança.

### Fonte e guarda

- A fonte é a CoinGecko já usada (ADR-040): ativo `usd-coin`, moeda de
  referência `brl`. Mesma chave Demo, mesmo timeout de 5 s e mesmos tetos da
  `CoinGeckoQuoteSource`. A chave segue só por variável de ambiente.
- Tabela nova `cotacao_usdc_brl` (migration V40), sem `usuario_id`: é dado de
  mercado público, como `indice`. Cada linha é uma leitura (`momento`, `preco`
  `numeric(20,8)` maior que zero, `fonte`). O que o histórico traz entra com o
  momento do ponto diário; o job grava a leitura atual com o horário que a
  CoinGecko informa. A última linha é a última leitura, com data e hora.
- O job de cotações existente atualiza também o USDC/BRL, no mesmo ritmo (30
  minutos, 24/7, sábado e domingo inclusive). O backfill traz até 364 dias,
  que é o que o Demo permite (ADR-041), uma vez, enquanto a tabela estiver
  vazia ou com buraco antes da primeira linha.

### Conversão

- O dólar de uma data é a última cotação USDC/BRL com momento até o fim daquele
  dia em São Paulo. Sábado e domingo usam a última leitura do próprio fim de
  semana, porque a série não para.
- **Fallback:** se não há USDC/BRL até a data (movimento mais antigo que o
  histórico), vale a PTAX da data, como no ADR-038.
- **Sem nenhuma das duas, some só a moeda que precisaria da conversão**, igual
  ao ADR-038. O patrimônio nunca falha.
- USDC/BRL ausente, zero ou negativo não entra na tabela (rejeitado na leitura
  e por `CHECK`). API fora do ar: ninguém espera por ela, valem a última leitura
  guardada e a data dela, que a tela mostra.
- Arredondamento uma vez, na saída, em centavos com `HALF_EVEN`. Cada moeda sai
  do valor cheio, nunca uma a partir da outra já arredondada.

### JSON

- Fiat em centavos inteiros, e o dólar também em centavos de dólar inteiros.
  Quantidade de cripto em string.
- O objeto `dolar` das posições passa a descrever a cotação usada: valor,
  data, momento da leitura e fonte (`USDC` ou `PTAX`).
- Cada conta cripto traz o valor em reais e em dólar, somados a partir dos
  valores cheios das posições. O patrimônio traz `criptoUsdCentavos`, nulo
  quando falta a cotação.

## Justificativa

A cotação do token acompanha o mercado nos sete dias, e é a que o dono
enxerga na exchange. Manter a PTAX como fallback preserva os movimentos antigos
sem migrar dado. Tabela própria evita misturar uma série de 30 minutos com a
série diária do BCB em `indice`.

## Alternativas descartadas

- **Gravar o USDC/BRL em `indice`**: a tabela guarda um valor por dia e a regra
  de cobertura do BCB. A série nova é por instante e vem de outra fonte.
- **Buscar o USDC/BRL na hora da requisição**: prende a requisição na CoinGecko
  e quebra o princípio do ADR-040.

## Consequência

Os testes que esperavam a PTAX na cripto passam a esperar o USDC/BRL, com um
caso próprio para o fallback. `dados.md` registra a tabela. Dívida: o Demo só
dá 365 dias; movimento mais antigo usa a PTAX.
