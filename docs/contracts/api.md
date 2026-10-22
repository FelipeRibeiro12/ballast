# Contrato da API

A spec é [`api/openapi.yaml`](../../api/openapi.yaml), gerada do código pelo
springdoc e commitada (ADR-019). Este arquivo não repete a spec: mudou rota,
corpo ou status no código, a spec regenerada entra no mesmo commit.

- O CI falha se a spec commitada divergir da gerada do código.
- O cliente TS é gerado da spec no build do web; o CI falha se o arquivo
  commitado divergir do gerado.
- As rotas declaram `produces = application/json`. Cliente que manda só
  `Accept: text/html` recebe 406, e isso vale também para o `/healthz`: probe
  de orquestrador precisa aceitar JSON ou `*/*`.
- Em qualquer rota, banco fora responde 503 `{"erro":"serviço indisponível"}`, e
  corpo acima de 4 KiB responde 413 (ADR-023).
- Em `/contas/{id}`, conta de outro usuário, id inexistente e id que não é UUID
  respondem 404 com o mesmo corpo.
- Dinheiro de fiat sai e entra como inteiro de centavos
  (`saldoInicialCentavos`, `saldoCentavos`), entre -9007199254740991 e
  9007199254740991. Decimal no JSON (`12.5`) é recusado com 400.
- Em `/lancamentos/{id}`, vale a mesma regra de 404 (outro usuário, inexistente
  ou id que não é UUID, mesmo corpo). `valorCentavos` é sempre positivo, de 1
  a 9007199254740991, e o sinal vem do `tipo` (ADR-024).
- `GET /lancamentos` exige `de` e `ate` (AAAA-MM-DD, ano de 1900 a 2199, `de`
  menor ou igual a `ate`, no máximo 366 dias contando os dois extremos) e filtra
  por `dataCaixa`. `contaId` opcional: UUID malformado dá 400; UUID bem formado
  de conta alheia ou inexistente dá lista vazia, para não revelar a conta.
- Em `POST` e `PUT /lancamentos`, conta inexistente, de outro usuário ou id
  inválido dá 400 `conta não encontrada`; categoria inexistente, de outro
  usuário, de tipo diferente do lançamento ou id inválido dá 400
  `categoria não encontrada`; conta arquivada dá 400 `conta arquivada`. No `PUT`,
  a recusa por conta arquivada vale só ao trocar de conta: editar ou apagar
  lançamento que já está numa conta arquivada é permitido.
- O saldo de cada conta fica entre -9007199254740991 e 9007199254740991
  centavos. `POST`, `PUT` e `DELETE /lancamentos` que levariam o saldo de
  alguma conta afetada (no `PUT` que troca de conta, as duas) para fora dessa
  faixa dão 400 `saldo da conta fora do limite` e nada grava. Toda escrita de
  lançamento é serializada por conta (ADR-024): depois que o `PATCH` de arquivar
  respondeu 200, nenhum lançamento novo entra na conta.
- O corpo do lançamento recebe uma `data` e a API grava a mesma em
  `dataCompetencia` e `dataCaixa`, menos em conta de cartão de crédito, onde a
  `dataCaixa` é o vencimento da fatura (ver Cartão de crédito). Campo não
  declarado dá 400.

## Transferência (ADR-025)

- `POST /transferencias` recebe `{"contaOrigemId", "contaDestinoId",
  "valorCentavos", "data", "titulo"?, "descricao"?}` e responde 201 `{id,
  contaOrigemId, contaDestinoId, valorCentavos, data, titulo, descricao,
  saidaId, entradaId, criadaEm}`. Título ausente, nulo ou em branco vira
  `Transferência`. `GET /transferencias/{id}` devolve o mesmo corpo e
  `DELETE /transferencias/{id}` responde 204 e apaga a ligação e as duas pernas.
  Não existe `PUT`: corrigir é apagar e criar de novo.
- Em `/transferencias/{id}`, outro usuário, inexistente e id que não é UUID
  dão 404 com o mesmo corpo.
- Recusas do `POST`, todas 400 sem gravar nada: `conta não encontrada` (origem
  ou destino inexistente, de outro usuário ou id inválido, a mesma mensagem
  para os dois), `conta arquivada` (origem ou destino), `contas devem ser
  diferentes`, `cartão não pode ser origem de transferência` (origem em conta de
  cartão; o cartão só entra como destino, para pagar a fatura, vindo de qualquer
  conta do usuário) e `saldo da conta fora do limite` (qualquer das duas contas). O
  `DELETE` dá 400 `saldo da conta fora do limite` se tirar uma das contas da
  faixa.
- Cada transferência são dois lançamentos com `tipo` `transferencia` (as
  pernas), sem categoria, com o mesmo valor, título, descrição e data nas duas
  datas. A de saída subtrai do saldo da origem, a de entrada soma no destino.
- `GET /lancamentos` e `GET /lancamentos/{id}` ganham `transferenciaId` e
  `direcao` (`saida` ou `entrada`), nulos nos outros tipos. Nas pernas,
  `categoriaId` vem nulo. O cliente tira `transferencia` dos totais de receita
  e despesa e mostra as pernas na lista.
- `POST` e `PUT /lancamentos` só aceitam `tipo` `receita` ou `despesa`:
  `transferencia` dá 400. `PUT` e `DELETE /lancamentos/{id}` numa perna dão 400
  `use a rota de transferência` e não mudam nada.

## Recorrência (ADR-026)

Criar, listar, ver e desativar a recorrência, e confirmar, cancelar, editar e
apagar uma ocorrência (seção no fim).

- `POST /recorrencias` recebe `{"tipo", "titulo", "descricao"?,
  "valorCentavos", "categoriaId", "contaId", "regra": {"frequencia",
  "intervalo"?, "diaDoMes"?, "diaDaSemana"?, "inicio", "fim"?,
  "ocorrenciasMax"?}}` e responde 201 `{"recorrencia": {...},
  "ocorrenciasMaterializadas": N}`. `tipo` é `receita` ou `despesa`; título,
  descrição e valor seguem as regras do lançamento. Sem `data`: quem dá as datas
  é a regra.
