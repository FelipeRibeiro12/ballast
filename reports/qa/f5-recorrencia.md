# Relatório QA: F5 Recorrência (ADR-026)

**Data:** 2026-10-19, horário de São Paulo, execução entre 22:37 e 23:05.
**Branch:** `feat/f5-recorrencia`. Na abertura da sessão, HEAD `5a3c4e9` e árvore limpa. Durante a sessão o outro agente commitou `8ed6dbb` (`feat: adiciona recorrências e ocorrências previstas no web`), que mexe só em `web/`. O diff de `api/` entre os dois commits é vazio, então o que foi testado vale para a API atual. Nenhum arquivo de produção foi alterado por mim.
**Escopo:** API de recorrência (`POST`, `GET`, `PATCH /recorrencias`), materialização e job diário, ações sobre a ocorrência (`PATCH`, `PUT` e `DELETE /lancamentos` numa ocorrência), espera de trava (`WriteTransaction`), log, banco fora, volume. Web não testada.
**Ambiente:** container descartável `ballast-qa-f5` (`postgres:17`, `127.0.0.1:55435`). Migração como `qa_dono` (superusuário só do container descartável). Aplicação com `ballast_app`, papel só com LOGIN, criado pelo `db/init/01-cria-usuario-da-aplicacao.sh` do repositório em modo leitura. API na porta 18084, subida com variáveis na linha de comando, com `REGISTRO_ABERTO=true`, `APP_ENV=development`, `COOKIE_SECURE=false` e `--ballast.recurrence.cron='*/20 * * * * *'` no lugar das 03:00. Três usuários: A e B para autorização e um terceiro para volume. Não usei o Postgres do compose do dono, não li nem imprimi nenhum `.env`. Credenciais descartáveis apagadas no fim; API e container derrubados.
**Não cobre:** navegador, HTTPS, idempotência de requisição (o contrato não tem), o cron de produção `0 0 3 * * *` ao vivo, e o dia real passando (o relógio é o do sistema; "passado" foi simulado com fixture no banco, ver "Não testado"). Também não reproduzi o impasse PUT ou confirmar contra o job, só o caso de espera do job (D1).

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` | BUILD SUCCESS, 726 testes, 0 falhas, 0 erros, 0 ignorados |

Verde antes de qualquer teste manual. O log de testes tem uma linha `APPLICATION FAILED TO START`, que é um teste negativo de configuração (`COOKIE_SECURE=false` sem `APP_ENV`), esperado.

## Resumo

- **Achados:** 2 Bloqueia, 2 Dívida, 4 Sugestão.
- **Travam primeiro:** B1 (espera de trava passa de 2 s, contrato violado) e B2 (senha gerada do Spring Security impressa no log, regra do `seguranca.md`).
- **Checagens automatizadas:** 186 passaram de 192 no registro bruto. Das 6 que falharam, 4 são os achados (B1 em duas medidas, D2, S1) e 2 são erro do meu roteiro, refeitas e passaram na rerrodada (ver "Erros do meu roteiro").
- **Recomendação:** **segurar.** B1 e B2 estão nas regras de bloqueio do projeto. D1 é a causa de B1 e precisa de decisão sobre a ordem de travas do job, que hoje contradiz o ADR-026. D2 pode entrar como dívida registrada. As sugestões vão para o backlog. A decisão é do tech-lead.

## Achados

### Bloqueia

**B1. Espera de trava passa de 2 s quando o job está na fila da conta.**

- **O que acontece:** com a conta travada por outra transação, `POST /lancamentos` responde 503 em 2,0 s se a conta não tem template. Se a conta tem template ativo, o job entra na fila da trava da conta e a escrita do usuário sai com 503 só depois de 3,7 s. Medido: `POST /recorrencias` 2,0 a 4,0 s; `POST /transferencias` 3,3 a 3,8 s; `POST /lancamentos` 4,0 s na primeira tentativa. O contrato (`docs/contracts/api.md`, Espera de trava) diz "espera no máximo 2 s".
- **Medidas isoladas:** conta sem template, quatro `POST /lancamentos` com conta travada: 2,02, 2,03, 2,03 e 2,03 s. Conta com 34 templates, mesma situação: 2,02 s e depois 3,73, 3,70 e 3,71 s.
- **Como reproduzir:**
  1. Criar uma recorrência mensal na conta X (qualquer, com template ativo). Isso deixa a conta com um template para o job.
  2. Abrir sessão psql e manter aberta: `BEGIN; SELECT id FROM conta WHERE id='X' FOR UPDATE; SELECT pg_sleep(22); COMMIT;`
  3. Por 20 s, repetir `POST /lancamentos` com `contaId` X e medir o tempo de cada resposta.
  4. Para comparar, repetir com uma conta sem nenhum template.
  Roteiro: `s7c_fila_job.py` e `s7b_tempo.py`, no scratchpad da sessão.
- **O que deveria acontecer:** 503 em até 2 s, mensagem `serviço indisponível`, nada gravado.
- **Observado:** 503 em 3,7 s quando o job está na fila. O log mostra, a cada tick, `recorrência pulada por trava ocupada` para cada template da conta, cada um esperando 2 s.
- **Causa:** D1. O job pega a conta por template, em série, e cada espera de 2 s entra antes da escrita do usuário na fila da trava.
- **Gravidade:** Bloqueia, por contrato violado. Só latência: nada é gravado e não há perda de dado.

**B2. Senha gerada do Spring Security impressa no log de arranque.**

- **O que acontece:** em toda subida, o log da API traz `Using generated security password: <uuid>` seguido do aviso de que a senha é só para desenvolvimento. É credencial gerada pelo framework, não do usuário.
- **Uso hoje:** não é explorável por esta via. `SecurityConfig` desliga `formLogin` e `httpBasic` (linhas 47 a 49), e não há `UserDetailsService` próprio. Ainda assim, `seguranca.md` manda que senha nunca apareça em log, e a lista de "parar e avisar" inclui dado sensível em log.
- **Como reproduzir:** subir a API (`java -jar api/target/ballast-0.0.1-SNAPSHOT.jar` com as variáveis de ambiente) e procurar `generated security password` no log de arranque. Apareceu na linha 56 do log da sessão.
- **O que deveria acontecer:** nenhuma senha no log de arranque.
- **Escopo:** fora da F5, afeta toda subida da API. Registro aqui como aviso imediato, conforme a regra do projeto.
- **Gravidade:** Bloqueia pela regra de dado sensível em log. Não é explorável hoje.

### Dívida

**D1. O job segura a conta enquanto espera linha de lançamento (ordem de travas invertida).**

- **O que acontece:** a materialização trava o template, depois a conta (`lockAccount`, `FOR UPDATE`, em `RecurrenceService.java:150`) e só então roda `INSERT ... ON CONFLICT (template_id, ocorrencia_data) DO NOTHING` (`RecurrenceQueries.java:42`) para todas as datas da janela, a cada rodada. Para cada data que já existe, o `INSERT` espera a transação que estiver editando aquela linha. Ou seja, o job segura a conta e espera um lançamento. O ADR-026 diz "lançamento, depois conta" e afirma que não há ciclo.
- **Como reproduzir:** sessão psql com `BEGIN; UPDATE lancamento SET descricao = 'trava' WHERE id='<ocorrência de um template ativo>';` mantida por 26 s. Durante a janela, chamar `POST /lancamentos` na mesma conta e consultar `pg_stat_activity` filtrando `wait_event_type = 'Lock'`. Roteiro: `s7d_linha_job.py`.
- **Observado:** o job pulou o template em vários ticks durante a janela, com `recorrência pulada por trava ocupada` no log. A consulta mostrou o `INSERT INTO lancamento` do job esperando `transactionid` da minha sessão, e o `SELECT arquivada ... FOR UPDATE` da escrita da API esperando `transactionid` do job. A escrita terminou em 1,93 s com 201, quando o job desistiu pelo `lock_timeout`.
- **Não reproduzido:** impasse entre um `PUT` ou `PATCH` (que seguram a linha e depois pedem a conta) e o job (que segura a conta e pede a linha). Em tese os dois param, e o `lock_timeout` de 2 s corta, então vira 503 e não impasse eterno. Não testei esse cruzamento.
- **O que deveria acontecer:** o job não deve esperar linha de usuário enquanto segura a conta, ou a ordem do ADR-026 deve valer também para o job. A escolha é do tech-lead.
- **Gravidade:** Dívida. É a causa de B1, sem perda de dado e limitada pelo `lock_timeout`. Corrigir junto com B1.

**D2. `proximaOcorrencia` usa a data da regra, não a data de caixa, para ocorrência editada.**

- **O que acontece:** `RecurrenceQueries.java:51-53` calcula `min(l.ocorrencia_data)`. Depois de um `PUT` mudar a data de uma prevista, a lista do mês e o saldo mostram a data nova, mas `proximaOcorrencia` continua com a data original.
- **Como reproduzir:**
  1. Criar `POST /recorrencias` mensal, dia 31, início 2026-10-19, conta A1.
  2. `PUT /lancamentos/{id}` na ocorrência de 2026-12-31, com `data` 2026-12-05.
  3. `GET /lancamentos?de=2026-12-01&ate=2026-12-31` mostra a prevista em 2026-12-05.
  4. `GET /recorrencias/{id}` devolve `proximaOcorrencia: "2026-12-31"`.
  5. Depois de `PATCH /recorrencias/{id}` com `{"ativo": false}`, a prevista desgarrada continua, e o campo continua em 2026-12-31.
  Roteiro: `s3b_desativar.py`.
- **O que deveria acontecer:** `api.md` diz que `proximaOcorrencia` é "a menor data prevista de hoje em diante". A data que o usuário vê numa prevista é a `dataCaixa`, então o esperado é 2026-12-05.
- **Observado:** 2026-12-31.
- **Leitura:** tomei "data prevista" como `dataCaixa`, pelo contrato de lista. Se o tech-lead quiser outra leitura, o contrato deve dizer.
- **Gravidade:** Dívida. Campo de leitura com valor errado, sem efeito no saldo nem na lista.

### Sugestão

**S1. Frequência inválida responde mensagem genérica.** `POST /recorrencias` com `"frequencia": "quinzenal"` responde 400 `requisição inválida`. A constante `RuleRequest.FREQUENCY_MESSAGE` (linha 80) tem o texto certo, mas só entra no caso nulo (linha 19). O contrato é cumprido (mensagem fixa, sem o valor enviado), só não é a mensagem que o código prevê. Os outros campos (`intervalo`, `diaDoMes`, `inicio`) dão a mensagem certa.

**S2. Cancelar e apagar uma ocorrência devolve a data como prevista.** Com o template ativo, uma ocorrência cancelada e apagada (204) volta como `previsto` na rodada seguinte do job. O contrato diz isso (`api.md`, Ocorrência). O usuário que apaga uma cancelada para limpar a lista a vê reaparecer. Decisão de produto: manter, ou apagar cancelada só quando o template está inativo. Registrar no backlog.

**S3. Arquivar ou desarquivar conta (`PATCH /contas/{id}`) não tem `lock_timeout`.** Com a conta travada por outra transação, o `PATCH` espera até a trava ser liberada: respondeu 200 em 29 s, com a trava segurada por cerca de 40 s. O contrato lista só lançamento, transferência e recorrência como sujeitos à espera de 2 s, então não viola o contrato. Mas `POST /lancamentos` na mesma conta dá 503 em 2 s, e o usuário vê comportamentos diferentes para a mesma conta.

**S4. Log de rodada do job.** Pulo e falha logam `usuario_id` e `template_id`. Rodada com `ocorrencias > 0` loga só o agregado (`templates=... ocorrencias=...`), sem qual template recebeu. Quando o banco cai no meio, a rodada inteira aborta antes da linha de resumo e o Spring registra `Unexpected error occurred in scheduled task` com stack trace, sem SQL com valores. A rodada volta a funcionar quando o banco volta.

## Não testado ou não reproduzido

- **Impasse PUT ou confirmar contra o job (D1).** O mecanismo está comprovado, mas o cruzamento que faria o impasse não foi montado.
- **Dia que passa.** O relógio é o do sistema e não há como avançá-lo. Para simular "ontem", alterei no banco a data de uma linha realizada (`ocorrencia_data` e `data_caixa`) e então o `DELETE` de realizada passada seguiu o contrato (não voltou). Não está comprovado o que acontece com uma prevista que vence (fica `previsto` para sempre, o contrato não diz). É observação de produto, não falha reproduzida.
- **Cron de produção.** O job foi exercitado com `*/20 * * * * *`. A expressão `0 0 3 * * *` não foi rodada ao vivo; coberta pelo teste de configuração da suíte.
- **Duplo `POST /recorrencias` idêntico.** Confirmado: duas séries diferentes (201 e 201). Já é conhecido e documentado no contrato como ausência de idempotência de requisição. Não é achado novo.

## O que passou

Números de checagens por bloco, do registro bruto.

- **Datas (29 templates, 29 passaram).** Mensal dia 31, 29 e 30 a partir de hoje (fevereiro de 2027 cai em 28, 12 datas cada); mensal dia 31 com intervalo 2 a partir de janeiro de 2026 (30/11, 31/01, 31/03 ...); mensal dia 15 com intervalo 3 (15/01, 15/04, 15/07, 15/10 de 2027); anual com início 29/02/2024 (próxima é 28/02/2027, única dentro do horizonte); diária intervalo 1 (366 datas, de 19/10/2026 a 19/10/2027) e intervalo 7 (53); semanal em cada dia da semana com intervalo 1 e 2, com o dia da semana conferido no banco (`extract(dow)`); fim e ocorrências máximas com início no passado (as que caíram antes de hoje contam para o máximo: `ocorrenciasMax 20` a partir de 10/10 materializou 11, de 19/10 a 29/10; `ocorrenciasMax 5` a partir de 10/10 materializou 0); início futuro dentro do horizonte (2027-03-01 gerou 8); início além do horizonte (zero, `proximaOcorrencia` nulo); fim igual ao início no limite do horizonte (2). Cada template comparado com um oráculo independente (varredura dia a dia no Python), com conferência de `ocorrencia_data`, `data_caixa`, `data_competencia`, status, valor, tipo e `ocorrenciasMaterializadas`.
- **Idempotência.** 1.041 linhas, contagem igual antes e depois de 70 s (três ou quatro rodadas do job, todas com `ocorrencias=0`). Sem duplicata por (template, data) em todo o banco.
- **Ocorrências.** Confirmar debita o saldo uma vez, na data de caixa. Confirmar de novo dá 400 `só ocorrência prevista pode mudar de status`. `PATCH` com `previsto` dá 400 `status deve ser realizado ou cancelado`. Cancelar não mexe no saldo, e o job não recria a cancelada. `PUT` com nova data marca `desgarrado`, mantém `ocorrencia_data`, e o job não cria nada na data original nem sobrescreve. `DELETE` de prevista dá 400 `cancele a ocorrência` e não apaga. `DELETE` de realizada futura apaga e o job recria como previsto em nova linha. `DELETE` de realizada passada não volta. Com o template inativo, realizada futura apagada não volta. `DELETE` de cancelada com template ativo volta como previsto (S2).
- **Desativar.** Apaga exatamente as nove previstas não desgarradas de hoje em diante, e mantém realizada, cancelada, desgarrada e passada. Repetir dá o mesmo estado. `{"ativo": true}` dá 400 `reativar não é suportado`. Campo extra dá 400. Depois, o job não gera nada para o template.
- **Autorização (25 de 25).** Usuário A com GET e PATCH de recorrência de B: 404 com o mesmo corpo, e nada muda em B. A criando com conta de B: 400 `conta não encontrada`. A criando com categoria de B ou de tipo errado: 400 `categoria não encontrada`, nada gravado. A confirmando, cancelando, editando e apagando ocorrência de B: 404. A lendo ocorrência de B: 404. Lista de A não contém nada de B. Sem cookie: 401 em todas as rotas. Id que não é UUID e id inexistente: 404 com o mesmo corpo.
- **Validação (42 de 43).** Intervalo 0 e 100, `mensal` sem `diaDoMes`, `semanal` sem `diaDaSemana`, `diaDoMes` em semanal, `diaDaSemana` em mensal, `diaDoMes` em diária, `diaDaSemana` em anual, `diaDoMes` 0 e 32, `diaDaSemana` 7, `fim` antes de `inicio`, `fim` com `ocorrenciasMax`, `ocorrenciasMax` 0 e 10001 (e 10000 aceito), `inicio` 2026-02-30, 1899-12-31 e ausente, campo extra na raiz e na regra, `valorCentavos` 0, -1, 12.5 e texto, `tipo` transferência, título só com espaços, de 101 caracteres e com quebra de linha, regra e categoria ausentes, JSON truncado (400), `text/plain` (415), corpo de 4 KiB (413), `ativo` como texto, sem `ativo` e nulo no `PATCH`. A exceção é S1. Recusas não gravaram nada. Descrição de 500 caracteres e `fim` e `ocorrenciasMax` nulos foram aceitos.
- **Conta arquivada.** Criar recorrência na conta arquivada dá 400 `conta arquivada`. Confirmar prevista na conta arquivada dá 400 e a prevista segue `previsto`. Cancelar funciona (200). O job não recria ocorrência apagada numa conta arquivada, e recria numa conta ativa (controle com a mesma fixture).
- **Corrida.** Dez confirmações paralelas da mesma prevista: um 200, nove 400, saldo debitado uma vez. Confirmar em paralelo com desativar, quatro rodadas: sempre consistente (ou a linha ficou `realizado` com saldo debitado uma vez, ou a linha foi apagada). Desativar em paralelo com o job, seis templates com atrasos aleatórios, com a cauda do horizonte apagada no banco para o job ter o que inserir: 200 em todos, nenhuma linha criada depois da resposta, nenhuma prevista restante. Arquivar sobreposto a 334 confirmações em três threads: 64 antes do 200 do arquivar, 265 depois, nenhuma depois da resposta voltou 200, saldo final igual a 64 confirmações.
- **Espera de trava (além de B1).** Conta travada: `POST /lancamentos`, confirmar, `POST /transferencias` e `POST /recorrencias` dão 503 (2,0 a 4,0 s, ver B1), nada gravado (contagens de lançamento e template iguais antes e depois). `GET /contas` com a conta travada responde em 0,01 s (leitura não espera). Template travado: `PATCH` de desativar dá 503 em 2,02 s e o template segue ativo. O job pula o template travado e loga `usuario_id` e `template_id`. Depois de soltar, `POST /lancamentos` volta a 201.
- **Banco fora.** `docker pause`: POST e GET de recorrência dão 503 em 2,0 s, PATCH de lançamento dá 503 (não 500), `/healthz` dá 503 e a API não cai. O job registra o erro e não morre. `docker unpause`: POST volta a 201 sem reiniciar a API, e o job volta a rodar (linhas de rodada voltam a aparecer). `docker stop`: POST dá 503 em 2,0 s. `docker start`: POST volta a 201 em 2 s, sem reiniciar a API, e o job volta a rodar.
- **Sinal e teto.** Confirmar receita soma no saldo (+2500). Confirmar receita que estoura o teto dá 400 `saldo da conta fora do limite` e a prevista segue `previsto`. `PATCH` de cancelar numa perna de transferência dá 400 `use a rota de transferência`, e a perna segue `realizado`.
- **Log (além de B2, nada a reportar).** Procurei no log da sessão, e não apareceram e-mail, senha, cookie de sessão, título, descrição de 500 caracteres, nome de conta, senha de banco nem SQL com valor. A stack de erro de trava mostra o SQL com `?`, sem valor. O job loga `usuario_id` e `template_id` nos pulos e falhas (S4 para o caso de sucesso).
- **Integridade do banco** (depois de todos os cenários, 23.089 linhas de recorrência): duplicata por (template, data) 0; previstas não desgarradas de template inativo, de hoje em diante, 0; linha com template e `ocorrencia_data` desalinhados 0; desgarrado sem template 0; `data_caixa` diferente de `data_competencia` em previsto de template 0; linha de template de outro usuário 0; previsto com valor diferente do template 0.

## Volume

Usuário novo, uma conta, 50 recorrências diárias com início em 19/10/2026 (366 datas cada, 18.300 previstas).

| Medida | Mediana | Máximo | Observação |
|---|---|---|---|
| `POST /recorrencias` diária de 366 datas (50 chamadas) | 14 ms | 22 ms | 18.300 linhas no total |
| Uma rodada do job com 92 templates, medida pela diferença entre o tick das 23:00:00 e o log das 23:00:00,320 | cerca de 0,3 s | | sem pulos e sem falhas |
| `GET /contas` com 18.300 previstas | 13 ms | 34 ms | saldo correto (0, só previstas) |
| `GET /lancamentos` de novembro (1.500 linhas) | 14 ms | 28 ms | 200 |
| `GET /recorrencias` com 50 itens | 4 ms | 5 ms | 200 |

## Erros do meu roteiro

Falhas que foram erro do meu teste, não do produto. Ficam aqui para ninguém ler como achado.

- **Sobreposição de arquivar com confirmações (S6e), primeira rodada.** Com 61 confirmações, todas terminaram antes do arquivar responder, então o ramo "depois" ficou vazio. Refeito com 334 confirmações e o ramo "depois" exercitado (265).
- **Cancelar e apagar (S12).** Testei o ciclo num template que eu mesmo tinha desativado no bloco anterior. Pelo contrato, template inativo não ressuscita data, então a checagem estava errada. Refeita num template ativo, passou (S2).
- **Checagem de "job segue agendado" durante a pausa (S9).** A checagem aceitava zero linhas de rodada, e ela deu zero. A leitura real está em S4: durante a falha a rodada não loga resumo.
- **Transferência e recorrência em 4 s (S7).** Foi a primeira aparição de B1, no cenário de travas. A medida isolada, com e sem template na conta, confirmou a causa (B1).

## Recomendação

**Segurar a liberação da F5.** Motivos, na ordem:

1. **B1**: a espera de 2 s do contrato é violada quando há template na mesma conta. O impacto é latência, sem perda de dado, mas é contrato violado.
2. **B2**: senha gerada no log de arranque, regra de dado sensível em log do projeto. Não é explorável hoje, mas precisa ser corrigida antes de liberar, por regra.
3. **D1** é a causa de B1 e contradiz o ADR-026 (ordem de travas). A correção exige decisão do tech-lead sobre o job.

D2 pode ir como dívida registrada, se o tech-lead aceitar a leitura de "data prevista" como `dataCaixa` ou esclarecer o contrato. S1 a S4 podem ir para o backlog.

## Como refazer

Roteiro em Python, em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad`, fora do repositório. Resultado bruto em `results.jsonl` do mesmo diretório. Os scripts leem credenciais de um arquivo que já foi apagado, então é preciso gerar um novo com o mesmo formato antes de rodar de novo. Os de corrida e de job dependem de tempo; os de datas e validação são determinísticos.
