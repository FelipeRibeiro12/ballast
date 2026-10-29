# ADR-061: Conta tipo cofrinho

**Status:** aceito
**Data:** 2026-10-28

## Contexto

O dono deixa o dinheiro parado em cofrinho de liquidez diária, que rende uma
parte do CDI. Ele quer o saldo no Posso gastar e uma estimativa do rendimento.

## Decisão

- Novo tipo de conta `cofrinho` (V28). Recebe lançamento e tem saldo como
  corrente. Entra no saldo projetado e no Posso gastar sem regra especial.
- `conta.percentual_cdi numeric(6,2)`: só o cofrinho tem, de 0,01 a 1000.
  Nulo é cofrinho sem percentual, que não tem rendimento estimado.
- `PUT /contas/{id}/cofrinho` define o percentual. No JSON vai em texto
  (`"110.00"`), nunca número, como o resto que não é centavo inteiro.
- O CDI vem do Banco Central pelo mesmo caminho da renda fixa (ADR-037,
  `IndexSeries.CDI`). Definir o percentual pede a busca em segundo plano.
- `CdiYield.estimate` calcula o rendimento do saldo entre duas datas com o
  fator da renda fixa: `saldo x (fator - 1)`, `RoundingMode.DOWN` para a
  estimativa nunca prometer mais do que o banco paga. Depois do último CDI
  publicado, usa o último valor nos dias de semana.
- A estimativa não vira lançamento. O banco credita o rendimento de verdade, e
  o usuário lança em Rendimento quando quiser conferir o saldo.

## Consequência

O Posso gastar soma a estimativa do cofrinho até a data-limite (ADR-063). Sem
CDI guardado, o rendimento estimado é zero.

## Revisão de 2026-10-29

- O percentual é validado como valor, de 0,01 a 1000: o regex só confere o
  formato, e `1000.50` passava e estourava o CHECK da V28 com 500. Agora dá 400.
- `PUT /contas/{id}/cofrinho` pede a busca do CDI e do IPCA no máximo uma vez a
  cada 10 minutos, para todos os usuários juntos. O Banco Central não é nosso.