- Regra: `frequencia` é `diaria`, `semanal`, `mensal` ou `anual`; `intervalo`
  de 1 a 99 (padrão 1) e conta a partir do período do `inicio` (mensal a cada 2
  meses com início em janeiro: janeiro, março, maio). `inicio` obrigatório
  (AAAA-MM-DD, ano de 1900 a 2199); no máximo um entre `fim` (incluído) e
  `ocorrenciasMax` (1 a 10000, conta desde o `inicio`, também as que caíram
  antes de hoje). `mensal` exige `diaDoMes` (1 a 31; dia maior que o último do
  mês cai no último dia) e não aceita `diaDaSemana`. `semanal` exige
  `diaDaSemana` (0 domingo a 6 sábado) e não aceita `diaDoMes`. `diaria` e
  `anual` não aceitam nenhum dos dois (anual repete dia e mês do `inicio`; 29/2
  cai em 28/2 em ano não bissexto). Semanal com intervalo maior que 1 conta
  semanas de domingo a sábado a partir da semana do `inicio`.
- A criação materializa, na mesma transação, as ocorrências de `max(inicio,
  hoje)` até `hoje + 12 meses`, as duas pontas incluídas, como lançamentos
  `previsto`. Hoje é a data em `America/Sao_Paulo`. A data que a regra gera antes
  de hoje não vira lançamento. Série que já terminou é criada com
  `ocorrenciasMaterializadas` 0. Um job diário (03:00 São Paulo) estende o
  horizonte dos templates ativos; o cliente não chama nada para isso.
- Corpo de `recorrencia` (também o de `GET`): `{id, tipo, titulo, descricao,
  valorCentavos, categoriaId, contaId, ativo, criadoEm, regra: {frequencia,
  intervalo, diaDoMes, diaDaSemana, inicio, fim, ocorrenciasMax},
  proximaOcorrencia}`. Campos da regra que a frequência não usa vêm `null`.
  `proximaOcorrencia` é a menor `dataCaixa` entre as ocorrências previstas de hoje
  em diante (a data que a lista mostra, também para prevista editada), ou `null`
  quando não há ou a recorrência está inativa.
- Recusas do `POST`, todas 400 sem gravar nada, com mensagem fixa e sem o valor
  enviado: campos fora da faixa ou combinação que a frequência não aceita (por
  exemplo `mensal exige diaDoMes`, `informe fim ou ocorrenciasMax, não os dois`,
  `fim deve ser igual ou posterior a inicio`), `conta não encontrada` (inexistente,
  de outro usuário ou id inválido), `conta arquivada` e `categoria não
  encontrada` (inexistente, de outro usuário, de tipo diferente do template ou id
  inválido). Campo não declarado dá 400.
- `GET /recorrencias` lista as do usuário, ativas e inativas, por título sem
  diferenciar maiúscula de minúscula e depois por id. `GET /recorrencias/{id}`
  devolve uma.
- `PATCH /recorrencias/{id}` com `{"ativo": false}` desativa: marca o template
  inativo e apaga as ocorrências `previsto`, não desgarradas, com data de hoje em
  diante. As de antes de hoje, as realizadas e as editadas ficam. Repetir a
  chamada devolve o mesmo estado. `{"ativo": true}` reativa, e `PUT` e `DELETE`
  editam e apagam (ver "Editar e apagar contas e recorrências").
- Em `/recorrencias/{id}`, outro usuário, inexistente e id que não é UUID dão 404
  com o mesmo corpo.
- Banco fora responde 503 `serviço indisponível`; corpo acima de 4 KiB, 413.
- `GET /lancamentos` e `GET /lancamentos/{id}` ganham `templateId` (id da
  recorrência que gerou a ocorrência, `null` nos avulsos), e `status` passa a
  poder ser `previsto` (a guarda de tipo do cliente precisa aceitar), além de
  `realizado` e `cancelado`. As previstas entram na lista do mês pela
  `dataCaixa`, com `dataCompetencia` igual. **O saldo continua somando só
  `realizado`**: previsto não muda `saldoCentavos`.
  Nenhuma rota cria uma prevista: só a regra gera.

### Divisão de salário (ADR-027)

- `POST /recorrencias` aceita `divisao` opcional: `{"modo": "percentual" |
  "valor", "parcelas": [{"diaDoMes", "percentualCentesimos" | "valorCentavos"}]}`,
  de 2 a 10 parcelas. A ordem do array é a ordem das parcelas (a primeira é a
  ordem 1). `diaDoMes` de 1 a 31, `percentualCentesimos` de 1 a 9999 (40% é
  `4000`, 33,33% é `3333`: inteiro, nunca decimal) e `valorCentavos` de 1 a
  9007199254740991. Todas as parcelas usam o campo do modo declarado, e o campo
  do outro modo não pode vir.
- Com `divisao`, a regra é mensal e **não manda `diaDoMes`** (as parcelas dão os
  dias). O servidor grava o dia da parcela de ordem 1 na regra, e a resposta
  devolve esse valor em `regra.diaDoMes`. Sem `divisao`, `mensal` continua
  exigindo `diaDoMes`.
- Recusas, 400 sem gravar nada: `divisao só vale em mensal`, `diaDoMes não vale
  junto com divisao`, `divisao.modo deve ser percentual ou valor`,
  `divisao.parcelas deve ter entre 2 e 10 parcelas`, `cada parcela deve trazer
  só o campo do modo da divisao`, `os dias das parcelas devem ser distintos`,
  `os percentuais das parcelas devem somar 10000`, `os valores das parcelas
  devem somar o valorCentavos` e `toda parcela deve valer ao menos 1 centavo`
  (percentual pequeno sobre valor pequeno que trunca para 0,00). Faixas por
  campo têm mensagem própria. Campo não declarado, na divisão ou na parcela,
  dá 400.
