# Relatório QA: F10 Cartão de crédito (ADR-031)

**Data:** 2026-10-20, fim de tarde, horário de São Paulo.
**Branch:** `feat/f10-cartao-de-credito`, HEAD `a1a039b`. `git status -- api/` vazio ao fim: nenhum arquivo de produção alterado.
**Escopo:** conta de cartão, regra da fatura, lançamento em cartão (POST, PUT, PATCH, DELETE), recorrência e divisão em cartão, job, faturas, pagamento por transferência, saldo projetado com cartão, corrida, trava, banco fora, log, volume e autorização das rotas de cartão. Web não testado (outro agente).
**Ambiente:** Postgres 17.11 descartável `ballast-qa-f10` em `127.0.0.1:55440`. Papel da aplicação `ballast_app` só com LOGIN, sem superusuário. Migração e preparo como `qa_dono`, superusuário só do container. API na `18089` a partir de uma cópia do jar gerado pelo `verify` em `a1a039b`. Job desligado durante o roteiro, e ligado com cron de 20 s só no teste de recorrência. Não usei `ballast-db-1` nem a API da `8080`. Não li nem imprimi nenhum `.env`. Dois usuários de QA (A e B) e um terceiro para o volume. Container e API derrubados ao fim.
**Roteiro:** Python stdlib, fora do repositório, em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f10/`. O oráculo da regra da fatura, do saldo projetado e das faturas é escrito de novo lá, sem reaproveitar código de produção.

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B -q verify` em `a1a039b`, antes de qualquer teste manual | saiu com código 0. 1054 testes, 0 falhas, 0 erros, 0 ignorados |

Verde antes do roteiro. Os containers do `testcontainers` que a suíte sobe são dela, separados do banco do dono.

## Resumo

- **Achados:** 0 Bloqueia, 1 Dívida, 3 Sugestão.
- **Checagens:** 237 registros no arquivo de resultados, 226 PASS e 11 FAIL. Os 11 FAIL foram todos classificados como erro meu de teste ou de harness (ver "Falhas de teste corrigidas"). Nenhum FAIL é achado de produto. Entre os PASS há registros informativos (tempo, resposta de sonda) e dois sem asserção de verdade, que não contam (ver "Falhas de teste corrigidas").
- **Travam primeiro:** nenhum.
- **Recomendação:** **liberar com dívida registrada.** A Dívida D1 é uma decisão de regra pendente (o que um saque no cartão significa na fatura), não quebra de dado nem de contrato: o contrato escrito descreve o comportamento. Registrar D1 e S1 em `docs/backlog.md` com dono. A decisão é do tech-lead.

## Achados

### Bloqueia

Nenhum.

### Dívida

**D1. Transferência com origem no cartão (saque) baixa saldo e disponível e não entra em nenhuma fatura.**

- **O que acontece:** a transferência com `contaOrigemId` de cartão é aceita (201). O saldo do cartão cai R$ 100,00 e o disponível cai junto. As faturas da janela ficam idênticas antes e depois (total, pago, restante e situação). Uma fatura pode aparecer `paga` com dívida real no saldo.
- **Como reproduzir:** logar como A. `GET /contas/{Cartao C8}`: `saldoCentavos` -875537, `disponivelCentavos` -775537. `GET /contas/{Cartao C8}/faturas?de=2026-10-01&ate=2026-12-31`, guardar a resposta. `POST /transferencias` com `contaOrigemId` = C8, `contaDestinoId` = Corrente QA A, `valorCentavos` 10000, `data` 2026-10-20: 201. Depois: `GET /contas/{C8}` mostra saldo -885537 e disponível -785537. A fatura repetida é a mesma do primeiro `GET`.
- **O que deveria acontecer:** uma decisão explícita e registrada. Hoje `docs/contracts/api.md` diz que o cartão pode ser origem e que pernas de transferência ficam fora de `totalCentavos` e de `pagoCentavos`. Isso é coerente com a escrita do contrato, mas deixa o saldo e a fatura contando coisas diferentes. Opções para o tech-lead: recusar transferência com origem em cartão até a F11, ou tratar o saque como lançamento que entra na fatura pela data de caixa.
- **Gravidade:** Dívida. Erra em silêncio no número que o usuário compara com o aplicativo do banco.

