# Relatório QA: F13.1 editar e apagar contas e recorrências (ADR-036)

**Data:** 2026-10-22, noite, horário de São Paulo.
**Branch:** `feat/f13-1-editar-apagar-contas-recorrencias`, PR #21. HEAD testado: `287cfb0`. O HEAD mudou durante a sessão: o commit `287cfb0` ("recusa apagar conta que pagou fatura e trava na ordem dos outros caminhos") entrou às 19:45, no meio do roteiro. Repeti a linha de base e refiz os achados de deadlock e de fatura no jar desse HEAD, com banco zerado. Os números abaixo são desse HEAD.
**Escopo:** `PATCH /contas/{id}`, `DELETE /contas/{id}`, `GET /contas/{id}/resumo-exclusao`, `PUT`, `PATCH` e `DELETE /recorrencias/{id}`, e a regeração das previstas. Cobri o caminho feliz, as bordas, a autorização entre usuários em todas as rotas novas e corridas entre as escritas. Contra a API real.
**Não commitei nada e não alterei arquivo de produção.** Não criei teste temporário no repositório. Os roteiros de curl e os scripts de corrida ficaram em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/4ce6bf65-5d49-45e0-a885-89817918f3e4/scratchpad/`, fora do repositório.
**Ambiente:** Postgres 17 descartável `ballast-qa-f13-1` em `127.0.0.1:55433`, banco `ballast_qa`, com papel de dono e papel de aplicação, como no `db/init`. API em `127.0.0.1:18080` a partir do jar de `./mvnw -B clean verify` no HEAD `287cfb0`, com `REGISTRO_ABERTO=true`, `COOKIE_SECURE=false`, `APP_ENV=development` e job de recorrência desligado. Não usei o banco do compose, a porta 8080 nem o `.env`. Container e API derrubados ao fim.

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` (sem `clean`), duas vezes, com `target/` de antes | Vermelho. Primeira: 1134 testes, 24 erros. Segunda: 1339 testes, 8 falhas, todas em `AccountDeleteTest` com 500 e `MatchException` em `AccountController.delete` |
| `cd api && ./mvnw -B clean verify` no HEAD `287cfb0` | BUILD SUCCESS. 1342 testes, 0 falhas, 0 erros, 0 ignorados |
| `cd web && pnpm test --run` no HEAD `287cfb0` | 25 arquivos, 764 testes, todos passam |
| `cd web && npx tsc -b` | Sem erro |
| `cd web && npx oxlint` | Sem erro. Um aviso de referência triple-slash em `vite.config.ts`, fora da fatia |

Parei na primeira vermelhice e a investiguei. Com `clean`, `AccountDeleteTest` passa 12 de 12, e a suíte inteira fica verde. O vermelho sem `clean` veio de classes compiladas velhas em `target/`, não do código do branch. Isso está registrado como sugestão S5. A primeira rodada também mostrou cascata de "Connection refused" a partir dos testes de banco congelado. Só vi uma vez e não consegui repetir em build limpo.

## Resumo

- **Achados:** 1 Bloqueia, 2 Dívida, 5 Sugestão.
- **Checagens:** centenas de requisições contra a API real, e 76 rodadas de corrida em 12 pares de operações. Nenhum 500 e nenhum `erro não tratado` no log da API em todas as rodadas.
- **Travam primeiro:** B1. O `api.md` contradiz o ADR-036 e o código sobre reativar e sobre editar recorrência. Pelo texto literal do contrato, a API viola o contrato. A correção é apagar três linhas do documento, não mexer em código. Depois vem D1, o deadlock: editar recorrência contra editar ocorrência do mesmo template dá 503 em 5 de 5 rodadas, e o ADR-036 diz que a ordem de travas evita esse ciclo.
- **Recomendação:** **segurar** até corrigir B1, que é um arquivo e poucas linhas. Depois disso, **liberar com dívida registrada**: D1 (deadlock), D2 (divisão que deixa de fechar a soma do mês quando há parcela realizada) e S2 (mensagem de "pagou fatura" que manda arquivar). A decisão é do tech-lead. Se o dono tratar a ordem de travas do ADR-036 como garantia, D1 sobe para bloqueia.

## Achados

### Bloqueia

**B1. `docs/contracts/api.md` contradiz o ADR-036 e o código sobre reativar e editar recorrência.**

