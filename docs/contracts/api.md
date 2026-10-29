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
- Em qualquer rota, passar do limite por IP responde 429
  `{"erro":"muitas requisições, tente mais tarde"}` com `Retry-After` em
  segundos (ADR-049). A spec só lista o 429 em login e cadastro. Os limites
  estão em Limites, no fim deste arquivo.
- Em qualquer rota, parâmetro de query que a rota não declara responde 400
  `{"erro":"requisição inválida"}` (ADR-051). O nome é exato: `?contaid=` não é
  `?contaId=`. Rota sem parâmetro declarado recusa qualquer query.
- Em qualquer rota, caminho que o servidor recusa antes do Spring (`%2F`, `{`,
  `|`) responde 400 com o mesmo JSON, e toda resposta leva
  `X-Content-Type-Options: nosniff` (ADR-051).
- E-mail, nome de usuário, nome de conta e demais textos livres recusam
  caracteres Cf (U+200B, U+202E, U+FEFF e outros) com 400. Só o e-mail recusa
  também ZWJ e ZWNJ. O e-mail passa por NFKC e minúsculas antes de valer, e o
  "İ" é recusado (ADR-051).
- Em qualquer rota que muda estado (POST, PUT, PATCH, DELETE), `Origin` de
  outra origem, `null` ou malformado responde 403
  `{"erro":"origem não permitida"}`. Sem `Origin`, passa, salvo se
  `Sec-Fetch-Site` for `cross-site`. São aceitos o próprio Host e as origens de
  `ORIGENS_PERMITIDAS` (ADR-051).
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
  Não existe `PUT`: corrigir é apagar e criar de novo. Transferência ligada a um
  aporte de meta (`transferenciaId` do aporte) não se apaga: `DELETE` dá 409
  `transferência vinculada a aporte` e nada muda; apague o aporte antes (ADR-057).
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
  apagada (204). Apagar `cancelado` some de vez: a data nunca volta como
  `previsto` (ADR-054). Apagar `realizado` libera a data para a regra: se é de
  hoje em diante e a recorrência segue ativa, o job a gera de novo como `previsto`
  na próxima rodada; data de antes de hoje não volta.
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
  `descricao` ausente mantém; nula, vazia ou em branco limpa a descrição.
  Responde 200 com a compra.
  Valor, parcelas, data e conta não se editam: apague e lance de novo.
- `DELETE /compras-parceladas/{id}` apaga todas as parcelas que existem: 204. O
  saldo do cartão volta. 404 como no GET.
- 401 sem sessão, 503 com o banco fora ou trava ocupada por mais de 2 s.

### Uma parcela em `/lancamentos`

- `GET /lancamentos` e `GET /lancamentos/{id}` (e as respostas de POST, PUT e
  PATCH) ganham `compraParceladaId` (id da primeira parcela), `parcelaNum` e
  `parcelaTotal`, `null` nos lançamentos que não são parcela.
