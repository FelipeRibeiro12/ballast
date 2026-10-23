# Relatório QA: F13.2 renda fixa (ADR-037)

**Data:** 2026-10-22, noite, horário de São Paulo.
**Branch:** `feat/f13-2-renda-fixa`, PR #22. Testei dois HEADs. O primeiro é `0a7943b`, que era o HEAD quando comecei. O segundo é `ffb8257` ("fix: limita a busca de índice ao que o Banco Central tem e recusa valor implausível"), que entrou na branch durante a sessão. Os achados valem para `0a7943b`. Na seção "Corrigidos" digo o que `ffb8257` resolveu e o que continua aberto.
**Escopo:** cálculo contra conta feita à mão (prefixado, % do CDI, IPCA + taxa), IOF e IR, marcação, resgate, dias úteis, limites de valor e taxa, validação do corpo, autorização entre usuários em todas as rotas de título, conta de investimento recusada nos outros caminhos, apagar conta com títulos, corridas entre escritas, Banco Central fora, parado e malformado, patrimônio com `rendaFixaCentavos`. Não cobri navegador: a parte web foi a suíte vitest, `tsc` e `oxlint`.
**Não commitei nada e não alterei arquivo de produção.** Não criei teste no repositório. Os scripts de roteiro, a conta à mão e as saídas ficaram em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/4ce6bf65-5d49-45e0-a885-89817918f3e4/scratchpad/qa/`.
**Ambiente:** Postgres 17 descartável `ballast-qa-f13-2` em `127.0.0.1:55434`, banco `ballast_qa` (e bancos `bcb_*` e `ballast_qa_ff` para os cenários de BCB). API a partir do jar de `./mvnw -B clean verify` de cada HEAD, em `127.0.0.1:18081` (`0a7943b`) e `127.0.0.1:18083` (`ffb8257`), com `REGISTRO_ABERTO=true`, `COOKIE_SECURE=false`, `APP_ENV=development`, `BALLAST_INDICES_JOB_ENABLED=false` e job de recorrência desligado. O Banco Central foi um servidor local em `127.0.0.1:18999` que imita o SGS, com modos `500`, `html`, `objeto`, `hang` (30 s sem resposta), `parado` (CDI até 30/09, IPCA vazio) e `lixo` (valor de 5000 e formatos errados). Não chamei a API real do BCB. Não usei o banco do compose, a porta 8080 nem o `.env`. Os índices de cálculo foram inseridos direto em `indice`: CDI de 02/01/2023 a 21/10/2026 (981 dias úteis, sem feriados nacionais) e IPCA de jan/2023 a set/2026. Container parado ao fim. O Docker Desktop reiniciou no meio da sessão e o container voltou com os dados (`docker start`).

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B clean verify` em `0a7943b` (export com `git archive api`) | 1404 testes, 1 erro em `SeedParityTest`, que lê `../docs/contracts/dados.md`. Erro do meu export, que não trouxe `docs/`. Com `docs/` presente, `SeedParityTest` passa |
| `cd api && ./mvnw -B clean verify` em `ffb8257` (mesmo conteúdo da árvore, export com `api/`, `docs/`) | BUILD SUCCESS. 1408 testes, 0 falhas, 0 erros |
| `cd api && ./mvnw -B spotless:check` em `ffb8257` | Sem erro |
| `cd web && pnpm test --run` | 786 testes, todos passam. Web não mudou entre `0a7943b` e `ffb8257` |
| `cd web && npx tsc -b` | Sem erro |
| `cd web && npx oxlint` | Sem erro. Um aviso de referência triple-slash em `vite.config.ts`, fora da fatia |

## Resumo

- **Achados:** 1 bloqueia, 6 dívida, 2 sugestão. Além disso, 3 problemas que estavam em `0a7943b` foram corrigidos em `ffb8257` e verifiquei a correção.
- **Travam primeiro:** B1. O contrato diz `dataAplicacao deve ser antes do resgate` no `PUT`, e a API aceita data igual ao resgate. Pelo critério de contrato violado, bloqueia. A correção é uma linha de código ou uma palavra no contrato, e a escolha é do dono.
- **Mais sérios depois:** D1, corrida entre editar ou marcar e apagar o mesmo título, que dá 500 em 8 de 8 rodadas. D2, um título fora do limite derruba a lista e o patrimônio inteiros. D4, D5 e D6, cálculo que erra em silêncio com índice faltando ou marcação ignorada.
- **Cálculo:** 59 de 59 comparações com a conta à mão batem, nos dois HEADs. A parte de cálculo, que é o centro da fatia, está correta no caminho feliz e nas bordas que testei.
- **Recomendação:** **segurar** até corrigir B1. Depois, **liberar com dívida registrada**: D1 a D6 no backlog, cada um com a fatia que paga. Se o dono tratar 500 em corrida como contrato violado, D1 sobe para bloqueia. A decisão é do tech-lead.