- **O que acontece:** a seção "Recorrência (ADR-026)" do contrato ainda diz, nas linhas 122 a 125, que `PATCH /recorrencias/{id}` com `{"ativo": true}` dá 400 `reativar não é suportado`, e que "Não existe `PUT` nem edição da regra". A API faz o oposto: reativa com 200 e tem `PUT /recorrencias/{id}`. A seção do ADR-036 no mesmo arquivo descreve o comportamento que o código tem. Ou seja, o contrato se contradiz e a API viola o trecho literal.
- **Como reproduzir:**
  ```
  # conta C corrente, recorrência R criada em C
  PATCH /recorrencias/R  {"ativo": false}   -> 200
  PATCH /recorrencias/R  {"ativo": true}    -> 200 (contrato ADR-026 diz 400 reativar não é suportado)
  PUT   /recorrencias/R  {corpo do POST}    -> 200 (contrato ADR-026 diz que não existe PUT)
  ```
- **O que deveria acontecer:** o trecho do ADR-026 sai ou aponta para o ADR-036. Um contrato não pode dizer duas coisas sobre a mesma rota.
- **Gravidade:** trava (contrato violado). A correção é textual e pequena. Não sugiro mexer no código.

### Dívida

**D1. Editar recorrência e editar ocorrência do mesmo template se travam e um dos dois volta com 503.**

- **O que acontece:** com o HEAD `287cfb0`, a requisição de editar recorrência e a de editar ocorrência, disparadas juntas, dão 503 para uma delas. No HEAD final, o 503 caiu na edição da recorrência em 5 de 5 rodadas. Numa série anterior de 5 rodadas, caiu na recorrência em 4 e na ocorrência em 1. O mesmo acontece com apagar conta contra editar uma ocorrência dela: `DELETE /contas` deu 503 em 5 de 5 rodadas. O log da API registra `transação desfeita por deadlock` e o log do Postgres registra `deadlock detected`. Nenhum 500, e nada fica gravado pela metade. O mesmo par, fora da corrida, funciona.
- **Evidência no log do Postgres:**
  - Processo A: `DELETE FROM lancamento WHERE usuario_id = $1 AND template_id = $2 AND status = 'previsto' AND NOT desgarrado AND ocorrencia_data >= $3`, que é `deleteFuturePlanned` chamado por `RecurrenceService.update`.
  - Processo B: `UPDATE lancamento SET desgarrado = true WHERE usuario_id = $1 AND id = $2 AND template_id IS NOT NULL`, que é a edição de ocorrência.
  - No caso de apagar conta, o processo A é `SELECT count(*) FROM (SELECT id FROM lancamento WHERE usuario_id = $1 AND conta_id = $2 ORDER BY id FOR UPDATE)` (`AccountDeletionQueries.lockEntries`), e o processo B é o mesmo `UPDATE`.
  - Não fechei a causa linha a linha. O que o log mostra é um ciclo entre essas instruções, e o `DELETE` das previstas não usa a mesma ordem de travas das edições.
- **Como reproduzir:**
  ```
  # conta C corrente. Recorrência T mensal, dia 9, início 2026-10-01, em C, valor 300.
  # Ocorrência O de 2026-12-09 de T (GET /lancamentos?contaId=C&de=2026-11-01&ate=2026-12-31).
  curl -X PUT $BASE/recorrencias/$T -b jar -H 'Content-Type: application/json' \
       -d '{"tipo":"despesa","titulo":"x","valorCentavos":450,"categoriaId":"$CAT","contaId":"$C","regra":{"frequencia":"mensal","diaDoMes":9,"inicio":"2026-10-01"}}' &
  curl -X PUT $BASE/lancamentos/$O -b jar -H 'Content-Type: application/json' \
       -d '{"tipo":"despesa","titulo":"ed","valorCentavos":777,"categoriaId":"$CAT","contaId":"$C","data":"2026-12-09"}' &
  wait
  # resultado: uma das duas responde 503; no log do Postgres, "deadlock detected"
  # repetir 5 vezes com recorrências novas: 5 de 5
  ```
  Para apagar conta: a mesma coisa, com `DELETE /contas/$C` no lugar do `PUT` da recorrência e uma ocorrência da conta no lugar de `O`.