- `POST /lancamentos` não aceita esses campos (campo extra dá 400).
- `PUT` numa parcela muda descrição e valor. Trocar título, categoria, conta ou
  data (a `data` do corpo é comparada com a `dataCompetencia` gravada, que é a
  data da compra) dá 400 `edite a compra inteira`: título e categoria mudam pelo
  `PATCH /compras-parceladas/{id}` (ADR-054); `tipo` receita dá 400
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
  benefício tem categorias permitidas`; conta arquivada dá 400 `conta
  arquivada`, como as outras escritas em conta; conta de outro usuário, inexistente ou
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
- Valor de posição fora de ±9007199254740991 não dá mais 400: a posição vem sem
  valor (ADR-058, abaixo). Só a soma do patrimônio fora da faixa dá 400
  `patrimônio fora do limite`.

#### Custo médio e valorização (ADR-042)

- Cada item de `GET /posicoes-cripto` traz `custoMedioUsd` (texto, dólares por
  unidade, até 8 casas), `custoDaPosicaoUsdCentavos` e `custoDaPosicaoCentavos`
  (custo médio do que resta), `ganhoUsdCentavos` e `ganhoCentavos` (valor menos
  custo da posição, nulos sem cotação) e `realizadoUsdCentavos` e
  `realizadoCentavos` (recebido nas vendas menos o custo médio do vendido).
- No mesmo dia a compra conta antes da venda.
- `GET /patrimonio` traz `criptoAportadoCentavos` (compras menos vendas, em
  reais, das posições com cotação) e `criptoValorizacaoCentavos`
  (`criptoCentavos` menos o aportado). Nulos sem a PTAX de algum movimento.

## Carteira observada (ADR-071, F24)

O app só observa um endereço público. Nenhuma rota pede seed, chave privada ou
assinatura. Tudo escopado pelo dono: carteira ou movimento de outro usuário dá
404 `não encontrado` (também id inexistente ou que não é UUID). O endereço
aparece no corpo das respostas e em nenhum log, URL ou mensagem de erro.

- `POST /carteiras` com `{contaId, rede, endereco, rotulo?}` responde 201 com a
  `Carteira`. `rede`: `ethereum`, `base` ou `solana`. Endereço EVM: `0x` mais 40
  hexadecimais, caixa mista exige checksum EIP-55, gravado em minúsculas.
  Solana: base58 que decodifica em 32 bytes. `rotulo` opcional até 60
  caracteres. Não lê a rede.
- 400, sem repetir o valor enviado: corpo inválido ou com campo a mais,
  `endereço inválido para a rede`, `conta não encontrada` (inexistente, de
  outro usuário ou id malformado), `conta deve ser cripto`, `conta arquivada`.
  409 `carteira já cadastrada` (mesmo usuário, rede e endereço, em qualquer
  caixa) e 409 de teto (`LIMITE_CARTEIRAS`, padrão 20).
- `GET /carteiras`, `GET /carteiras/{id}` e `DELETE /carteiras/{id}` (204; leva
  os movimentos sincronizados dela, os digitados ficam).
- `Carteira`: `{id, contaId, rede, endereco, rotulo, verificadaEm, sincronizadaEm,
  leituraFalhou, saldos: [{ativoId, simbolo, contrato, quantidade, confiavel,
  custoPendente}]}`. `quantidade` é string decimal sem zero à direita.
  `verificadaEm` é o instante da prova de posse (F25, abaixo), nulo se nunca
  provou; só informa, não condiciona nada. `sincronizadaEm` é a última leitura
  que deu certo, nulo se nunca leu.
  `leituraFalhou` é `true` quando a última tentativa falhou: os saldos são os
  da `sincronizadaEm`, nunca zero.
- `POST /carteiras/{id}/sincronizar` lê a rede e responde 200 com a `Carteira`.
  Tudo ou nada. No máximo uma leitura por carteira a cada 60 s (a tentativa
  conta, mesmo falha): dentro do prazo devolve o estado atual sem chamar o
  indexador. Indexador fora do ar, sem chave ou com resposta fora do formato é
  200 com `leituraFalhou: true`, não erro. 409 com conta arquivada ou teto de
  movimentos de cripto.
- A leitura grava a diferença contra os movimentos sincronizados da carteira e do
  ativo como `entrada` (saldo subiu) ou `saida` (caiu), `origem` `sincronizada`,
  data de hoje em `America/Sao_Paulo`, sem custo. Rodar de novo sem mudança na
  rede não grava nada. Ativo novo nasce `confiavel: false`, menos o nativo da
  rede. O símbolo vem do indexador, limpo e cortado em 20; no Solana são os 8
  primeiros caracteres do mint. Passou o teto de ativos: os novos ficam de
  fora e a leitura segue.
- `PUT /movimentos-cripto/{id}/custo` com `{custoUsdCentavos}` (1 a
  180143985094819) grava o custo em dólar de uma entrada ou saída sincronizada,
  uma vez, e responde 200 com o `MovimentoCripto`. 409 `o custo já foi
  informado`; 400 em movimento digitado.

### Prova de posse (ADR-072, F25)

Opcional, sobre carteira já cadastrada. Não assina transação e não custa nada.
Escopo e 404 como as demais rotas de carteira.

- `POST /carteiras/{id}/posse/desafio`, sem corpo, responde 200 com
  `{nonce, mensagem, expiraEm}`. `nonce`: 32 hexadecimais. `mensagem`: o texto
  exato a assinar, montado no servidor (EVM em formato SIWE com `Chain ID` 1
  ou 8453; Solana em texto equivalente com `Rede`), com o domínio da config
  (`BALLAST_POSSE_DOMAIN`), nunca o do cabeçalho `Host`. Vale 5 minutos e
  substitui o desafio anterior da carteira. Um desafio novo a cada 5 s por
  carteira: antes disso 429 `muitas tentativas, tente mais tarde` (com
  `Retry-After: 5`), e o desafio anterior segue valendo.
- `POST /carteiras/{id}/posse/verificar` com `{nonce, assinatura}` (campos
  obrigatórios, nenhum a mais, `nonce` até 64 e `assinatura` até 200
  caracteres) responde 200 com a `Carteira` e `verificadaEm` preenchido.
  `assinatura`: EVM é `0x` mais 130 hexadecimais (`r`, `s`, `v` do
  `personal_sign`, `v` 0, 1, 27 ou 28, `s` alto recusado); Solana são os 64 bytes
  do `signMessage` em base64.
- 400 `assinatura inválida` (nonce, assinatura ou endereço não conferem) ou 400
  `desafio expirado` (sem desafio, vencido ou já usado). O desafio é consumido
  antes de conferir a assinatura: qualquer 400 aqui exige pedir outro. Corpo
  inválido ou com campo a mais é 400 com texto fixo (`nonce e assinatura são
  obrigatórios e têm tamanho limitado` para campo ausente ou grande). Endereço, assinatura, mensagem e nonce não vão para log.

Mudanças em rotas que já existiam (campos só acrescentados):

- `MovimentoCripto` ganha `origem` (`manual` | `sincronizada`), `tipo` passa a
  poder ser `entrada` ou `saida`, e `custoUsdCentavos` e `custoCentavos` podem
  ser `null` (entrada ou saída sem custo informado).
- `POST /movimentos-cripto` continua só com `compra` e `venda` (400 para
  `entrada` e `saida`). `DELETE /movimentos-cripto/{id}` de movimento
  sincronizado dá 409 `movimento sincronizado não se apaga nem se edita`.
- `PosicaoCripto` ganha `confiavel` e `custoPendente`. Com `custoPendente:
  true`, `custoMedioUsd`, `custoDaPosicao*` e `ganho*` vêm `null`; o valor de
  mercado continua. Posição de ativo não confiável aparece com `confiavel:
  false` e não entra em `criptoCentavos`, no patrimônio, em `semCotacao` nem na
  cotação do job.
- `GET /patrimonio`: `criptoAportadoCentavos` e `criptoValorizacaoCentavos`
  vêm `null` quando uma posição somada tem custo pendente.
- `PUT /ativos-cripto/{id}` aceita `confiavel` opcional (ausente mantém):
  `true` promove o ativo de uma leitura e ele passa a valer no patrimônio.

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
  cartão; apagar desmontaria a fatura. Arquive a conta`. Conta com transferência
  ligada a um aporte de meta dá 409 `transferência vinculada a aporte` e nada é
  apagado (ADR-057).
- Conta de outro usuário, inexistente ou id que não é UUID: 404 `não
  encontrado`. Funciona com a conta arquivada.
- Com o MFA ligado (ADR-053), o `DELETE` pede o código num corpo JSON
  `{"codigo"}`, do app ou de recuperação (1 a 32 caracteres). O corpo é opcional
  no contrato: sem MFA ele é ignorado e o `DELETE` sem corpo segue valendo. Com
  MFA, corpo ausente ou código errado dá 400 `código inválido` e nada é
  apagado; no atraso por usuário do MFA dá 429 com `Retry-After`. O código é
  conferido antes de olhar a conta, e gasta mesmo quando a resposta depois é
  404 ou 400 de regra. A conferência de `Origin` vale como em todo método que
  muda estado.
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
- Recorrência ativa com conta arquivada dá 400 `conta arquivada`. A inativa
  passa: editar o template não gera nada, e ativar continua recusando.
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

## Orçamento (ADR-043)

