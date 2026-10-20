# Relatório QA: F11 Compra parcelada (ADR-032)

**Data:** 2026-10-20, noite, horário de São Paulo.
**Branch:** `feat/f11-compra-parcelada`. O HEAD mudou durante a rodada, de `55c12b0` para `26158a1` (`feat: adiciona compra parcelada no cartão na API e no web`, às 20:43). Eu não commitei nada. Os fontes de `api/` não mudaram depois de 20:19, e a API testada foi buildada em 20:37 a 20:42 a partir deles, então o código de `api/` testado é o de `26158a1`. Web não testado (outro agente). Nenhum arquivo de produção foi alterado por mim: só `api/target/` (saída do `verify`).
**Escopo:** compra parcelada ponta a ponta contra Postgres real: valores, datas, faturas, recusas, gestão, autorização, corrida, banco fora, trava, log, volume e a janela do pago em fevereiro (dívida da F10).
**Ambiente:** Postgres 17.11 descartável `ballast-qa-f11` em `127.0.0.1:55441`. Papel `qa_dono` para preparo e migração, papel da aplicação `ballast_app` só com LOGIN e sem superusuário. API na `18090` a partir de uma cópia do jar do `verify`, com `REGISTRO_ABERTO=true` e job de recorrência desligado. Não usei o banco do compose do dono (`ballast-db-1`) nem a API da `8080`. Não li nem imprimi nenhum `.env`. As senhas de teste ficaram num arquivo fora do repositório, com permissão 600, e não foram impressas. Container e API derrubados ao fim.
**Roteiro:** Python stdlib fora do repositório, em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f11/`: `qa_f11.py` (roteiro e oráculo) e `probes.py` (sondagens finais). O oráculo foi escrito de novo a partir de `docs/contracts/api.md`, da ADR-032 e de `docs/contracts/dados.md`, sem reaproveitar código de produção.

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` no código de `api/` em `26158a1`, antes do roteiro | BUILD SUCCESS. 1153 testes, 0 falhas, 0 erros, 0 ignorados |

Verde antes do roteiro.

## Resumo

- **Achados:** 0 Bloqueia, 1 Dívida, 3 Sugestão. Mais 2 observações sem gravidade, sobre o contrato e sobre o roteiro.
- **Checagens:** 123 na rodada final: 120 PASS e 3 FAIL. Dos 3 FAIL, um é a Dívida D1. Outro é a Sugestão S2, que falha pela minha expectativa de contrato. O terceiro é uma checagem que escrevi com expectativa errada (título da parcela do meio); o comportamento real foi confirmado na sonda P3 e está registrado como S1.
- **Travam primeiro:** nenhum.
- **Recomendação:** **liberar com dívida registrada.** Registrar D1, S1, S2 e S3 em `docs/backlog.md` com dono. Não achei quebra de dado, de contrato escrito, de permissão nem de valor de parcela. Se o tech-lead ler a frase "todo valor fica em ±9007199254740991" como regra geral, e não só da seção de faturas, D1 vira contrato violado e a recomendação passa a ser segurar. A decisão é do tech-lead.

## Achados

### Bloqueia

Nenhum.

### Dívida

**D1. `GET /compras-parceladas/{id}` devolve valor acima de ±9007199254740991 quando a soma das parcelas passa da faixa.**

- **O que acontece:** cartão com saldo inicial +9007199254740991 aceita um PUT que leva uma parcela a 9007199254740991. O saldo fica em -500, dentro da faixa, e o PUT responde 200. Depois o GET da compra devolve `valorTotalCentavos` 9007199254741491. Em JSON, esse número vira `double` no navegador e lê-se como 9007199254741492, um centavo a mais na tela.
- **Como reproduzir:**
  1. `POST /contas` com `{"nome":"Cartão saldo cheio","tipo":"cartao_credito","cartao":{"limiteCentavos":1000000000000,"diaFechamento":15,"diaVencimento":25,"contaPagamentoId":"<corrente>"},"saldoInicialCentavos":9007199254740991}`. Espera-se 201.
  2. `POST /compras-parceladas` com `{"titulo":"Compra F11","valorTotalCentavos":1000,"parcelas":2,"categoriaId":"<despesa>","contaId":"<cartão>","data":"2026-11-10"}`. Espera-se 201, com itens de 500 e 500.
  3. `PUT /lancamentos/<id da parcela 1>` com `{"titulo":"Compra F11","valorCentavos":9007199254740991,"categoriaId":"<despesa>","contaId":"<cartão>","data":"2026-11-10","tipo":"despesa"}`. Observado: 200.
  4. `GET /compras-parceladas/<id>`. Observado: `valorTotalCentavos` 9007199254741491.
  Roteiro: seção `limite-json` de `qa_f11.py`.