- Regra do centavo: cada parcela por percentual é `valor × percentual / 100`
  truncada em centavos, e a última (maior `ordem`) recebe o resto, então a soma
  das parcelas é sempre `valorCentavos`. R$ 1.000,00 em 3333, 3333 e 3334 dá
  333,30 + 333,30 + 333,40. Em modo valor, cada parcela é o valor informado.
- `recorrencia.divisao` (em `POST`, `GET /recorrencias` e `GET
  /recorrencias/{id}`) é `null` sem divisão, e senão `{modo, parcelas: [{ordem,
  diaDoMes, percentualCentesimos | valorCentavos, valorParcelaCentavos}]}`. Só o
  campo do modo vem em cada parcela; `valorParcelaCentavos` é o que a parcela
  gera por mês, já com a regra do centavo. A guarda de tipo do cliente precisa
  aceitar `divisao` anulável e a ausência de `percentualCentesimos` ou
  `valorCentavos`.
- Materialização: cada mês que a regra gera vira uma ocorrência `previsto` por
  parcela, com `dataCaixa` e `dataCompetencia` no dia da parcela (dia maior que
  o último do mês cai no último dia: parcelas dos dias 30 e 31 em fevereiro caem
  as duas em 28/2, ou 29/2 em ano bissexto, e seguem duas ocorrências).
  `ocorrenciasMax` conta meses, não parcelas. `fim` e o início do horizonte
  (hoje) valem por data de parcela: o mês corrente pode sair com só as parcelas
  de hoje em diante. `ocorrenciasMaterializadas` conta parcelas. O título é o do
  template, igual nas parcelas.
- `GET /lancamentos` e `GET /lancamentos/{id}` ganham `parcelaOrdem` (1 a 10 na
  ocorrência de uma divisão, `null` nos avulsos e nas ocorrências sem divisão).
  Confirmar, cancelar, editar e apagar valem por parcela, sem mudança de
  formato; apagar libera só a data e a ordem daquela parcela.

### Ocorrência

- `PATCH /lancamentos/{id}` com `{"status": "realizado"}` confirma e com
  `{"status": "cancelado"}` cancela uma ocorrência. Só esses dois valores, e o
  campo é obrigatório: sem ele, 400 `status é obrigatório`; outro valor
  (inclusive `previsto`), 400 `status deve ser realizado ou cancelado`; campo não
  declarado, 400. Responde 200 com o lançamento (mesmo corpo de `GET`).
- Confirmar aceita ajuste (ADR-028): `{"status": "realizado", "valorCentavos"?,
  "data"?}`, os dois opcionais. `valorCentavos` é inteiro de 1 a
  9007199254740991 e `data` é `AAAA-MM-DD` estrito com ano de 1900 a 2199, as
  mesmas regras e mensagens do `POST /lancamentos`; inválido dá 400 e nada muda.
  A `data` vale para `dataCompetencia` e `dataCaixa` (em conta de cartão, a
  `dataCaixa` é recalculada pelo vencimento). Valor e data entram na mesma
  transação da confirmação e o teto do saldo é conferido com o valor novo. A
  ocorrência sai do mês antigo da lista e aparece no novo. A chave da regra
  (`ocorrencia_data`, que a API não expõe) não muda, então o job não recria a
  ocorrência na data original. Com `cancelado`, `valorCentavos` ou `data`
  presentes dão 400 `cancelar não aceita valorCentavos nem data` e nada muda.
  Numa parcela de divisão (ADR-027), o ajuste vale só para ela: as outras
  parcelas do mês não se rebalanceiam, e a soma do mês passa a ser o que de fato
  entrou, não mais o valor cheio do template.
- Só parte de `previsto`. De `realizado` ou `cancelado`, 400 `só ocorrência
  prevista pode mudar de status` (confirmar duas vezes dá 400 na segunda e o saldo
  conta uma vez). As duas mudanças são definitivas: não existe voltar a
  `previsto`.
- Confirmar entra no saldo na `dataCaixa` da ocorrência, recusa conta arquivada
  (400 `conta arquivada`) e confere o teto do saldo (400 `saldo da conta fora do
  limite`); nos dois casos a ocorrência segue `previsto`. Cancelar não mexe em
  saldo e vale mesmo com a conta arquivada.
- Perna de transferência no `PATCH` dá 400 `use a rota de transferência`. Outro
  usuário, inexistente e id fora da forma canônica dão 404 com o mesmo corpo do
  resto de `/lancamentos/{id}`.
- `PUT /lancamentos/{id}` numa ocorrência (qualquer status) funciona como num
  lançamento comum e a deixa desgarrada: a regra nunca mais altera a linha.
  `templateId` fica, e o `status` não muda pelo `PUT`. Numa prevista o teto do
  saldo não entra (previsto não conta no saldo); mover para conta arquivada dá
  400 `conta arquivada`. Mudar a data da ocorrência não faz a regra gerar outra
  na data original.
- `DELETE /lancamentos/{id}` numa ocorrência `previsto` dá 400 `cancele a
  ocorrência` e nada muda: cancele. Ocorrência `realizado` ou `cancelado` pode ser
  apagada (204). Apagar libera a data para a regra: se a data é de hoje em diante
  e a recorrência segue ativa, o job a gera de novo como `previsto` na próxima
  rodada; data de antes de hoje não volta.
  Quem quer só tirar a ocorrência do saldo e da lista de previstas cancela, não
  apaga.
- A resposta do lançamento não traz `desgarrado`. Quem precisa saber se a
  linha saiu do controle da regra usa `status` (confirmada e cancelada já saíram).

### Vencidas (ADR-028)

- `GET /lancamentos/vencidos` devolve as previstas do usuário com `dataCaixa`
  antes de hoje (dia de `America/Sao_Paulo`), da mais antiga para a mais nova
  (depois `id`), no máximo 200:
  `{"lancamentos": [...], "maisAlgum": boolean}`. Cada item tem o mesmo formato
  de `GET /lancamentos`. `maisAlgum` é `true` quando havia mais de 200; confirmar
  ou cancelar as primeiras traz o resto na chamada seguinte.