- `GET /orcamentos?mes=AAAA-MM`: sem `mes`, o mês corrente em
  `America/Sao_Paulo`. Responde `{mes, orcamentos: [...]}` com as categorias
  que têm teto no mês, pelo nome. Cada item: `categoriaId`, `categoriaNome`,
  `tetoCentavos`, `desde` (AAAA-MM em que esse teto começou), `gastoCentavos`
  (realizado com competência no mês até hoje), `previstoCentavos` (previsto e
  realizado com data depois de hoje) e `restanteCentavos` (teto menos gasto,
  negativo quando passou). Soma a categoria e as filhas. Parcela de compra
  parcelada conta no mês dela e pelo valor dela: é gasto se o mês dela já
  começou e previsto se é de mês futuro (ADR-075). 400 para `mes` fora do
  formato ou "orçamento fora do limite".
- `PUT /orcamentos/{categoriaId}` com `{tetoCentavos, aPartirDe}`: o teto vale
  de `aPartirDe` até a próxima mudança. No mesmo mês, troca. 204.
- `DELETE /orcamentos/{categoriaId}?aPartirDe=AAAA-MM`: encerra a partir do
  mês; os anteriores ficam. 204.
- Categoria de outro usuário, inexistente ou id que não é UUID: 404
  `categoria não encontrada`. Categoria de receita: 400 `orçamento só vale para
  categoria de despesa`.

## Metas (ADR-045)

- `GET /metas`, `POST /metas`, `PUT /metas/{id}`, `DELETE /metas/{id}`. Corpo:
  `{titulo, valorAlvoCentavos, dataAlvo?, aporteMensalCentavos?}`. No PUT,
  ausente ou nulo deixa sem valor.
- Cada meta traz, além do corpo:
  - `acumuladoCentavos` e `faltaCentavos`;
  - `atingida` e `prazoVencido`;
  - `necessarioPorMesCentavos`: até a data alvo, contando o mês atual,
    arredondado para cima;
  - `chegadaPeloPlano`: AAAA-MM pelo aporte mensal;
  - `ritmoMensalCentavos`: média dos três meses fechados;
  - `chegadaPeloRitmo`: AAAA-MM nesse ritmo.
- `GET /metas/{id}/aportes`: do mais novo ao mais antigo, cada item
  `{id, valorCentavos, data, transferenciaId}` (`transferenciaId` nulo sem vínculo).
- `POST /metas/{id}/aportes` com `{valorCentavos, data, transferenciaId?}`.
  Positivo guarda, negativo retira, zero dá 400. Responde 201 com a meta
  atualizada. Data no futuro e retirada maior que o acumulado dão 400.
  `transferenciaId` opcional liga o aporte à transferência que moveu o dinheiro
  (ADR-057): de outro usuário, inexistente ou que não é UUID dá 400
  `transferenciaId não encontrada`; transferência que já liga outro aporte dá
  409 `transferência já vinculada a outro aporte`. O vínculo só informa, o
  acumulado continua sendo a soma dos aportes.
- `DELETE /metas/{id}/aportes/{aporteId}`: 204. Responde 400 quando o
  acumulado ficaria negativo.
- Meta ou aporte de outro usuário, inexistente ou id que não é UUID: 404
  `meta não encontrada`.

## Posso gastar (ADR-048)

- `GET /posso-gastar` responde:
  - `hoje` e `ate`: a véspera do próximo salário, ou o fim do mês sem salário;
  - `proximoSalario`: `{data, valorCentavos}` ou nulo;
  - `livreCentavos`: o menor saldo projetado das contas que não são de
    benefício até `ate`, negativo quando falta;
  - `menorSaldoEm`;
  - `dias`, contando hoje e `ate`;
  - `porDiaCentavos`: livre dividido por dias, para baixo, nulo se não sobra;
  - `vencidasCentavos`: soma com sinal das previstas vencidas, já dentro do
    livre;
  - `beneficios`: `{contaId, nome, livreCentavos}` por conta de benefício
    ativa.
- O próximo salário é a primeira receita depois de hoje na categoria Salário,
  ou numa filha dela, numa conta que não é de benefício.
- 400 `projeção fora do limite`.

## Rendimento diário do cofrinho (ADR-074)

- O job diário (03:30 em São Paulo) lança o rendimento estimado de cada cofrinho
  com percentual como receita `realizado` na categoria Rendimento, título
  `Rendimento do cofrinho (estimado)`. Aparece no extrato e nos saldos como
  qualquer lançamento; o usuário edita ou apaga e o dia não volta.
- `POST /contas/{id}/cofrinho/rendimento`, corpo vazio, roda a conta para hoje.
  200 com `{situacao, valor}`:
  - `situacao`: `lancado`, `ja_lancado`, `sem_cdi`, `sem_rendimento` (saldo
    zero ou negativo, valor abaixo de um centavo, sem categoria Rendimento ou
    hoje sem dia útil) ou `sem_percentual`;
  - `valor`: inteiro de centavos em `lancado`, nulo nas outras.
- 404 `não encontrado` para conta de outro usuário, inexistente, que não é
  cofrinho, arquivada ou id que não é UUID. 401 sem sessão. 503 se a conta
  estiver em uso por outra requisição.

## Relatórios (ADR-046)

- `GET /relatorios?mes=AAAA-MM`: sem `mes`, o mês corrente em
  `America/Sao_Paulo`. Conta só o realizado com competência até hoje. Parcela de
  compra parcelada conta no mês dela (mês da compra mais `parcelaNum - 1`) e só
  pelo valor dela, inclusive em mês depois de hoje (ADR-075).
  Previsto, cancelado, data depois de hoje e transferência ficam fora.
- Responde:
  - `mes`;
  - `totalDespesasCentavos`;
  - `despesasPorCategoria`: `{categoriaId, categoriaNome, gastoCentavos}` da
    maior para a menor, e pelo nome no empate. A filha soma na categoria de
    cima;
  - `receitasEDespesas`: `{mes, receitaCentavos, despesaCentavos}` dos seis
    meses que terminam em `mes`, do mais antigo para o mais novo, com zero no
    mês sem lançamento.
- 400 para `mes` fora do formato ou `relatório fora do limite`.

## Patrimônio no tempo (ADR-047)

- `GET /patrimonio/mensal?mes=AAAA-MM`: sem `mes`, o mês corrente em
  `America/Sao_Paulo`. Responde `{mes, meses: [...]}` com até doze meses
  terminando em `mes`, do mais antigo para o mais novo. Mês depois do
  corrente fica de fora.
- Cada ponto é o `/patrimonio` de `data`: o último dia do mês, ou hoje no mês
  corrente. Traz `mes`, `data`, `contasCentavos`, `criptoCentavos`,
  `rendaFixaCentavos`, `totalCentavos` e `posicoesSemCotacao` (fora do total).