- **O que deveria acontecer:** as duas requisições seriam serializadas com 200 nas duas, ou a segunda esperaria a primeira sem chegar a 503. O ADR-036 diz em "Ordem de travas" que "apagar as previstas antes de travar a conta evita o ciclo com editar ocorrência". O commit `287cfb0` diz "trava na ordem dos outros caminhos", e o `RecurrenceQueries.deleteFuturePlanned` não mudou nesse commit.
- **Gravidade:** erra em silêncio no sentido de que o usuário vê "serviço indisponível" sem motivo, mas a escrita é desfeita por completo e repetir resolve. Não há perda de dado. A probabilidade real depende de duas ações sobrepostas no mesmo template, por exemplo duas abas. Classifico como dívida de prioridade alta, porque a garantia que o ADR anuncia não se sustenta para esse par.

**D2. Trocar a divisão depois que uma parcela foi realizada deixa a soma do mês diferente do total da divisão, sem aviso.**

- **O que acontece:** a divisão tem 2 parcelas, 50% em dia 5 e 50% em dia 15, de R$ 1.000. Confirmei a parcela 1 de novembro (R$ 500). Depois troquei para 3 parcelas, 33,33% em dia 5, 33,33% em dia 15 e 33,34% em dia 25. Novembro ficou com a parcela 1 realizada de R$ 500, a parcela 2 prevista de R$ 333,30 e a parcela 3 prevista de R$ 333,40. Soma: R$ 1.166,70 para uma divisão de R$ 1.000. A regeração não criou a parcela nova de dia 5, porque a chave `(template_id, ocorrencia_data, parcela_ordem)` bloqueia a data que ficou com a parcela realizada.
- **Como reproduzir:**
  ```
  POST /recorrencias  divisao 2 partes (5000 em dia 5, 5000 em dia 15), valor 100000, regra mensal inicio 2026-10-01
  PATCH /lancamentos/{parcela ordem 1 de 2026-11-05} {"status":"realizado"}
  PUT /recorrencias/{id}  divisao 3 partes (3333 dia 5, 3333 dia 15, 3334 dia 25)
  GET /lancamentos?contaId=C&de=2026-11-01&ate=2026-11-30
  # parcelas: [1 realizado 50000], [2 previsto 33330], [3 previsto 33340]. Soma 116670.
  ```
- **O que deveria acontecer:** o comportamento é coerente com "realizado fica como está" e com a regra de chave do ADR-036. O que falta é o contrato dizer o que a divisão faz com o mês nesse caso, ou o produto decidir outra saída, por exemplo gerar a parcela de dia 5 com o novo valor no lugar da realizada. Hoje nada avisa o usuário de que o mês não fecha.
- **Gravidade:** erra em silêncio. O número está somado errado para quem lê a lista do mês.

### Sugestão

**S1. Editar recorrência cuja conta está arquivada é recusado mesmo sem trocar de conta.**

- **O que acontece:** `PUT /recorrencias/{id}` com a mesma conta, arquivada, responde 400 `conta arquivada`. Para corrigir o valor ou o título, o usuário precisa desarquivar a conta antes.
- **Como reproduzir:** criar recorrência em C, `PATCH /contas/C {"arquivada": true}`, depois `PUT /recorrencias/R` com `contaId = C` e outro valor. Observado: 400 `conta arquivada`.
- **O que deveria acontecer:** o contrato diz que o `PUT` tem as mesmas validações do `POST`, e o `POST` recusa conta arquivada, então o comportamento segue o texto. Vale decidir se a recusa deve valer só para troca de conta, como a regra de lançamento do contrato ("no PUT, a recusa por conta arquivada vale só ao trocar de conta"). Hoje a regra de lançamento e a de recorrência não são iguais.
- **Gravidade:** incomoda.

**S2. A mensagem de "conta pagou fatura" manda arquivar, e arquivar não permite apagar.**