## Achados

### Bloqueia

**B1. `PUT /titulos-renda-fixa/{id}` aceita `dataAplicacao` igual à data do resgate. O contrato diz `antes`.**

- **Classificação:** bloqueia (contrato violado no texto literal). Gravidade: erra em silêncio, o valor fica com resgate no mesmo dia da aplicação.
- **O que acontece:** a API responde 200 e grava a aplicação no mesmo dia do resgate. `docs/contracts/api.md`, seção Título, diz `dataAplicacao deve ser antes do resgate` para o `PUT`.
- **Como reproduzir:**
  ```
  # conta investimento I, título T (cdb, valor 1000,00, aplicado 2026-01-05)
  POST /titulos-renda-fixa/T/resgate  {"data":"2026-07-01","valorCentavos":123456}   -> 200
  PUT  /titulos-renda-fixa/T  {... "dataAplicacao":"2026-07-01" ...}                 -> 200
  ```
  Verificado em `0a7943b` e em `ffb8257` (`probes1.py`, linha "PUT aplicacao igual ao resgate").
- **O que deveria acontecer:** 400 `dataAplicacao deve ser antes do resgate`, se o texto do contrato estiver certo. Se a regra for "não depois do resgate", o contrato deve dizer isso. Hoje o código e o texto não concordam.

### Dívida

**D1. Corrida entre editar ou marcar um título e apagá-lo dá 500.**

- **Classificação:** dívida, prioridade alta. Gravidade: trava na requisição (500 em vez de 404), sem perda de dado. Se o tech-lead ler "contrato violado" como bloqueio, sobe.
- **O que acontece:** o `DELETE` responde 204, e a escrita concorrente responde 500 com `{"erro":"erro interno"}`. O corpo é genérico, mas o contrato promete 404 para título inexistente. Em `0a7943b` e em `ffb8257`:
  - `PUT` contra `DELETE`: 500 em 8 de 8 rodadas. Log: `java.util.NoSuchElementException` em `FixedIncomeService.save`, no `orElseThrow()` depois do `update`, que atualizou 0 linhas.
  - `POST marcacoes` contra `DELETE`: 500 em 8 de 8 rodadas. Log: `DataIntegrityViolationException` com `sqlstate=23503`, constraint `marcacao_renda_fixa_titulo_do_usuario`, em `FixedIncomeQueries.upsertMark`.
- **Como reproduzir:** título novo na conta de investimento. Duas requisições em paralelo: `DELETE /titulos-renda-fixa/T` e `PUT /titulos-renda-fixa/T` com o mesmo corpo válido. Repetir com títulos novos. Script: `probes3.py`.
- **O que deveria acontecer:** a escrita que chega depois do `DELETE` responde 404 `não encontrado`, como o contrato diz para título inexistente.

**D2. Um título fora do limite derruba a lista e o patrimônio inteiros, e a tela não diz qual título é.**

- **Classificação:** dívida, prioridade média. Gravidade: trava a tela de investimentos e o patrimônio até o usuário apagar o título pelo id. Só acontece com valores extremos.
- **O que acontece:** o valor aceito vai até 9007199254740991 centavos. Um título nesse valor com qualquer rendimento passa do limite de ±9007199254740991 centavos. `GET /titulos-renda-fixa` responde 400 `renda fixa fora do limite` para a lista inteira, e `GET /patrimonio` responde 400 `patrimônio fora do limite`. O usuário não vê a lista para achar o título. Só o id permite apagar.
- **Como reproduzir:**
  ```
  # conta investimento, sem outros títulos
  POST /titulos-renda-fixa {"valorAplicadoCentavos":9007199254740991,"dataAplicacao":"2026-01-05","indexador":"prefixado","taxa":"12.5",...} -> 201
  GET  /titulos-renda-fixa?data=2026-10-22   -> 400 renda fixa fora do limite
  GET  /patrimonio?data=2026-10-22           -> 400 patrimônio fora do limite
  DELETE /titulos-renda-fixa/{id}            -> 204
  GET  /titulos-renda-fixa?data=2026-10-22   -> 200
  ```
  Verificado em `ffb8257`. Em `0a7943b` acontece igual.