- **O que deveria acontecer:** a soma das parcelas de uma compra fica dentro de ±9007199254740991. O PUT que a leva para fora responde 400, como a fatura faz com `fatura fora do limite`, ou o GET recusa. O contrato diz "Todo valor fica em ±9007199254740991" na seção de faturas, e para a compra a regra é implícita.
- **Gravidade:** Dívida. Exige saldo inicial de cartão quase no teto, então é raro. Erra em silêncio no número mostrado. As faturas não são afetadas: a fatura de novembro, com a parcela de MAX, fica em MAX, dentro da faixa.

### Sugestão

**S1. O título de uma parcela do meio fica diferente do título da compra, sem aviso.**

- **O que acontece:** `PUT /lancamentos/<parcela 2>` com `titulo` "Titulo so na parcela 2" responde 200 e o banco grava. `GET /compras-parceladas/<id>` continua mostrando o título da primeira parcela. A lista de `/lancamentos` mostra o título novo na parcela 2.
- **Como reproduzir:** sonda P3 de `probes.py`. Ou: compra 3x com título X, `PUT` da parcela 2 com título Y, `GET` da compra.
- **O que deveria acontecer:** decidir. Ou o PUT de título em parcela responde 400 `edite a compra inteira`, como já acontece com conta e data, ou a compra mostra a divergência. A ADR-032 diz que o PUT numa parcela muda o título e que o GET da compra usa a primeira. A regra está escrita, mas a divergência fica invisível na tela da compra.
- **Gravidade:** Sugestão. Afeta só texto, não valor nem permissão.

**S2. Título aceita caractere de formatação bidi (U+202E).**

- **O que acontece:** `PATCH /compras-parceladas/<id>` com `{"titulo":"‮abc"}` responde 200. O validador `NoControlCharacters` recusa só `Character.CONTROL` (categoria Cc). U+202E é Cf e passa. O mesmo validador vale para `POST /lancamentos`.
- **Cenário concreto:** o próprio dono digita um título que a tela mostra invertido ("Mercado" lido ao contrário). Não há dado de outra pessoa envolvido.
- **O que deveria acontecer:** decidir se caractere bidi entra na lista de recusa. A escolha vale para o projeto inteiro, não só para a F11.
- **Gravidade:** Sugestão. Incomoda na exibição.

**S3. A mensagem do valor vence a mensagem do tipo de conta.**

- **O que acontece:** `POST /compras-parceladas` com `contaId` de conta corrente, `valorTotalCentavos` 1 e `parcelas` 2 responde 400 `cada parcela precisa ter ao menos 1 centavo`. A causa principal é `compra parcelada só em cartão de crédito`.
- **Como reproduzir:** sonda do bloco `recusas-extra` do roteiro.
- **O que deveria acontecer:** a mensagem do tipo de conta, que é a causa principal. A recusa em si está certa, e nada é gravado.
- **Gravidade:** Sugestão. Incomoda: a mensagem aponta para o valor quando o erro é a conta.

### Observações sem gravidade

- **O1.** `PUT` de parcela e `PATCH` de compra com o cartão arquivado respondem 200 (sonda P2). O contrato diz que o PATCH vale com cartão arquivado, e não diz nada sobre o PUT de parcela. Para o tech-lead decidir se o contrato precisa de uma linha.
- **O2.** O roteiro pedia 400 para o DELETE da parcela do meio. A ADR-032 e o contrato dizem que só a primeira parcela não se apaga sozinha. Observado: 204, e a compra passa a ter 2 parcelas com total 66667. O comportamento segue o contrato. A expectativa estava errada no roteiro.

## Checagens

### Valores (13 PASS)
- Combinações de N de 2 a 48 com totais 2, 47, 48, 49, 99, 700, 12345, 100000, 100003 e 99999: 423 compras criadas e 47 recusadas por parcela de zero centavo. Em todas, parcelas, soma e datas batem com o oráculo.
- R$ 1.000,00 em 3x: 333,33 + 333,33 + 333,34. R$ 7,00 em 3x: 2,33 + 2,33 + 2,34. R$ 0,48 em 48x: 48 parcelas de 0,01. R$ 0,47 em 48x: recusado, sem gravar.
- Teto: 9007199254740991 em 48x aceito, com soma exata. Mais 2 centavos depois é recusado com `saldo da conta fora do limite`, e nenhuma linha fica gravada.
- Limite exato: com 5000 de folga, compra de 5000 entra e compra de 5001 é recusada.