- 400 para `mes` fora do formato ou `patrimônio fora do limite`.

## Renda fixa (ADR-037)

### Conta de investimento

- `tipo` ganha `investimento`, com as regras da conta cripto: `saldoInicialCentavos`
  só 0 (outro valor dá 400 `conta de investimento não tem saldo em reais`, no
  `POST` e no `PATCH`), não recebe lançamento nem recorrência (400 `conta de
  investimento não recebe lançamento`) e não entra em transferência (400 `conta
  de investimento não participa de transferência`).
- `DELETE /contas/{id}` leva os títulos e as marcações da conta, e o resumo de
  exclusão ganha `titulosRendaFixa`.

### Título (`/titulos-renda-fixa`)

- `POST` com `{contaId, tipo, nome, valorAplicadoCentavos, dataAplicacao,
  vencimento, indexador, taxa}` responde 201 com o `TituloRendaFixa`; `PUT
  /{id}` com o mesmo corpo responde 200; `DELETE /{id}` responde 204.
- `tipo`: `lci`, `lca` ou `cdb`. `indexador`: `prefixado`, `cdi` ou `ipca`.
  `taxa` é **string** decimal maior que zero e até 1000, com até 4 casas:
  % ao ano no prefixado, % do CDI no `cdi`, % ao ano acima do IPCA no `ipca`.
  `nome` de 1 a 100. `vencimento` opcional.
- Recusas, todas 400: `dataAplicacao não pode ser no futuro`, `vencimento deve
  ser depois da aplicação`, `conta não encontrada` (inexistente, alheia ou id
  malformado), `conta deve ser de investimento`, `conta arquivada` e, no `PUT`,
  `dataAplicacao não pode ser depois do resgate` (resgate no dia da aplicação
  vale). Título alheio, inexistente ou id
  que não é UUID: 404 `não encontrado`.
- `GET ?data=` (padrão hoje em `America/Sao_Paulo`) responde `{data, indices:
  {cdiAte, ipcaAte}, titulos}`, só dos títulos de contas ativas, por data de
  aplicação; data futura vale o valor de hoje, sem projeção. Cada título traz `valorBrutoCentavos`, `iofCentavos`,
  `irCentavos`, `valorLiquidoCentavos` e `rendimentoCentavos` na data: nulos
  antes da aplicação, zero depois do resgate (com o rendimento do resgate). IOF
  e IR são estimativa e só existem no CDB. `resgate` e `ultimaMarcacao` são
  `{data, valorCentavos}` ou `null`.
- O valor parte da última marcação até a data (ou da aplicação) e aplica o
  fator do indexador; os dias úteis são os dias com CDI publicado, e os dias de
  semana depois do último CDI guardado usam o último CDI. Arredondado uma vez
  para centavos com `HALF_EVEN`. Fora de ±9007199254740991 o título vem sem
  valor (ADR-058, abaixo).
- Cadastrar ou editar com aplicação antes do primeiro índice guardado pede os
  índices que faltam ao Banco Central em segundo plano e responde na hora
  (ADR-058); com o Banco Central fora, o título é gravado do mesmo jeito.

### Marcação e resgate

- `POST /titulos-renda-fixa/{id}/marcacoes` com `{data, valorCentavos}` grava o
  valor que o banco mostrou e responde 201 sem corpo. Outra no mesmo dia troca o
  valor. Data antes da aplicação ou no futuro: 400 `data deve ficar entre a
  aplicação e hoje`; no dia do resgate ou depois: 400 `título já resgatado
  nessa data`.
- `POST /titulos-renda-fixa/{id}/resgate` com `{data, valorCentavos}` responde
  200 com o título; de novo, troca data e valor. Mesma faixa de data.

### Índices e patrimônio

- `GET /indices` responde `{cdiAte, ipcaAte}`, a última data guardada de cada
  série (`null` se o Banco Central nunca respondeu).
- `GET /patrimonio` ganha `rendaFixaCentavos`, a soma do líquido estimado dos
  títulos na data, e o `totalCentavos` passa a incluí-la.

## Cripto em dólar (ADR-038)

Emenda a seção "Cripto manual": cotação e custo passam a ser em dólar, e o valor
em reais vem da PTAX do Banco Central.

- `POST /ativos-cripto/{id}/cotacoes` recebe `precoUsd` no lugar de `preco`
  (mesmas regras de formato; a mensagem de erro cita `precoUsd`) e responde
  `{ativoId, momento, precoUsd, fonte}`.
- `POST /movimentos-cripto` recebe `custoUsdCentavos` no lugar de
  `custoCentavos` (de 1 a 180143985094819: vezes a PTAX máxima aceita, R$ 50,
  ainda cabe no inteiro seguro do JavaScript). O `MovimentoCripto` traz
  `custoUsdCentavos` e `custoCentavos`, este em reais pela PTAX da data do
  movimento. Movimento gravado na F13 é em reais: `custoCentavos` é o que foi
  digitado e `custoUsdCentavos` sai pela PTAX. Sem PTAX até a data, o campo
  convertido é `null`.
- `GET /posicoes-cripto` responde `{data, dolar, posicoes}`. `dolar` é `{valor,
  data}`, a última PTAX até a data (`valor` em texto, reais por dólar), ou
  `null`. Cada posição traz `quantidade` (tokens), `custoUsdCentavos`,
  `custoCentavos`, `cotacao: {precoUsd, momento}`, `valorUsdCentavos` e
  `valorCentavos`. O valor em reais é o valor em dólar vezes a PTAX da data;
  cada moeda é arredondada uma vez, a partir do valor cheio, com `HALF_EVEN`.
- Sem PTAX até a data, a moeda que precisaria de conversão fica `null`: com a
  cotação em dólar, `valorCentavos` é `null` e a posição entra em `semCotacao`
  do patrimônio, fora do total; com uma cotação antiga em reais (F13), o valor
  em reais vale e entra no total, e quem fica `null` é `valorUsdCentavos` (e
  `cotacao.precoUsd`). O patrimônio nunca falha por falta de dólar.
- `cotacao.precoUsd` vindo de cotação em reais sai com no máximo 8 casas
  (`HALF_EVEN`), o mesmo formato que a rota de cotação aceita.