- **O que acontece:** `DELETE /contas/{id}` de conta que pagou fatura de cartão por transferência responde 400 `esta conta pagou fatura de cartão; apagar desmontaria a fatura. Arquive a conta`. Arquivei a conta e a mesma recusa voltou. A única saída que apaga a conta é apagar o cartão antes. Confirmei: depois de apagar o cartão, a conta pagadora apagou com 204.
- **Como reproduzir:** conta P corrente, cartão K com `contaPagamentoId` diferente de P, transferência de P para K de R$ 100, `DELETE /contas/P` (400), `PATCH /contas/P {"arquivada": true}` (200), `DELETE /contas/P` (400 de novo).
- **O que deveria acontecer:** a mensagem diz que arquivar é a alternativa para esconder a conta, sem sugerir que ela vira apagável. Ou o texto deixa claro que o apagar só sai depois de apagar o cartão.
- **Gravidade:** incomoda. Não há dano de dado. A fatura continua paga e a recusa não grava nada.
- **Observação:** o ADR-036 que li no início da sessão não tinha essa recusa. O commit `287cfb0` a acrescentou durante a sessão, e o `api.md` agora a descreve.

**S3. A espera por trava do contrato não lista `PUT` e `DELETE` de recorrência nem `DELETE` de conta.**

- **O que acontece:** a seção "Espera de trava" lista `POST` e `PATCH` de `/recorrencias`, mas não `PUT` nem `DELETE`. Também não lista `DELETE /contas/{id}`. Essas três rotas travam e respondem 503 ao estourar o prazo, como as outras, e o D1 mostra isso na prática.
- **Como reproduzir:** ler `docs/contracts/api.md`, seção "Espera de trava", e comparar com os métodos que travam em `RecurrenceController` e `AccountController`.
- **O que deveria acontecer:** a lista do contrato cobre todas as rotas de escrita de conta e recorrência que travam.
- **Gravidade:** incomoda. Documentação.

**S4. `DELETE /contas/{id}` aceita UUID em maiúscula e apaga a conta.**

- **O que acontece:** `DELETE /contas/<uuid em maiúscula>` responde 204 e apaga a conta. O contrato diz "id que não é UUID dá 404", e o `/lancamentos` diz "forma canônica". A `CanonicalUuid` aceita maiúscula, em todas as rotas. Isso é comportamento antigo e atinge o app inteiro, não é nova desta fatia. Não há risco de dono: a busca continua filtrada por `usuario_id`.
- **Como reproduzir:** criar conta C, `DELETE /contas/{id de C em maiúscula}` com a sessão do dono.
- **O que deveria acontecer:** uma decisão escrita sobre se o id é canônico minúsculo ou qualquer caixa. Hoje o contrato e o código não concordam sobre a palavra.
- **Gravidade:** incomoda. Não é furo de autorização.

**S5. A suíte da API só é confiável com build limpo.**

- **O que acontece:** `./mvnw -B verify` sem `clean`, em cima de `target/` de compilação anterior, deu duas vezes resultados vermelhos sem relação com o código: 24 erros e 8 falhas com `MatchException` no `delete` do controller. Com `clean`, o mesmo código passa. A primeira rodada também teve cascata de conexão recusada depois dos testes de banco congelado, e não consegui repetir isso em build limpo.
- **Como reproduzir:** `./mvnw -B verify` duas vezes seguidas em um checkout que já tem `target/` de outro commit.
- **O que deveria acontecer:** a suíte passar de forma igual com ou sem `target/` antigo, ou o `make test` rodar `clean`. Os testes de banco congelado não deveriam derrubar o container compartilhado da JVM.
- **Gravidade:** incomoda. Atrapalha o diagnóstico de quem roda localmente, não o produto.

## Checagens

Todas contra a API real no HEAD `287cfb0`. Os achados estão na seção anterior.

