# Relatório QA: F13.3 cripto em dólar (ADR-038)

**Data:** 2026-10-22, noite, horário de São Paulo.
**Branch:** `feat/f13-3-cripto-em-dolar`, PR #23. Testei dois HEADs. O primeiro é `b2bd6c3`, que era o HEAD quando comecei. O segundo é `d80a0c1` ("fix: busca a PTAX só quando falta, limita o custo em dólar e garante a faixa no banco"), que entrou na branch durante a sessão, às 22:17, depois de eu ter congelado `b2bd6c3`. Os achados valem para `d80a0c1`. Na seção "Corrigidos" digo o que `d80a0c1` resolveu e o que continua aberto.
**Escopo:** conversão USD para BRL e BRL para USD contra conta feita à mão, PTAX da data do movimento no custo e PTAX da data da posição no valor, HALF_EVEN uma vez por moeda, posição mista (linhas da F13 em reais com movimentos novos em dólar), sem PTAX nenhuma, PTAX chegando depois de movimentos, cotação antiga em reais, patrimônio com `semCotacao`, limites do JSON, venda que zera posição, contrato (campos novos, campos antigos recusados), autorização entre usuários em todas as rotas de cripto, corridas, BCB mudo e lento, e a web (leitura de código e vitest). Não rodei o navegador.
**Não commitei nada e não alterei arquivo de produção.** Não criei teste no repositório. O roteiro, a conta à mão e as saídas ficaram em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/4ce6bf65-5d49-45e0-a885-89817918f3e4/scratchpad/f13-3/qa/` (`probes.py`, `run_final.txt` para `b2bd6c3`, `run_d80a0c1.txt` para `d80a0c1`, `resultados.json`). A cópia testada de cada SHA está em `.../scratchpad/f13-3/` e `.../scratchpad/f13-3b/`, feita com `git archive`.
**Ambiente:** Postgres 17 descartável `ballast-qa-f13-3` em `127.0.0.1:55435`, banco `ballast_qa_f133`, papel da aplicação `ballast_app` criado pelo init da cópia. API a partir do jar de `./mvnw -B clean verify` de cada SHA, em `127.0.0.1:18085`, com `REGISTRO_ABERTO=true`, `COOKIE_SECURE=false`, `APP_ENV=development`, `BALLAST_INDICES_JOB_ENABLED=false` e `BALLAST_RECURRENCE_JOB_ENABLED=false`. `BALLAST_INDICES_BASE_URL` apontou para um servidor local que imita o SGS (`fake_bcb.py`), com modos `erro` (500), `hang` (nunca responde) e `ok` (PTAX só de 2020-01-06 a 2024-06-02). Não chamei a API real do BCB. Não usei o banco do compose, a porta 8080 nem o `.env`. A PTAX de 2024-06-03 a 2026-10-22 foi gravada direto em `indice` (serie `dolar`): 2024-06-03 = 5,0500; 2026-10-01 = 5,2000; 2026-10-20 = 5,4000; 2026-10-21 = 5,4321; 2026-10-22 = 5,5555. Container parado ao fim.

## Linha de base

| Comando | Resultado |
|---|---|
| `./mvnw -B clean verify` em `d80a0c1` (export `git archive`) | BUILD SUCCESS. 1417 testes, 0 falhas, 0 erros |
| `./mvnw -B clean verify` em `b2bd6c3` | BUILD SUCCESS. 1415 testes, 0 falhas, 0 erros |
| `./mvnw -B spotless:check` em `d80a0c1` e em `b2bd6c3` | Sem erro |
| `pnpm install --frozen-lockfile && pnpm test --run` em `d80a0c1` | 787 testes, 27 arquivos, todos passam |
| `npx tsc -b` em `d80a0c1` | Sem erro |
| `npx oxlint` em `d80a0c1` | Sem erro. Um aviso de referência triple-slash em `vite.config.ts`, fora da fatia, igual ao da F13.2 |

A suíte estava verde nos dois SHAs. Os testes unitários da cripto em dólar (`DollarConversionTest`, `DolarMigrationTest`, `NetWorthTest`, `CryptoQuoteTest`) passam, mas nenhum deles cobre a cotação antiga em reais sem PTAX (achado B1).

## Resumo

- **Achados em `d80a0c1`:** 1 bloqueia, 2 dívidas, 3 sugestões. Dos achados de `b2bd6c3`, dois estão corrigidos (custo fora do seguro e `POST` com custo nulo), e um está corrigido em parte (latência, ver D1). Um problema conhecido da F13 (backlog) foi reconfirmado e não entra como novo.
- **Travam primeiro:** B1. A cotação antiga em reais (movimento e cotação gravados na F13, em reais) com PTAX ausente ganha `valorCentavos` e entra no total do patrimônio. O contrato diz `null` e `semCotacao`. Reproduzido nos dois SHAs. Não há teste que cubra esse caminho: o teste existente só cobre cotação em dólar.
- **Depois:** D1, o lançamento espera 15 s quando o BCB não responde e ainda não há PTAX nenhuma, ou quando a data é anterior à primeira PTAX. D2, a posição e o patrimônio inteiros viram 400 com cotação de 1e11 vezes quantidade de 1000 (backlog F13 D1, reconfirmado).
- **Conta à mão:** 140 de 143 checagens do roteiro passam em `d80a0c1`, e as 3 que falham são todas de B1. Conversão, HALF_EVEN, PTAX da data do movimento e da posição, posição mista, venda que zera, limites e autorização batem com a conta à mão.
- **Recomendação:** **segurar** até corrigir B1, que é código ou texto do contrato, decisão do dono. Depois, **liberar com dívida registrada**: D1 e D2 no backlog com dono, e S1 a S3 como sugestão. Se o dono ler "cotação fora do ar não trava lançamento" como contrato, D1 sobe para bloqueia. A decisão é do tech-lead.

## Achados em `d80a0c1`

### Bloqueia

**B1. Cotação antiga em reais sem PTAX ganha valor em reais e entra no patrimônio. O contrato diz `null` e `semCotacao`.**

- **Classificação:** bloqueia (contrato violado no texto de `docs/contracts/api.md`, seção "Cripto em dólar (ADR-038)": "Sem PTAX até a data, `valorCentavos` é `null` e a posição entra em `semCotacao` do patrimônio, fora do total"). Gravidade: erra em silêncio. O valor entra no total sem nenhum aviso de falta de dólar.
- **O que acontece:** `NetWorthService.positions` calcula o valor em reais de uma cotação em reais como `value` direto (`brl = inUsd ? convert(...) : value`), sem olhar a PTAX. Então a posição com cotação em BRL e sem PTAX tem `valorCentavos` preenchido, `valorUsdCentavos` nulo, não aparece em `semCotacao` e soma em `criptoCentavos` e `totalCentavos`. Na tela, o valor aparece grande em R$ no card da posição, e o aviso logo acima diz "Ainda sem dólar do Banco Central: os valores ficam só em US$ e fora do patrimônio". Os dois dizem coisas diferentes. Li o código de `Cripto.tsx` para isso, não renderizei.
- **Como reproduzir:** o estado vem da F13 (linhas em reais). A API atual só grava em dólar, então a linha entra por SQL, como a migração deixa as linhas da F13. Com nenhuma PTAX em `indice`:
  ```
  # conta cripto C de A, ativo TOK (base, contrato 0x111...), 10 TOK
  INSERT INTO cotacao (usuario_id, ativo_id, momento, preco, fonte, moeda)
    VALUES (<A>, <TOK>, '2026-10-21 12:00:00-03', 250.00, 'manual', 'BRL');
  INSERT INTO movimento_cripto (id, usuario_id, conta_id, ativo_id, tipo, quantidade, data, custo, moeda)
    VALUES (gen_random_uuid(), <A>, <C>, <TOK>, 'compra', 10, '2026-10-01', 2000.00, 'BRL');
  GET /posicoes-cripto?data=2026-10-22   -> dolar null; TOK: valorCentavos 250000, valorUsdCentavos null
  GET /patrimonio?data=2026-10-22        -> criptoCentavos 250000, totalCentavos 250000, semCotacao sem TOK
  ```
  Verificado em `b2bd6c3` e em `d80a0c1` (`probes.py`, fase 0, as 3 checagens "TOK ...").
- **O que deveria acontecer:** `valorCentavos` nulo e TOK em `semCotacao`, fora do total, como o contrato diz. Ou o contrato muda para dizer que a cotação em reais vale sem PTAX, e o aviso da tela muda junto. O ADR-038 diz "sem dólar guardado não há valor em reais", então a primeira opção é a que o ADR escreve. A escolha é do dono.
- **Gap de teste:** `DollarConversionTest.withoutPtaxTheDollarValueStaysAndTheReaisValueIsLeftOut` cobre só cotação em dólar. Falta um teste com cotação em reais e sem PTAX.

### Dívida

**D1. Lançamento espera 15 s com o BCB mudo, quando ainda não há PTAX nenhuma ou quando a data é anterior à primeira PTAX.**

- **Classificação:** dívida, prioridade média-alta. Gravidade: incomoda. O lançamento conclui com 201, mas segura a thread HTTP por 15 s. A regra de CLAUDE.md "Cotação: API fora do ar não trava lançamento" não se cumpre nesses dois casos.
- **O que acontece:** o `d80a0c1` corrigiu o caso comum: com PTAX guardada e data recente, o lançamento leva 0,01 s com o BCB mudo (era 15 s em `b2bd6c3`). Mas `MovementService.create` ainda chama `indices.updateFrom` em duas situações:
  - sem nenhuma PTAX guardada (`firstDollarDate` vazio, `orElse(true)`): cada lançamento tenta CDI, IPCA e PTAX, com 5 s de timeout cada. Medido 15,1 s.
  - data anterior à primeira PTAX: 15,05 s, com o BCB mudo.
- **Como reproduzir:** `BALLAST_INDICES_BASE_URL` apontando para um servidor que aceita a conexão e não responde. Caso 1: banco sem linha `dolar`, `POST /movimentos-cripto` com data de hoje. Caso 2: com PTAX a partir de 2024-06-03, `POST` com data 2019-06-03. Os dois medidos em `probes.py`, fase 0 e fase 5.
- **O que deveria acontecer:** a busca sai da requisição, como o backlog da F13.2 (D3) já pede, ou a PTAX de data antiga é buscada em segundo plano, com o valor em reais preenchido na próxima leitura. O ADR-038 diz "ao gravar um movimento com data anterior à primeira PTAX", e isso bate com o caso 2, não com o caso 1.

**D2. Cotação grande vezes quantidade grande faz `GET /posicoes-cripto` e `GET /patrimonio` responderem 400 para o usuário inteiro.**

- **Classificação:** dívida. Já está no backlog como "DÍVIDA ... (QA da F13, D1). ... Dono: backend, na F14". Reconfirmado em `d80a0c1`, não é achado novo.
- **O que acontece:** o teto novo do custo (1,8e12 dólares) só cobre o custo. O valor da posição é quantidade vezes cotação, e a cotação aceita 12 dígitos inteiros. Com cotação `100000000000` e compra de 1000 unidades, a posição passa do limite em reais e a lista e o patrimônio inteiros respondem 400 `patrimônio fora do limite`. A mensagem também é do patrimônio para `/posicoes-cripto` (ver S3).
- **Como reproduzir:** conta cripto e ativo sem outra posição. `POST /ativos-cripto/{id}/cotacoes {"precoUsd":"100000000000"}` (201). `POST /movimentos-cripto` com `quantidade "1000"`, `custoUsdCentavos 100`, data de hoje (201). `GET /posicoes-cripto?data=2026-10-22` responde 400. `DELETE` do movimento devolve a lista ao normal.
- **O que deveria acontecer:** a conferência do valor com a última cotação na escrita, ou devolver a posição sem valor. Já é o que o backlog pede.

### Sugestão

**S1. Cotação antiga em reais sai com 34 dígitos em `precoUsd`, e a API não aceita de volta o que ela mesma devolve.**

- **Classificação:** sugestão. Gravidade: incomoda. Nenhuma regra do contrato é violada, mas o arredondamento de saída (CLAUDE.md, "Arredondamento acontece uma vez, na borda de saída") não se aplica a esse campo.
- **O que acontece:** `PositionResponse.from` divide o preço em reais pela PTAX com `MathContext.DECIMAL128`, e a saída fica com 34 dígitos significativos. Exemplo: `precoUsd` `45.000450004500045000450004500045`. A web mostra `US$ 45,000450004500045000450004500045`. `lerPreco` recusa o texto (mais de 8 casas), e `POST /ativos-cripto/{id}/cotacoes` com esse valor responde 400 (verificado com `probes.py`, fase 7).
- **O que deveria acontecer:** arredondar `precoUsd` derivado para 8 casas com HALF_EVEN na saída, que é o limite de entrada do próprio campo.

**S2. ADR-038 não diz qual PTAX vale para um movimento de hoje antes de a PTAX de hoje sair. O custo em reais muda sozinho quando ela sai.**

- **Classificação:** sugestão, decisão de produto e de ADR. Gravidade: erra em silêncio, para o usuário, se ninguém souber da regra.
- **O que acontece:** a compra de 0,01 BTC por US$ 100,00 em 2026-10-22, gravada antes da publicação da PTAX de hoje, responde `custoCentavos` 54321 (PTAX de 21/10, 5,4321). Quando a PTAX de 22/10 entra, a mesma compra lê 55555 (5,5555) sem ninguém tocar nela. O contrato diz "pela PTAX da data do movimento". Antes da publicação, a PTAX da data não existe, e o código usa a anterior sem o contrato dizer isso.
- **Como reproduzir:** `probes.py`, fase 4: apagar a linha `dolar` de 2026-10-22, `POST` compra de hoje, inserir a linha de 22/10, `GET /movimentos-cripto`.
- **O que deveria acontecer:** o ADR escolhe uma das duas: congelar o custo na gravação, ou dizer que o custo acompanha a PTAX até ela sair. Também pode dizer que o `POST` de hoje devolve `null` até a publicação.

**S3. `GET /posicoes-cripto` com limite estourado responde "patrimônio fora do limite".**

- **Classificação:** sugestão. Gravidade: incomoda. A mensagem é a da rota de patrimônio, e quem chama `/posicoes-cripto` não sabe o que estourou.
- **O que deveria acontecer:** mensagem própria da rota de posições, junto com a correção de D2.

## Corrigidos em `d80a0c1`

- **C1. Custo grande em reais estourava o JSON e quebrava a lista de movimentos na web.** Em `b2bd6c3`, `custoUsdCentavos` ia até 9007199254740991, e um custo de 9e15 centavos de dólar virava 5,0e16 centavos de real. `GET /movimentos-cripto` devolvia esse número, `isMovimentoCripto` na web recusava (`Number.isSafeInteger` é falso) e `fetchMovimentosCripto` lançava erro, deixando a lista de movimentos inteira em erro. Verifiquei com um teste temporário em vitest na cópia de `b2bd6c3`: a lista falha. Em `d80a0c1`, o teto do custo é `180143985094819` (`MAX_CENTS / 50`, com PTAX máxima de R$ 50). Com o custo no teto, o custo em reais sai em 1000789909194267 centavos (R$ 10.007.899.091.942,67), dentro do inteiro seguro. Acima do teto, 400 com a mensagem nova. Verificado no roteiro, fase 3.
- **C2. Lançamento antigo respondia `custoCentavos` nulo com a PTAX já gravada.** Em `b2bd6c3`, a resposta do `POST` era montada antes do `updateFrom`, e o `GET` logo depois mostrava o valor (40 no caso de teste). Em `d80a0c1`, a resposta é relida depois da busca. Verificado: `POST` de compra em 2020-01-06 responde `custoCentavos` 40.
- **C3. PTAX fora da faixa entrava no banco.** Em `d80a0c1`, o CHECK `indice_dolar_faixa` recusa valor fora de 0,50 a 50 (V15). Verificado: `INSERT` de 0,10 falha.
- **C4 (parcial). Busca de PTAX a cada lançamento.** O `d80a0c1` busca só quando a data é anterior à primeira PTAX. Com PTAX guardada e data recente, o lançamento com BCB mudo caiu de 15 s para 0,01 s. O resto está em D1.
- **C5. Cotação em reais: a web mostra o custo em reais mesmo sem PTAX.** Mudança em `Cripto.tsx` no `d80a0c1`. O custo em reais aparece quando não há custo em dólar. Não verifiquei na tela, só li o código. A suíte web passa com a mudança.

## Já registrado no backlog, reconfirmado

- D2 acima: "Um movimento de cripto aceito pela escrita pode deixar `/patrimonio` e `/posicoes-cripto` em 400 ..." (QA da F13, D1).
- Cotação e ativo não se editam nem se apagam (backlog da F13).
- `GET /movimentos-cripto` sem paginação (backlog da F13).

## Checagens

Roteiro `probes.py`, 143 checagens em `d80a0c1`: 140 passam, 3 falham (todas em B1). Em `b2bd6c3`, 138 checagens: 133 passam, 5 falham (B1 em 3, D3 e D1 em 1 cada). O que o roteiro cobriu:

- **Cotação:** resposta com exatamente `{ativoId, momento, precoUsd, fonte}`. Recusa de `preco` (campo antigo), `precoUsd` numérico, com vírgula, zero, 13 dígitos inteiros, notação científica, 9 casas, negativo.
- **Movimento:** resposta com as seis chaves do contrato. Recusa de `custoCentavos` (campo antigo), `custoUsdCentavos` como string, 100.5, 1000.0, zero, negativo, booleano, ausente. Quantidade zero, com vírgula, com casas além do ativo, numérica. Data 30/02, campo extra, conta corrente (400 `conta deve ser cripto`), ativo de B com conta de A, tipo inválido. UUID de conta em maiúsculas é aceito (`CanonicalUuid` aceita hex maiúsculo). Não é achado: é o comportamento do projeto.
- **Conversão USD para BRL:** `custoCentavos` de compra com PTAX da data. Exemplos: 15000,25 x 5,4000 = 81001,35, sai 8100135. 1000 x 5,0500 = 505000. 100 x 5,5555 = 55555.
- **HALF_EVEN:** dois movimentos de US$ 0,10 com PTAX 5,0500 dão 0,505, que sai 50 em cada (empate para o par). Um de US$ 0,30 dá 1,515, que sai 152. Posição de ETH com os três: 2,525 reais dá 252 (um arredondamento só sobre o valor cheio).
- **Conversão BRL para USD (linhas da F13 simuladas por SQL):** `custoUsdCentavos` de 2000 / 5,2 = 384,615... sai 38462, que confere. `custoCentavos` 200000.
- **Posição mista:** TOK com a linha em reais de 2026-10-01 e compra nova em dólar de 2026-10-20. Quantidade 11, custo US$ 38762 e R$ 201620 (200000 + 3,00 x 5,4000), valor R$ 2750 = 275000. Confere.
- **Posição na data:** `dolar` de cada data (5.4 em 20/10, 5.5555 em 22/10). Valor em reais da posição com PTAX da data.
- **Patrimônio:** `criptoCentavos` = soma das posições com valor em reais. `semCotacao` lista só a posição sem cotação (ETH) quando a PTAX existe. `totalCentavos` = contas + cripto + renda fixa. Posição de cotação em dólar sem PTAX entra em `semCotacao` e fica fora do total.
- **Venda:** venda que zera a posição (3 ETH) sai da lista. Venda acima da posição zerada: 400 `venda maior que a posição`. Venda antes da primeira compra: 400. Apagar a venda devolve a posição. Apagar compra que cobriria uma venda posterior: 400 `apagar deixaria a posição negativa`, e a compra fica.
- **Limites:** teto novo do custo (aceito, 201, custo em reais no inteiro seguro), teto + 1 (400), 9007199254740991 (400 no `d80a0c1`, aceito no `b2bd6c3`). Quantidade com 20 dígitos inteiros e 18 casas num ativo de 0 casas: 400.
- **Datas nas bordas:** movimento em 1900-01-01 e em 2199-12-31 aceitos (201). `GET /posicoes-cripto` e `/patrimonio` em 1900-01-01 respondem 200 com `dolar` nulo.
- **Autorização, usuário B contra recursos de A:** `POST` de movimento com conta de A (400 `conta não encontrada`) e com ativo de A (400 `ativo não encontrado`). Cotação em ativo de A (404). `DELETE` de movimento de A (404, mesmo corpo de id inexistente). `GET /movimentos-cripto` com `contaId` e `ativoId` de A (lista vazia). `GET /posicoes-cripto` e `/patrimonio` de B sem nada de A. Anônimo: `GET` e `POST` de movimento e de cotação, `GET /ativos-cripto`, `DELETE`, todos 401.
- **Corridas, 8 rodadas cada:** duas vendas de 1 BTC em paralelo sobre posição de 1: sempre `[201, 400]`. Duas remoções do mesmo movimento em paralelo: sempre `[204, 404]`.
- **Contrato, nomes:** `POST /ativos-cripto/{id}/cotacoes` responde `{ativoId, momento, precoUsd, fonte}`. `GET /posicoes-cripto` responde `{data, dolar, posicoes}`, e cada posição tem as oito chaves do contrato. `GET /patrimonio` tem `{data, contasCentavos, criptoCentavos, rendaFixaCentavos, totalCentavos, semCotacao}`. `dolar` é `{valor, data}`.
- **Web:** `fetchMovimentosCripto` lança erro com custo fora do inteiro seguro (C1, verificado em `b2bd6c3`). `lerPreco` recusa o preço de 34 dígitos (S1). `formatarCentavosEmDolar(9007199254740991)` sai `US$ 90.071.992.547.409,91`. Suíte vitest passa nos dois SHAs.

## Observações sem reprodução

Não são achados. Li no código e não testei.

- `IndexUpdater.updateFrom` usa `tryLock`. Quem chega com outra atualização rodando sai na hora, sem repetir. Um movimento gravado nessa janela só ganha PTAX no próximo job ou lançamento. Não medi. Mesma observação da F13.2.
- Arredondamento do custo em dólar de linhas da F13: a leitura de `positions` faz `custo / dolar` em SQL, sem escala explícita, e arredonda em Java. `MovementResponse` faz a mesma conta em Java com 34 dígitos. Não testei se os dois caminhos podem diferir um centavo. Só poderia acontecer com divisão de mais de 16 dígitos caindo exatamente no meio do centavo.
- A regra de CLAUDE.md "Cotação: API fora do ar não trava lançamento" tem teste de caminho feliz da fatia, mas não um teste de tempo com o BCB mudo. Medi o tempo (D1) e não há teste que o fixe.
- Não rodei o navegador. A parte web teve a suíte vitest, `tsc`, `oxlint` e os três testes temporários descritos em C1 e S1.