- **O que deveria acontecer:** a lista sai com os outros títulos e marca o título que estourou. Ou a validação recusa, no cadastro, um valor cujo rendimento não cabe. Hoje o erro não identifica nada.
- **Nota:** por aritmética, e não medido, 1000% ao ano por 8 anos sobre R$ 1 milhão também estoura. É caso extremo, mas a consequência é uma tela inteira fora do ar.

**D3. Cadastro de aplicação antiga leva 10 s com o Banco Central sem resposta.**

- **Classificação:** dívida, prioridade média. Gravidade: incomoda. O cadastro conclui com 201, mas segura a thread HTTP por 10 s.
- **O que acontece:** `IndexUpdater` pede CDI e depois IPCA, em série, e cada pedido tem timeout de 5 s. Com o BCB mudo, cada cadastro de aplicação antiga leva 10,05 s. Medido em 3 de 3 cadastros, nos dois HEADs.
- **Como reproduzir:** `BALLAST_INDICES_BASE_URL` apontando para um servidor local que aceita a conexão e não responde (modo `hang`). `POST /titulos-renda-fixa` com `dataAplicacao` de 2024-06-03 e `indexador` `prefixado`.
- **O que deveria acontecer:** o cadastro responde sem esperar o timeout de cada série, ou em segundo plano. O ADR-037 diz que o Banco Central fora "não trava nada". Hoje trava por 10 s.

**D4. Índice de IPCA vazio: título IPCA + taxa rende como prefixado, sem aviso por título.**

- **Classificação:** dívida, prioridade média. Gravidade: erra em silêncio.
- **O que acontece:** com IPCA vazio, `ipcaFactor` pula todos os meses e devolve fator 1. Um título LCA IPCA + 6 aplicado em 2024-06-03 mostra R$ 1.152,01 (115201 centavos), que é o valor de um prefixado a 6%. A conta à mão com o mesmo prefixado a 6% dá 115175. A diferença de 26 centavos é o dia útil a mais de S2. Com o IPCA da seed, a conta à mão dá 132963. `GET /indices` responde `ipcaAte: null`, mas o aviso da tela (`RendaFixa.tsx`, linha 434) só olha `cdiAte`.
- **Como reproduzir:** BCB no modo `parado` (IPCA sem valores). Cadastrar LCA `ipca` taxa 6, aplicado 2024-06-03. `GET /titulos-renda-fixa?data=2026-10-22`.
- **O que deveria acontecer:** o título sinaliza que o índice do IPCA não entrou, e o aviso da tela cobre IPCA além de CDI.

**D5. Histórico de CDI incompleto: o cálculo ignora o período sem índice e a resposta não diz nada.**

- **Classificação:** dívida, prioridade média. Gravidade: erra em silêncio. Corrige sozinho quando o índice chega.
- **O que acontece:** o cálculo só conta dias com CDI guardado. Se o CDI começa em 01/10/2025 e o título foi aplicado em 03/06/2024, os dias de 2024 a 2025 não entram. Um prefixado a 12,5% aplicado em 03/06/2024 vale R$ 1.133,97. Com o CDI completo, vale R$ 1.330,53, que é a conta à mão. `cdiAte` mostra `2026-10-21`, então nada indica o buraco.
- **Como reproduzir:** banco `indice` com CDI só a partir de 01/10/2025. Cadastrar LCI prefixada 12,5 aplicada em 03/06/2024, com BCB fora (`500`). `GET` de 22/10/2026 mostra 113397. Inserir o CDI de 03/06/2024 a 30/09/2025 e repetir o `GET`: 133053, igual à conta à mão.
- **O que deveria acontecer:** o título sinaliza o buraco, ou o cadastro recusa a aplicação até o índice existir. Hoje o usuário vê um valor baixo sem explicação, até o job das 8h ou o próximo cadastro.

**D6. Depois de `PUT` com aplicação posterior a uma marcação, a marcação some do cálculo sem aviso.**

