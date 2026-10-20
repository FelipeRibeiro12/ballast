# ADR-026: Recorrência na F5

**Status:** aceito
**Data:** 2026-10-19

## Contexto

A F5 traz regra de recorrência, template e materialização 12 meses à frente,
idempotente. O modelo em `docs/contracts/dados.md` separa `regra_recorrencia`,
`template_lancamento` e as ocorrências em `lancamento`, e fixa que dia 29, 30
ou 31 em mês curto usa o último dia do mês. Divisão de salário é a F6.
Faltava fixar o recorte, a chave de idempotência, quando a materialização roda
e o que acontece com cada ocorrência.

## Decisão

### Regra

- Frequências `diaria`, `semanal`, `mensal` e `anual`, com `intervalo` de 1 a
  99, `inicio` obrigatório e no máximo um entre `fim` e `ocorrencias_max`.
- `mensal` usa **um** dia do mês (1 a 31); mais de um dia é a divisão da F6.
  Dia maior que o último do mês cai no último dia.
- `semanal` usa `dia_da_semana` (0 domingo a 6 sábado).
- `anual` repete dia e mês do `inicio`; 29 de fevereiro cai em 28 no ano não
  bissexto.
- `diaria` conta N dias a partir do `inicio`.
- O intervalo conta a partir do período do `inicio`: mensal a cada 2 meses com
  início em janeiro gera janeiro, março, maio.
- `ocorrencias_max` conta as ocorrências da série desde o `inicio`, inclusive
  as que caíram antes de a materialização começar.

### Template

- Receita ou despesa, com título, descrição, categoria, conta e valor, igual
  ao lançamento da F3 (mesmas validações e mesmas FKs compostas com
  `usuario_id`). Sem divisão (F6) e sem transferência recorrente.
- Rotas: `POST /recorrencias` (template e regra juntos), `GET /recorrencias`,
  `GET /recorrencias/{id}` e `PATCH /recorrencias/{id}` com `{"ativo":
  false}`. Desativar apaga as ocorrências **previstas, não desgarradas e com
  data a partir de hoje**. Sem editar a regra nem reativar na F5: corrigir é
  desativar e criar outra.
- Conta arquivada no template dá 400. A materialização pula template cuja
  conta está arquivada.

### Ocorrência

- `lancamento` ganha `template_id` (FK composta com `usuario_id`),
  `desgarrado` e `ocorrencia_data`, a data que a regra gerou. O `status`
  passa a aceitar `previsto`, `realizado` e `cancelado`.
- **Chave de idempotência: `unique (template_id, ocorrencia_data)` em toda
  linha com template**, desgarrada ou não. Desvia de `dados.md`, que usava
  `data_competencia` só nas não desgarradas: com aquela chave, mudar a data de
  uma ocorrência editada deixaria a regra gerar outra no dia antigo. A
  materialização insere com `ON CONFLICT DO NOTHING`.
- Previsto não entra no saldo (já era a regra: só `realizado`).
- `PATCH /lancamentos/{id}` com `{"status": "realizado"}` confirma uma
  prevista e `{"status": "cancelado"}` cancela. Só a partir de `previsto`.
  Confirmar segue a trava e o teto do ADR-024 e recusa conta arquivada.
- `PUT /lancamentos/{id}` numa ocorrência de template marca `desgarrado`;
  a materialização nunca toca linha desgarrada.
- `DELETE /lancamentos/{id}` numa ocorrência prevista de template dá 400
  `cancele a ocorrência`: apagar liberaria a chave e a regra geraria a mesma
  data de novo. Realizada de template pode ser apagada.
- `desgarrado` no confirmar e no cancelar é redundante hoje: a materialização
  nunca atualiza linha (a chave unique com `ON CONFLICT DO NOTHING` já ocupa o
  slot) e desativar só apaga `previsto`. Fica marcado mesmo assim, para
  `desgarrado` querer dizer "o usuário assumiu a linha" em qualquer caminho.
- `DELETE` de ocorrência `realizado` ou `cancelado` apaga a linha e libera a
  chave. Data de antes de hoje nunca volta (a materialização começa em hoje).
  Data de hoje em diante com o template ativo volta como `previsto` na próxima
  rodada do job; com o template inativo, não volta. Quem quer manter a
  ocorrência fora do saldo e da lista de previstas cancela, não apaga.
