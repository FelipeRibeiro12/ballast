# Relatório QA: F8 Saldo projetado (ADR-029)

**Data:** 2026-10-20, horário de São Paulo, tarde.
**Branch:** `feat/f8-saldo-projetado`. Testado com a API de `253db9c`, que era o HEAD no início. Nenhum arquivo de produção foi alterado: `git diff -- api/` vazio ao fim.
**Escopo:** `GET /saldo-projetado` contra um cálculo independente, dia a dia; parâmetros; autorização; faixa; banco fora; volume; log; contrato HTTP. Web não testado (outro agente).
**Ambiente:** container descartável `ballast-qa-f8` (`postgres:17`, `127.0.0.1:55438`). Papel da aplicação `ballast_app` só com LOGIN, sem superusuário. Migração e preparo de dados como `qa_dono`, superusuário só do container. API na porta 18087, subida de uma cópia do jar de `253db9c` com `REGISTRO_ABERTO=true`, `APP_ENV=development`, `COOKIE_SECURE=false`, `HOST=127.0.0.1` e `--ballast.recurrence.job-enabled=false`, para o job das 3h não mexer nos dados durante o roteiro. Não li nem imprimi nenhum `.env`. Não usei o banco `ballast-db-1` nem a API da 8080. Credenciais geradas para a sessão e apagadas ao fim. API e container derrubados.
**Não cobre:** virada de dia ao vivo, web, fevereiro bissexto (ver "Não testado").

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B -q verify` em `253db9c` | saiu com código 0. Relatórios desta execução: 87 arquivos, 890 testes, 0 falhas, 0 erros, 0 ignorados |

Verde antes de qualquer teste manual.

## Resumo

- **Achados:** 0 Bloqueia, 0 Dívida, 2 Sugestão.
- **Checagens:** 81 no total, 81 passaram. 79 registradas no roteiro final mais 2 de faixa no piso negativo, rodadas em script avulso. Nenhuma falhou depois da correção de roteiro descrita abaixo.
- **Travam primeiro:** nenhum.
- **Recomendação:** **liberar.** Nenhum achado bloqueia: os números batem com o cálculo independente centavo a centavo, nenhuma rota respondeu 5xx, a autorização passou em todas as rotas testadas e o banco fora volta sem reiniciar a API. Os dois achados de Sugestão são de contrato e de tratamento de parâmetro. A decisão é do tech-lead.

## Achados

### Bloqueia

Nenhum.

### Dívida

Nenhuma.

### Sugestão

**S1. Parâmetro de consulta não declarado é ignorado em silêncio.**

- **O que acontece:** `GET /saldo-projetado?dias=10&contaid=<id de conta do usuário>` (com `c` minúsculo) responde 200 com `contaId: null` e a série somada de todas as contas ativas. O filtro some sem aviso e o número muda de escopo.
- **Como reproduzir:** logar como A. `GET /saldo-projetado?dias=10&contaid=01a15a96-df16-772f-9b60-d225a10d05a5` (id de uma conta de A). Observado: 200, `contaId: null`, `saldoAtualCentavos` de todas as contas ativas.
- **O que deveria acontecer:** 400 para parâmetro desconhecido, ou o contrato dizer que é ignorado. O corpo de `POST` recusa campo não declarado (`docs/contracts/api.md`, "Campo não declarado dá 400"), e a GET não diz nada.
- **Padrão do projeto:** o mesmo acontece em `GET /lancamentos?de=2026-10-01&ate=2026-10-31&conta=<id>`, que responde 200 com a lista de todas as contas. Não é só esta rota.
- **Gravidade:** erra em silêncio. Classificada como Sugestão porque o cliente web é gerado da spec e hoje não há caminho de uso que erre; registrar e decidir.

**S2. `vencidasCentavos` depende do filtro, e a descrição do OpenAPI não diz isso.**

- **O que acontece:** sem `contaId`, `vencidasCentavos` soma só as contas ativas. A prevista vencida de R$ 400,00 da conta arquivada "Antiga QA" não entra. No cenário: `vencidasCentavos` sem filtro = -2000 (R$ -20,00); com `contaId` da conta arquivada = -40000.
- **Como reproduzir:** logar como A. `GET /saldo-projetado?dias=1` e depois `GET /saldo-projetado?dias=1&contaId=<id da conta arquivada>`. Observado: -2000 e -40000.
- **O que deveria acontecer:** o número está certo pela regra do ADR-029 ("todas as contas ativas por padrão"), mas a descrição de `SaldoProjetado.vencidasCentavos` no OpenAPI diz só "Soma com sinal das previstas com dataCaixa antes de hoje", sem a ressalva de escopo. Quem lê a spec espera incluir a arquivada. A tela que avisa "tem vencida" na visão de todas não avisa dos R$ 400,00.
- **Gravidade:** ambiguidade de contrato, sem número errado. Sugestão: dizer "das contas do filtro" na descrição do OpenAPI e no `api.md`, ou decidir que a arquivada com vencida entra no aviso.

## Não testado ou não reproduzido

- **Virada do dia ao vivo.** O relógio do servidor está em UTC e a meia-noite de São Paulo é 03:00 UTC. A janela não foi alcançada, e não há `faketime` no ambiente. Conferido só por leitura: o controlador converte o `Clock` (UTC) para `America/Sao_Paulo` antes de tirar a data de hoje, e `inicio` bateu com a data de São Paulo em todas as chamadas. Não reproduzi a virada.
- **Fevereiro bissexto.** A massa cobre 31 de fevereiro (2027, não bissexto). O horizonte de 366 dias a partir de 2026-10-20 termina em 2027-10-20 e não passa por 29 de fevereiro. Não testado.
- **Corrida com arquivar ou com desativar recorrência** durante a leitura. Não rodei.
- **Web.** Fora do escopo.

## O que passou

Números do roteiro final, a partir dos arquivos do scratchpad, fora do repositório.

- **Cálculo independente (oráculo), antes e depois do volume.** O oráculo lê os lançamentos crus por SQL (`Decimal`, sem a consulta de produção) e aplica as regras do ADR-029: sinal por tipo, transferência por perna, cancelado fora, saldo acumulado até cada dia, entradas e saídas do dia, `saldoAtual`, vencidas e menor saldo com o primeiro dia em empate. Comparação exata, dia a dia, centavo a centavo.
  - 15 cortes: 30, 90 e 366 dias, cada um com todas as ativas, C1, C2, C3 e a conta arquivada C4. Rodados na passada antes do volume e de novo na passada final, depois dele. Todos bateram nas duas.
  - Padrão sem `dias` = 90 dias, com o mesmo corpo de `dias=90`.
  - 4 cortes com o volume (90 e 366 dias, todas e C2). Todos bateram.
- **`saldoAtualCentavos` contra `GET /contas`.** Bateu para C1, C2, C3, C4 arquivada e para a soma das ativas.
- **Parâmetros (24 checagens).** `dias` 1 e 366 aceitos. Recusados com 400 e a mensagem do contrato: 0, 367, -1, 1.5, `abc`, vazio, repetido, `+5`, `005`, 1000, dígito árabe, `1e2`, número de 20 dígitos. `contaId` malformado, vazio, repetido, sem hífen e com espaço: 400 `contaId deve ser um UUID`. `contaId` inexistente, de B e nil: 404 `conta não encontrada`. UUID maiúsculo canônico aceito.
- **Autorização.** A pedindo conta de B: 404. B pedindo conta de A: 404. Corpo de conta alheia e de conta inexistente byte a byte igual. Sem cookie: 401, inclusive com `contaId` e com parâmetro inválido.
- **Contrato HTTP.** `Accept: text/html` dá 406. `POST` dá 405. Chaves da resposta e de cada dia são exatamente as do contrato. `inicio` é o dia de São Paulo, `fim` é `inicio + dias - 1`, `contaId` é null sem filtro, valores são inteiros de centavos.
- **Faixa.** Teto positivo com conta em +9.007.199.254.740.991 centavos: `dias=12` dá 200, `dias=13` dá 400 `projeção fora do limite` (a prevista de +1 centavo cai em 1º de novembro, offset 12). Dois tetos sem `contaId`: 400. Teto B com 366 dias: 200. Piso negativo em -9.007.199.254.740.991: `dias=12` dá 200, `dias=13` dá 400.
- **Previstas na fronteira.** Previstas com `dataCaixa` hoje (6) entram no primeiro ponto e não em vencidas. Previstas em T+365 (6) entram no último ponto de 366. Em T+366 (4) não entram.
- **Banco fora.** `docker pause`: 503 `serviço indisponível` em cerca de 2,0 s, com e sem `contaId`. Depois do `unpause`, 200 com corpo igual ao de antes, sem reiniciar a API. `docker stop`: 503 em 5 a 10 ms. Depois do `start`, 200 com corpo igual.
- **Volume.** 20.019 realizados e 2.116 previstas no usuário A. As 2.000 previstas extras foram inseridas pelo dono sem recorrência (`template_id` nulo, permitido pelas constraints). A projeção não lê o template, então a premissa não muda o resultado, mas não é o caminho que o usuário real percorre. Tempo, 7 chamadas por corte: 90 dias sem filtro, mediana 23 ms e máximo 48 ms. 90 dias com C2, mediana 11 ms e máximo 12 ms. 366 dias sem filtro, mediana 22 ms e máximo 34 ms. 366 dias com C2, mediana 12 ms e máximo 14 ms.
- **Concorrência de leitura com escrita.** Uma confirmação de vencida (200) com 4 leitores paralelos de 6 chamadas cada: as 24 leituras batem com o estado antes ou com o estado depois da confirmação. Nenhuma leitura mostrou meio-termo.
- **Log.** 650 linhas no log do processo da API. Nenhum título de teste, nenhum valor, nenhum e-mail, nenhuma senha, nenhum token (busca por `QA`, pelos valores e pelos e-mails: zero). O login registra `usuario_id` e IP, sem e-mail. Durante a pausa, o erro de banco aparece no log com stack trace, e o cliente recebe 503 genérico.

## Correções de roteiro (para o registro)

- Na primeira passada, a bateria falhou por erro meu no script ao listar contas (usei só `arquivadas=true`). Corrigi e rodei de novo do zero.
- Duas sondas de corrida e de `Accept` saíram com o id ou o cookie errados. Corrigi e rodei de novo. O resultado final é o da passada corrigida.
- Antes do volume, movi três previstas ativas para o passado com o dono (uma receita de Freela e duas despesas), porque as vencidas de contas ativas tinham sido todas confirmadas no preparo. As previstas vencidas da conta arquivada foram movidas só no preparo.

## Como refazer

Roteiro em Python (stdlib) em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f8/`, fora do repositório: `f8_lib.py` (cliente HTTP, SQL pelo container e oráculo), `f8_setup.py` (cenário dos dois usuários), `f8_run.py` (oráculo, parâmetros, autorização, faixa), `f8_perf.py` (oráculo e tempo com volume), `f8_probe2.py` (parâmetro desconhecido, `Accept`, método, corrida), `f8_outage.py` (pause e stop). `launch_api.sh` sobe a API na 18087. As credenciais foram apagadas, então é preciso gerar novas e recriar o container com `POSTGRES_USER=qa_dono`, criar o papel `ballast_app` com LOGIN e sem superusuário, e rodar `f8_setup.py` antes do resto.
