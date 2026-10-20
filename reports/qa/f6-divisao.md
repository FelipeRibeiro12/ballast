# Relatório QA: F6 Divisão de salário (ADR-027)

**Data:** 2026-10-20, horário de São Paulo, execução entre 05:27 e 05:45.
**Branch:** `feat/f6-divisao-de-salario`. Testado em `feabca3`. Durante a sessão o outro agente commitou `58092e9` (`feat: adiciona a divisão de salário no formulário de recorrência do web`), que mexe só em `web/`. `git diff feabca3 HEAD -- api/` é vazio, então o que foi testado vale para a API atual. Nenhum arquivo de produção foi alterado por mim. Árvore limpa ao fim.
**Escopo:** API da divisão de salário em `POST /recorrencias`, `GET /recorrencias` e `GET /recorrencias/{id}`, materialização (job e criação), ações por parcela (confirmar, cancelar, editar, apagar), desativar, validação, autorização, lista de lançamentos, banco fora, trava de conta, log e volume. Web não testado.
**Ambiente:** container descartável `ballast-qa-f6` (`postgres:17`, `127.0.0.1:55436`), com o script de init do repositório montado em modo leitura. Migração como `qa_dono`, superusuário só do container. Aplicação com `ballast_app`, papel só com LOGIN (`rolsuper` falso). API na porta 18085, subida com variáveis na linha de comando, `REGISTRO_ABERTO=true`, `APP_ENV=development`, `COOKIE_SECURE=false` e `--ballast.recurrence.cron='*/10 * * * * *'` no lugar das 03:00. Credenciais geradas para a sessão e apagadas no fim. Não usei o banco do compose do dono (`ballast-db-1`), não li nem imprimi nenhum `.env`. API e container derrubados.
**Não cobre:** navegador, HTTPS, o cron de produção `0 0 3 * * *` ao vivo, o dia real passando, e o cruzamento de escrita de conta com o job numa conta com template (ver "Não testado").

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B -q verify` em `feabca3` | saiu com código 0. Nos relatórios do surefire, 158 arquivos, zero falhas e zero erros |

Verde antes de qualquer teste manual. Não reabri a contagem exata de testes.

## Resumo

- **Achados:** 0 Bloqueia, 0 Dívida, 2 Sugestão. Além disso, 3 falhas do meu roteiro, listadas em "Erros do meu roteiro", e nenhuma é do produto.
- **Travam primeiro:** nenhum.
- **Checagens:** 119 reais, 116 passaram. As 3 que não passaram são 1 bissexto não testado ao vivo (não cabe no horizonte) e 2 expectativas minhas erradas. Uma entrada de placeholder (`diaDoMes` nulo junto com divisão) foi descontada: não foi testada, ver "Não testado".
- **Confirmado nesta build:** a correção do D2 da F5 (`proximaOcorrencia` agora usa a data de caixa), e a ausência da senha gerada no log de arranque (B2 da F5).
- **Recomendação:** **liberar com dívida registrada.** Nenhum achado bloqueia. Registrar as Sugestões S1 e S2 e a cobertura pendente do cruzamento de escrita com job. A decisão é do tech-lead.

## Achados

### Bloqueia

Nenhum.

### Dívida

Nenhuma nova. O cruzamento de escrita com o job numa conta que tem template (D1 e B1 da F5) não foi reexecutado nesta rodada, ver "Não testado". Não é achado, é cobertura pendente.

### Sugestão

**S1. Espera de trava medida em 2,03 s, contrato diz "no máximo 2 s".**

- **O que acontece:** com a conta travada por outra transação, `POST /recorrencias` com divisão responde 503 `serviço indisponível` em 2,03 s. Nada grava. É o mesmo valor que a F5 mediu para `POST /lancamentos` com conta sem template (2,02 a 2,03 s).
- **Como reproduzir:**
  1. Criar um usuário, uma conta `C` e uma categoria de receita.
  2. Numa sessão psql no container: `BEGIN; SELECT id FROM conta WHERE id='C' FOR UPDATE; SELECT pg_sleep(9); COMMIT;`, com a sessão aberta e a trava segurada.
  3. Logo depois, `POST /recorrencias` com divisão (dois percentuais de 5000) na conta `C`. Medir o tempo da resposta.
- **O que deveria acontecer:** 503 em no máximo 2 s, sem gravar nada (`docs/contracts/api.md`, Espera de trava).
- **Observado:** 503 em 2,03 s. Em outra execução, o banco pausado deu 503 em 2,01 s.
- **Leitura:** são 30 ms acima do limite, medidos com o cliente e o servidor no mesmo host. O `lock_timeout` de 2 s está certo e o excedente é o custo do request. O que resta é decidir se "no máximo 2 s" é limite rígido ou orçamento aproximado, e dizer no contrato. Não bloqueia.
- **Gravidade:** Sugestão.

**S2. Doc de `dados.md` diz "soma dos percentuais = 100", a implementação e o contrato usam 10000.**

- **O que acontece:** `docs/contracts/dados.md`, na seção de divisão, linha 422: "Validação: soma dos percentuais = 100, ou soma dos valores fixos = `valor_total`". O ADR-027 e `api.md` dizem soma de `percentualCentesimos` igual a 10000 (100,00%). A API recusa 9999 e aceita 10000 em centésimos, então o comportamento está certo.
- **Como reproduzir:** ler a linha 422 de `docs/contracts/dados.md` e comparar com `api.md`, seção Divisão de salário, e com `DivisionRequest.java`, linha 40 (`FULL_PERCENT_CENTS = 10_000`).
- **O que deveria acontecer:** `dados.md` dizer a mesma unidade que o contrato, para quem implementa o web não somar 100 onde o servidor pede 10000.
- **Gravidade:** Sugestão. Doc, sem efeito no comportamento.

## Não testado ou não reproduzido

- **`diaDoMes` nulo junto com divisão.** Esperava 201, sem o dia na regra. Foi uma entrada de placeholder no registro, marcada OK sem nenhum teste. Não foi testada, e não reabri o ambiente só para isso. O contrato não fala do caso nulo, então fica como pendência de teste.
- **Fevereiro bissexto ao vivo (dia 29 em 2028).** O horizonte vai de 2026-10-20 a 2027-10-20, e `from` nunca passa de hoje, então a materialização não alcança 2028. A suíte cobre isso em `RecurrenceDivisionTest` (linha 377, `days30And31InFebruaryOfALeapYearFallOn29`, com 2028-02-29 nas parcelas 1 e 2) e em `RecurrenceScheduleTest`. Não reexecutei a suíte para isso, só li o teste.
- **Cruzamento de escrita de conta com job (B1 e D1 da F5).** Não montei `PUT` ou confirmação concorrendo com o job numa conta com template. O código atual não trava a conta no job (`RecurrenceService.java`, comentário de ordem de travas), então a causa da B1 da F5 deve ter saído, mas não comprovei com medida.
- **Desativar concorrendo com o job.** Não rodei. Só cobri o desativar sozinho e a rodada seguinte sem gerar nada.
- **Job durante banco fora.** A pausa não caiu numa rodada do job, então não vi o log de rodada interrompida (S4 da F5, que continua aberta por não ter sido retestada).
- **Dia real passando.** Não há como avançar o relógio, então a parcela que vence e fica `previsto` não foi observada.

## O que passou

Números do registro bruto de `reports/` da sessão (`results.jsonl`, fora do repositório), contados por nome distinto.

- **Regra do centavo (15 de 15).** 1.000,00 em 3333/3333/3334 dá 333,30, 333,30 e 333,40, soma 1.000,00 em cada mês. 5.000,00 em 4000/6000 dá 2.000,00 e 3.000,00. 0,03 em 3333/3333/3334 dá 400 com `toda parcela deve valer ao menos 1 centavo`. 1.234,57 em 4 parcelas de 2500 dá 308,64 três vezes e 308,65, somando 1.234,57. 10 parcelas de 10% sobre 999,99 somam 999,99. Teto de 9007199254740991 em 50/50 soma o teto exato. Teto mais um centavo dá 400. 100,00 em 0,01%/99,99% dá 0,01 e 99,99. 99,99 nas mesmas faixas dá 400. O resto vai na última parcela pela ordem do array. Valor fixo somando certo passa, e errado por 1 centavo para menos ou para mais dá 400. Percentual somando 9999 ou 10001 dá 400. Em cada caso a comparação é parcela a parcela com um oráculo independente, em inteiros de centavos.
- **Datas (10 de 10, mais o bissexto não testado).** Dias 15 e 30 nos próximos 12 meses (24 parcelas, comparadas). Dias 30 e 31 em 28/02/2027 com duas ocorrências, de ordem 1 e 2. Dias 29, 30 e 31 em fevereiro de 2027, três no dia 28. Intervalo 2 com dia 31. `ocorrenciasMax 3` com início em novembro (6 parcelas) e com início hoje (5 parcelas, o mês de início conta). `fim` no meio do mês (dia 20 de novembro fora). Hoje no meio do mês (dia 10 passou, dia 25 entra). Início no futuro (5 de junho fora, 20 de junho dentro). Dia 31 e dia 1 com início hoje. Em todos, `ocorrenciasMaterializadas` igual ao número de parcelas do oráculo.
- **Idempotência (2 de 2).** Depois de ~32 s, com três rodadas do job, as duas séries mantêm as mesmas linhas, com os mesmos ids. Nenhuma duplicata por (template, data, ordem) no banco.
- **Ações por parcela (11 de 11).** Confirmar parcela 1 e cancelar parcela 2 do mesmo mês dá 200. Confirmar de novo dá 400 `só ocorrência prevista pode mudar de status`. `PATCH` com `previsto` dá 400. `DELETE` de prevista dá 400 `cancele a ocorrência` e não apaga. `PUT` na parcela 1 de dezembro mudando a data para 30/12 (dia da parcela 2) dá 200: `ocorrencia_data` fica em 15/12, `data_caixa` vai para 30/12, `desgarrado` vai para verdadeiro. Depois de 25 s o job não recria 15/12 e não duplica 30/12. Confirmar a parcela 2 de dezembro depois disso dá 200.
- **Desativar (7 de 7, mais uma checagem minha com expectativa errada, ver "Erros do meu roteiro").** Antes, 24 linhas. Depois, 4: a realizada de 15/11, a cancelada de 30/11, a desgarrada e a confirmada. Previstas de hoje em diante não desgarradas, sumiram. `proximaOcorrencia` vai para nulo. Depois de 25 s o job não gera nada. Desativar de novo devolve o mesmo estado. Reativar dá 400 `reativar não é suportado`.
- **Forma da resposta (5 de 5, mais uma).** `proximaOcorrencia` de prevista editada (30/10 movida para 22/10) bate com a lista: é a correção do D2 da F5, confirmada nesta build.  `divisao` traz `modo`, `ordem`, `diaDoMes`, `percentualCentesimos` e `valorParcelaCentavos`, sem o campo do outro modo. `regra.diaDoMes` é o dia da parcela de ordem 1, também quando o array vem fora de ordem (30 antes de 15 dá 30). `proximaOcorrencia` é a menor `dataCaixa` prevista de hoje em diante. Lista de recorrências com `divisao` presente. Divisão em valor fixo traz `valorCentavos` e `valorParcelaCentavos` iguais.
- **Recriação pelo job (5 de 5).** Confirmar a parcela 1 de 05/11, depois apagar (204), devolve saldo a zero. Na rodada seguinte o job recria 05/11 com `parcela_ordem` 1, valor 333,30 e status `previsto`. Em todo o horizonte, cada `parcela_ordem` tem um único valor (o job reinsere com o mesmo valor). Sem duplicata depois da recriação.
- **Validação (26 de 26, mais o controle de mensal com `diaDoMes` sem divisão, 1 de 1).** Divisão em diária, semanal e anual dá 400 `divisao só vale em mensal`. `diaDoMes` junto com divisão dá 400 com a mensagem do contrato. Mensal sem `diaDoMes` e sem divisão dá 400 `mensal exige diaDoMes`. Modo misto, campo do outro modo numa parcela e nas duas chaves dão 400 `cada parcela deve trazer só o campo do modo da divisao`. Dia repetido dá 400. Uma parcela e onze parcelas dão 400. Percentual 0, 10000 e negativo dão 400 com a faixa. Decimal `33.33` e string dão 400 `requisição inválida`. Valor como string dá 400. `valorCentavos` 0 e acima do teto na parcela dão 400 com a faixa. `diaDoMes` 0 e 32 dão 400. Modo inventado dá 400 `divisao.modo deve ser percentual ou valor`. Campo extra na divisão e na parcela dá 400. Divisão vazia e `parcelas` nulo dão 400. Em cada recusa, nenhum template nem lançamento foi gravado.
- **Autorização (11 de 11).** A lê divisão de B dá 404 com o corpo de inexistente. A desativa recorrência de B dá 404 e nada muda. A cria divisão com conta de B dá 400 `conta não encontrada` e nada é gravado. A cria divisão com categoria de B dá 400 `categoria não encontrada`. A lista não contém nada de B. A confirma e apaga ocorrência de B dá 404, e a linha segue no banco. Sem cookie, 401 em `GET /recorrencias`, `POST /recorrencias` e `GET /recorrencias/{id}`.
- **Sinal, teto e lista (8 de 8).** `parcelaOrdem` vem em todas as parcelas de divisão e é nulo em avulso e em recorrência sem divisão. Em 28/02/2027 aparecem as parcelas de ordem 1 e 2. Despesa dividida: confirmar a parcela de 450,00 debita o saldo para -45000. Cancelar depois de confirmada dá 400 e o saldo fica. Saldo conta só o realizado (50000 com uma confirmada). Teto: duas metades de 90071992547409,91 chegam ao teto exato, e confirmar mais 0,50 dá 400 `saldo da conta fora do limite` e a parcela segue `previsto`.
- **Concorrência (1 de 1).** Dez confirmações paralelas da mesma parcela dão um 200 e nove 400. O saldo é debitado uma vez.
- **Banco fora (3 de 3).** Com `docker pause` no container descartável, `POST /recorrencias` com divisão dá 503 `serviço indisponível` em 2,01 s. Templates de 7 para 7 e lançamentos de 38 para 38. Depois de `unpause`, o mesmo POST dá 201 sem reiniciar a API.
- **Trava de conta (1 de 1).** Com a conta travada por `FOR UPDATE`, `POST /recorrencias` com divisão dá 503 em 2,03 s e nada é gravado.
- **Log (3 de 3).** O corpo de erro não repete título nem valor. O título sigiloso e o valor da recusa não aparecem no log inteiro da sessão. A string `Using generated security password` não aparece no log de arranque, então B2 da F5 não se repete nesta build. O log de erro só tem `sessão: banco indisponível` e `espera por trava estourou`, os dois esperados nos testes de banco fora e de trava.
- **Job (verificado no log, não entra na contagem).** A rodada que reinseriu 400 parcelas logou `ocorrencias=400`. O contador confere com o banco.

## Volume

Usuário novo, uma conta, 20 recorrências mensais com 10 parcelas cada, início em 20/10/2026, 2400 parcelas no total.

| Medida | Mediana | Máximo | Observação |
|---|---|---|---|
| `POST /recorrencias` com 10 parcelas (20 chamadas) | 18 ms | 28 ms | 201 em todas |
| Rodada do job reinserindo a cauda apagada (400 parcelas em 20 templates) | | 0,07 s de espalhamento de `criado_em` | a cauda voltou inteira |
| `GET /contas` com 2400 parcelas | 4 ms | | |
| `GET /lancamentos` de novembro com o volume | 8 ms | | |
| `PATCH` de desativar uma das 20 (apaga previstas de hoje em diante) | 12 ms | | 200 |

## Erros do meu roteiro

Falhas que foram erro do meu teste, não do produto. Não contam como achado.

- **Bissexto ao vivo (datas).** Marquei como falha um cenário que não cabe no horizonte. Está em "Não testado" e a suíte cobre.
- **Desativar, conjunto mantido.** Esperava a chave `(data_caixa, ordem)` da desgarrada, mas a linha mantém `ocorrencia_data` 15/12 e a data de caixa 30/12. O banco guardou a linha certa. Corrigido na leitura, não reexecutei o bloco.
- **Placeholder no registro.** A entrada `validação: diaDoMes null junto com divisão` foi marcada OK sem teste. Descontada da contagem.
- **Série sem fim nem máximo.** Esperava 400 numa série mensal com divisão sem `fim` e sem `ocorrenciasMax`. Essa série é válida, como já era na F5, e a API aceitou com 24 parcelas.
- **Primeira rodada do bloco de extras.** Quebrou com `NameError` na constante do teto antes de chegar ao fim. Corrigido e refeito. O arquivo de resultados tem as linhas da rodada parcial, que não entram na contagem.

## Recomendação

**Liberar com dívida registrada.** Nenhum achado bloqueia: nada travou, nada foi gravado sem contrato, e as regras de dinheiro, autorização e log passaram nos cenários do roteiro. Registrar:

1. **S1**, espera de 2,03 s contra "no máximo 2 s": o tech-lead decide se o contrato fala em limite rígido ou em orçamento. Não exige mudança de código para liberar.
2. **S2**, doc de `dados.md` com a unidade errada da soma de percentuais. Corrigir no mesmo PR, sem efeito no código.
3. **Cobertura pendente:** cruzamento de escrita de conta com job numa conta com template, desativar concorrendo com o job, e o log de rodada com banco caindo no meio (S4 da F5).

O web não foi testado aqui. A decisão de liberar é do tech-lead.

## Como refazer

Roteiro em Python (stdlib) em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f6/`, fora do repositório: `f6_lib.py` (cliente HTTP, SQL pelo container, oráculo de datas e centavos), `f6_centavo_datas.py`, `f6_acoes.py`, `f6_extras.py`, `f6_recria.py` e `f6_proxima.py`. Para refazer: subir `postgres:17` com `POSTGRES_USER=qa_dono`, montar `db/init` em modo leitura com `DB_USER` e `DB_PASSWORD` gerados, subir a API na 18085 com as variáveis de "Ambiente", e rodar os scripts na ordem. As credenciais foram apagadas, então é preciso gerar novas. `f6_centavo_datas.py` apaga `results.jsonl` ao começar.