- Nada vence sozinho: a prevista fica `previsto` até o usuário confirmar (com ou
  sem ajuste) ou cancelar. Realizada, cancelada e prevista de hoje em diante não
  entram. Sem sessão, 401.

### Espera de trava

Toda escrita de lançamento, transferência e recorrência (`POST`, `PUT`,
`PATCH`, `DELETE` de `/lancamentos`; `POST` e `DELETE` de `/transferencias`;
`POST`, `PUT`, `PATCH` e `DELETE` de `/recorrencias`; `PATCH` e `DELETE` de
`/contas/{id}`) espera cerca de 2 s por trava de conta (o prazo do `lock_timeout`,
mais o tempo da resposta),
lançamento ou template. Estourar dá 503 `serviço indisponível` sem gravar nada,
o mesmo corpo de banco fora, e o cliente pode tentar de novo. Deadlock (40P01)
responde o mesmo 503: editar recorrência ou apagar conta ao mesmo tempo que se
edita uma ocorrência dela pode cair nele (QA da F13.1). O job pula o
template travado e o pega na rodada seguinte. O job não trava a conta: numa
corrida com arquivar, pode sobrar uma prevista numa conta arquivada. Ela não
entra no saldo, confirmar dá 400 `conta arquivada` e cancelar funciona.

## Saldo projetado (ADR-029)

- `GET /saldo-projetado?dias=90&contaId=` devolve a série diária do saldo por
  `dataCaixa`: `{"inicio", "fim", "contaId", "saldoAtualCentavos",
  "vencidasCentavos", "menorSaldo": {"data", "saldoCentavos"}, "dias":
  [{"data", "entradasCentavos", "saidasCentavos", "saldoCentavos"}]}`. Tudo em
  inteiro de centavos; `contaId` é `null` quando a série soma as contas ativas.
- `inicio` é hoje (`America/Sao_Paulo`), `fim` é hoje + `dias` - 1, e `dias` tem
  um ponto por dia, sem buraco. `dias` é inteiro de 1 a 366 (padrão 90), só
  dígitos, sem sinal nem zero à esquerda. Fora disso, ou repetido, dá 400
  `dias deve ser um inteiro de 1 a 366`. `contaId` malformado ou repetido dá 400
  `contaId deve ser um UUID`.
- `saldoCentavos` do dia `d` é o saldo inicial das contas do filtro mais tudo
  `realizado` ou `previsto` com `dataCaixa` até `d` (receita e perna de entrada
  somam, despesa e perna de saída subtraem). `cancelado` nunca entra. A prevista
  vencida entra pela própria data, então já está no primeiro ponto, e
  `vencidasCentavos` é a soma com sinal delas, nas mesmas contas do filtro: sem
  `contaId`, vencida de conta arquivada não entra. Realizado com data futura
  entra no dia dele.
- `entradasCentavos` e `saidasCentavos` de um dia são só os lançamentos com
  `dataCaixa` naquele dia (o primeiro ponto mostra só o movimento de hoje); saída
  vem positiva. A perna de transferência só conta quando a outra perna está numa
  conta fora do filtro: sem `contaId`, transferência entre duas ativas não
  aparece como entrada nem saída (o saldo já se anula), de ativa para arquivada
  aparece como saída e de arquivada para ativa como entrada; com `contaId`,
  toda perna daquela conta conta.
- `saldoAtualCentavos` é o saldo de `GET /contas` (todo realizado, de qualquer
  data) somado nas contas do filtro. Pode diferir do primeiro ponto: previstas e
  realizado futuro mexem num e não no outro.
- `menorSaldo` é o menor `saldoCentavos` da série e o primeiro dia em que ocorre
  (série toda igual dá hoje).
- Sem `contaId`: todas as contas ativas do usuário, menos as de benefício
  (ADR-034). Com `contaId` de uma conta de benefício, a série é só dela.
  Transferência entre duas
  ativas se anula no saldo; de ativa para arquivada, ou o inverso, só a perna da
  conta ativa conta e o valor sai (ou entra) no total. Com `contaId`: só aquela
  conta, arquivada também, e a transferência conta pelo sinal da perna dela.
- `contaId` de outro usuário ou inexistente dá 404 `conta não encontrada`, mesmo
  corpo. Sem sessão, 401. Banco fora, 503.
- Qualquer valor da resposta fora de ±9007199254740991 centavos (só acontece
  somando várias contas perto do teto) dá 400 `projeção fora do limite`.

## Cartão de crédito (ADR-031)

- `POST /contas` com `tipo` `cartao_credito` exige `cartao`:
  `{"limiteCentavos" (1 a 9007199254740991), "diaFechamento" (1 a 31),
  "diaVencimento" (1 a 31), "contaPagamentoId"}`. Campo fora do esperado, número
  decimal ou campo faltando dá 400. `saldoInicialCentavos` do cartão é a dívida
  que já existia, em geral negativa. Conta e cartão nascem na mesma transação.
- 400 sem gravar nada: `cartao é obrigatório para conta de cartão de crédito`
  (cartão sem o campo), `cartao só vale para conta de cartão de crédito` (outro
  tipo com o campo), `conta de pagamento não encontrada` (inexistente, de outro
  usuário ou id que não é UUID, a mesma mensagem) e `conta de pagamento deve ser
  uma conta corrente ou poupança ativa` (cartão, dinheiro ou arquivada).
- `Conta` (`POST`, `GET /contas`, `GET /contas/{id}`, `PATCH`) ganha `cartao`,
  sempre presente e `null` fora de cartão: `{limiteCentavos, diaFechamento,
  diaVencimento, contaPagamentoId, disponivelCentavos}`. A guarda de tipo do
  cliente precisa aceitar `cartao` anulável e `tipo` `cartao_credito`.
