# ADR-027: Divisão de salário na F6

**Status:** aceito
**Data:** 2026-10-20

## Contexto

A F6 deixa um template mensal gerar mais de uma ocorrência por período, cada
uma num dia, por percentual do valor cheio ou por valor fixo. O modelo está em
`docs/contracts/dados.md` (`template_divisao`), com a regra do centavo:
truncar cada parcela e pôr o resto na última pela `ordem`. O ADR-026 avisou
que a chave de idempotência teria de crescer.

## Decisão

- **Só em frequência mensal.** Divisão em diária, semanal ou anual dá 400.
- **Tabela `template_divisao`** como em `dados.md`, com `unique (template_id,
  dia_do_mes)` e `unique (template_id, ordem)`, de 2 a 10 parcelas. Cada linha
  tem percentual **ou** valor fixo, e todas as linhas de um template usam o
  mesmo modo; a aplicação garante o modo único e a soma, e o teste trava.
- **Dia da regra.** Com divisão, a requisição não manda `diaDoMes` na regra.
  O servidor grava em `regra_recorrencia.dia_do_mes` o dia da parcela de ordem
  1, só para o `CHECK` da regra mensal continuar valendo; quem gera as datas
  são as parcelas.
- **Chave de idempotência.** `lancamento` ganha `parcela_ordem` (nulo sem
  divisão) e a chave passa a ser `(template_id, ocorrencia_data,
  parcela_ordem)` com `NULLS NOT DISTINCT`. Parcelas dos dias 30 e 31 caem as
  duas em 28 de fevereiro e precisam continuar sendo duas ocorrências.
- **Valor de cada parcela.**
  - Percentual: `valor_total × percentual / 100` com `setScale(2,
    RoundingMode.DOWN)` em todas menos a última pela `ordem`, que recebe
    `valor_total` menos a soma das outras. A soma fecha sempre no total.
  - Valor fixo: a soma das parcelas tem de ser igual a `valor_total`, senão
    400.
  - Percentual entre 0,01 e 99,99 por parcela, soma exatamente 100,00.
- **JSON.** O percentual trafega como inteiro em centésimos de ponto
  percentual (`percentualCentesimos`: 40% é `4000`, 33,33% é `3333`), pelo mesmo
  motivo do ADR-005: decimal no JSON vira `double` no JavaScript. Valor fixo em
  `valorCentavos`.
- **Requisição.** `POST /recorrencias` aceita `divisao` opcional: `{"modo":
  "percentual" | "valor", "parcelas": [{"diaDoMes", "percentualCentesimos" |
  "valorCentavos"}]}`. A ordem é a do array. A resposta devolve a divisão com o
  valor em centavos que cada parcela gera.
- **Período e limites.** `ocorrenciasMax` conta períodos, não parcelas. `fim`
  vale por data: parcela com data depois do `fim` não é gerada. Parcela com data
  antes de hoje não é materializada, como no ADR-026, então o mês corrente pode
  sair com só parte das parcelas.
- **Ocorrência.** Cada parcela vira um lançamento previsto com o mesmo título e
  `parcelaOrdem` na resposta. Confirmar, cancelar e editar valem por parcela.

## Justificativa

Centésimos de ponto percentual cabem em `numeric(5,2)` sem perda e dispensam
parsing decimal no web.

Gravar o dia da primeira parcela na regra evita uma migration que mexa no
`CHECK` mensal só para o caso com divisão.

## Alternativas descartadas

- **Percentual como string decimal**: exato, mas o web teria de parsear
  decimal à mão só para isso.
- **Chave sem a ordem, com `ocorrencia_data` deslocada para evitar a
  colisão**: a data da ocorrência deixaria de ser a que a regra gerou.
- **Resto do centavo na primeira parcela**: `dados.md` já fixou a última, e é
  a que o usuário espera receber "o que sobrou".

## Consequência

`docs/contracts/dados.md` ganha `parcela_ordem` e a chave nova. A V9 troca o
índice único da V8.
