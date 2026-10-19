# Relatório QA: F3 Lançamento realizado (ADR-024)

**Data:** 2026-10-19
**Branch:** `feat/f3-lancamento-realizado`
**Escopo:** API de lançamentos, categorias e saldo (migration V6, rotas `/lancamentos`, `/categorias`, saldo em `/contas`), rodando contra Postgres 17 descartável. Web não testada.
**Ambiente:** container `ballast-qa-f3` (`postgres:17`, `127.0.0.1:55433`), papel da aplicação só com LOGIN (ADR-021), dono `qa_dono` como migração. API na porta 18082, processo encerrado no fim, container removido. Credenciais descartáveis geradas para o teste, guardadas fora do repositório. Não usei o Postgres do compose do dono nem nenhum `.env`.
**Não cobre:** navegador, borda HTTPS, concorrência acima de 16 threads, falha do banco no meio de um `INSERT`, a rota de `PUT` com corpo acima de 4 KiB (o filtro é o mesmo do `POST`, mas não medi), e a corrida entre arquivar e `PUT` que muda de conta (mesmo mecanismo do achado 2, não testado separadamente).

## Suíte existente (linha de base)

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` | BUILD SUCCESS, 438 testes, 0 falhas, 0 erros |

Verde antes de qualquer teste manual, então a avaliação parte de base sã.

## Achados

### Bloqueia

**B1. Saldo passa da faixa do contrato com dois lançamentos válidos.**

- **O que acontece:** duas receitas de `valorCentavos: 9007199254740991` na mesma conta (inicial zero) são aceitas (201 e 201). `GET /contas` devolve `saldoCentavos: 18014398509481982`, fora de ±9007199254740991. O `api.md` promete essa faixa para `saldoInicialCentavos` e `saldoCentavos`. O ADR-024 justifica o teto dizendo que estourar o saldo "precisaria de milhares de lançamentos no limite". Dois bastam.
- **Como reproduzir:**
  1. Conta nova, inicial 0.
  2. `POST /lancamentos` com `tipo: receita`, `categoriaId` de Salário, `valorCentavos: 9007199254740991`, `data: 2026-03-10`, duas vezes.
  3. `GET /contas`, ler `saldoCentavos` da conta.
- **O que deveria acontecer:** ou o lançamento que levaria o saldo para fora da faixa é recusado com 400 (com mensagem própria), ou o contrato e o ADR-024 mudam para o limite real. A escolha é do dono: o ADR-024 está aceito e mudá-lo sai da alçada do QA.
- **Gravidade:** Bloqueia. O contrato é violado com entrada válida, sem erro. Como o ADR-024 parte de uma premissa falsa, o tech-lead precisa decidir a regra antes de liberar.
- **Evidência:** `saldo` no harness `t2_saldo_datas_valor.py`, linha "dois lançamentos no teto".

### Dívida

**D1. Lançamento gravado em conta arquivada quando o arquivar roda em paralelo com POSTs.**

- **O que acontece:** o `POST` checa `arquivada` numa leitura e grava depois, sem trava na conta. O arquivar pode confirmar (200) entre a leitura e o `INSERT`. Nesse intervalo o `INSERT` passa, porque a FK composta só confere existência e dono, não o estado da conta. Reproduzido em 3 de 3 rodadas, com 16 threads de POST: 1, 3 e 4 lançamentos receberam 201 com a resposta chegando depois do 200 do arquivar. No banco, as linhas estão em conta com `arquivada = t`. Nenhum 5xx.
- **Como reproduzir:**
  1. Conta nova; 16 threads fazendo `POST /lancamentos` nela em loop.
  2. Depois de 0,6 s, `PATCH /contas/{id}` com `{"arquivada": true}`, anotar o instante da resposta 200.
  3. Parar as threads. Entre os 201 com resposta posterior ao 200, consultar `select c.arquivada from lancamento l join conta c on c.id = l.conta_id where l.id = ...`: `t`.
  4. Repro sem o harness completo: o laço de arquivar em `t3_texto_corrida_arquivada.py` (seção "Corrida: arquivar no meio de POSTs paralelos"), com os ids dos 201 cruzados com o banco.
- **O que deveria acontecer:** depois que o arquivar respondeu 200, nenhum `POST` ou `PUT` que mova para a conta responde 201 ou 200 com ela como destino. Com a regra do `CLAUDE.md` ("contrato violado segura o merge"), o tech-lead pode subir para Bloqueia. Classifiquei Dívida porque a janela existe só quando há concorrência com o arquivamento, nenhum centavo se perde (a linha é do próprio usuário e entra no saldo da conta), e a correção é local.
- **Causa provável:** `EntryService.create` lê `arquivada` em `findAccountArchived` fora da transação do `saveAndFlush`. Trava na linha da conta (`SELECT ... FOR SHARE` na mesma transação do `INSERT`) fecha a janela.
- **Gravidade:** Dívida (ver a nota sobre Bloqueia acima).

**D2. Caractere atribuído fora do BMP é recusado como "caractere de controle".**

- **O que acontece:** `titulo` com `𝠀` (U+1D800, letra do bloco Sutton SignWriting, atribuída) dá 400 `titulo deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle`. Um `titulo` com `𝠀a` também dá 400.
- **Como reproduzir:** `POST /lancamentos` com `"titulo": "𝠀"` e os demais campos válidos.
- **O que deveria acontecer:** 201. U+1D800 não é controle, não é surrogate e não é separador.
- **Causa provável:** `NoControlCharacters.Validator.isControl` faz `Character.isSurrogate((char) codePoint)`. O cast para `char` corta os 16 bits de cima. Todo code point cujos 16 bits baixos caem em D800 a DFFF (U+1D800 a U+1DFFF, U+2D800, U+10D800 e assim por diante) é recusado. O teste de emoji comum (U+1F600) passa, por isso não apareceu antes. A correção é testar o `codePoint` inteiro (`cp >= 0xD800 && cp <= 0xDFFF`, ou `Character.getType(cp) == SURROGATE`).
- **Gravidade:** Dívida. Rejeita texto válido com mensagem enganosa, sem perda de dado.

### Sugestão

**S1. Espaço invisível no título e na descrição.**

- **O que acontece:** `titulo` só com NBSP (U+00A0) dá 201 e grava um título vazio para o olho. `titulo` com U+200B no meio dá 201. `strip()` do Java e `btrim()` do banco não tiram NBSP, e o banco só tira espaço ASCII.
- **Por que é Sugestão:** a mesma decisão está aberta desde a F2 (S1 de `f2-contas.md`). O ADR-024 não diz o que conta como "sem espaço nas pontas". Não viola o contrato como escrito.
- **Como reproduzir:** `POST /lancamentos` com `"titulo": " "`, depois `"titulo": "a​b"`.

**S2. UUID em maiúsculas é aceito em todo lugar.**

- **O que acontece:** `GET`, `PUT` e `DELETE /lancamentos/{id}` com o id em maiúsculas do próprio dono respondem 200, 200 e 204. `categoriaId` e `contaId` em maiúsculas também são aceitos.
- **Por que é Sugestão:** UUID em maiúsculas é UUID válido, e o contrato só manda 404 para id que não é UUID. O mesmo ponto está aberto na F2 (S4). Não há cenário de dano: quem não é dono recebe 404 do mesmo jeito.

## O que passou

**Sementes**
- Cadastro novo: 16 categorias, 5 receitas e 11 despesas, nomes conforme `dados.md`, `paiId`, `cor` e `icone` nulos.
- `GET /categorias` ordenado por tipo (despesa antes), `?tipo=receita` devolve 5, `?tipo=` vazio, `?tipo=DESPESA` e `?tipo=transferencia` dão 400.
- 8 cadastros simultâneos do mesmo e-mail: 1 com 201 e 7 com 409. Um único usuário, 16 categorias para o vencedor, zero categoria órfã. Banco inteiro: nenhum usuário com número de categorias diferente de 16.

**Autorização**
- A lê, edita e apaga lançamento de B: 404 com o mesmo corpo de inexistente. Lançamento de B intacto no banco (comparação campo a campo).
- A cria com `contaId` de B: 400 `conta não encontrada`. A cria com `categoriaId` de B: 400 `categoria não encontrada`. Nada gravado.
- A edita o próprio lançamento com conta de B ou categoria de B: 400, e a linha não muda.
- A move o próprio lançamento para a conta de B por `PUT`: 400.
- `GET /lancamentos?contaId=` de conta de B e de id inexistente: lista vazia, sem distinguir.
- Sem cookie: 401 em `GET`, `POST`, `GET /{id}`, `PUT`, `DELETE` de lançamento e em `GET /categorias`.

**Saldo**
- Inicial 100,00 + receita 25,00 − despesa 10,00 = 115,00, igual no detalhe e na lista.
- 1000 receitas de 0,01: saldo exato 10,00 no detalhe e na lista. Uma despesa de 0,01 leva a 9,99.
- `PUT` trocando conta, tipo e valor: conta de origem recebe o estorno, conta de destino recebe o novo valor, lista e detalhe batem.
- `DELETE` tira o lançamento do saldo. `DELETE` de novo: 404.
- Saldo negativo (−500,00) igual no detalhe e na lista.
- Conta arquivada mantém o saldo, na lista de arquivadas e no detalhe.
- Saldo de 20 mil lançamentos bate com a soma no banco, nas duas contas.

**Tipo e categoria**
- Despesa com categoria de receita, e receita com categoria de despesa: 400 `categoria não encontrada`.
- `PUT` que troca só o tipo (despesa para receita, mantendo categoria de despesa): 400, e o tipo no banco segue despesa.
- `PUT` que troca tipo e categoria juntos: 200.

**Datas**
- Aceitas: 2028-02-29, 1900-01-01, 2199-12-31. Recusadas: 2027-02-29, 2026-02-30, 2026-13-01, 1899-12-31, 2200-01-01, 0000-01-01.
- Formatos recusados: `2026-1-5`, `20260105`, `2026/03/10`, `+2026-03-10`, `2026-03-10 `, `2026-03-10T10:00:00`, vazio, número, lista, objeto e booleano.
- `dataCompetencia` e `dataCaixa` iguais após POST.
- Filtro: as duas pontas (01/03 e 31/03) entram, dias de fora não entram. `de == ate` aceito. `de > ate`: 400. Janela de 366 dias (2028, bissexto): 200. Janela de 367: 400.
- `de` ou `ate` ausente, ou ambos, `de=` vazio, `de` repetido, `de=2026-03-01,2026-03-02`, `de` com hora: 400.
- Ordem decrescente por `dataCaixa`.

**Valor**
- `0`, `-1`, `null`, `12.5`, `1.0`, `1e2`, `1e3`, `true`, `[1]`, `"100"`, `9007199254740992`, `99999999999999999999999`: 400.
- `1` e `9007199254740991` aceitos. O último volta exato no JSON e grava `90071992547409.91` no banco.

**Texto**
- Título com 100 caracteres: 201. Com 101: 400. Com 100 emojis (200 unidades UTF-16): 201. Com 101 emojis: 400.
- Descrição com 500 caracteres e com 500 emojis (UTF-8 cru): 201. Com 501 caracteres ou 501 emojis: 400.
- Controle: NUL, BEL, U+0085, U+2028, U+2029, tab, quebra de linha, surrogate solto e NUL escapado em JSON: 400, sem 500.
- Título só espaços, vazio: 400. Título com espaço nas pontas: gravado sem espaços.
- Descrição vazia e só espaços viram `null`.
- Acentos e emoji simples preservados.

**Corrida**
- 20 `POST` simultâneos, mesma conta: todos 201, saldo 2,10 (soma de 1 a 20 centavos), 20 linhas.
- 40 `PUT` simultâneos trocando lançamentos entre duas contas: todos 200, nenhum 5xx, dinheiro total preservado (10,00 nas duas contas).
- Arquivar com POSTs paralelos: nenhum 5xx em 4 rodadas (ver D1 sobre o conteúdo).

**Conta arquivada (sequencial)**
- `POST` nela: 400 `conta arquivada`. Nenhuma linha gravada.
- `PUT` editando lançamento que já está nela, sem trocar de conta: 200, saldo reage.
- `PUT` movendo para ela: 400 `conta arquivada`. Movendo dela para conta ativa: 200.
- `DELETE` de lançamento que está nela: 204.

**Banco fora**
- `docker pause` com pool aquecido: `GET /lancamentos`, `POST /lancamentos`, `GET /categorias`, `GET /contas`, `/healthz`, todos 503 `serviço indisponível` em cerca de 2,0 s. Nada gravado durante a pausa (conferido no banco).
- `docker stop`: `GET` e `POST /lancamentos`, `GET /categorias`, `GET /contas`, `GET /lancamentos/{id}` e `/auth/eu`, todos 503 em cerca de 2,0 s. Nada de 404 onde devia ser 503.
- Banco volta com `docker start`: a API se recupera sozinha. Uma chamada 503 enquanto o banco ainda não aceitava conexão TCP, e a seguinte 200 em menos de 50 ms, sem reiniciar a API.

**Corpo e verbos**
- `Content-Type` `text/plain` e `application/x-www-form-urlencoded`: 415.
- Corpo de 4097 bytes e corpo de 4 KiB de espaço em branco: 413 `corpo grande demais`. Corpo chunked acima do limite: 413, e a resposta não ecoa o corpo.
- Corpo de 4096 bytes passa o filtro.
- Campo extra (`status`, `usuarioId`, `dataCaixa`, `dataCompetencia`, `id`, `criadoEm`) no `POST` e no `PUT`: 400. O lançamento não muda.
- `PATCH /lancamentos/{id}`: 405 com `Allow: PUT, GET, DELETE`. `POST /lancamentos/{id}`: 405.
- Id malformado (`nao-e-uuid`, `1-1-1-1-1`, UUID zero, `%20`, `urn:uuid:...`, sem hífen, 300 caracteres) em `GET` e `DELETE`: 404.
- `Accept: text/html` em `GET /lancamentos`: 406.
- JSON truncado, corpo vazio, UTF-8 inválido, JSON com 1500 níveis: 400, sem 500.
- `GET /lancamentos/{id}` de id inexistente: 404 `não encontrado`.

**Log**
- Busca literal em toda a sessão de log (sete arquivos, 7999 linhas): título, descrição, nome de conta, senha, token de sessão e nome de categoria, zero ocorrências. O e-mail aparece só mascarado (`c***@exemplo.com`).
- Zero linhas `erro não tratado`, zero 500 na sessão.
- Logs de lançamento (`lançamento criado usuario_id=... lancamento_id=...`) não carregam o conteúdo do lançamento.

**Volume**
- 20 mil lançamentos num usuário, duas contas, inseridos direto no banco (dado de teste). `GET /contas`: 16 ms. `GET /lancamentos` de um mês (837 linhas): 12 a 24 ms. Janela de 366 dias (10 172 linhas): 79 ms. Saldos batem com a soma do banco.

## Não reproduzido

- **B1 da F2 (primeira chamada com banco congelado leva 30 s).** Não reproduzi na F3, em três subidas da API. Duas sem nenhuma requisição antes da pausa, e uma pelo fluxo exato da F2: subir, `POST /auth/login`, `docker pause`, `GET /contas`. Em todas, 503 em cerca de 2,0 s. Não verifiquei em que commit o comportamento mudou. Isto não prova que B1 está corrigido em todo ambiente: prova que não reproduz neste, com Docker Desktop no macOS e Postgres 17.

## Limites

- A corrida de D1 foi testada com 16 threads. Não medi com mais, nem com o `PUT` que move para a conta arquivada.
- Falha do banco no meio de um `INSERT` ou `UPDATE` não foi reproduzida, só a falha antes da consulta.
- Carga de 20 mil lançamentos foi inserida direto no banco, não pela API.

## Resumo

- **Achados:** 1 Bloqueia, 2 Dívida, 2 Sugestão.
- **Os que travam primeiro:** B1. O saldo passa da faixa do contrato com dois lançamentos válidos, e o ADR-024 que fixou o teto parte de uma premissa falsa. A decisão de fundo é do dono.
- **Recomendação: segurar.** B1 precisa de decisão e de correção ou de mudança de contrato. D1 e D2 são correções pequenas e localizadas. D1 tem alternativa de subir para Bloqueia pela regra do `CLAUDE.md`. Autorização, isolamento por dono, sementes, datas, valor, corpo, verbos e log passaram em todos os cenários. Saldo e volume passaram exceto B1. A decisão final é do tech-lead.