- `disponivelCentavos` é o limite mais o saldo da conta, **limitado ao
  limite**: saldo positivo (fatura paga a mais) não passa dele. Negativo quando
  a dívida passa do limite. O limite não bloqueia lançamento.
- Fechamento, vencimento, limite e conta de pagamento não se editam na F10:
  corrigir é arquivar o cartão e criar outro. Arquivar a conta de pagamento é
  permitido e o cartão continua apontando para ela; pagar a fatura a partir
  dela passa a dar 400 `conta arquivada` até desarquivar ou pagar por outra.
- Em conta de cartão, a `data` de `POST` e `PUT /lancamentos` é a da compra:
  `dataCompetencia` recebe a data e `dataCaixa` recebe o vencimento da fatura
  (regra em `docs/contracts/dados.md`). Vale para despesa e receita (estorno).
  `PUT` que move o lançamento de ou para cartão recalcula a `dataCaixa`.
  `PATCH` realizado com `data` numa prevista de cartão grava a data em
  `dataCompetencia` e recalcula a `dataCaixa`; sem `data`, as duas ficam.
- Recorrência e divisão em conta de cartão: a ocorrência fica na data gerada
  (competência e chave da regra) e a `dataCaixa` é o vencimento. O horizonte, o
  `fim` e o filtro de desativar seguem a data gerada. `proximaOcorrencia` e
  `GET /lancamentos/vencidos` seguem a `dataCaixa`, então numa ocorrência de
  cartão mostram o vencimento.
- Transferência de ou para conta de cartão (pagar a fatura) não segue a regra:
  as duas datas são a data informada. `POST /transferencias` aceita o cartão
  só como destino (pagamento), vindo de qualquer conta do usuário; como origem
  dá 400 `cartão não pode ser origem de transferência`.
- `GET /lancamentos` filtra por `dataCaixa`, então uma compra de cartão aparece
  no mês do vencimento, não no da compra. Não existe filtro por competência
  nessa rota (dívida: parâmetro `por=competencia`, para ver as compras do mês).
- `GET /saldo-projetado` não muda: a saída do cartão aparece no vencimento.
### Faturas (`GET /contas/{id}/faturas`)

- `GET /contas/{id}/faturas?de=AAAA-MM-DD&ate=AAAA-MM-DD` responde 200
  `{"contaId", "faturas": [{"vencimento", "fechamento", "totalCentavos",
  "pagoCentavos", "restanteCentavos", "situacao"}]}`. `de` e `ate` são
  obrigatórios, com as mesmas regras e mensagens de `GET /lancamentos` (ISO
  estrito, ano 1900 a 2199, `de` menor ou igual a `ate`, no máximo 366 dias
  contando as pontas).
- Lista **toda fatura cujo vencimento cai no intervalo**, ordenada por
  vencimento, inclusive as sem compra (total, pago e restante 0), para a tela
  mostrar o ciclo inteiro. `fechamento` é a data em que aquela fatura fecha.
- `totalCentavos`: despesas menos receitas (estorno) da conta com `dataCaixa`
  igual ao vencimento, `previsto` ou `realizado`; `cancelado` fica fora, e as
  pernas de transferência também. Pode ser negativo.
- `pagoCentavos`: transferências **realizadas** com destino no cartão, de
  qualquer conta do usuário (não só a de pagamento), e
  `dataCaixa` depois do fechamento desta fatura e até o fechamento da seguinte,
  inclusive. Assim o pagamento atrasado (depois do vencimento, antes do próximo
  fechamento) continua na fatura que venceu. Pagamento feito antes do
  fechamento conta na fatura **anterior**, a que já fechou; não existe
  "pagamento antecipado" da fatura aberta. O fechamento da seguinte é o do ciclo
  do cartão, mesmo que caia depois de `ate`.
- `restanteCentavos` é `totalCentavos` menos `pagoCentavos`; fica negativo
  quando se pagou a mais.
- `situacao` depende só da data e do restante: `aberta` se hoje
  (`America/Sao_Paulo`) é até o dia do fechamento, inclusive; depois do
  fechamento, `fechada` com restante maior que zero e `paga` com restante menor
  ou igual a zero. Fatura sem compra já fechada é `paga`.
- Todo valor fica em ±9007199254740991; se algum sair disso, 400 `fatura fora do
  limite`.
- 404 com o mesmo corpo `{"erro":"não encontrado"}` para conta de outro
  usuário, inexistente, id que não é UUID canônico e conta do usuário que não é
  cartão. 401 sem sessão. 503 com o banco fora.
- Pagar a fatura continua sendo `POST /transferencias` da conta de pagamento
  para o cartão; o web preenche valor com o restante e data com o vencimento.

## Compra parcelada (ADR-032)

Só em conta de cartão de crédito, só despesa, de 2 a 48 parcelas. Todas as
parcelas nascem `realizado`: o saldo do cartão cai pelo total na hora, e cada
parcela entra na fatura do seu vencimento (`dataCaixa`).

- `POST /compras-parceladas` com `{"titulo", "descricao"?, "valorTotalCentavos",
  "parcelas", "categoriaId", "contaId", "data"}` responde 201 com
  `{id, titulo, descricao, valorTotalCentavos, parcelas, categoriaId, contaId,
  dataCompra, criadaEm, itens: [{lancamentoId, parcelaNum, valorCentavos,
  dataCaixa}]}`. `id` é o da primeira parcela e é o id das rotas da compra.
  Campo fora do esperado dá 400.
- Valor: o total dividido por `parcelas`, truncado em centavos, em todas menos a
  última, que leva o resto (a soma fecha no total). Total que dá zero centavo
  por parcela dá 400 `cada parcela precisa ter ao menos 1 centavo`.
- Datas: todas têm `dataCompetencia` = `data`. A primeira vence na fatura da
  compra; a parcela `k` vence `k - 1` meses depois, no dia de vencimento do
  cartão, cortado no último dia do mês curto.
