# ADR-007: Análise em Python só como serviço de leitura, na fatia 27

**Status:** aceito
**Data:** 2026-10-03

## Contexto

Foi considerado usar Python para os gráficos e a análise de dados do app, como
um serviço separado desde o início.

## Decisão

Gráficos e agregações do MVP são feitos em **SQL no Postgres**, servidos pela
API Go.

Python entra na **fatia 27**, como serviço separado, somente leitura, para o
que SQL não faz: previsão de gasto, detecção de lançamento anômalo e
classificação automática de categoria por descrição.

## Justificativa

Os gráficos descritos são `GROUP BY categoria, mês` com `SUM` — e o volume é de
alguns milhares de lançamentos por ano. Postgres responde isso em
milissegundos. Um serviço Python no meio adicionaria rede e serialização para
fazer mais devagar o que o banco já faz.

O critério para separar um serviço é ter cadência de deploy diferente,
dependências diferentes e não participar da transação. Agregação para gráfico
falha nos três. Previsão e classificação marcam os três:

- roda em lote, uma vez por dia, não por requisição
- arrasta pandas e scikit-learn, que não devem entrar no binário da API
- é somente leitura: lê histórico, escreve previsão. Nenhum risco de
  inconsistência de saldo

## Alternativas descartadas

- **Python como serviço de analytics desde a fatia 1** — complexidade sem
  problema correspondente.
- **Previsão dentro do monolito em Go** — possível, mas o ecossistema de
  modelagem em Go é bem mais magro, e o peso das dependências de Python no
  binário principal é justamente o que se quer evitar.

## Consequência

Quando a fatia 27 chegar, o serviço Python lê o Postgres direto (usuário de
banco somente leitura, por menor privilégio) ou consome um endpoint de
exportação. A decisão entre os dois fica para aquele momento, com ADR próprio.

Até lá, nenhuma dependência de Python entra no projeto.