### Sugestão

**S1. Pagamento de fatura aceita qualquer conta de origem, não só a conta de pagamento do cartão.**

- **O que acontece:** `POST /transferencias` de `Dinheiro QA A` para `Cartao C3` (100 centavos) responde 201. A perna de entrada conta em `pagoCentavos` da fatura. O oráculo confirma a soma.
- **Como reproduzir:** logar como A, `POST /transferencias` com `contaOrigemId` = Dinheiro QA A, `contaDestinoId` = Cartao C3, `valorCentavos` 100, `data` 2026-10-20.
- **O que deveria acontecer:** ADR-031 diz que pagar fatura é transferência da conta de pagamento, e o cartão guarda `contaPagamentoId`. O código não confere a origem contra esse campo. Decidir se a origem deve ser a conta de pagamento ou se qualquer conta pode pagar.
- **Gravidade:** Sugestão. Não quebra número nem contrato escrito, mas o usuário pode pagar a fatura a partir de uma conta que o app não associou ao cartão.

**S2. Login válido com quatro ou mais requisições simultâneas para o mesmo e-mail recebe 429.**

- **O que acontece:** dez logins paralelos com a senha certa para o mesmo e-mail: quatro 200 e seis 429 `muitas tentativas, tente mais tarde`. O log registra `login recusado: muitas tentativas email=q***@example.com ... falhas=4 retry_after=2`.
- **Como reproduzir:** dez threads com `POST /auth/login` para o mesmo e-mail, senha certa, sem sessão prévia.
- **O que deveria acontecer:** é o desenho de `IdentityService.authenticate`: a tentativa é reservada e contada antes da senha, e só o sucesso zera o contador. Não é corrupção de dado. O efeito é que um cliente que dispara logins paralelos (várias abas, reenvio no PWA) toma 429 por um ou dois segundos. Decidir se o web deve serializar o login ou se a reserva deve contar só falhas.
- **Gravidade:** Sugestão, incomoda. Não bloqueia.

**S3. Compra com data futura entra como `realizado` e pesa no saldo atual do cartão desde hoje.**

- **O que acontece:** `POST /lancamentos` não aceita status, e o padrão é `realizado` (ADR-024). Compras com data de 2027 e 2028 entraram como `realizado`. O `saldoCentavos` de hoje e o `disponivelCentavos` já descontam essas compras. O oráculo do saldo atual confirma.
- **Como reproduzir:** `POST /lancamentos` em Cartao C5 com `data` 2028-02-29: resposta com `status` `realizado`. `GET /contas/{C5}` já desconta o valor.
- **O que deveria acontecer:** é o comportamento do ADR-024 e o saldo bate com ele. A pergunta é se o dono quer compra futura como prevista. Confirmar antes de a tela de cartão mostrar "disponível" para o usuário.
- **Gravidade:** Sugestão, pergunta de produto.

## Falhas de teste corrigidas

Onze FAIL do arquivo de resultados não são achados. Cada um tem a causa e, quando foi o caso, o rerun que passou.