- 400 sem gravar nada: `parcelas deve ser um inteiro entre 2 e 48`,
  `compra parcelada só em cartão de crédito` (corrente, poupança, dinheiro),
  `conta não encontrada` (inexistente, de outro usuário ou id inválido),
  `conta arquivada`, `categoria não encontrada` (inexistente, de outro usuário,
  de receita), `saldo da conta fora do limite`, `compra fora do limite` (soma
  das parcelas acima de ±9007199254740991 centavos). Mensagens de título, descrição,
  valor e data iguais às de `POST /lancamentos`.
- `GET /compras-parceladas/{id}`: mesma forma do 201. `valorTotalCentavos` é a
  **soma das parcelas que ainda existem**: muda se uma parcela for editada ou
  apagada em `/lancamentos`. `parcelas` é o número que a compra teve ao nascer.
  Título, descrição, categoria e conta vêm da primeira parcela. `itens` traz só
  as parcelas que existem, por `parcelaNum`. Outra parcela, outro usuário,
  inexistente ou id malformado: 404 `{"erro":"não encontrado"}`. Se a soma passar
  de ±9007199254740991 centavos, 400 `{"erro":"compra fora do limite"}` (o mesmo
  vale para as respostas do POST e do PATCH). O `PUT` de uma parcela impede esse
  estado, então só dado antigo chega aqui.
- `PATCH /compras-parceladas/{id}` com `{"titulo"?, "descricao"?,
  "categoriaId"?}`: pelo menos um (senão 400 `informe titulo, descricao ou
  categoriaId`), campo extra dá 400. Aplica em todas as parcelas numa transação.
  `descricao` vazia ou em branco limpa a descrição. Responde 200 com a compra.
  Valor, parcelas, data e conta não se editam: apague e lance de novo.
- `DELETE /compras-parceladas/{id}` apaga todas as parcelas que existem: 204. O
  saldo do cartão volta. 404 como no GET.
- 401 sem sessão, 503 com o banco fora ou trava ocupada por mais de 2 s.

### Uma parcela em `/lancamentos`

- `GET /lancamentos` e `GET /lancamentos/{id}` (e as respostas de POST, PUT e
  PATCH) ganham `compraParceladaId` (id da primeira parcela), `parcelaNum` e
  `parcelaTotal`, `null` nos lançamentos que não são parcela.
- `POST /lancamentos` não aceita esses campos (campo extra dá 400).
- `PUT` numa parcela muda título, descrição, categoria e valor. Trocar a conta
  ou a data (a `data` do corpo é comparada com a `dataCompetencia` gravada, que é
  a data da compra) dá 400 `edite a compra inteira`; `tipo` receita dá 400
  `parcela de compra parcelada só pode ser despesa`. A `dataCaixa` não muda. O
  teto do saldo vale, e o `PUT` que levaria a soma das parcelas da compra para
  fora de ±9007199254740991 centavos dá 400 `compra fora do limite` e nada muda.
- `DELETE` numa parcela que não é a primeira apaga só ela. Na primeira dá 400
  `apague a compra inteira`.
- `PATCH` de status numa parcela dá 400 `só ocorrência prevista pode mudar de
  status` (ela já é realizada).

### Fatura (correção da F10)

Quando o vencimento cortado cai no mesmo dia do fechamento cortado (fechamento
30 e vencimento 31 em fevereiro), o pagamento desse dia conta na própria fatura
e sai da anterior. O contrato de `GET /contas/{id}/faturas` não muda.

## Benefício com restrição (ADR-034)

- `tipo` ganha `beneficio`. Toda `Conta` ganha `categoriasPermitidas`: lista de
  ids de categoria de despesa em ordem estável (por id), **vazia quando a conta
  aceita qualquer categoria** e `null` nos outros tipos. O cliente precisa
  aceitar `tipo` `beneficio` e o campo novo (sempre presente, anulável).
- `POST /contas` com `tipo` `beneficio` aceita `categoriasPermitidas` (opcional,
  até 50 ids, sem repetir). Conta e lista nascem na mesma transação. Recusas,
  todas 400 sem gravar nada: `categoria não encontrada` (id inexistente, de outro
  usuário, de categoria de receita ou malformado, a mesma mensagem),
  `categoriasPermitidas não pode repetir categoria` (a comparação é pelo UUID,
  maiúscula e minúscula do mesmo id repetem), `categoriasPermitidas deve ter no
  máximo 50 itens` e `categoriasPermitidas só vale para conta de benefício`
  (qualquer outro tipo com o campo, até lista vazia).
- `PUT /contas/{id}/categorias-permitidas` com `{"categoriaIds": [...]}` troca a
  lista inteira e responde 200 com a `Conta`. Lista vazia libera tudo. Mesmas
  recusas de lista do `POST` (campo ausente dá 400 `categoriaIds é
  obrigatório`). Conta do usuário que não é benefício dá 400 `só conta de
  benefício tem categorias permitidas`; conta de outro usuário, inexistente ou
  id que não é UUID dá 404 `não encontrado`. Repetível. A conta fica travada até
  o commit, então escrita de lançamento nela espera a troca (até 2 s, depois 503).
- A regra vale só para **despesa**. Receita em conta de benefício aceita
  qualquer categoria. Despesa em conta de benefício com lista não vazia e
  categoria fora dela dá 400 `categoria não permitida nesta conta`, sem gravar,
  em `POST /lancamentos`, `PUT /lancamentos/{id}` (inclusive ao mover a despesa
  para a conta de benefício ou ao virar despesa) e `POST /recorrencias`.
- O `PUT /lancamentos/{id}` regrava a categoria, então editar uma despesa antiga
  cuja categoria saiu da lista também dá 400: o cliente manda a categoria nova
  junto. Mudar a lista não mexe em lançamento já gravado, e `PATCH` de status
  (confirmar, com ajuste de valor ou data) não muda categoria e continua
  valendo.