- A PTAX (série 1 do SGS, desde 01/07/1994, entre R$ 0,50 e R$ 50,00 também por
  CHECK no banco) é baixada junto com CDI e IPCA, ao subir, às 8h e ao gravar um
  movimento com data anterior à primeira PTAX guardada; nesse caso a resposta do
  `POST` já vem com o custo em reais.

## Cotação automática (ADR-040)

Emenda "Cripto em dólar".

- `GET /posicoes-cripto` traz `cotacao: {precoUsd, momento, fonte}`. `fonte` é
  `manual` (digitada pela rota de cotação) ou `coingecko` (gravada pelo job).
  A cotação da posição é a última até a data, de qualquer fonte.
- Nenhuma rota chama a CoinGecko. Com ela fora, as posições e o patrimônio
  seguem com a última cotação guardada, e `momento` mostra de quando ela é.
- `POST /ativos-cripto/{id}/cotacoes` continua gravando só `manual`.
- Ativo novo ganha, numa das rodadas seguintes do job, a cotação diária do
  último ano antes da primeira cotação dele (ADR-041). A posição e o
  patrimônio de uma data passada passam a ter preço sem mudar o formato.

## Paginação e idempotência (ADR-056)

### Paginação por cursor

Vale em `GET /lancamentos` e `GET /movimentos-cripto`. As outras listagens ficam
sem paginar: contas, recorrências, títulos e ativos têm teto por usuário (ADR-055).

- Parâmetros opcionais `limite` e `cursor`. `limite` é inteiro de 1 a 1000, padrão
  1000. Fora disso, ou com texto, dá 400 `limite deve ser um inteiro de 1 a 1000`.
- O corpo continua sendo o array de sempre. Quando há mais itens, a resposta traz
  o cabeçalho `X-Next-Cursor` com o cursor da próxima página; na última página o
  cabeçalho não vem.
- Para a próxima página, repita a mesma chamada, com os mesmos filtros, e
  acrescente `cursor=<valor do cabeçalho>`. Cursor inválido ou adulterado dá 400
  `cursor inválido`.
- O cursor é opaco: não interprete nem monte. Vale só como ponto de partida e a
  consulta continua escopada pelo usuário da sessão.
- Ordem estável: `dataCaixa`, `criadoEm`, `id` decrescentes em `/lancamentos`; `data`,
  `criadoEm`, `id` decrescentes em `/movimentos-cripto`. Sem `limite` e `cursor`,
  a resposta é a de antes, até 1000 itens.

### Idempotency-Key

Cabeçalho opcional em `POST /contas`, `/lancamentos`, `/transferencias`,
`/compras-parceladas`, `/recorrencias`, `/metas`, `/metas/{id}/aportes`,
`/movimentos-cripto`, `/titulos-renda-fixa`,
`/titulos-renda-fixa/{id}/marcacoes` e `/resgate`, e em
`/importacoes/{id}/confirmacao`. Nas outras rotas é ignorado.

- Formato: 1 a 80 caracteres entre letras, dígitos e `. _ : -` (um UUID serve).
  Fora do formato, ou repetido no mesmo pedido, dá 400
  `Idempotency-Key inválida`.
- Mesma chave, mesmo usuário, mesma rota e mesmo corpo (bytes iguais): devolve o
  status e o corpo da resposta original, com `Idempotent-Replayed: true`, sem
  gravar de novo. Só resposta 2xx fica guardada; um erro libera a chave para o
  cliente corrigir e tentar de novo.
- Mesma chave com outra rota ou outro corpo: 422
  `Idempotency-Key já usada com outra requisição`.
- Mesma chave ainda em andamento: a segunda requisição espera até 10 s a
  primeira terminar e devolve a resposta dela. Passado o prazo, 409
  `requisição com esta Idempotency-Key ainda em andamento` com `Retry-After: 1`.
- A chave vale por 24 h e é do usuário: a mesma chave de outro usuário é outra.
- O cliente deve gerar uma chave por intenção do usuário (um clique em salvar) e
  reusá-la só no retry daquela mesma requisição.

## Importação de extrato OFX (ADR-008, ADR-069)

Duas etapas: enviar o arquivo devolve uma proposta por linha, e a confirmação
cria os lançamentos. Nada entra no extrato antes dela. Toda rota exige sessão e
filtra por dono: importação, linha, conta, lançamento ou categoria de outro
usuário responde 404, com o mesmo corpo do inexistente.

- `POST /importacoes?conta_id=<uuid>` com o arquivo cru no corpo
  (`Content-Type: application/octet-stream`; outro tipo dá 415). Sem multipart,
  então nome de arquivo nunca chega ao servidor. Teto de 1 MiB, contado na
  leitura (413 `corpo grande demais`); com o envio e a prévia de CSV (abaixo), são
  as únicas rotas com teto acima de 4 KiB.
  OFX 1.x (SGML) e 2.x (XML), em UTF-8 ou Windows-1252. 201 com o detalhe
  abaixo. Erros: 400 `conta_id` ausente ou que não é UUID, `conta arquivada`,
  `conta cripto ou de investimento não recebe lançamento`; 404 `conta não
  encontrada`; 409 `{"erro":"arquivo já importado","importacaoId":"..."}` para
  o mesmo arquivo (descartar a pendente libera) ou `limite de importações
  pendentes atingido` (20); 422 para arquivo vazio, que não é OFX, com
  `DOCTYPE` ou `ENTITY`, sem transação ou com mais de 2000.
- `GET /importacoes`: até 100, da mais nova para a mais antiga, sem as linhas.
  Cada item: `id`, `contaId`, `status` (`pendente` ou `confirmada`),
  `totalLinhas`, `totalInvalidas`, `totalImportar`, `totalConciliar`,
  `totalIgnorar` (pela ação vigente de cada linha), `criadoEm`, `confirmadaEm`.
- `GET /importacoes/{id}`: os mesmos campos mais `linhas`, em ordem do arquivo.
- `DELETE /importacoes/{id}`: descarta a pendente (204). Confirmada: 409
  `importação já confirmada`.
- `PUT /importacoes/{id}/linhas/{linhaId}` com `{acao, lancamentoId?,
  categoriaId?, titulo?}`. `acao` é `importar`, `ignorar` ou `conciliar`.
  `conciliar` exige `lancamentoId` (realizado, da mesma conta, sem vínculo de
  importação); `categoriaId` (do mesmo tipo da linha) e `titulo` (1 a 100) só
  valem com `importar`. Campo que não pertence à ação dá 400. Linha que a
  proposta já ignorou só aceita `ignorar` (400). Devolve a linha. Importação
  confirmada: 409.