### Datas (2 PASS)
- 196 compras em 10 cartões, com vencimento 5, 10, 28, 30 e 31, fechamento menor e maior que o vencimento. Compras em F-1, F, F+1 e no último dia. Meses de dez/26, jan/27, fev/27, fev/28 e dez/28. N em 2, 3, 12 e 48. A `data_caixa` de todas as parcelas bate com o oráculo.
- No banco: `data_competencia` igual à data da compra em todas as parcelas, status `realizado`, tipo `despesa`, `parcela_total` e cartão corretos.

### Janela do pago em fevereiro, dívida da F10 (9 PASS, mais 1 da varredura)
- Cartão F30/V31: compra em 15/01/2027 em 3x cai em 31/01, 28/02 e 31/03. Pagamento de 50000 em 28/02/2027 entra na fatura de 28/02. Com o pagamento de 30/03 pelo fechamento seguinte, a fatura de 28/02 soma 70000, como o contrato pede. Pagamento de 31/01 fica na fatura de 31/01.
- F30/V31 em 2028 (bissexto): pagamento de 29/02/2028 entra na fatura de 29/02/2028.
- Varredura de 49 cartões, F em {1, 5, 15, 28, 29, 30, 31} e V em {1, 5, 10, 28, 29, 30, 31}, com compras e pagamentos nos dias de fechamento e de vencimento de jan a abr de 2027 e fev de 2028. Nenhuma divergência em nenhuma fatura de 2027 e de jan a mar de 2028. Essa varredura cobre também os meses de 30 dias (abr, jun, set, nov) onde fechamento e vencimento caem no mesmo dia por clamp.

### Faturas (5 PASS)
- Compra de 300,00 em 3x cai em 28/01, 28/02 e 28/03, uma parcela em cada fatura. Vencimento, fechamento, total, pago e restante batem com o oráculo. Soma dos totais = 34000 com a compra de 2x que entrou junto.

### Situação (4 PASS)
- Hoje é 20/10/2026 em São Paulo. Fatura com fechamento hoje: `aberta`. Fatura que fechou ontem sem pagamento: `fechada`. Fatura paga no vencimento: `paga`.

### Recusas (3 PASS, com 27 casos e 3 sem cookie ou formato)
- Corrente, poupança, dinheiro: `compra parcelada só em cartão de crédito`. Cartão arquivado: `conta arquivada`. Cartão de B e conta inexistente: `conta não encontrada`. Categoria de receita, de B e malformada: `categoria não encontrada`.
- 1, 49 e 0 parcelas, `"3"` e `3.0` como parcelas, valor 10.5, valor como string, valor zero, valor negativo, valor acima do teto, campo extra `status` e `usuarioId`, data `2026-02-30`, título vazio, título de 101 caracteres, descrição de 501, título com controle. Todos respondem 400.
- Sem cookie: 401. Cookie inválido: 401. Content-Type `text/plain`: 415.
- Snapshot das tabelas `lancamento` e `conta` antes e depois: idêntico. Nenhuma linha gravada.

### Gestão (24 PASS)
- GET da compra: total 100000, 3 parcelas na ordem. GET por id de parcela do meio: 404 `não encontrado`, igual ao inexistente.
- PATCH de título e de descrição: aplica nas 3 parcelas. Descrição vazia ou só com espaços: limpa. Categoria de receita: 400. PATCH vazio, com campo extra, título em branco ou longo: 400. PATCH por id de parcela: 404.
- PUT de valor numa parcela: 200, e o GET reflete a soma (116667). A fatura do vencimento da parcela muda pelo mesmo delta (33333 para 50000).
- PUT trocando conta: 400 `edite a compra inteira`. PUT trocando data: 400 `edite a compra inteira`. PUT trocando tipo para receita: 400. PUT com categoria de receita: 400 `categoria não encontrada`. PUT com campo extra `parcelaNum`: 400.
- DELETE da primeira parcela: 400 `apague a compra inteira`, nada apagado. PATCH de status em parcela realizada: 400 `só ocorrência prevista pode mudar de status`.
- DELETE da parcela do meio: 204 (ver O2). DELETE da compra: 204, e o saldo do cartão volta. GET depois do DELETE: 404. DELETE de compra já apagada: 404. DELETE usando id de parcela (sonda P1): 404, e nenhuma parcela apagada.