- O job de recorrência pula o template cuja categoria saiu da lista da conta e
  segue com os outros. O template continua ativo e sem ocorrência nova até o
  usuário trocar a categoria dele ou a lista da conta. Desde a F13.1 o
  usuário troca a categoria pelo `PUT /recorrencias/{id}` (ADR-036).
- Transferência com origem ou destino de benefício dá 400 `conta de benefício
  não participa de transferência`, sem gravar. Conta alheia continua dando
  `conta não encontrada`.
- `GET /saldo-projetado` sem `contaId` não soma contas de benefício (ver Saldo
  projetado). O saldo do Início ("Saldo nas contas") é conta do cliente: soma de
  `saldoCentavos` filtrando `tipo`.

## Cripto manual (ADR-035)

### Conta cripto

- `tipo` ganha `cripto`. `POST /contas` com `tipo` `cripto` aceita
  `saldoInicialCentavos` ausente ou 0; outro valor dá 400 `conta cripto não tem
  saldo em reais`. `cartao` e `categoriasPermitidas` seguem recusados como em
  qualquer tipo que não é o deles. O `saldoCentavos` da conta cripto é sempre 0:
  o que ela guarda está em `GET /posicoes-cripto`.
- Conta cripto não recebe lançamento: `POST` e `PUT /lancamentos` e
  `POST /recorrencias` com ela dão 400 `conta cripto não recebe lançamento`;
  `POST /transferencias` com ela na origem ou no destino dá 400 `conta cripto
  não participa de transferência`; `POST /compras-parceladas` já dá a recusa de
  conta que não é cartão. Conta alheia continua dando `conta não encontrada`
  antes de qualquer uma dessas.

### Ativo (`/ativos-cripto`)

- `POST /ativos-cripto` com `{"rede", "contrato", "simbolo", "nome",
  "decimais"}` responde 201 com o `AtivoCripto`: `{id, rede, contrato,
  simbolo, nome, decimais, confiavel}`. `confiavel` é sempre `true` no ativo
  digitado.
- `rede`: `bitcoin`, `ethereum`, `base`, `arbitrum`, `polygon` ou `solana`.
  `contrato` ausente ou `null` é o ativo nativo da rede; nas redes EVM é `0x`
  mais 40 hexadecimais e é gravado e devolvido em minúsculas; em `solana` é
  base58 de 32 a 44 caracteres; em `bitcoin` precisa ser nulo. Fora disso, 400
  `contrato inválido para a rede`.
- `simbolo` de 1 a 20 caracteres sem as pontas em branco, `nome` opcional até
  100, os dois sem caracteres de controle. `decimais` inteiro de 0 a 18.
- O mesmo `rede` + `contrato` do mesmo usuário dá 409 `ativo já cadastrado`
  (dois nativos da mesma rede também). Outro usuário pode cadastrar o mesmo.
- `GET /ativos-cripto` lista os ativos do usuário por `simbolo` (sem diferenciar
  caixa), depois `rede`.

### Cotação manual (`POST /ativos-cripto/{id}/cotacoes`)

- Corpo `{"preco", "momento"}`. `preco` é **string** decimal maior que zero, com
  até 12 dígitos inteiros e até 8 casas, em reais por unidade (`"350000.5"`).
  Sinal, expoente, zero ou casa a mais dão 400 `preco deve ser um decimal maior
  que zero, em texto, com até 12 dígitos inteiros e 8 casas`; número no JSON dá
  400 `requisição inválida`.
  `momento` é opcional, ISO-8601 com fuso (`2026-10-21T15:00:00-03:00`), ano de
  1900 a 2199; ausente vale o instante da requisição. Fora disso, 400 `momento
  deve ser data e hora ISO-8601 com fuso, ano entre 1900 e 2199`. Fração abaixo
  do microssegundo é descartada. Momento depois do instante da requisição dá
  400 `momento não pode ser no futuro`.
- 201 `{ativoId, momento, preco, fonte}`, `fonte` `manual`, `momento` em UTC.
  Já existe cotação do ativo naquele instante: 409 `já existe cotação nesse
  momento`.
- Ativo de outro usuário, inexistente ou id que não é UUID: 404 `não
  encontrado`.

### Movimento (`/movimentos-cripto`)

- `POST /movimentos-cripto` com `{"contaId", "ativoId", "tipo", "quantidade",
  "data", "custoCentavos"}` responde 201 com o `MovimentoCripto`: `{id,
  contaId, ativoId, tipo, quantidade, data, custoCentavos}`.
- `tipo` `compra` ou `venda`. `quantidade` é **string** decimal maior que zero,
  com até 20 dígitos inteiros e até 18 casas (fora disso, 400 `quantidade deve
  ser um decimal maior que zero, em texto, com até 20 dígitos inteiros e 18
  casas`; número no JSON dá `requisição inválida`); com mais casas que os `decimais`
  do ativo dá 400 `quantidade tem mais casas que o ativo permite`. `data`
  AAAA-MM-DD, ano de 1900 a 2199. `custoCentavos` de 1 a 9007199254740991: o
  que pagou na compra ou recebeu na venda.
- Recusas, todas 400 sem gravar: `conta não encontrada` (inexistente, de outro
  usuário ou id malformado), `conta deve ser cripto` (conta do usuário de outro
  tipo), `conta arquivada`, `ativo não encontrado` (inexistente, de outro
  usuário ou id malformado) e `venda maior que a posição`.
- **A posição nunca fica negativa em data nenhuma.** A soma das compras menos as
  vendas do ativo na conta, dia a dia, tem de ficar maior ou igual a zero do
  primeiro movimento em diante. Uma venda com data anterior a uma compra conta
  como sem a compra. Escrita e remoção travam a conta até o commit.
- `GET /movimentos-cripto?contaId=&ativoId=` lista por `data` decrescente,
  depois o mais novo primeiro. Os dois filtros são opcionais: UUID malformado dá
  400 `contaId deve ser um UUID` ou `ativoId deve ser um UUID`, UUID de outro
  usuário dá lista vazia.