- `POST /importacoes/{id}/confirmacao`, sem corpo, com `Idempotency-Key`
  opcional. Cria os lançamentos pelas mesmas regras do `POST /lancamentos` e
  responde 200 `{id, status: "confirmada", criadas, conciliadas, ignoradas,
  duplicadas}`. Uma recusa de regra (`conta arquivada`, `categoria não
  encontrada`, `categoria não permitida nesta conta`, `saldo da conta fora do
  limite`) é 400 e desfaz tudo; a importação segue pendente. Segunda
  confirmação: 409. Outra confirmação do mesmo usuário em andamento: 409
  `outra confirmação está em andamento, tente de novo`, sem esperar; repetir
  depois. Envio e confirmação têm limite próprio de 429 por IP (10 por minuto,
  somados), além do limite geral.

Linha (`ImportacaoLinha`): `id`, `ordem`, `data`, `tipo` (`receita` ou
`despesa`), `valorCentavos` (inteiro de centavos, sempre positivo), `titulo`,
`descricao`, `valida`, `erro`, `proposta`, `motivo` (`ja_importada`,
`invalida` ou `duplicada_no_arquivo` quando a proposta é ignorar), `decisao`
(nula enquanto vale a proposta), `acao` (a vigente), `categoriaId` (a escolhida
ou Outros / Outras receitas), `lancamentoId` (alvo da conciliação; depois da
confirmação, o lançamento criado) e `resultado` (`criada`, `conciliada`,
`ignorada` ou `duplicada`, nulo antes da confirmação). Em linha inválida `data`,
`tipo`, `valorCentavos` e `categoriaId` são nulos.

Detalhes: o `TRNAMT` aceita ponto ou vírgula e no máximo 2 casas; mais que isso
deixa a linha inválida. A `DTPOSTED` vale como foi escrita, sem conversão de
fuso. `dataCompetencia` do lançamento criado é essa data e `dataCaixa` segue a
conta (vencimento da fatura em cartão). Logs levam só ids e contagens.

## Importação de extrato CSV (ADR-008, ADR-070)

Entra na mesma máquina do OFX: o envio devolve a proposta por linha, e a
revisão (`PUT /importacoes/{id}/linhas/{linhaId}`), a confirmação, o descarte, o
`GET /importacoes` e o `GET /importacoes/{id}` são as rotas da seção anterior,
sem mudança. O CSV só traz um mapeamento de colunas salvo por usuário e uma
prévia. Toda rota exige sessão e filtra por dono: conta ou mapeamento de outro
usuário responde 404, com o mesmo corpo do inexistente.

### Mapeamentos (`/mapeamentos-csv`)

`MapeamentoCsv`: `id`, `nome`, `separador`, `formatoData`, `decimal`, `sinal`,
`temCabecalho`, `colunaData`, `colunaValor`, `colunaDescricao`, `colunaId`
(nulo se o banco não tem identificador). Valores:

- `separador`: `ponto_e_virgula`, `virgula`, `tab` ou `barra_vertical`
- `formatoData`: `dd/MM/yyyy`, `yyyy-MM-dd`, `MM/dd/yyyy` ou `dd-MM-yyyy`
- `decimal`: `virgula` (1.234,56) ou `ponto` (1,234.56)
- `sinal`: `negativo_despesa` ou `positivo_despesa` (extrato de cartão, onde a
  compra vem positiva)
- colunas: índice a partir de 0, de 0 a 49, diferentes entre si

- `GET /mapeamentos-csv`: do mais antigo para o mais novo.
- `POST /mapeamentos-csv` com todos os campos menos `id` (`colunaId` é
  opcional): 201 com o `MapeamentoCsv`. `nome` tem de 1 a 60 caracteres, sem
  caractere de controle, e é único por usuário. 400 para entrada inválida,
  campo não declarado ou colunas repetidas; 409 `já existe um mapeamento com
  esse nome` ou `limite de mapeamentos de CSV atingido` (20 por usuário).
- `PUT /mapeamentos-csv/{id}`: mesmo corpo, troca tudo. 200 com o mapeamento.
  404 se não existe, é de outro usuário ou o id não é UUID; 409 para nome de
  outro mapeamento seu.
- `DELETE /mapeamentos-csv/{id}`: 204. As importações já criadas não mudam.

### Envio

`POST /importacoes/csv?conta_id=<uuid>&mapeamento_id=<uuid>` com o arquivo cru
no corpo (`application/octet-stream`; outro tipo dá 415), como o OFX: sem
multipart, sem nome de arquivo, nada em disco. Mesmo teto de 1 MiB (413), 2000
linhas de dados (422), no máximo 50 colunas por linha (422) e célula cortada em
200 caracteres. UTF-8 (com ou sem BOM) ou Windows-1252. Linhas totalmente
vazias são ignoradas e não contam na `ordem`. Resposta 201 com o mesmo
`ImportacaoDetalhe` do OFX. Mesmos erros do OFX (400 `conta_id` ou
`mapeamento_id` ausente ou que não é UUID, parâmetro não declarado, `conta
arquivada`, `conta cripto ou de investimento não recebe lançamento`; 404 `conta
não encontrada` ou `mapeamento não encontrado`; 409 arquivo já importado, com
`importacaoId`, ou 20 pendentes), mais o 422 de leitura, com mensagem fixa e
sem trecho do arquivo: `arquivo vazio`, `o arquivo não parece um CSV de texto`
(byte NUL), `aspas sem fechar no arquivo`, `nenhuma linha de dados encontrada`,
`arquivo com mais de 2000 linhas`, `arquivo com mais de 50 colunas por linha`.

Linha com menos colunas que o mapeamento, data ou valor ilegível entra como
inválida (`valida: false`, `erro`, `proposta: ignorar`, `motivo: invalida`) e
fica fora da confirmação; não derruba o arquivo. Os `erro` são `linha com menos
colunas que o mapeamento`, `data inválida`, `valor inválido`, `valor zero`,
`valor com mais de 2 casas decimais` e `valor fora do limite`.