- **Classificação:** dívida, prioridade baixa. Gravidade: erra em silêncio.
- **O que acontece:** título LCI prefixada 12,5 aplicada em 05/01/2026, marcação de 02/02/2026 com R$ 1.100,00, depois `PUT` com aplicação em 02/03/2026. O `PUT` responde 200. O `GET` de 22/10/2026 dá bruto 107916, que é o valor a partir da nova aplicação sobre R$ 1.000,00. Mas `ultimaMarcacao` continua mostrando `{2026-02-02, 110000}`, então a tela mostra uma marcação que não entra no cálculo.
- **Como reproduzir:** sequência acima. `probes1.py`, linha "PUT com aplicacao depois de marcacao existente".
- **O que deveria acontecer:** o `PUT` que deixa a marcação antes da aplicação responde 400, ou a marcação continua valendo. Hoje o usuário não sabe qual valor está sendo usado.

### Sugestão

**S1. `GET /titulos-renda-fixa?data=` com data futura responde 200 com os valores de hoje.**

- **Classificação:** sugestão. Gravidade: incomoda. O contrato em `ffb8257` documenta o comportamento, mas a tela deixa escolher qualquer data até 2199.
- **O que acontece:** `data=2199-12-31` responde 200 com `data: 2199-12-31` e os valores de 22/10/2026. Em `0a7943b`, a mesma chamada respondia 400 `renda fixa fora do limite`, por estouro na projeção.
- **O que deveria acontecer:** a tela não deixa escolher data futura, ou a resposta traz a data efetiva do cálculo.

**S2. Dia útil depois do último CDI conta feriado nacional.**

- **Classificação:** sugestão, porque é uma aproximação que o ADR-037 aceita. Gravidade: incomoda. A janela depois do último CDI conta de segunda a sexta. Em `parado`, o último CDI é 30/09/2026, e 12/10/2026 (feriado) entra como dia útil. Medido: 26 centavos a mais sobre R$ 1.000,00 aplicados em 2024-06-03 com prefixado a 6%, o que bate com 1 dia útil a mais.
- **O que deveria acontecer:** se a aproximação continuar, o ADR deve dizer que a janela sem CDI pode ter dias úteis a mais. Se não, usar a lista de feriados que o próprio projeto já aceitou evitar.

### Corrigidos em `ffb8257` (verificados)

- **C1. Taxa acima de 1000 dava valor errado em silêncio.** Em `0a7943b` a taxa ia até 99999. Prefixado 99999% ao ano, aplicado R$ 1.000,00: 1 dia útil dá 2.251,97, e a conta à mão dá 1.027,79. 22 dias úteis dá R$ 57.067.930.858,73, e a conta à mão dá R$ 1.827,86. A raiz 252 é calculada por Newton com no máximo 200 iterações, e quando não fecha devolve o último chute sem avisar. Com 5000% os casos testados deram valor certo, e com 99999% deram errado. Além disso o `GET` inteiro caía no limite. Em `ffb8257` a taxa é recusada acima de 1000 (400), `1000.0001` também, e 1000 bate com a conta à mão em 1, 22 e 252 dias úteis.
- **C2. Valor de índice implausível era gravado para sempre.** Em `0a7943b`, o BCB respondendo CDI de 5000 num dia grava esse valor (`ON CONFLICT DO NOTHING` não corrige), e o título passa a valer R$ 69.182,60, contra R$ 1.356,52 sem o valor ruim. Em `ffb8257`, valor fora de [0, 10] para CDI e [-5, 100] para IPCA é descartado, com log `fora da faixa ignorado`.
- **C3. Data futura estourava o limite na projeção.** Coberto por S1.

## Checagens

Tudo contra a API real local nos dois HEADs, salvo onde está dito.