- **Apagar conta com transferências nos dois sentidos:** X com R$ 930,00 e Y com R$ 570,00 antes. Apagar X dá 204 e Y continua em R$ 570,00. A perna de entrada virou receita em "Outras receitas" de R$ 100,00, e a de saída virou despesa em "Outros" de R$ 30,00. Confirmado também com seis e oito rodadas em paralelo com transferência para X.
- **Cartão com parcelada e pagamento de fatura:** C com R$ 2.000,00 pagou fatura de R$ 100,00 para K. `DELETE` de C dá 400 `esta conta paga um cartão`. `DELETE` de K dá 204, e C continua em R$ 1.900,00. Fatura de 20/10 com `pagoCentavos` correto antes da exclusão.
- **Conta que pagou fatura (commit 287cfb0):** `DELETE` dá 400 com a mensagem do S2. A fatura continua `paga` e o saldo da conta pagadora não muda.
- **Conta cripto com movimento:** `resumo-exclusao` mostra 1 movimento. Apagar dá 204, o movimento some e o ativo fica em `GET /ativos-cripto`.
- **Conta de benefício com lançamento:** `resumo-exclusao` mostra 1 lançamento. Apagar dá 204 e a lista de categorias do usuário fica intacta.
- **Recorrência editada, ocorrências intactas:** a realizada de novembro, que nasceu com R$ 2.500,00, continua com esse valor depois de a recorrência ir para R$ 3.000,00. A cancelada de dezembro continua cancelada. A desgarrada de janeiro continua com o valor editado à mão (R$ 999,00). O saldo da conta R1 fica em -R$ 2.500,00, que é a parcela realizada.
- **Vencida:** previsto de 2026-10-03 (simulado com `UPDATE` direto no banco, porque o job não gera data passada) sobrevive ao `PUT` e some no `DELETE` da recorrência, com 404 depois.
- **Ocorrência movida para outra conta:** ocorrência movida de R1 para R2 continua ligada ao template depois de o template ir para R2. A regeração pula a data dela: 11 ocorrências novas em vez de 12. Apagar R3 (conta do template) com uma ocorrência movida para R4 deixa a ocorrência em R4 como lançamento avulso, sem `templateId`.
- **Divisão:** 3 partes de 33,33% de R$ 1.000,00 geram R$ 333,30, R$ 333,30 e R$ 333,40, que fecham em R$ 1.000,00 todo mês. Recusa de valor que não soma o total com 400 `os valores das parcelas devem somar o valorCentavos`. Apagar a recorrência com parcela realizada: a parcela fica como avulsa e o saldo não muda.
- **Troca de conta por recorrência:** para cripto, arquivada, benefício com categoria fora da lista e conta inexistente, 400 com a mensagem do contrato, sem gravar. Benefício com categoria dentro da lista e cartão aceitos.
- **Reativar:** desativar, depois reativar, repete e não duplica nenhuma data. `proximaOcorrencia` correto. Editar recorrência inativa não gera nada até reativar. Reativar em conta arquivada dá 400 `conta arquivada`.
- **Benefício com categoria removida da lista:** reativar e editar com a categoria fora dão 400 `categoria não permitida nesta conta`, sem ocorrência gerada. Antes do commit `287cfb0` esse caso dava 500, e agora dá 400.
- **PATCH de conta:** corpo vazio e com todos os campos nulos dão 400 `informe ao menos um campo para alterar`. Nome só de espaço, vazio e com 101 caracteres dão 400. Nome repetido dá 409, também com caixa diferente. Saldo inicial `9007199254740991` aceito, `9007199254740992`, decimal, string e campo `tipo` dão 400. Saldo que estoura o limite com uma receita já lançada dá 400 `saldo da conta fora do limite`, e nada muda (saldo inicial continua 0). `cartao` em conta corrente dá 400. `cartao` com `diaFechamento` dá 400 (campo não editável). Limite zero dá 400. Conta de pagamento de outro usuário, malformada, ou cartão, dá 400 `conta de pagamento não encontrada` ou `deve ser uma conta corrente ou poupança ativa`. Mudar só o limite mantém a conta de pagamento. Cripto com saldo diferente de zero dá 400 `conta cripto não tem saldo em reais`.
- **Autorização, usuário B contra recursos de A:** `PATCH /contas/A`, `GET /contas/A/resumo-exclusao`, `DELETE /contas/A`, `PUT`, `PATCH` e `DELETE /recorrencias/A`, todos com 404 `não encontrado`. Um id inexistente tem a mesma resposta. B editando a própria recorrência com conta de A ou categoria de A recebe 400 `conta não encontrada` e `categoria não encontrada`, sem gravar, e A continua vendo a recorrência intacta.
- **Corridas, sem 500:**
  - Apagar conta contra transferência para ela (6 + 8 rodadas): sempre 204 com 201 da transferência, ou 400/404 do outro lado. Saldo da outra conta igual nos dois casos.
  - Apagar conta contra novo lançamento nela (3 rodadas): 204 e 201 em todas. A conta some e o lançamento vem junto.
  - Apagar conta contra confirmar ocorrência dela (3 rodadas): 204 e 200 em todas.
  - Apagar conta contra mover a recorrência para outra conta (3 rodadas): 204 com o template movido intacto, ou 404 se o apagar veio primeiro. Sem previsto órfão.
  - Apagar recorrência contra editá-la (5 rodadas): 204 do `DELETE` com 404 do `PUT` em 4 rodadas, e 200 do `PUT` com 204 do `DELETE` em 1. Nenhuma sobra.
  - Apagar recorrência contra confirmar ocorrência (5 rodadas): 204 e 200, com a ocorrência realizada mantida como avulsa.
  - Editar recorrência contra confirmar ocorrência do mesmo template (5 rodadas): 200 e 200 em todas.
  - Editar recorrência contra apagar lançamento realizado do mesmo template (5 rodadas): 200 e 204 em todas.
  - Cripto: apagar conta contra novo movimento (4 rodadas): 204 em todas. O movimento deu 201 em 3 delas e 400 em 1, sem erro.
  - Apagar conta contra arquivar (4 rodadas): 204 com 200. Sem erro.