| Registro | Causa | Como ficou |
|---|---|---|
| `lanc.patch_cancelado_cartao` | Meu teste cancelou um lançamento já realizado. O contrato recusa (só prevista muda de status). | API correta, 400. Cancelamento coberto na recorrência. |
| `rec.c2_max14.competencia_e_caixa` | Janela de `GET /lancamentos` até o horizonte, mas o filtro é por `dataCaixa`, e as parcelas de set/2027 vencem em nov/2027. | API materializou 9, como o oráculo. Recheck com janela correta. |
| `rec.divisao_c3.competencia_caixa_ordem` | Mesma causa. | Recheck: 35 parcelas, competência, caixa e ordem batem. |
| `fatura.vencimento_sem_compra_zerado` | Premissa minha: o cartão Poupança recebeu pagamento de R$ 40,00 em outubro. | As 3 faturas batem com o oráculo. |
| `fatura.janela_367_dias_recusada` | Janela de 2026-10-01 a 2027-10-01 tem 366 dias contando as pontas, e é aceita. | Confirmado: 367 dias (até 2027-10-02) dá 400 com a mensagem do contrato. |
| `corrida.compras_paralelas_respeitam_o_teto` e `corrida.pagamento_e_compra_paralelos_sem_5xx` | Cada thread fazia login próprio e tomou 429 (S2). Os 401 foram do harness. | Rerun com uma sessão compartilhada: 3 aceitas e 7 recusadas pelo teto, e pagamentos e compras fechando o saldo. |
| `corrida.setup_cartao_no_teto` | Nome repetido de execução anterior. | Rerun com nome único: 201. |
| `rec.cartao_arquivado_recusado` e `lanc.cartao_arquivado_recusado` | Arquivei a conta de pagamento do cartão, não o cartão. Os 201 estavam certos. | Rerun arquivando o próprio cartão: 400 `conta arquivada` nas duas rotas. |
| `job.apos_apagar_zero` | Corrida de tempo: o job rodou às 17:14:00, no meio do meu `sleep(1)`, e regenerou a ocorrência. O contrato prevê isso. | Job correto: regenerou uma vez como prevista, com caixa 2027-01-05, e as rodadas seguintes não duplicaram. |

Dois PASS não contam por não terem asserção: `rec.recheck.divisao_c3_fatura_28_12` (condição `or True`) e `rec.recheck.c2_max14_caixa_completa_com_janela_certa` (`True` fixo). Descarto ambos. Os demais registros `True` são informativos (tempo, resposta de sonda).

## O que passou

Os números são do roteiro final. Os scripts estão no scratchpad, fora do repositório.

**Regra da fatura contra oráculo.** Oito cartões com fechamento e vencimento em 1/15, 15/10, 28/28, 30/31, 31/5, 31/31, 20/25 e 15/15. Compras em 123 dias por cartão: outubro/2026 inteiro, 20/12/2026 a 10/01/2027 (virada do ano), 25/01 a 05/03/2027 (fevereiro de 2027, sem bissexto) e 01/02 a 01/03/2028 (fevereiro de 2028, bissexto, com compras no futuro). Total: 984 POSTs, e `dataCaixa` do banco igual ao oráculo em 984 de 984. `dataCompetencia` igual à data informada em todas. Uma em cada onze compras foi receita (estorno).
- Fechamento 31 em fevereiro: Cartao C5 (F31 V5), compra em 2027-02-28 cai em 2027-03-05. Cartao C6 (F31 V31) incluído na varredura.
- Vencimento menor que o fechamento: Cartao C2 (F15 V10) e C5 (F31 V5), na varredura inteira.
- Compra no próprio dia do fechamento: Cartao C1 (F1), compra em 2026-10-01 cai em 2026-10-15. Cartao C7 (F20), compra em 2026-10-20 (hoje) cai em 2026-10-25.
- Bissexto: Cartao C5 com compra em 2028-02-29 cai em 2028-03-05. Cartao C3 cai em 2028-04-28.

**Conta de cartão (39 checagens).** Criação com e sem `cartao`. `cartao` em corrente e em dinheiro, recusado. Pagamento em cartão, dinheiro, arquivada, de B e inexistente, com as mensagens do contrato. Corpo de B igual ao de id inexistente, byte a byte. Poupança aceita como pagamento. Dias 0 e 32, decimal, string, ausente: 400. Limite 0, negativo, acima do teto, decimal, string, ausente: 400. Teto exato aceito e um centavo acima recusado. Campo extra no cartão e na conta: 400. Dias 1 e 31 aceitos. Nome repetido sem diferenciar caixa: 409. Disponível: sem dívida = limite, dívida dentro = limite menos dívida, dívida acima = negativo, saldo positivo = limite (capped). Invariante `disponivel = min(limite, limite + saldo)` em todos os 19 cartões. `cartao` nulo nas contas que não são de cartão. Cartão de B: 404 com o mesmo corpo de id inexistente.