Leitura do valor: sinal `-` ou `+` à frente, prefixo `R$` e espaços (inclusive
NBSP) aceitos; parênteses não. Com `decimal: virgula`, `.` é milhar (em grupos de
3) e `,` é decimal; com `ponto`, o inverso. No máximo 2 casas, sem arredondar.
`negativo_despesa`: valor menor que zero é despesa; `positivo_despesa`: maior que
zero é despesa. A data segue `formatoData` de forma estrita (31/02 é inválida),
entre os anos 1900 e 2199.

Deduplicação: com `colunaId` preenchida o id da célula manda (mesmo id no
arquivo: a segunda linha é `ignorar`, `duplicada_no_arquivo`). Sem id, vale a
conta, a data, o valor, a descrição e a ordem entre linhas iguais, então dois
cafés iguais no mesmo dia são dois lançamentos e um extrato reenviado, ou
sobreposto, cai em `ja_importada`.

Fórmula injetada: texto de célula (título, descrição, `bruta`) que começa com
`=`, `+`, `-`, `@`, TAB ou CR ganha um apóstrofo na frente. O título que o
cliente vê e o lançamento confirmado já levam o apóstrofo.

### Envio de PDF de fatura (ADR-073)

`POST /importacoes/pdf?conta_id=<uuid>` com o PDF cru no corpo
(`application/octet-stream`). Melhor esforço: a leitura é heurística e a revisão
é obrigatória; nada vira lançamento antes de `POST /importacoes/{id}/confirmacao`.
A conta precisa ser de cartão de crédito. Resposta 201 com o mesmo
`ImportacaoDetalhe`. Erros: 400 (`conta_id` ausente, repetido ou fora do formato
UUID, parâmetro não declarado, `conta arquivada`, `o PDF de fatura só vale para
conta de cartão de crédito`); 404 conta de outro usuário ou inexistente; 409
arquivo já importado (`importacaoId`) ou 20 pendentes; 413 acima de 5 MiB; 415
outro Content-Type; 422 com mensagem fixa: `arquivo vazio`, `o arquivo não parece
um PDF`, `não foi possível ler o PDF`, `PDF protegido por senha ou criptografado
não é aceito`, `PDF com mais de 30 páginas`, `PDF com texto demais para uma
fatura`, `o PDF demorou demais para ser lido`, `o PDF não tem texto para ler...`,
`nenhuma transação reconhecida nesta fatura`, `fatura com mais de 2000 linhas`.
Mesmo limite de 10 por minuto por IP do OFX.

Linha reconhecida: `data` (a da compra), `tipo` (`despesa`, ou `receita` para
`CR` e sinal de menos), `valorCentavos`, `titulo` (descrição cortada em 100) e a
parcela `n/m` no texto. Linha que começa com data ou termina em valor e não foi
lida vem com `valida: false`, `erro: linha não reconhecida` (ou `data inválida`,
`valor zero` etc.), `proposta: ignorar` e o texto cru em `titulo`; só aceita
`ignorar`. Crédito de pagamento da fatura vem inválido (`pagamento da fatura:
registre como transferência`). Na confirmação, `data_competencia` é a data da
linha e `data_caixa` é o vencimento da fatura pela regra do cartão (ADR-031).

### Prévia

`POST /importacoes/csv/previa` com o mapeamento na query e o arquivo cru no
corpo. Parâmetros, todos obrigatórios menos o último: `separador`,
`formato_data`, `decimal`, `sinal`, `tem_cabecalho` (`true` ou `false`),
`coluna_data`, `coluna_valor`, `coluna_descricao`, `coluna_id`. Os valores são os
do mapeamento. Parâmetro ausente, inválido, repetido ou não declarado, ou colunas
repetidas: 400. Mesmos tetos, 413 e 422 do envio. Não grava nada: não cria
importação nem hash e não usa vaga das 20 pendentes.

200 `CsvPrevia`: `totalLinhas` (do arquivo inteiro), `colunasDetectadas` (da
primeira linha) e `linhas` (até 10). Cada `CsvPreviaLinha`: `ordem`, `celulas`
(texto, já cortado e com o apóstrofo de fórmula), `valida`, `erro`, `data`,
`tipo`, `valorCentavos` (centavos, sempre positivo) e `titulo`. Em linha
inválida `data`, `tipo` e `valorCentavos` são nulos.

## Limites (ADR-049)

- Por IP (IPv6 pelo `/64`): 600 requisições por minuto em qualquer rota, 10
  `POST /auth/login` por minuto e 5 `POST /auth/cadastro` por hora.
- Login e cadastro somados, de todos os IPs: 60 por minuto.
- `POST /importacoes`, `POST /importacoes/csv`, `POST /importacoes/csv/previa` e
  `POST /importacoes/{id}/confirmacao`: 10 por minuto por IP, somados entre as
  quatro rotas, e fora do teto somado do argon2.
- A recusa vem antes de qualquer outra checagem: um 429 não diz se a sessão,
  o corpo ou a rota valiam.
- O login com atraso por e-mail e IP (ADR-053) responde 429 com a mensagem
  `muitas tentativas, tente mais tarde`. O limite por IP usa
  `muitas requisições, tente mais tarde`. Os dois mandam `Retry-After`.
- Argon2 sem vaga por 5 s: login e cadastro respondem 503
  `serviço indisponível` com `Retry-After: 5`.
- Cadastro com o teto de usuários atingido (`CADASTRO_MAX_USUARIOS`, padrão
  20): 403 `cadastro fechado: limite de usuários atingido`, sem gravar nada.
- Teto por usuário (ADR-055): criar conta, recorrência ativa (inclusive
  reativar), compra parcelada, título de renda fixa, movimento de cripto, ativo
  de cripto ou cotação manual além do teto responde 409
  `{"erro":"limite de X atingido"}`, com X em `contas`, `recorrências ativas`,
  `compras parceladas`, `títulos de renda fixa`, `movimentos de cripto`,
  `ativos de cripto`, `cotações` ou `importações pendentes` (20, variável
  `LIMITE_IMPORTACOES_PENDENTES`). Nada é gravado. O teto vale por usuário, e
  os padrões e as variáveis estão no `.env.example`. Os 409 que já existiam
  (nome repetido, ativo repetido, cotação repetida) mantêm a mensagem.
- `DELETE /contas/{id}` que passa do prazo por instrução (`TIMEOUT_APAGAR_CONTA`,
  padrão 15 s): 503 `serviço indisponível`, e nada é apagado.