- Ordem de travas, para não haver deadlock: lançamento, depois conta (`PUT`,
  `DELETE`, confirmar); cancelar trava só o lançamento; transferência, depois
  lançamento, depois conta. Criar recorrência trava só a conta, o template ainda
  não existe e o `INSERT` não colide com linha de ninguém. Desativar trava o
  template e depois pega a linha de cada prevista no `DELETE`. O job trava só o
  template (`FOR UPDATE`), lê `arquivada` da conta sem travar e insere; confirmar
  não trava template, então não há ciclo com ele.
- O job não trava a conta porque o `INSERT ... ON CONFLICT` espera a linha que
  um `PUT` ou confirmar está editando, e esses dois seguram a linha e pedem a
  conta: travar a conta no job fechava o ciclo (achado D1 do QA). Consequência
  aceita: numa corrida com arquivar, o job pode inserir prevista numa conta que
  acabou de ser arquivada. Essa prevista não entra no saldo, não confirma (400
  `conta arquivada`) e pode ser cancelada. Arquivar não deixa de valer para
  lançamento, confirmar e transferência, que continuam travando a conta.
- A FK do `INSERT` do job ainda pede `KEY SHARE` na conta no fim da instrução, e
  a trava `FOR UPDATE` das escritas a bloqueia. Para o job não ficar na frente
  da fila e dobrar a espera da escrita do usuário (cada espera tem o prazo
  cheio), a transação do job usa `lock_timeout` de 200 ms. O template que estourar
  é pulado e entra na rodada seguinte.

### Materialização

- Gera as datas da regra no intervalo de `max(inicio, hoje)` até `hoje + 12
  meses`, respeitando `fim` e `ocorrencias_max`. Data que a regra gera antes de
  hoje nunca é materializada.
- "Hoje" é a data em `America/Sao_Paulo`, por um `Clock` injetável.
- Roda na criação do template, na mesma transação, e num `@Scheduled` diário
  que estende o horizonte de todos os templates ativos, um template por
  transação. A cada rodada, o template é travado com `FOR UPDATE`, para
  desativar e materializar não se cruzarem. O job pula template de conta
  arquivada, lida sem trava (ver ordem de travas).
- O job roda por template com o `usuario_id` do próprio template; nenhuma
  escrita cruza usuário.

### Espera de trava

- Toda transação de escrita de lançamento, transferência, recorrência e de
  arquivar conta faz `SET LOCAL lock_timeout = '2s'`, antes da primeira trava. A
  transação do job usa 200 ms (ver ordem de travas). Estourar dá 503
  `serviço indisponível`. Fecha a dívida da F3. O job pula o template travado e
  loga, sem stack. O Postgres devolve SQLState 55P03; o Spring não o traduz
  pelo `JdbcClient` (chega como `UncategorizedSQLException`), então a
  aplicação confere o SQLState na cadeia de causas.

## Justificativa

`ocorrencia_data` separa "qual ocorrência da série" de "quando ela aconteceu".
Só assim editar a data de uma prevista não cria uma duplicata.

Materializar só daqui para frente evita encher o extrato de previstas
vencidas ao cadastrar uma série que começou há anos.

Sem editar regra, a F5 não precisa decidir o que fazer com as previstas
geradas pela regra antiga, que é o problema mais difícil da recorrência. Ele
entra quando houver pedido, com ADR próprio.

## Alternativas descartadas

- **Gerar ocorrências na leitura, sem materializar**: não deixa editar nem
  confirmar uma ocorrência, que é o motivo de existir o `desgarrado`.
- **Chave em `data_competencia`**, como em `dados.md`: ver acima.
- **Materializar todo o passado desde o `inicio`**: previstas vencidas sem
  sentido para uma série antiga.
- **Job sem trava do template**: desativar no meio da rodada deixaria
  previstas novas de um template inativo.

## Consequência

`docs/contracts/dados.md` muda a chave de idempotência e ganha
`ocorrencia_data`. A F6 (divisão) gera mais de uma ocorrência por período do
mesmo template e vai precisar alargar a chave com a ordem da parcela.