**Lançamento em cartão.** POST em cartão: competência = data, caixa = vencimento. PUT corrente para C2, C2 para C4, C4 para corrente, corrente para C1, mesmo cartão com data nova, e cartão para corrente com data de 2027: caixa recalculada em todos os passos. Estorno (receita) em cartão: caixa pelo vencimento. Datas de borda (31/01, 28/02, 29/02) em C3 e C5. Compra acima do limite entra (limite não bloqueia). Teto de saldo no cartão: `MAX` aceito, `MAX+1` centavo recusado com `saldo da conta fora do limite`. Cancelamento de prevista de cartão: sai do total da fatura pelo valor exato: total de R$ 316,13 para R$ 266,13, diferença de R$ 50,00, que é o valor da prevista. DELETE de compra: 204.

**Recorrência e divisão em cartão.** Mensal dia 31 em C5: 12 ocorrências em 2026-10-31, 11-30, 12-31, 2027-01-31, 02-28, 03-31 e assim por diante, com caixa no vencimento. Mensal dia 29 com `ocorrenciasMax` 14 em C2: 9 dentro do horizonte, todas com caixa certa. Divisão de R$ 1.000,00 em C3 com 3333, 3333 e 3334: parcelas de 333,30, 333,30 e 333,40, soma 1.000,00, 35 parcelas com caixa e ordem certas. Confirmar com ajuste de data (2026-10-31 para 2026-11-02): competência nova e caixa 2026-12-05. Confirmar só com valor: caixa mantida. Cancelar prevista: 200. Cancelar com data: 400. Confirmar duas vezes: 400 na segunda. Confirmar parcela de divisão com data nova: caixa 2027-01-28. Desativar: previstas futuras apagadas (0 restante), repetir dá o mesmo estado. `proximaOcorrencia` usa `dataCaixa`.

**Job com cron de 20 s.** Apagar uma cancelada com data futura: o job regenera uma vez como prevista, com caixa certa. Quatro rodadas seguintes sem duplicar. Sem datas repetidas no template.

**Faturas (108 comparações campo a campo).** Nove cartões (C1 a C8 e Poupança) com doze faturas cada, de 2026-10-01 a 2027-09-30. `vencimento`, `fechamento`, `totalCentavos`, `pagoCentavos`, `restanteCentavos` e `situacao` batem com o oráculo em todos. A `dataCaixa` gravada pelo banco bate com a regra em todas as compras. Pagamentos das quatro janelas em `Cartao C4` (F30 V31):
- 15/10 (antes do fechamento de 30/10) foi para a fatura que fechou em 30/09.
- 30/10 (dia do fechamento) também foi para a fatura de 30/09.
- 31/10 (em dia) e 15/11 (atrasado) foram para a fatura de 30/10, com vencimento 31/10.
- 01/12 foi para a fatura de 30/11.
- 31/12 (depois do fechamento seguinte, 30/12) foi para a fatura de 30/12.
Situações na janela de out/2026 a set/2027: 96 faturas, 88 `aberta`, 3 `fechada` e 5 `paga`. Fatura de hoje com fechamento hoje (C7, F20): `aberta`. Vencimento sem compra e sem pagamento aparece com zero (40 faturas). Janela de 366 dias aceita; 367 dá 400. `de` maior que `ate`: 400. Formato fora de AAAA-MM-DD: 400. `ate` ausente: 400. Conta corrente: 404. Cartão de B visto por A: 404 com o mesmo corpo de id inexistente. Sem cookie: 401.

**Pagamento por transferência.** Pernas com a data informada nos dois lados (competência e caixa), para pagamento no dia, atrasado, no fechamento e depois do fechamento. Poupança paga cartão. Conta de origem arquivada: 400 `conta arquivada` (F4). Destino de B e destino inexistente: 400 `conta não encontrada`. Origem de B: 400 `conta não encontrada`.

**Saldo projetado com cartão (13 checagens).** Dez cortes dia a dia contra o oráculo (todas as contas, C3, C4, C7 e a corrente, em 90 e 366 dias): todos batem. Compra de C3 em 2026-10-25 não sai nesse dia (saídas 0). Sai em 2026-11-28 (vencimento), com valor exato. Total de saldo atual igual à soma das contas.