- `/healthz` com outra checagem em andamento: 503 `{"erro":"indisponível"}`,
  sem tocar no banco.

## MFA (ADR-050)

- `GET /auth/mfa`: `{"ativo", "codigosRestantes"}`. Com o MFA desligado,
  `codigosRestantes` é 0.
- `POST /auth/mfa/configurar` `{"senha"}`: devolve `{"segredo", "uri"}`. O
  segredo vem em base32 sem `=`, e a `uri` é `otpauth://totp/Ballast:<email>`.
  Não liga nada; chamar de novo troca o segredo pendente. Erros: 400
  `senha incorreta`, 409 `mfa já ativo` e 503 `mfa indisponível` (sem
  `MFA_CHAVE`).
- `POST /auth/mfa/ativar` `{"codigo"}`: só o código do app. Devolve
  `{"codigosDeRecuperacao"}`, dez códigos `xxxxx-xxxxx`, mostrados só aqui.
  Erros: 400 `código inválido`, 409 `configure o mfa antes` ou `mfa já ativo`,
  429 com `Retry-After`. Ligar o MFA revoga todas as outras sessões do usuário
  (ADR-053); a sessão que ativou continua, e as demais passam a dar 401.
- `POST /auth/mfa/desativar` `{"senha", "codigo"}`: aceita o código do app ou
  um de recuperação e responde 204. Erros: 400 `senha incorreta` ou
  `código inválido`, 409 `mfa não está ativo`, 429.
- `POST /auth/mfa/codigos-de-recuperacao` `{"codigo"}`: gera dez códigos novos e
  invalida os antigos (ADR-053). Pede o código do app de agora; código de
  recuperação e código já usado dão 400. Responde 200 com
  `{"codigosDeRecuperacao"}`, como o `ativar`, mostrados só aqui. Erros: 400
  `código inválido`, 409 `mfa não está ativo`, 429 com `Retry-After`. Entra no
  mesmo atraso por usuário e no limite de login por IP.
- `POST /auth/login` com MFA ligado e senha certa responde 202
  `{"mfaPendente":true}`, sem sessão, e põe o cookie `ballast_mfa` (5 min).
- `POST /auth/login/mfa` `{"codigo"}` é público e lê o cookie `ballast_mfa`.
  Certo, responde 200 com o usuário, abre a sessão e expira o `ballast_mfa`.
  Erros:
  - 401 `desafio expirado ou inválido`: sem cookie, desafio vencido ou já usado;
  - 401 `código inválido`: código errado, código repetido ou código de
    recuperação já usado;
  - 429 com `Retry-After`.
- O código aceita espaço, hífen e maiúscula. O campo vai de 1 a 32 caracteres;
  fora disso dá 400 `código inválido`.
- Atraso por usuário: 3 códigos errados livres, depois 2^(n−3) s até 15 min,
  contando ativação, desativação e login. O código certo zera a contagem.

## Renda fixa e cripto endurecidos (ADR-058)

Nenhum formato consumido muda. Os campos novos são opcionais na spec.

### Título

- Cada `TituloRendaFixa` ganha `vencido` (boolean, `true` só na listagem, a
  partir do dia do vencimento: o valor fica congelado nele), `avisoIndice`
  (texto curto ou `null`: o CDI ou o IPCA guardado não cobre o período) e
  `motivoSemValor` (`"fora do limite"` ou `null`).
- Título com valor fora de ±9007199254740991 vem com `valorBrutoCentavos`,
  `iofCentavos`, `irCentavos`, `valorLiquidoCentavos` e `rendimentoCentavos`
  nulos e `motivoSemValor: "fora do limite"`. `GET /titulos-renda-fixa` não
  responde mais 400 `renda fixa fora do limite`.
- `DELETE /titulos-renda-fixa/{id}/resgate` desfaz o resgate: 204, idempotente
  (sem resgate também dá 204); 404 `não encontrado` para título alheio,
  inexistente ou id que não é UUID; 401 sem sessão.
- Novos 400: `POST /resgate` com data antes de uma marcação gravada (`resgate
  não pode ser antes de uma marcação`) e `PUT` com aplicação depois de uma
  marcação (`dataAplicacao não pode ser depois de uma marcação`).
- `ultimaMarcacao` e o cálculo só consideram marcação entre a aplicação e o
  resgate, inclusive.
- `POST` e `PUT` do título não esperam o Banco Central.

### Patrimônio e posições

- `GET /patrimonio` ganha `rendaFixaSemValor: [{tituloId, motivo}]` (títulos
  fora de `rendaFixaCentavos`) e `semCotacao[].motivo` (`"sem cotação"` ou
  `"fora do limite"`).
- `GET /posicoes-cripto`: posição com valor fora da faixa vem com todo valor e
  custo nulos, `quantidade` preenchida e `motivoSemValor: "fora do limite"`; fora
  isso `motivoSemValor` é `null`. Não responde mais 400 `posição fora do limite`.
- `POST /movimentos-cripto` em dólar antes da primeira PTAX guardada responde
  com `custoCentavos` nulo; a PTAX chega em segundo plano e a leitura seguinte
  já traz o valor em reais.

### Ativo e cotação

- `PUT /ativos-cripto/{id}` com `{simbolo, nome, decimais}` responde 200 com o
  `AtivoCripto`. `rede` e `contrato` no corpo dão 400. 409 `há movimento com mais
  casas decimais`. 404 `não encontrado` para ativo alheio, inexistente ou id mal
  formado.
- `DELETE /ativos-cripto/{id}` responde 204 e leva as cotações; 409 `ativo tem
  movimentos`; 404 como acima.
- `PUT /ativos-cripto/{id}/cotacoes/{momento}` com `{precoUsd}` responde 200 com
  a `Cotacao`; `DELETE` no mesmo caminho responde 204. `momento` é o da resposta
  da cotação (ISO-8601 com fuso). Cotação da CoinGecko: 409 `cotação automática
  não se altera`. Ativo alheio, momento sem cotação ou fora do formato: 404.

### Saldo projetado

- `GET /saldo-projetado` sem `contaId` não inclui conta cripto. Com `contaId` de
  conta cripto: 400 `conta cripto não tem saldo projetado`; de outro usuário,
  404 `conta não encontrada` como antes.
