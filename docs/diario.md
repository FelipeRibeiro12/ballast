# Diário

Uma entrada por fatia fechada, a mais recente no topo.

## 2026-10-23 — F18.1: patrimônio no tempo

O que entrou:
- `GET /patrimonio/mensal`: o `/patrimonio` do último dia de cada um dos doze meses que terminam no mês pedido. O mês corrente vale hoje.
- No web, o cartão Patrimônio em Relatórios, com linha, número de destaque, mudança no período, detalhe por parte, aviso de cotação ausente e tabela.
- `useLargura` saiu do saldo projetado para os dois gráficos usarem (commit de refator).

Decisões: ADR-047. A série chama o mesmo cálculo de `/patrimonio`, sem regra nova. Mês futuro fica de fora.

Teste:
- O mês fechado vale no último dia e o corrente vale hoje, sem a receita agendada.
- A cripto sem cotação no mês fica fora do total e é contada à parte.
- Usuário A não vê o patrimônio de B.
- Na tela, o teclado escolhe o mês como o toque.
- Conferido em 360px no Chromium, claro e escuro.

Em aberto: o peso de doze leituras do saldo, junto da dívida do histórico inteiro.

## 2026-10-23 — F18: relatórios

O que entrou:
- `GET /relatorios` com a despesa do mês por categoria de cima e as receitas contra despesas dos seis meses, por competência e só o realizado até hoje.
- No web, a tela Relatórios com barras por categoria, colunas por mês com detalhe e tabela, o terceiro atalho de planejamento e o cartão de despesas no Início.
- `Months` foi para o pacote `web`, para orçamento e relatórios usarem o mesmo formato de mês.

Decisões: ADR-046. Patrimônio no tempo ficou para a F18.1. O consumo de orçamento já tinha barra na F16. Azul e laranja nas séries porque verde e vermelho falham no validador de daltonismo. Barras em CSS, sem biblioteca de gráfico.

Teste:
- Compra no cartão conta no mês da compra.
- A filha soma na categoria de cima.
- Agendado, recorrência sem confirmar e transferência ficam fora.
- Os seis meses vêm com zero nos vazios.
- Usuário A não vê nada de B.
- Na tela, a fatia que arredonda para zero aparece como `<1%`.
- Conferido em 360px no Chromium, claro e escuro, sem rolagem lateral. A tabela rola dentro do cartão.

Em aberto: F18.1 e o desenho da navegação, no backlog.

## 2026-10-23 — F17: metas

O que entrou:
- Migration V19 com `meta` e `aporte_meta` (FK composta e CASCADE).
- Rotas de meta e de aporte. A retirada nunca deixa o acumulado negativo, com a meta travada por `FOR UPDATE`.
- Projeção na leitura: falta, necessário por mês arredondado para cima, chegada pelo plano e pelo ritmo dos três meses fechados.
- No web, a tela Metas (criar, editar, aportar, retirar, ver e apagar aportes, apagar meta), os atalhos Orçamento e Metas e o cartão no Início.

Decisões: ADR-045. Aporte à mão e aporte mensal como plano, sem recorrência nem ligação com lançamento. Feita numa worktree a partir da main, com o PR #30 de produção ainda aberto.

Teste:
- Três meses de 333,33 não chegam a 1.000: o necessário arredonda para cima.
- O mês corrente fica fora do ritmo.
- Retirada maior que o acumulado é recusada, e apagar o depósito de que ela depende também.
- Usuário A não vê nem mexe na meta de B.

Em aberto: o desenho da navegação e o aporte ligado à transferência, no backlog.

## 2026-10-23 — F16: orçamento por categoria

