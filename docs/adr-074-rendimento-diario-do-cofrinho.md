# ADR-074: Rendimento diário do cofrinho vira lançamento

**Status:** aceito
**Data:** 2026-10-29

## Contexto

O Mercado Pago credita o rendimento do cofrinho todo dia às 03:00. O dono quer o
saldo do Ballast igual ao do banco sem lançar nada à mão. O ADR-061 dizia que a
estimativa não vira lançamento; o dono pediu para mudar.

## Decisão

Este ADR substitui o trecho do ADR-061 que diz "A estimativa não vira lançamento".
O resto do ADR-061 vale (tipo, percentual, `CdiYield.estimate`, `RoundingMode.DOWN`).

- Job diário (`ballast.cofrinho.cron`, padrão `0 30 3 * * *` em
  America/Sao_Paulo, trinta minutos depois do banco). Ligado por padrão, como os
  outros jobs: `ballast.cofrinho.job-enabled`, variável
  `BALLAST_COFRINHO_JOB_ENABLED=false` para desligar. O perfil `migracao` e os
  testes desligam.
- Para cada conta `cofrinho` não arquivada com `percentual_cdi`, e para o dia de
  crédito D, o job cria um lançamento `receita` `realizado` na categoria semente
  Rendimento do dono, com `data_competencia = data_caixa = D` e título
  "Rendimento do cofrinho (estimado)". Passa pelo `ImportedEntries`, então valem
  as regras do `POST /lancamentos`.
- Valor: `CdiYield.estimate(CDI, saldo, percentual, L, D)`, com a janela `[L, D)`.
  `L` é o último dia creditado da conta; na primeira execução é o dia útil
  anterior a D. O saldo é o realizado até o fim do dia anterior a `L`
  (`data_caixa < L`). A janela de uma segunda-feira cobre sexta, sábado e
  domingo pelo fator de dias úteis do CDI, e o feriado também.
- Só há crédito em dia útil (`BusinessDays`, ADR-060). Sábado, domingo e
  feriado não rodam; o dia útil seguinte leva a janela inteira.
- Sem CDI guardado, sem categoria Rendimento, ou valor que arredonda para zero
  com saldo positivo: não cria lançamento e não avança `L`, então a janela
  acumula e nada se perde. Saldo zero ou negativo: grava só o marcador (sem
  lançamento) e avança `L`, porque nada rendeu.
- Idempotência: tabela `rendimento_cofrinho` (V38) com `unique (conta_id, data)`
  e `lancamento_id` nulo quando só avançou a janela. O job trava a conta
  (`FOR UPDATE`, mesma ordem do ADR-057) numa transação com prazo de trava curto
  (ADR-026) e confere o marcador dentro dela. Duas rodadas no mesmo dia não
  duplicam; trava ocupada pula a conta até a próxima rodada.
- Recuperação: se a API ficou fora, o job processa os dias úteis que faltam, no
  máximo 7 para trás, um lançamento por dia. A primeira execução de uma conta
  só faz o dia de hoje. Nunca retroativo.
- `POST /contas/{id}/cofrinho/rendimento` roda a conta agora para hoje, para o
  dono acertar o dia sem esperar o job. Escopado por `usuario_id` (conta alheia
  dá 404), idempotente pelo mesmo marcador, sob o limite geral por IP do
  ADR-049. Responde 200 com `situacao` (`lancado`, `ja_lancado`, `sem_cdi`,
  `sem_rendimento`, `sem_percentual`) e `valor` em centavos inteiros quando
  lançou.
- O lançamento é a estimativa. O banco pode diferir em centavos. O usuário
  edita ou apaga o lançamento como qualquer outro; o marcador continua, então o
  job não recria o dia apagado.
- Posso gastar não conta o rendimento duas vezes: a estimativa dele parte de
  hoje (`[hoje, menor saldo)`) sobre o saldo realizado, e o crédito de D já está
  no saldo; os dias futuros ainda não foram creditados. Há teste.

## Consequência

Saldo e extrato passam a conter receita automática. Gráfico por categoria mostra
Rendimento do cofrinho. O saldo base do dia ignora o crédito do próprio `L`, o
que subestima o juro sobre juro em um dia (para baixo, de propósito). Marcador
só some com a conta (`ON DELETE CASCADE`).