**Corrida.** Dívida inicial a R$ 10,00 do teto, dez compras de R$ 3,00 em paralelo: 3 aceitas, 7 recusadas por `saldo da conta fora do limite`, saldo final igual ao oráculo. Cinco pagamentos e cinco compras de R$ 1,00 em paralelo: sem 5xx, saldo final igual ao de inicial + pagamentos aceitos - compras aceitas.

**Trava e banco fora.** Trava de conta (transação aberta segurando `FOR UPDATE` por 9 s): POST de lançamento responde 503 `serviço indisponível` em 2,02 s, sem gravar. `docker pause`: POST de conta de cartão, GET de faturas e GET de saldo projetado respondem 503 em 2,01 a 2,02 s. Depois do `unpause`, a leitura é igual à de antes e a conta que falhou não foi gravada. `docker stop`: 503 em 0,01 s. Depois do `start`, 200 sem reiniciar a API.

**Volume.** Usuário com 20.000 lançamentos, sendo 2.000 compras no cartão, carregados por `COPY` com datas do oráculo. `GET /contas/{cartão}/faturas` de 2026-01-01 a 2026-12-31 (12 meses): mediana 13 ms, máximo 36 ms, em 7 chamadas. Os 12 totais batem com a soma feita direto no SQL. `GET /contas` (duas contas): mediana 18 ms, máximo 28 ms. Janela de out/2026 a set/2027: mediana 6 ms.

**Autorização (13 checagens nesta varredura, além das de conta e de fatura).** B contra os recursos de A em cada rota de cartão, lançamento, recorrência e transferência: `GET`, `PATCH`, `PUT`, `DELETE`, `GET` de faturas, `GET`/`PATCH` de recorrência, `GET`/`DELETE` de transferência. Todas dão 404 com o mesmo corpo que id inexistente. `GET /lancamentos?contaId=` com cartão de A, pedido por B: lista vazia. Dados de A intactos depois da varredura.

**Log.** 2.108 linhas nos três logs da API. Títulos de teste, valores, senhas, tokens e cabeçalho de autorização: zero. E-mail só aparece mascarado (`q***@example.com`). Stack trace só aparece em erro de banco (503 para o cliente, mensagem genérica). A única linha com `SELECT` é de erro de trava e vem com `?` no lugar dos valores.

## Não testado ou não reproduzido

- **29/2 bissexto na recorrência.** O horizonte é hoje mais 12 meses, então fevereiro de 2028 não é materializado com o relógio de hoje. Só o dia 29 em fevereiro de 2027 (ano não bissexto, cai em 28) foi exercitado.
- **Intervalo maior que 1 na recorrência de cartão.** Não exercitado nesta rodada.
- **Virada do dia ao vivo.** O relógio ficou em 2026-10-20 durante a sessão. A situação `fechada` e `paga` em datas passadas foi exercitada só com 3 e 5 faturas.
- **Cotação.** Fora da F10.
- **Web.** Fora do escopo.
- **Parcelada (F11).** Fora do escopo.

## Incidente de manuseio, para o registro

Num comando de diagnóstico meu, imprimi no terminal as senhas das contas de QA e dos papéis do container, em texto puro. Eram credenciais descartáveis de container local, sem acesso fora dele. Não entraram em log, repositório nem neste relatório. O container e o arquivo de credenciais são apagados ao fim da sessão.

## Como refazer

Scripts em Python stdlib no scratchpad (fora do repositório): `f10_lib.py` (cliente HTTP, SQL pelo container e oráculo da fatura), `f10_setup.py`, `f10_purchases.py`, `f10_contas.py`, `f10_lancamentos.py`, `f10_recorrencia.py`, `f10_pagamentos.py`, `f10_faturas.py`, `f10_saldo.py`, `f10_concorrencia.py`, `f10_banco_fora.py`, `f10_job.py`, `f10_volume_setup.py`, `f10_volume.py`, `f10_probes.py` e `f10_autorizacao.py`. `launch_api.sh` sobe a API na 18089. As credenciais foram apagadas, então é preciso gerar novas, recriar o container com `POSTGRES_USER=qa_dono`, criar o papel `ballast_app` com LOGIN e sem superusuário, e rodar os scripts na ordem de nome.