O que entrou: migration V18 com `orcamento` por vigência (teto nulo encerra) e o índice de competência em `lancamento`; `GET`, `PUT` e `DELETE /orcamentos` com gasto por competência até hoje, previsto e restante, somando as categorias filhas; no web, a tela Orçamento com navegação por mês, novo teto, editar e encerrar a partir do mês, e um cartão no Início com os três tetos mais consumidos.
Decisões: ADR-043. Vigência no lugar de uma linha por mês. O Orçamento ficou fora da barra de navegação porque seis rótulos se sobrepõem em 360px (conferido com screenshot no Chromium); entra pelo Início.
Antes: deixei `strict` escrito nos tsconfig (PR #28); o TypeScript 6 já ligava por padrão.
Em aberto: nada.

## 2026-10-23 — F15: aporte × valorização

O que entrou: custo médio ponderado por posição, em dólar e em reais, calculado na leitura a partir dos movimentos (compra antes de venda no mesmo dia); na posição, custo médio, custo do que resta, ganho em aberto e ganho das vendas; no patrimônio, aporte e valorização da cripto; no web, as três linhas na posição e a frase no resumo do patrimônio.
Decisões: ADR-042. Sem migration: o custo é obrigatório no movimento desde a F13, e o custo faltando passou para a F24.
Teste: três vendas de um terço fecham o custo inteiro; compras menos vendas é o custo que resta menos o realizado; a venda gravada antes da compra do mesmo dia usa o custo das duas.
Em aberto: ganho realizado de posição zerada não aparece.

## 2026-10-23 — F14.1: histórico de cotação para trás

O que entrou: migration V17 com `ativo_cripto.historico_em`; `market_chart/range` da CoinGecko por id do nativo e por plataforma e contrato do token, 364 dias, 404 como ativo desconhecido; o job da cotação, depois de cotar, busca até 10 ativos pendentes por rodada e grava só os dias antes da primeira cotação de cada dono, na mesma transação da marca; o filtro de preço da F14 virou um método só.
Decisões: ADR-041. O endpoint por contrato dispensou o pedido do id da moeda que o backlog previa.
Teste: o ponto diário enche a posição de uma data passada; a cotação digitada segura o que vem depois dela; fonte fora deixa o ativo pendente; ativo desconhecido é pedido uma vez.
Em aberto: rodar com a chave real do dono.

## 2026-10-23 — F14: cotação automática pela CoinGecko

O que entrou: migration V16 com a fonte `coingecko` no CHECK de `cotacao`; cliente da CoinGecko (plano Demo, chave no cabeçalho, nativos num pedido e tokens por plataforma, preço lido em `BigDecimal`); job a cada 30 min gravando uma linha por ativo confiável de cada usuário, idempotente pelo `last_updated_at`; `fonte` na cotação da posição; no web, dia, hora e fonte da cotação.
Decisões: ADR-040 (provedor escolhido pelo dono). Fora de ADR: o e2e sobe com o job desligado.
Dívida registrada: F14.1, histórico para trás pelo `market_chart/range`.
Teste obrigatório: com a fonte fora do ar o job não lança, movimento e lançamento gravam e a posição mostra a última cotação, sem nenhuma requisição chamar a fonte.
Em aberto: rodar com a chave real do dono; sem ela o job só avisa no log.

## 2026-10-23 — Correção: total do mês pela data de caixa (PR #24)

O que entrou: Receitas e Despesas contam só o realizado com caixa até hoje; compra no cartão antes do vencimento e lançamento com data futura vão para "Previsto no mês", com selo, e o agendado fora de cartão se confirma antes com a data de hoje. "Próximas previstas" segue a mesma regra.
Decisões: ADR-039. O saldo da conta corrente continua somando o agendado (ADR-024) e fica com o dono.
Pipeline: verde em todos os checks.

## 2026-10-22 — F13.3: cripto em dólar (PR #23)

O que entrou: migration V15 com `moeda` em `movimento_cripto` e `cotacao` (o que a F13 gravou fica em reais) e a série `dolar` (PTAX) no índice, com faixa por CHECK; custo e cotação digitados em US$; posição com tokens, US$ e R$ pela PTAX da data; custo em reais pela PTAX da data do movimento; no web, valores em dólar e em reais e o aviso da PTAX usada.
Decisões: ADR-038 (custo em USD e PTAX automática, escolhas do dono). Fora de ADR: teto do custo em dólar em 1/50 do inteiro seguro; o POST relê o movimento depois de buscar a PTAX que faltava.
Dívida registrada: busca de PTAX dentro da requisição quando não há nenhuma, mensagem de limite das posições, nome de `earliestInvestment` e testes extras de conversão.
Achados do QA: 1 bloqueou (cotação antiga em reais sem PTAX mantinha o valor em reais, ao contrário do contrato; o código estava certo e o contrato e o ADR foram corrigidos, com teste), 2 dívidas (latência sem PTAX, registrada; valor no limite, já no backlog), 3 sugestões (preço em dólar com 34 dígitos, arredondado para 8; PTAX do dia no ADR; mensagem, registrada). 140 de 143 checagens bateram antes da correção.
Achados do revisor: 0 bloquearam, 3 corrigir antes (custo em reais passando do inteiro seguro, resposta sem o custo em reais depois de buscar a PTAX, busca em todo POST: os três corrigidos), 4 sugestões (custo em reais quando falta o em dólar, aplicada; as outras registradas). Security: 0 críticos, 0 altos, 2 médios (busca em todo POST e faixa da PTAX só na aplicação, corrigidos), 2 baixos (o teto do custo resolveu).
Pipeline: verde em todos os checks, o e2e incluído.
Em aberto: nada.

## 2026-10-22 — F13.2: renda fixa (PR #22)

O que entrou: migration V14 com o tipo `investimento`, `titulo_renda_fixa`, `marcacao_renda_fixa` e `indice` (público, sem `usuario_id`); download de CDI e IPCA do Banco Central ao subir e às 8h; cálculo em `BigDecimal` de prefixado, % do CDI e IPCA + taxa, com a raiz 252 por Newton e os dias úteis tirados da série do CDI; IR e IOF estimados no CDB; rotas de título, marcação, resgate e índices; renda fixa no patrimônio e na exclusão de conta; no web, a aba Investimentos com renda fixa e cripto e o patrimônio no topo.
Decisões: ADR-037 (cálculo híbrido e CDB com bruto e líquido, escolhas do dono). Fora de ADR: a V14 não faz DROP e deixa a coluna da V13 redundante; data futura vale o valor de hoje; taxa até 1000.
Dívida registrada: coluna redundante da V13 (depende de autorização para o DROP), desfazer resgate, IPCA sem projeção, vencimento que continua rendendo, título no teto derrubando a lista, aviso por título quando o índice não cobre o período, busca de índice dentro da requisição, tetos e rate limit, link para a aba Cripto, nomes de erro reaproveitados, e2e consultando o Banco Central.
Achados do QA: 1 bloqueou (contrato dizia "antes do resgate" e o banco aceita o mesmo dia; texto corrigido), 6 dívidas (uma corrigida: corrida dava 500 e passou a 404; cinco registradas), 2 sugestões (uma registrada no ADR). 59 de 59 contas à mão bateram.
Achados do revisor: 0 bloquearam, 2 corrigir antes (IPCA no meio do mês sem o mês da aplicação; busca síncrona amplificada por data antiga; os dois corrigidos), 7 sugestões registradas. Security: 0 críticos, 1 alto (aplicação de 1900 pedia dezenas de janelas vazias ao Banco Central por cadastro; busca passou a começar no primeiro dado da série, uma atualização por vez), 2 médios (data futura e valor implausível do Banco Central, corrigidos; teto e rate limit registrados), 3 baixos (taxa extrema e faixa do numeric corrigidos; teto de bytes registrado).
Pipeline: verde em todos os checks, o e2e incluído.
Em aberto: nada.

## 2026-10-22 — F13.1: editar e apagar contas e recorrências (PR #21)

O que entrou: `PATCH /contas/{id}` com nome, saldo inicial, limite e conta de pagamento; `DELETE /contas/{id}` que leva tudo da conta e converte a perna de transferência da outra conta em lançamento comum; `GET /contas/{id}/resumo-exclusao`; `PUT`, reativar e `DELETE /recorrencias/{id}`; deadlock (40P01) respondendo 503; no web, editar e apagar conta com confirmação pelo nome, editar, reativar e apagar recorrência com o formulário de criação em modo edição; passo no e2e.
Decisões: ADR-036 (pedido do dono: apagar leva tudo e o arquivar continua). Fora de ADR: o PUT de recorrência recebe o corpo do POST com o mesmo tipo; a conta de pagamento só vai no PATCH quando muda.
Dívida registrada: deadlock entre editar recorrência ou apagar conta e editar ocorrência (responde 503), divisão trocada com parcela realizada no mês, `statement_timeout` no apagar conta, CSRF dos DELETE sem corpo na hospedagem, recorrência inativa em conta arquivada, categoria semente buscada pelo nome.
Achados do QA: 1 bloqueou (contrato da ADR-026 ainda dizia que reativar não existe, corrigido), 2 dívidas (deadlock e divisão, registradas), 5 sugestões (duas registradas, as outras sem ação).
Achados do revisor: 1 bloqueou (apagar conta que pagou fatura desmontava a fatura do cartão; passou a ser recusado), 3 corrigir antes (ordem de travas, reativar com categoria fora da lista dando 500, semente ausente: duas corrigidas, uma registrada), 4 sugestões (duas corrigidas). Security: 0 críticos, 0 altos, 1 médio (lista de ids estourava o limite de parâmetros do driver, trocada por subconsulta e CTE), 3 baixos registrados.
Pipeline: a primeira rodada falhou no `RecurrenceJobTest`, que conferia no banco inteiro que toda ocorrência está na conta do template; o ADR-036 desfez esse invariante e a checagem passou a olhar só os templates do teste. Verde na segunda rodada, o e2e incluído.
Em aberto: nada.

## 2026-10-22 — F13: cripto manual (PR #20)

O que entrou: migration V13 com o tipo `cripto` (sem saldo em reais e recusado em lançamento por FK com coluna gerada), `ativo_cripto` por usuário, `movimento_cripto` e `cotacao`; rotas de ativo, cotação manual, movimento, posições e patrimônio com data; recusa da conta cripto em lançamento, transferência e recorrência; no web, a aba Cripto (patrimônio, posições, movimentos, ativos, aviso de que o app não pede seed), o tipo Cripto em Contas e a conta cripto fora dos selects; passo de cripto no e2e.
Decisões: ADR-035. Fora de ADR: quantidade com mais casas que o ativo é recusada, não arredondada; quantidade aceita vírgula ou ponto sem milhar e o preço usa o formato brasileiro; conta cripto arquivada não recebe movimento e deixa apagar; cotação com momento no futuro é recusada.
Dívida registrada: editar e apagar ativo e cotação, paginação de movimentos, teste de 503 nas rotas novas, patrimônio fora do limite com valor absurdo, conta cripto no escopo da projeção, aviso de conta cripto arquivada, `isCryptoAccount` repetida; custo médio da posição foi para a F15.
Achados do QA: 0 bloquearam, 2 dívidas (patrimônio em 400 com valor absurdo, registrada; "Custo" que é compras menos vendas, rótulo corrigido e custo médio na F15), 3 sugestões (projeção, parâmetro desconhecido e 503, registradas ou já no backlog).
Achados do revisor: 0 bloquearam, 1 corrigir antes (data da cotação em UTC na tela, corrigida), 3 sugestões (duas registradas, um comentário acrescentado). Security: 0 críticos, 0 altos, 0 médios, 2 baixos (teto e paginação, já no backlog; cotação no futuro, recusada na fatia).
Pipeline: verde em todos os checks na primeira rodada, o e2e incluído.
Em aberto: nada.

## 2026-10-21 — F12: benefícios com restrição (PR #19)

O que entrou: migration V12 com o tipo `beneficio`, `conta_categoria_permitida` (FK composta com colunas geradas: só conta de benefício e só categoria de despesa do mesmo usuário) e um gatilho que recusa despesa fora da lista; a regra também na aplicação em lançamento, PUT, recorrência e job; benefício fora de transferência; saldo projetado sem conta escolhida sem benefício; no web, o tipo Vale-benefício com as categorias em checkboxes, o select de categoria filtrado na despesa e o cartão "Benefícios" no Início.
Decisões: ADR-034, com emenda do ADR-029. A regra vale só para despesa e só na escrita; o gatilho trava a conta com FOR SHARE e a troca da lista trava com FOR UPDATE.
Dívida registrada: helpers de constraint repetidos, mensagens novas fora das descrições do OpenAPI, recorrência pulada para sempre quando a categoria sai da lista.
Achados do QA: 0 bloquearam, 0 dívidas, 2 sugestões (texto do invariante 4 corrigido para "no momento da escrita"; lista editável em conta arquivada, inofensivo). 150 checagens, inclusive 180 operações cruzadas de corrida.
Achados do revisor: 0 bloquearam, 2 dívidas (criar benefício sem as categorias carregadas gravava lista vazia, corrigido na fatia; consulta repetida, registrada), 3 sugestões (texto do ADR-034 sobre confirmar e PUT, corrigido; as duas do QA, registradas). Security: 0 críticos, 0 altos, 0 médios, 2 baixos sem ação.
Pipeline: verde nos 9 checks em duas rodadas (o e2e incluído); nenhum alerta do CodeQL.
Em aberto: o backend parou uma vez pelo limite de uso antes de mexer em arquivo; dois laços de espera do PR #18 que nunca terminariam foram encerrados.

## 2026-10-20 — Redesenho visual e e2e em navegador (PR #18)

O que entrou: Playwright 1.63.0 com `make e2e`, que sobe Postgres, API e web descartáveis e roda o fluxo principal em 360px e 1280px com capturas no claro e no escuro, e o job `e2e` no CI fora do ruleset; o web redesenhado com quatro seções por hash, tokens de cor e espaço, modo escuro com seletor, cartões, selos, painéis acessíveis para criar, menu de ações por lançamento, faturas compactas, Início com saldo, projeção e próximas previstas, ícone próprio do app.
Decisões: ADR-033. Sem biblioteca de UI nem de ícones. As capturas não vão para o git.
Dívida registrada: tornar o e2e obrigatório depois de estabilizar; a conferência em navegador deixou de ser dívida.
Achados do revisor: 0 bloquearam, 3 dívidas (foco perdido ao fechar painel aberto pelo menu e painel sem campo sem foco, corrigidas na fatia; duas asserções trocadas por mudança de UI com teste equivalente, aceitas), 2 sugestões sem ação. Security: 0 críticos, 0 altos, 1 médio (trace do Playwright no artifact com credencial de teste, desligado no CI e retenção de 1 dia), 3 baixos sem ação.
Pipeline: o job e2e passou no primeiro run no GitHub; o CodeQL acusou escape incompleto num helper de teste (só o ponto), corrigido escapando todos os metacaracteres.
Em aberto: duas rodadas de revisão nas capturas pelo frontend e uma pelo tech-lead (cartão de destaque no escuro, "ok" do healthz solto na tela, aviso de negativo encoberto pela barra fixa, faturas vazias ocupando a tela, ações ocupando uma linha por item).

## 2026-10-20 — F11: compra parcelada (PR #17)

O que entrou: migration V11 com `parcela_de_id`, `parcela_num` e `parcela_total`, com uma FK composta que garante dono, primeira parcela e total, e uma coluna gerada que garante parcela só em cartão; rotas de compra parcelada (criar, ver, editar texto e categoria, apagar) e as regras de uma parcela só em `/lancamentos`; prévia das parcelas e dos vencimentos no web, "parcela k de N" na lista e as ações por compra e por parcela. A janela do pago passou a incluir o dia do vencimento quando ele cai no mesmo dia do fechamento (dívida da F10).
Decisões: ADR-032. Só cartão e só despesa; todas realizadas; a primeira parcela aponta para si mesma; valor, número de parcelas, data e conta não se editam.
Dívida registrada: `descricao` nula no PATCH da compra.
Achados do QA: 0 bloquearam; 1 dívida (soma da compra acima da faixa do JSON com 200) tratada como contrato violado e corrigida na fatia; 3 sugestões sem ação (título de uma parcela diferente do da compra, documentado; bidi, já na dívida dos invisíveis; ordem das mensagens).
Achados do revisor: 0 bloquearam, 2 dívidas (título e categoria de uma parcela, registrada; a dívida de fevereiro paga e tirada do backlog), 3 sugestões sem ação. Security: 0 críticos, 0 altos, 1 médio (48 linhas por POST, somado ao teto por usuário da F20.5), 2 baixos sem ação.
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: o backend parou uma vez pelo limite de uso, com um laço de espera que nunca terminaria por causa do mvnw do make dev; encerrado sem tocar no make dev. O teste que compara o proxy com a spec pegou a rota nova esquecida.

## 2026-10-20 — F10: cartão de crédito (PR #16)

O que entrou: migration V10 com `cartao` 1:1 e o tipo `cartao_credito`, com o banco garantindo por coluna gerada e FK composta que só conta de cartão tem cartão e que a conta de pagamento é corrente ou poupança; regra da fatura num componente puro (até o fechamento, inclusive; vencimento menor ou igual ao fechamento vira o mês) aplicada em lançamento, PUT, recorrência, divisão e confirmar com ajuste; `GET /contas/{id}/faturas` derivado dos lançamentos; tela de cartão com limite, disponível, faturas e pagar fatura preenchido; data da compra e dica do vencimento nos formulários.
Decisões: ADR-031, completado na fatia: a janela do pagamento passou a ser do fechamento desta ao fechamento da seguinte (com a janela por vencimento, pagar com atraso deixava a fatura aberta para sempre); cartão não pode ser origem de transferência (QA, D1); disponível limitado ao limite; fechamento, vencimento e limite não se editam.
Dívida registrada: lista de lançamentos por competência.
Achados do QA: 0 bloquearam, 1 dívida corrigida na fatia (saque pelo cartão sumia da fatura), 3 sugestões (pagar de qualquer conta, aceito e documentado; logins paralelos, desenho do F1; compra futura já desconta o saldo, ADR-024). Regra da fatura conferida em 984 de 984 compras contra cálculo independente.
Achados do revisor: 0 bloquearam, 1 dívida (vencimento igual ao fechamento em fevereiro, registrada para a F11), 2 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 2 baixos (cartão como origem garantido só na aplicação, registrado; tamanho do nome, coberto pelo DTO de conta).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: o frontend achou e corrigiu dois pontos em que o formulário partia da data de caixa e reenviaria o vencimento como data da compra; os dois têm teste que falha com a regressão. Conferência em navegador das telas em 360px.

## 2026-10-20 — F9: PWA instalável (PR #15)

O que entrou: manifest, ícones gerados de um SVG próprio, service worker com precache só do shell e a denylist do fallback montada da mesma lista de prefixos do proxy; aviso de "Nova versão disponível" com confirmação; metas de app no iOS.
Decisões: ADR-030. Dependências novas: `vite-plugin-pwa` 1.3.0 (dev) e `workbox-window` 7.4.1 (runtime), MIT, versão fixa; a 2.0.0 do plugin ficou para depois. Nenhuma resposta da API vai para cache.
Dívida registrada: nenhuma.
Achados do QA: não houve QA separado; a prova foi o build (sw.js só com assets e a denylist), o preview com os content-types e o dev sem service worker. Instalação em aparelho real fica com o dono.
Achados do revisor: 0 bloquearam, 0 dívidas, 4 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 2 baixos (`Cache-Control` do `sw.js` na hospedagem, registrado; shell velho até aceitar a atualização, aceito). `pnpm audit` sem vulnerabilidade.
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: instalar no Android e no iPhone de verdade.

## 2026-10-20 — Correção: proxy do Vite (PR #14)

O que entrou: o proxy de desenvolvimento passa a repassar os oito prefixos da API, com um teste que compara a lista com o `openapi.yaml` nos dois sentidos.
Por que passou: desde a F2 as telas de dados não funcionavam no `make dev`, porque o proxy só levava `/healthz` e `/auth`. Toda a suíte do web mocka `fetch`, o QA de cada fatia rodou contra a API direto, e ninguém abriu o web de verdade depois da F1. Achado ao ler a config na F9. É a mesma lacuna da dívida de conferência em navegador.
Pipeline: verde na primeira tentativa.

## 2026-10-20 — F8: saldo projetado (PR #13) — fim do MVP

O que entrou: `GET /saldo-projetado` com um ponto por dia de hoje até 1 a 366 dias, realizado e previsto pela data de caixa, vencidas no primeiro dia e somadas à parte, menor saldo; seção no web com gráfico de linha em SVG feito à mão, aviso de negativo em texto e a lista dos dias com movimento.
Decisões: ADR-029, completado na fatia: transferência entre contas do filtro não conta como entrada nem saída. Fora de ADR: "fica negativo em" usa o primeiro dia negativo, não o do mínimo.
Dívida registrada: parâmetro de query desconhecido ignorado (padrão do projeto).
Achados do QA: 0 bloquearam, 0 dívidas, 2 sugestões (uma virou dívida, outra virou texto no contrato). Série conferida centavo a centavo contra cálculo independente em 15 cortes.
Achados do revisor: 0 bloquearam, 1 dívida (custo linear com o histórico, registrada), 2 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 2 baixos (o mesmo custo, coberto pelo rate limit da F20.5; diferença de tempo para conta alheia, desprezível com UUID).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: conferência em navegador das telas em 360px, que segue de antes.

## 2026-10-20 — F7: previsto × realizado (PR #12)

O que entrou: confirmar uma prevista ajustando valor e data no mesmo `PATCH`, com o teto pelo valor novo e a chave da série intacta; `GET /lancamentos/vencidos` com as previstas antes de hoje; bloco "Vencidas" no topo da tela e o confirmar com valor e data preenchidos.
Decisões: ADR-028. Prevista vencida nunca confirma nem cancela sozinha. Ajuste numa parcela de divisão não rebalanceia as outras.
Dívida registrada: nenhuma.
Achados do QA: 0 bloquearam, 0 dívidas, 1 sugestão (soma da divisão depois de ajuste), registrada no contrato.
Achados do revisor: 0 bloquearam, 1 dívida (as duas datas no confirmar com data, anotada na F10), 3 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 2 baixos sem ação.
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: o QA foi interrompido duas vezes porque o computador dormiu; retomado com o mesmo container, contagem refeita do zero. A API do `make dev` do dono seguiu na 8080 o tempo todo.

## 2026-10-20 — F6: divisão de salário (PR #11)

O que entrou: migration V9 com `template_divisao` e `parcela_ordem`, com a chave de idempotência alargada pela ordem da parcela num índice parcial; `divisao` no `POST /recorrencias` mensal, por percentual em centésimos ou por valor fixo, de 2 a 10 dias; regra do centavo num componente puro (trunca todas, a última leva o resto); uma ocorrência por parcela por mês; formulário com soma e valores ao vivo no web pela mesma regra.
Decisões: ADR-027, ajustado durante a fatia pelo dba (o `NULLS NOT DISTINCT` simples faria lançamentos avulsos colidirem, daí o índice parcial). Fora de ADR: parcela de zero centavos é recusada; `ocorrenciasMax` conta meses.
Dívida registrada: leitura do template em duas consultas no `lockTemplate`.
Achados do QA: 0 bloquearam, 0 dívidas, 2 sugestões de texto corrigidas no contrato (espera de cerca de 2 s; 10000 centésimos em `dados.md`).
Achados do revisor: 0 bloquearam, 2 dívidas (1 registrada, 1 só se vier edição de divisão), 3 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 2 baixos sem ação (a divisão não muda a amplificação do job; IP fora do log de criação).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: conferência em navegador das telas em 360px.

## 2026-10-20 — F5: recorrência (PR #10)

O que entrou: migration V8 com regra, template e o lançamento alargado (`template_id`, `desgarrado`, `ocorrencia_data`, status previsto e cancelado); motor de datas puro para diária, semanal, mensal e anual com intervalo, fim e número de vezes; materialização idempotente de hoje a 12 meses na criação e num job diário; confirmar, cancelar e desgarrar ocorrência; `lock_timeout` em toda escrita de dinheiro; tela de recorrências e previstas na lista do mês.
Decisões: ADR-026, emendado depois do QA (o job trava só o template e usa 200 ms de espera). Chave de idempotência em `ocorrencia_data`, desviando de `dados.md`, para editar a data de uma prevista não gerar duplicata. Sem editar regra nem reativar.
Dívida registrada: deadlock como 503, apagar cancelada faz a data voltar, log do job, teto de recorrências por usuário (F20.5), prevista em conta arquivada por corrida, prevista vencida (na F7).
Achados do QA: 2 bloquearam (ciclo de trava do job com PUT, que estourava os 2 s; senha gerada pelo Spring Security no log de arranque, sem uso e sem commit, corrigida e avisada ao dono), 2 dívidas corrigidas junto, 4 sugestões (2 corrigidas, 2 no backlog).
Achados do revisor: 0 bloquearam, 3 dívidas (1 era decisão desta fatia, 2 registradas), 4 sugestões sem ação. Security: 0 críticos, 0 altos, 1 médio (teto por usuário, F20.5), 2 baixos (job ligado por padrão, documentado no `.env.example`; log do 503 de trava).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: interrupção por limite de uso no meio da fatia, retomada sem perda. Conferência em navegador das telas em 360px.

## 2026-10-19 — F4: transferência entre contas (PR #9)

O que entrou: migration V7 com `transferencia` e o `lancamento` alargado para o tipo `transferencia` com categoria nula; o banco garante dono, tipo e contas diferentes por FK composta e um gatilho só para o mesmo lançamento não ser saída de uma e entrada de outra. `POST`, `GET` e `DELETE /transferencias`, pernas com `transferenciaId` e `direcao` na lista e protegidas em `/lancamentos`, saldo com entradas e saídas, tela com formulário de transferência e totais sem ela. O log do 500 deixou de gravar o conteúdo da linha recusada (dívida que apareceu três vezes).
Decisões: ADR-025. Fora de ADR: a transferência vive no pacote `entries` para reaproveitar a trava; `criadaEm` vem da perna de saída; a checagem de perna fica depois da trava do lançamento e antes da trava da conta.
Dívida registrada: idempotência na escrita de dinheiro, mensagem do `POST /lancamentos` com tipo transferência, log do 500 sanitizando demais (paga no começo da F5), `search_path` do gatilho.
Achados do QA: 0 bloquearam, 0 dívidas, 3 sugestões (duas viraram dívida). As correções da F3 foram reconferidas contra a API real.
Achados do revisor: 0 bloquearam, 1 dívida (o log), 3 sugestões. Security: 0 críticos, 0 altos, 1 médio (idempotência, já registrada), 2 baixos (o log e o `search_path`).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: interrupção por limite de uso no meio do backend; retomado sem perda, árvore estava limpa. Conferência em navegador das telas em 360px.

## 2026-10-19 — F3: lançamento realizado (PR #8)

O que entrou: migration V6 com `categoria` e `lancamento` (duas datas, valor positivo, FK composta com `usuario_id` para conta e para categoria com o tipo), sementes de categoria no cadastro e para quem já existia, `GET /categorias`, CRUD de lançamento com listagem por período, saldo da conta somando os realizados, tela do mês no web.
Decisões: ADR-024, emendado na fatia depois do QA (teto do saldo por conta e escrita serializada pela trava da linha da conta). Fora de ADR: `contaId` de outro usuário no filtro devolve lista vazia; mover lançamento para conta arquivada é recusado, editar e apagar em conta arquivada é permitido; a semente roda por evento síncrono na transação do cadastro.
Dívida registrada: `lock_timeout` na trava (F5), tipo da categoria filha (CRUD de categoria), paginação de lançamentos (importação), log do 500 com a exceção inteira (paga no começo da F4, terceira aparição).
Achados do QA: 1 bloqueou (dois lançamentos no teto passavam do limite do JSON, premissa errada no ADR), 1 dívida subiu junto (lançamento gravado em conta arquivada por corrida), 1 corrigido (validador recusava letra fora do plano básico).
Achados do revisor: 0 bloquearam, 2 dívidas (uma registrada, outra sem ação), 3 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 1 baixo (o log do 500).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: conferência em navegador das telas de contas e lançamentos em 360px.

## 2026-10-19 — F2: contas (PR #7)

O que entrou: migration V5 da tabela `conta`; `POST /contas`, `GET /contas` (`?arquivadas=true`), `GET /contas/{id}` e `PATCH /contas/{id}` escopados pelo usuário da sessão, com saldo em SQL (hoje igual ao inicial); tela de contas no web com total, criação e arquivamento, dinheiro sem float. Banco fora dá 503 em qualquer rota e o limite de 4 KiB vale para todas.
Decisões: ADR-023. Fora de ADR: id fora da forma canônica de UUID dá 404; Jackson recusa escalar em campo de texto, float em inteiro e número em enum; tamanho de nome em code points.
Dívida registrada: teste do saldo com lançamentos (na F3), teto de contas e paginação (F20.5), restore do timeout no `HealthRepository` e teste do caminho normal do `SessionQueries`, nome de conta no log de violação de CHECK, invisíveis e "İ" em nome de conta, HTML do Tomcat para `%2F`, chave JSON repetida.
Achados do QA: 1 bloqueou (30 s na primeira chamada com banco congelado, causa no aliveBypassWindow do Hikari; teto de 2 s na consulta de sessão), 5 corrigidos junto, o resto virou dívida.
Achados do revisor: 0 bloquearam, 2 dívidas, 2 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 3 baixos (2 viraram dívida, 1 só registro).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: conferência em navegador da tela de contas em 360px.

## 2026-10-19 — F1.1: tela de cadastro (PR #6)

O que entrou: formulário de cadastro no web contra o `POST /auth/cadastro`, visível só com `VITE_REGISTRO_ABERTO=true`; depois do 201 o web entra com as mesmas credenciais. A API não mudou.
Decisões: ADR-022. Fora de ADR: o 400 mostra o `erro` do servidor, o resto é mensagem genérica.
Dívida registrada: trava no deploy contra `REGISTRO_ABERTO=true` em produção antes da F20.5 (dentro da F20.5).
Achados do revisor: 0 bloquearam. Uma dívida não procedia (o `make dev` repassa a flag do `.env` da raiz, conferido no módulo servido pelo Vite); virou uma linha no README. Três sugestões sem ação. Security: 0 críticos, 0 altos, 1 médio (a trava acima), 2 baixos sem ação.
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios.
Em aberto: teste em navegador real, que segue da F1. Antes desta fatia o dono rodou o seed e entrou no web; o banco local foi recriado com `docker compose down -v` porque o volume era da versão Go, sem os papéis da ADR-021.

## 2026-10-19 — F1: cadastro e login em Java (PR #5)

O que entrou: cadastro fechado por padrão, login com argon2id, sessão por token opaco, `/auth/eu`, logout com revogação, atraso progressivo com reserva atômica, seed, limite de corpo de 4 KiB, `/v3/api-docs` desligado por padrão, papel de banco só DML (V3 e V4), tela de login de volta no web.
Decisões: ADR-020, ADR-021. Fora de ADR: `@UuidGenerator` v7 do Hibernate, `socketTimeout` de 30 s no pool e `validation-timeout` de 1 s (banco congelado), `EnvironmentPostProcessor` para validar `COOKIE_SECURE` antes do Flyway, cookie repetido conta como sem sessão.
Dívida registrada: 500 em vez de 503 nas rotas autenticadas com banco fora (F2), log da exceção inteira no 500, cobertura parcial do osv-scanner, "İ" no e-mail; M1 a M4 da auditoria seguem na F20.5.
Achados do revisor: 0 bloquearam; a escrita no histórico do Flyway virou a V4, o resto já estava no backlog. Security: 0 críticos e 0 altos, 4 médios (todos F20.5), 6 baixos. O alerta de CSRF do CodeQL foi dispensado citando o ADR-020.
QA: 3 achados na primeira rodada (banco congelado, recusa depois do Flyway, cookie repetido), corrigidos e retestados com a API real.
Pipeline: os sete checks verdes na primeira tentativa; o check de resultados do CodeQL falhou pelo alerta de CSRF até a dispensa.
Em aberto: navegador real antes da F2; processo `api` antigo na porta 8080 da máquina do dono.

## 2026-10-19 — Migração do backend para Java 25 e Spring Boot 4 (PR #4)

O que entrou: ADR-016 a 019 e emendas no 005, 009 e 015. `api/` reescrito em
Spring Boot 4.1.1 com `/healthz`, migrations V1 e V2 portadas do goose para o
Flyway, spec OpenAPI gerada pelo springdoc com teste de divergência, CI em
Maven. A F1 em Go e a tela de login foram descartadas; ADR-015 e V2 ficam.
Decisões: ADR-016, ADR-017, ADR-018, ADR-019. Fora de ADR: osv-scanner na
auditoria da api, google-java-format 1.35.0 (1.37.0 quebra no spotless sob Java
25), overrides de Tomcat e Jackson por CVE enquanto o Boot 4.1.1 é a estável,
timeout de 2 s só na consulta do health, web devolvido ao estado da main.
Dívida registrada: renomear `análise estática (javascript-typescript)`;
liveness e readiness no `/healthz` (F20.5); requisitos da F1 vindos da
auditoria (usuário do Flyway separado, `/v3/api-docs` fora de dev, limite de
corpo JSON, CSRF e cabeçalhos); `Healthz` do web por `components`; `.env`
herdado pelo `pnpm install`.
Achados do revisor: 0 bloquearam. 3 "corrigir antes": timeout de consulta
(corrigido), CodeQL pendente (verde), este diário (criado). Security: 0
críticos e 0 altos, 2 médios e 6 baixos; B4 e a tag da spec corrigidos, o resto
virou dívida.
Pipeline: verde na primeira tentativa. Ruleset: `análise estática (go)` saiu
dos obrigatórios antes do push e `análise estática (api)` entrou depois do
primeiro run.
Em aberto: F1 reimplementada em Java é a próxima fatia. Quem tinha o Postgres
local da versão Go precisa de `docker compose down -v`.