- **Cálculo, 59 comparações com a conta à mão** (`ref.py`, Decimal com 50 dígitos, sem código de produção). Prefixado, % do CDI e IPCA + taxa, com LCI, LCA e CDB. IOF em todos os dias de 0 a 32 (tabela regressiva, zero a partir do dia 30). IR nas bordas de 179, 180, 181, 360, 361, 720 e 721 dias. 252 dias úteis exatos: 10% dá 1.100,00, 200% dá 3.000,00, 1000% dá 11.000,00. Marcação como ponto de partida, resgate com data anterior e posterior (rendimento = resgate menos aplicado, bruto zero depois), aplicado hoje (rendimento zero), data antes da aplicação (campos nulos). Feriados nacionais excluídos da série batem.
- **Arredondamento:** HALF_EVEN está coberto pelo teste unitário `eachTaxIsRoundedOnceHalfEven`. Não consegui um caso de empate exato pela API, porque a raiz 252 não é exata. Não reproduzi nada aqui.
- **Validação do corpo do `POST`:** cerca de 50 entradas. Nome vazio, só espaços, com 101 caracteres, com controle, com tag HTML (aceito, o React escapa). Valor zero, negativo, acima do limite JSON, string, decimal, nulo. Data 30/02, `2026-1-1`, 1899, amanhã, hoje (aceito). Vencimento antes, igual, 30/02, em branco (aceito como sem vencimento). Taxa zero, `0.0000`, vírgula, notação científica, negativa, com espaço, numérica no JSON, cinco casas, seis dígitos, 1000, 1000.0001, 99999. Tipo e indexador inválidos. Conta não-UUID, inexistente, corrente. Campo não declarado (`foo`) recusado com 400. JSON malformado, body vazio e `content-type` errado (415).
- **Nome com SQL:** `x'); DROP TABLE titulo_renda_fixa;--` gravado como texto (201), e a tabela continua respondendo.
- **Autorização, usuário B contra recursos de A:** `PUT`, `DELETE`, marcação e resgate do título de A, `PUT` com id inexistente, `DELETE` com id malformado, `POST` de título com conta de A, `GET` da lista, `PATCH`, `DELETE` e `resumo-exclusao` da conta de A. Todos 404 `não encontrado`, mesmo corpo para id inexistente. `POST` com conta de A: 400 `conta não encontrada`. A lista de B não traz nenhum título de A.
- **Anônimo:** `GET` e `POST` de título, `GET /indices`, `GET /patrimonio` e marcação, todos 401.
- **Conta de investimento:** saldo inicial diferente de zero e `PATCH` de saldo, 400 `conta de investimento não tem saldo em reais`. Lançamento, receita, transferência nos dois sentidos, recorrência e compra parcelada, todos 400 com as mensagens do contrato. Arquivar: título recusado com `conta arquivada`, a conta some da lista, e volta ao desarquivar. Os testes unitários de `InvestmentAccountTest` cobrem também o `PUT` de lançamento para a conta de investimento.
- **Apagar conta com títulos:** `resumo-exclusao` mostra `titulosRendaFixa: 1`, o `DELETE` responde 204, e o título some (404 depois). A remoção das marcações é por `ON DELETE CASCADE` na V14, lida no DDL, não verificada por consulta.
- **Corridas, 8 rodadas cada** (`probes3.py`). `DELETE` de conta contra `POST` de título na conta: `(204, 201)` em 7, `(204, 400)` em 1, sem órfão e sem 5xx. `DELETE` de conta contra resgate: `(204, 200)` em 8. Duas marcações no mesmo dia: `(201, 201)` em 8. `DELETE` de título contra marcação e contra `PUT`: 500 em 8 (D1).
- **Patrimônio:** `rendaFixaCentavos` = soma do `valorLiquidoCentavos` dos títulos (35.350.070 = 35.350.070), e `totalCentavos` = contas + cripto + renda fixa. Investimento não entra no saldo de contas (0).
- **Resgate:** `resgate` com valor zero aceito. Resgate de novo troca data e valor. Marcação no dia do resgate ou depois: 400 `título já resgatado nessa data`. `PUT` com aplicação depois do resgate: 400 `dataAplicacao deve ser antes do resgate`.
- **Log e erro:** o log da API não tem e-mail, senha, token de sessão nem o nome de título digitado. Erro 500 responde `{"erro":"erro interno"}`, sem stack.
- **Banco Central, cenários de `0a7943b` e `ffb8257`:** `500`, `html` e `objeto` e conexão recusada: cadastro responde 201 em 0,01 a 0,08 s, sem 500 e sem travar, mas os títulos antigos ficam com valor igual ao aplicado e `cdiAte: null`. `lixo`: em `0a7943b`, CDI de 5000 gravado (C2). Em `ffb8257`, descartado. Formatos errados (`abc`, data ISO) ignorados nos dois. `parado`: D4 e D5.

## Observações sem reprodução

Não são achados. Li no código e não reproduzi.

- `IndexUpdater.updateFrom` usa `tryLock`. Quem chega com outra atualização rodando sai na hora, sem repetir. Um título aplicado nessa janela só ganha índice no próximo cadastro ou job. Não medi.
- Não rodei o navegador. A parte web tem a suíte vitest, `tsc` e `oxlint`, e não tem teste de tela para o aviso de índice (D4).