### Autorização (12 PASS)
- A chamando 13 rotas sobre objetos de B: GET da compra, GET da parcela, PATCH da compra, PATCH com categoria de A, DELETE da compra, PUT de parcela, PATCH de status de parcela, DELETE de parcela, GET do lançamento, GET das faturas da conta de B, POST com conta de B, POST com categoria de B, GET da conta de B. Todas respondem 404 `não encontrado` ou 400 com a mensagem do inexistente. Snapshot de `lancamento` e `conta` igual antes e depois.
- GET de compra de B e de id inexistente: mesmo status e mesmo corpo.
- Sem cookie: 401 em GET, PATCH, DELETE e POST. Cookie inválido: 401. B continua vendo a própria compra depois das tentativas de A.

### Corrida (3 PASS)
- 12 POSTs em paralelo no teto: 10 entram (201) e 2 são recusadas (400 `saldo da conta fora do limite`). Saldo final exatamente -9007199254740991. Foram gravadas 20 parcelas.
- DELETE da compra em paralelo com PUT numa parcela, 12 rodadas: 3 PUT responderam 200 antes do DELETE, 9 responderam 404 depois. DELETE 204 em todas. Nenhuma órfã, nenhuma parcela sobrando, nenhum 500.

### Banco fora e trava (10 PASS)
- Banco congelado com `docker pause`: POST 503 em 2,03 s, DELETE 503 em 2,02 s, corpo `{"erro":"serviço indisponível"}`. Depois de descongelar: GET 200, compra intacta, DELETE 204, POST 201.
- Trava de parcela por `SELECT ... FOR UPDATE` em outra sessão: DELETE 503 em 2,02 s, compra íntegra depois.
- Trava de conta: POST 503 em 2,01 s, nada gravado.

### Log (6 PASS)
- Não aparecem no `api.log` o título marcado, o valor marcado (1234567891), o e-mail completo, nem as senhas de A e de B.
- Três ERROR são `sessão: banco indisponível`, do teste de banco congelado. As linhas de trava são WARN `espera por trava estourou`. Os stack traces trazem SQL com `?`, sem valores.

### Volume (4 PASS)
- 200 compras de 12x em um cartão: POST p50 11 ms, p95 14 ms, máximo 18 ms.
- GET da compra, 20 amostras: p50 1,2 ms, máximo 3 ms.
- Faturas de 12 meses (de 2026-12-01 a 2027-11-30): 6,7 ms, 12 faturas, batendo com o oráculo.
- `GET /lancamentos` do mês traz `compraParceladaId`, `parcelaNum` e `parcelaTotal` nas parcelas.

### Integridade no banco (10 PASS)
- Dez consultas de invariante, todas com zero violações: parcela sem coerência entre `parcela_num`, `parcela_total` e `parcela_de_id`; primeira que não aponta para si; parcela fora de cartão; status diferente de `realizado`; tipo diferente de `despesa`; parcela com template; órfã; competência divergente dentro da compra; `parcela_num` fora de 1..total; valor não positivo.

## O que passou

- Suíte da branch verde antes do roteiro (1153 testes).
- Divisão em centavo: soma exata em todas as combinações de N e total, inclusive 0,48 em 48x e o teto.
- Datas de caixa em todos os cartões e meses testados, inclusive fevereiro comum, bissexto e virada de ano.
- Janela do pago em fevereiro, com a varredura F x V inteira.
- Recusas sem gravar nada, com snapshot de tabela.
- Autorização de A sobre B em todas as rotas, com corpo igual ao do inexistente.
- Corrida no teto e corrida de DELETE com PUT, sem anomalia.
- Banco fora e trava devolvem 503 em cerca de 2 s, sem gravar nada pela metade.
- Log sem título, valor, e-mail ou senha.
- Volume com folga: POST de 11 ms p50 para 12x.

## Como reproduzir o roteiro

`python3 /private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f11/qa_f11.py` com a API da 18090 e o container `ballast-qa-f11` de pé, num banco recém-criado. Resultados em `results.json` da mesma pasta. Sondagens finais: `probes.py`.
