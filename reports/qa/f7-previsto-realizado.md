# Relatório QA: F7 Previsto × realizado (ADR-028)

**Data:** 2026-10-20, horário de São Paulo.
**Branch:** `feat/f7-previsto-realizado`. Testado com a API de `b8abf4f`. O HEAD agora é `ae41d8d`, que só mudou `web/` (`git diff b8abf4f ae41d8d -- api/` é vazio), então o que foi testado vale para a API atual. Nenhum arquivo de produção foi alterado por mim.
**Escopo:** `PATCH /lancamentos/{id}` com ajuste de valor e data na confirmação, `GET /lancamentos/vencidos`, recusas, autorização, corrida, trava de conta, banco fora, log e volume. Web não testado.
**Ambiente:** container descartável `ballast-qa-f7` (`postgres:17`, `127.0.0.1:55437`). Migração como `qa_dono`, superusuário só do container. Aplicação com `ballast_app`, papel só com LOGIN e `rolsuper` falso. API na porta 18086, subida com variáveis na linha de comando, `REGISTRO_ABERTO=true`, `APP_ENV=development`, `COOKIE_SECURE=false` e `--ballast.recurrence.cron='*/10 * * * * *'`. Não usei o banco do compose do dono (`ballast-db-1`), não toquei a API da 8080 e não li nem imprimi nenhum `.env`. Credenciais geradas para a sessão e apagadas no fim. API e container derrubados.
**Não cobre:** web, HTTPS, o cron de produção `0 0 3 * * *` ao vivo, o dia real passando, e a meia-noite UTC ao vivo.

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B -q verify` em `b8abf4f` | saiu com código 0. Nos relatórios do surefire, 833 testes, zero falhas e zero erros |

Verde antes de qualquer teste manual.

## Resumo

- **Achados:** 0 Bloqueia, 0 Dívida, 1 Sugestão.
- **Checagens:** 76 no roteiro final, 75 passaram, 1 ficou não testada de propósito (meia-noite UTC ao vivo). Nenhuma falhou.
- **Travam primeiro:** nenhum.
- **Recomendação:** **liberar.** Nenhum achado bloqueia: nenhuma recusa mudou o banco, o saldo bate com o valor confirmado, a autorização passou em todas as rotas testadas, e nada respondeu 500. A decisão é do tech-lead.

## Achados

### Bloqueia

Nenhum.

### Dívida

Nenhuma.

### Sugestão

**S1. Ajuste por parcela de divisão deixa a soma das parcelas diferente do total.**

- **O que acontece:** numa despesa dividida de R$ 1.000,00 em duas parcelas de 50%, confirmar a parcela 1 com `valorCentavos` 33333 grava a parcela 1 em 333,33 e deixa a parcela 2 em 500,00. A soma passa a ser 833,33, sem aviso.
- **Como reproduzir:** criar recorrência despesa de 100000 com `divisao` percentual, parcelas `diaDoMes` 5 e 20, 5000 cada. Levar a parcela 1 de novembro para outubro (data de caixa anterior a hoje) no banco. `PATCH /lancamentos/{id}` com `{"status":"realizado","valorCentavos":33333}`.
- **O que deveria acontecer:** o contrato diz que o ajuste vale por parcela (`docs/contracts/api.md`, Ocorrência). Então o comportamento está conforme o contrato. O que falta é o contrato dizer que a soma pode deixar de fechar, para quem implementa o web não tratar isso como erro.
- **Observado:** 200, parcela 1 em 333,33, parcela 2 intacta em 500,00.
- **Gravidade:** Sugestão. Não é bug de produto, é decisão a registrar no contrato.

## Não testado ou não reproduzido

- **Meia-noite UTC ao vivo.** O servidor não avança o relógio. A regra de "hoje" usa `America/Sao_Paulo` no `Clock`, mas não vi a virada do dia na prática.
- **Corrida de desativar com confirmar nos dois sentidos.** Rodei 3 rodadas com 10 confirmações e 1 desativar em paralelo. Em todas, a confirmação ganhou a trava primeiro e a desativação também devolveu 200. O caso em que a desativação apaga a prevista antes da confirmação (esperado 404 na confirmação) não apareceu, então esse ramo não foi exercitado.
- **Arquivar a conta em paralelo com confirmar.** Não rodei.
- **`"data": null` e `"valorCentavos": null` no `PATCH`.** Não testei. Pelo código, `null` é tratado como ausente.
- **Confirmar sem cookie.** Testei `GET /lancamentos/vencidos` sem cookie (401). O `PATCH` sem cookie ficou de fora.
- **Parada de runner no meio do roteiro.** Duas rodadas pararam no meio: o computador dormiu, e a execução ficou suspensa. Descartei as rodadas incompletas e a contagem acima é da rodada completa, do zero, com o banco recriado.
- **Queda de jar sob a JVM.** Num momento a API da 18086 respondeu `NoClassDefFoundError` para `ch.qos.logback.classic.spi.ThrowableProxy`. Achei que o `mvnw verify` de linha de base regravou o jar enquanto a JVM rodava. Não reproduzi como bug do produto. Subi de novo a partir de uma cópia do jar e não voltou.

## O que passou

Números da rodada final, a partir de `run.out` do scratchpad, fora do repositório.

- **Confirmar com ajuste (5 de 5).** Sem ajuste: 200, valor e data iguais, saldo debitado no valor previsto. Só data: 200, competência e caixa na data nova, valor preservado, `ocorrencia_data` igual. Os dois: 200, valor 1234,56 e data nova, saldo debitado no valor novo. Só valor: 200, valor novo, data preservada. Mudança de mês: prevista de hoje saiu da lista de outubro e entrou na de novembro.
- **Job depois do ajuste (3 de 3, mais o log).** Depois de 25 s e várias rodadas do job (59 linhas de rodada no log), cada `ocorrencia_data` ajustada ficou com uma linha só, e nenhuma prevista apareceu na data nova.
- **Recusas sem mudar nada (19 de 19).** Valor 0, negativo, acima do teto, decimal `12.5`, decimal `100.0`, string `"100"`, data `2026-13-01`, `2026-02-30`, `26-10-01`, `1899-12-31`, `2200-01-01`, data com hora, data com espaço, data como número, cancelado com valor, cancelado com data, campo extra, status ausente e status `previsto`. Em todas: 400 com a mensagem do contrato, linha no banco igual antes e depois, saldo igual.
- **Teto (2 de 2).** Conta com saldo inicial a 1 centavo do limite: confirmar com 2 centavos dá 400 `saldo da conta fora do limite`, a prevista segue `previsto`. Com 1 centavo, 200 e saldo exatamente 9007199254740991.
- **Conta arquivada (3 de 3).** Confirmar com valor dá 400 `conta arquivada`, confirmar com data dá o mesmo, e nada muda. Cancelar a prevista da conta arquivada dá 200.
- **Autorização (5 de 5, mais 2 na lista).** Prevista de B pelo cookie de A dá 404 com o corpo de inexistente e nada muda. Id inexistente e id que não é UUID dão o mesmo 404. A lista de vencidas de A e de C não traz a prevista de B.
- **Perna de transferência (2 de 2).** Com valor e sem valor, 400 `use a rota de transferência`, nada muda.
- **Divisão por parcela (3 de 3).** Ajuste na parcela 1 não mexe na parcela 2. Ajuste na parcela 2 com data dá 200 e `ocorrencia_data` igual (ver S1).
- **Corrida de confirmação (1 de 1).** 10 confirmações em paralelo com valores diferentes: um 200, nove 400 `só ocorrência prevista pode mudar de status`. O saldo debitou só o vencedor.
- **Corrida de confirmação com desativar (3 de 3).** Em cada rodada, 1 confirmação 200, 9 de 400, e a desativação 200. Nenhum 5xx. Saldo debitado uma vez. Linha continuou `realizado`.
- **Trava de conta (1 de 1).** Conta travada por outra sessão: 503 `serviço indisponível` em 2,06 s, nada mudou.
- **Banco fora (4 de 4).** Com `docker pause`, `GET /lancamentos/vencidos` deu 503 em 2,03 s e `PATCH` com ajuste deu 503 em 2,02 s. Depois do unpause, a linha segue igual e o `GET` volta a dar 200 sem reiniciar a API.
- **Vencidas (7 de 7, mais 4 de volume).** Conjunto e ordem batem com o `SELECT` do banco (3 de 3 com o oracle). Hoje não entra, ontem entra, realizada não entra, cancelada não entra, prevista de B não entra. Forma `{lancamentos, maisAlgum}`. Sem cookie, 401. Rota `/lancamentos/vencidos` responde como lista e não é capturada por `/lancamentos/{id}`.
- **Volume (4 de 4).** Usuário C com 5124 previstas vencidas: 200 com 200 itens e `maisAlgum` true, mediana de 6 ms e máximo de 9 ms em 5 chamadas. Com 201 vencidas: 200 e `maisAlgum` true. Com 200 vencidas: `maisAlgum` false.
- **Log (8 de 8).** Título e valores sigilosos (`Aluguel QA`, `Divisao QA`, `Conta arquivada QA`, `123456`, `1234.56`, `987,65`, `987.65`, `4444`) não aparecem no log. O log tem `lancamento mudou de status usuario_id lancamento_id status` sem valor. Os erros que aparecem são os esperados: `espera por trava estourou`, `banco indisponível` e `canceling statement due to lock timeout`.

## Recomendação

**Liberar.** Nenhum achado bloqueia. Registrar:

1. **S1**, a soma das parcelas pode deixar de fechar depois de um ajuste por parcela. Decidir se o contrato diz isso explicitamente ou se o web avisa.
2. **Cobertura pendente:** a corrida de desativar que apaga antes da confirmação, arquivar em paralelo com confirmar, e meia-noite UTC ao vivo.

O web não foi testado aqui. A decisão de liberar é do tech-lead.

## Como refazer

Roteiro em Python (stdlib) em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f7/`, fora do repositório: `f7_lib.py` (cliente HTTP, SQL pelo container, registro), `f7_setup.py` (usuários, contas, recorrências, divisão, transferência, previstas levadas ao passado), `f7_run.py` (blocos de checagem). `reset_all.sh` recria o container e a API. As credenciais foram apagadas, então é preciso gerar novas.