- `DELETE /movimentos-cripto/{id}` responde 204. Apagar uma compra que deixaria
  a posição negativa em alguma data dá 400 `apagar deixaria a posição negativa`.
  Movimento de outro usuário, inexistente ou id que não é UUID: 404 `não
  encontrado`. Apagar em conta arquivada é permitido.

### Posições e patrimônio

- `data` é opcional nas duas rotas, AAAA-MM-DD com ano de 1900 a 2199, padrão
  hoje em `America/Sao_Paulo`. Formato errado dá 400 `data deve ser uma data
  AAAA-MM-DD com ano entre 1900 e 2199`. Movimentos com `data` até ela,
  inclusive. Cotação é a última com `momento` até o fim do dia `data` em
  `America/Sao_Paulo`. Só contas ativas entram.
- `GET /posicoes-cripto?data=` responde `{data, posicoes: [{contaId, ativoId,
  quantidade, custoCentavos, cotacao, valorCentavos}]}`, ordenada pelo nome da
  conta e pelo símbolo do ativo. Posição com quantidade zero não aparece.
  `custoCentavos` é compras menos vendas e pode ser negativo (vendeu com
  lucro). `cotacao` é `{preco, momento}` ou `null`; sem cotação,
  `valorCentavos` é `null`.
- `valorCentavos` é quantidade vezes preço, arredondado uma vez para centavos
  com `HALF_EVEN`.
- `GET /patrimonio?data=` responde `{data, contasCentavos, criptoCentavos,
  totalCentavos, semCotacao: [{contaId, ativoId, quantidade}]}`.
  `contasCentavos` é a soma do saldo até a data de toda conta ativa que não é
  cripto (`saldo_inicial` mais os realizados com `dataCaixa` até a data,
  inclusive cartão e benefício). `criptoCentavos` soma o valor das posições com
  cotação; as sem cotação ficam em `semCotacao` e fora do total. Nunca falha por
  falta de cotação.
- Qualquer valor fora de ±9007199254740991 dá 400 `patrimônio fora do limite`.

## Editar e apagar contas e recorrências (ADR-036)

### Editar conta (`PATCH /contas/{id}`)

- Todo campo é opcional e campo ausente (ou `null`) não muda: `arquivada`,
  `nome`, `saldoInicialCentavos` e, só em cartão, `cartao: {limiteCentavos,
  contaPagamentoId}`. Corpo sem nenhum deles dá 400 `informe ao menos um campo
  para alterar`. Campo fora da lista (inclusive `tipo`, `cartao.diaFechamento`
  e `cartao.diaVencimento`) dá 400.
- `nome` e `saldoInicialCentavos` têm as regras e mensagens do `POST`. Nome de
  outra conta do usuário dá 409. Conta cripto com saldo inicial diferente de 0
  dá 400 `conta cripto não tem saldo em reais`. Saldo inicial que tiraria o
  saldo da faixa dá 400 `saldo da conta fora do limite`.
- `cartao` em conta que não é cartão dá 400 `cartao só vale para conta de
  cartão de crédito`. A conta de pagamento nova segue as regras do `POST`
  (`conta de pagamento não encontrada`, `conta de pagamento deve ser uma conta
  corrente ou poupança ativa`).
- Responde 200 com a `Conta`. A conta fica travada até o commit, como nas
  outras escritas dela.

### Apagar conta (`DELETE /contas/{id}`)

- Responde 204 e apaga a conta com seus lançamentos (parcelas e ocorrências
  inclusive), recorrências, cartão, categorias permitidas e movimentos de
  cripto. Ativos e cotações de cripto ficam.
- Transferência com outra conta: a perna da outra conta vira lançamento comum
  com o mesmo título, descrição, valor, datas e status. A de saída vira
  `despesa` em "Outros", a de entrada vira `receita` em "Outras receitas". O
  saldo da outra conta não muda.
- Conta que é conta de pagamento de um cartão dá 400 `esta conta paga um
  cartão; troque a conta de pagamento antes` e nada é apagado. Conta que já
  pagou fatura de cartão por transferência dá 400 `esta conta pagou fatura de
  cartão; apagar desmontaria a fatura. Arquive a conta`.
- Conta de outro usuário, inexistente ou id que não é UUID: 404 `não
  encontrado`. Funciona com a conta arquivada.
- `GET /contas/{id}/resumo-exclusao` responde `{lancamentos, transferencias,
  recorrencias, movimentosCripto}` com as contagens que o `DELETE` levaria, para
  o web pedir a confirmação. Mesmo 404.

### Editar recorrência (`PUT /recorrencias/{id}`)

- Corpo do `POST /recorrencias`, com as mesmas validações e mensagens. O `tipo`
  tem de ser o atual; outro dá 400 `o tipo da recorrência não muda`.
- As ocorrências previstas com `ocorrencia_data` de hoje (`America/Sao_Paulo`)
  em diante e não editadas à mão são apagadas e geradas de novo com os dados
  novos, se a recorrência estiver ativa. Realizadas, canceladas, editadas à mão e
  previstas de antes de hoje ficam.
- Responde 200 com `{recorrencia, ocorrenciasMaterializadas}`, como o `POST`. 404 igual
  ao `GET`.

### Reativar e apagar recorrência

- `PATCH /recorrencias/{id}` com `{"ativo": true}` reativa e gera de hoje até o
  horizonte; repetível. Conta arquivada dá 400 `conta arquivada`; categoria que
  saiu da lista do benefício dá 400 `categoria não permitida nesta conta`.
- Deadlock entre escritas (SQLState 40P01) responde 503 `serviço indisponível`,
  como a espera de trava: tentar de novo resolve.
- `DELETE /recorrencias/{id}` responde 204: apaga a recorrência, a regra, a
  divisão e todas as previstas dela, inclusive as vencidas. Realizadas e
  canceladas ficam como lançamentos avulsos, sem `templateId`. 404 igual ao
  `GET`.