- **Entrada:** `PUT` com campo extra, valor zero, negativo, decimal, string, acima do teto, corpo acima de 4 KiB (413), título com caractere de controle, título de 101 caracteres, título de 100 caracteres com emoji aceito, início 2026-02-30, `diaDoMes` com `divisao`, `ocorrenciasMax` com `fim`, mensal sem `diaDoMes`: todos com 400 ou 413, com a mensagem do contrato. `PATCH` de recorrência com `ativo` string, `ativo` nulo, corpo vazio ou com campo extra: 400. `ocorrenciasMax` 3 com início antes de hoje gera 2, como o contrato diz.
- **Logs:** nenhuma linha de nível `ERROR` e nenhum `erro não tratado` no log da API durante toda a sessão. A exceção de deadlock (D1) aparece como `WARN` com o texto do Postgres no stack trace. As demais `WARN` são recusas normais.

## O que passou

- Apagar conta com transferência nos dois sentidos, cartão com parcelada e pagamento, cripto com movimento e benefício: saldo das outras contas igual antes e depois.
- Recusa de apagar conta de pagamento de cartão, ativa ou arquivada.
- Edição de recorrência que preserva realizada, cancelada, desgarrada e vencida.
- Divisão de salário que fecha em R$ 1.000,00 em todo mês quando não há parcela realizada.
- Troca de conta e de tipo, e recusas de cripto, arquivada e categoria fora da lista do benefício.
- Reativar repetível e sem duplicar.
- Autorização entre usuários em todas as rotas novas, com respostas iguais às de id inexistente.
- Limites de entrada, com as mensagens do contrato.
- Corridas de apagar conta e de editar recorrência sem 500 e sem sobra de dado.

## Não testado

- **Job diário de recorrência.** Subi com `BALLAST_RECURRENCE_JOB_ENABLED=false`. Não rodei o job para verificar a regra de "benefício com categoria removida enquanto a recorrência está ativa". Essa parte do contrato ficou sem prova.
- **Interface no navegador.** Não rodei o web contra a API. Li `Contas.tsx` e `Recorrencias.tsx` nos pontos de envio (PATCH com saldo, divisão, PUT, confirmação de apagar) e não achei defeito. A suíte do web passou, mas não é prova de tela.
- **Vencida real.** Simulei a data passada com `UPDATE` no banco descartável. O comportamento de quem gerou a vencida pelo job não foi visto.
- **Estatística das corridas.** Cada par foi rodado de 3 a 14 vezes, não em centenas de rodadas. "Sem 500" é observado nessas rodadas. O 503 de D1 foi 5 de 5, e vale a pena rodar mais se o tech-lead quiser número.
- **Conta de cartão como destino de recorrência com divisão.** O contrato permite. Não verifiquei a soma da fatura com parcela de divisão em cartão.
- **Apagar recorrência contra apagar conta.** Não rodei esse par.

## Como reproduzir

```
cd /Users/mac/Programacao/meuApp/ballast/api && ./mvnw -B clean verify
# ambiente: Postgres 17 descartável, papéis de dono e de aplicação como no db/init
# API: jar de api/target, com DATABASE_URL, DB_USER, DB_PASSWORD, DB_MIGRATION_USER, DB_MIGRATION_PASSWORD,
#      REGISTRO_ABERTO=true COOKIE_SECURE=false APP_ENV=development BALLAST_RECURRENCE_JOB_ENABLED=false
# roteiro de curl e corridas: scratchpad (fora do repositório). Achados D1 e D2 e B1 têm passos acima.
```
