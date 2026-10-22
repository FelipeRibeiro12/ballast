# Relatório QA: F13 Cripto manual (ADR-035)

**Data:** 2026-10-22, fim de tarde, horário de São Paulo.
**Branch:** `feat/f13-cripto-manual`, HEAD `2cea829`. Não commitei nada e não alterei arquivo de produção. Usei só um teste temporário no web, que removi em seguida.
**Escopo:** conta cripto (criação e recusas em lançamento, troca de lançamento, transferência, recorrência e compra parcelada), ativo, cotação manual, movimento (compra e venda, posição nunca negativa, remoção), posições e patrimônio com data, fuso, arredondamento HALF_EVEN, limites do JSON, autorização entre usuários, conta arquivada, corrida e espera por trava. Contra a API real e Postgres 17.
**Ambiente:** Postgres 17 descartável `ballast-qa-f13` em `127.0.0.1:55432`, banco `ballast_qa`, papéis de dono e de aplicação criados pelo mesmo script de `db/init` do compose. API em `127.0.0.1:18080` a partir do jar do HEAD, com `REGISTRO_ABERTO=true`, `COOKIE_SECURE=false`, `APP_ENV=development` e job de recorrência desligado. Não usei o banco do compose do dono, a porta 8080 nem o `.env`. Container e API derrubados ao fim, sem sobra.
**Roteiro:** chamadas `curl` com um helper de shell fora do repositório, em `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/4ce6bf65-5d49-45e0-a885-89817918f3e4/scratchpad/qa-lib.sh`. O oráculo veio de `docs/contracts/api.md` (seção "Cripto manual (ADR-035)"), de `docs/adr-035-cripto-manual.md` e de `docs/contracts/dados.md`.

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` no HEAD `2cea829`, antes do roteiro | BUILD SUCCESS. 1310 testes, 0 falhas, 0 erros, 0 ignorados |
| `cd web && npx vitest run` nos três arquivos de cripto e decimal (`decimais.test.ts`, `Cripto.test.tsx`, `dinheiro.test.ts`) | 3 arquivos, 95 testes, todos passam |

Linha de base verde. O roteiro começou depois dela.

## Resumo

- **Achados:** 0 Bloqueia, 2 Dívida, 3 Sugestão.
- **Checagens:** mais de 200 requisições HTTP contra a API real, mais as rodadas de corrida. Nenhuma resposta 5xx. O contrato bate em todas as rotas novas, exceto nos pontos listados abaixo.
- **Travam primeiro:** nenhum achado bloqueia. D1 é o que mais pesa na prática: um movimento válido, aceito pela API, deixa a tela de patrimônio e a de posições em 400 até ser apagado.
- **Recomendação:** **liberar com dívida registrada**, com uma condição. D2 mostra na tela um número que o nome "Custo" faz ler como quanto a pessoa pagou pelo que tem. Antes da liberação, o dono precisa decidir entre preço médio e outro rótulo, e isso precisa ir para um ADR ou para o backlog com dono. A decisão é do tech-lead. Se D1 for considerado inaceitável para o patrimônio (um lançamento válido não pode travar a tela), ele sobe para bloqueia e a recomendação vira segurar.

## Achados

### Bloqueia

Nenhum.

### Dívida

**D1. Um movimento válido, aceito na escrita, deixa o patrimônio e as posições em 400 até ser apagado.**

- **O que acontece:** a escrita valida cada campo isoladamente, mas não confere se a soma ou o produto quantidade vezes preço cabe no limite do JSON. A leitura confere e responde 400 `patrimônio fora do limite` para qualquer data a partir do movimento, inclusive a data padrão de hoje. Como a rota de posições também falha, a tela inteira de cripto fica sem dado. Dois casos confirmados:
  - quantidade gigante: compra de `99999999999999999999.999999999999999999` (aceita: até 20 inteiros e 18 casas, ativo com 18 decimais) e cotação `1`. Depois disso, `/posicoes-cripto` e `/patrimonio` respondem 400 para 2026-09-01 e para a data de hoje.
  - custo acumulado: duas compras no mesmo ativo e conta, uma com `custoCentavos` 9007199254740991 e outra com 1. `/posicoes-cripto` e `/patrimonio` respondem 400 para as datas a partir da segunda compra.
  - Apagar o movimento que estoura devolve as duas rotas a 200.
  - Na fronteira exata, `/posicoes-cripto` aceita valor igual ao teto (9007199254740991 centavos). Só o total de `/patrimonio` estoura quando soma os R$ 1.000 de uma conta corrente.
- **Como reproduzir:**
  ```
  # conta cripto CONTA, ativo ATIVO com 18 decimais, usuário logado (jar)
  POST /movimentos-cripto {"contaId":"CONTA","ativoId":"ATIVO","tipo":"compra","quantidade":"99999999999999999999.999999999999999999","data":"2026-09-01","custoCentavos":100}   -> 201
  POST /ativos-cripto/ATIVO/cotacoes {"preco":"1","momento":"2026-09-01T10:00:00Z"}                 -> 201
  GET  /posicoes-cripto?data=2026-09-01                                                              -> 400 "patrimônio fora do limite"
  GET  /patrimonio                                                                                    -> 400 "patrimônio fora do limite"
  DELETE /movimentos-cripto/{id do movimento}                                                         -> 204
  GET  /patrimonio                                                                                    -> 200
  ```
- **O que deveria acontecer:** o contrato diz que 400 `patrimônio fora do limite` vale para valor fora da faixa, e a leitura cumpre isso. O que falta é uma decisão sobre a escrita. Ou a escrita recusa o movimento que estoura o teto por posição (quantidade acima de um limite, ou custo acumulado acima do teto), ou o contrato registra que um lançamento pode travar o patrimônio. Nos dois casos a mensagem deveria apontar qual posição causou o estouro.
- **Gravidade:** trava. A tela de cripto fica sem dado, sem indicação de qual movimento causa o problema, até o usuário achar e apagar o lançamento.

**D2. O campo `custoCentavos` da posição não é o custo do que resta em carteira. A tela mostra como se fosse.**

- **O que acontece:** o contrato define `custoCentavos` como "compras menos vendas". A API faz exatamente isso. Com o contrato literal, a conta fica assim: compra de 1 unidade por R$ 100, venda de 0,5 por R$ 80, cotação R$ 100. A posição tem 0,5 unidade com `custoCentavos` 2000 e `valorCentavos` 5000. A tela (`web/src/Cripto.tsx`, linha 625) mostra "Custo R$ 20,00" logo abaixo de "R$ 50,00". Quem lê a tela entende que a pessoa pagou R$ 20 pelo que tem. Pelo preço médio, o que ela pagou por essas 0,5 unidades é R$ 50.
- **Como reproduzir:**
  ```
  POST /movimentos-cripto {contaId cripto, ativo base 18 decimais, "tipo":"compra","quantidade":"1","data":"2026-02-10","custoCentavos":10000}   -> 201
  POST /movimentos-cripto {mesma conta e ativo, "tipo":"venda","quantidade":"0.5","data":"2026-02-11","custoCentavos":8000}               -> 201
  POST /ativos-cripto/ATIVO/cotacoes {"preco":"100","momento":"2026-02-12T10:00:00Z"}                                                       -> 201
  GET  /posicoes-cripto?data=2026-02-12
       -> {"quantidade":"0.5","custoCentavos":2000,"cotacao":{"preco":"100",...},"valorCentavos":5000}
  ```
- **O que deveria acontecer:** a decisão é de produto e fiscal, do dono. Duas saídas: (a) custo médio, que é o que costuma ser usado para cripto, com o custo das unidades vendidas saindo pelo preço médio; ou (b) manter o conceito de "aplicado líquido" e trocar o rótulo na tela, sem a palavra "custo". Em qualquer caso, a escolha vai para ADR. Hoje o contrato e o rótulo se contradizem.
- **Gravidade:** erra em silêncio. O número é plausível e está errado para a leitura que a tela sugere, e nada no sistema acusa.

### Sugestão

**S1. A projeção de saldo aceita conta cripto no escopo.**

- **O que acontece:** `ProjectionQueries` exclui só `beneficio` do escopo padrão (`ALL_ACTIVE`). Conta cripto entra na contagem de contas, e `GET /saldo-projetado?contaId=<cripto>` responde 200 com a série inteira em zero, em vez de recusar. O valor não muda hoje, porque conta cripto não tem lançamento.
- **Como reproduzir:** `POST /contas {"nome":"X","tipo":"cripto"}` e depois `GET /saldo-projetado?contaId=<id>`. Observado: 200, `saldoAtualCentavos` 0, `dias` com zeros.
- **O que deveria acontecer:** ADR-035 diz que conta cripto "não entra no saldo projetado". O escopo padrão deveria excluir `cripto` junto com `beneficio`. Para `contaId` de cripto, a decisão fica entre recusar e responder zero, mas ela precisa estar no contrato. Hoje a API nem recusa nem exclui.
- **Gravidade:** incomoda. Divergência entre ADR e código sem efeito numérico agora. Vira problema se um tipo de movimento novo chegar a uma conta cripto.

**S2. Parâmetro de consulta desconhecido é ignorado.**

- **O que acontece:** `GET /movimentos-cripto?foo=1` responde 200 e ignora `foo`.
- **Como reproduzir:** `GET /movimentos-cripto?foo=1` com sessão válida. Observado: 200 com a lista normal.
- **O que deveria acontecer:** a regra de entrada do projeto diz "rejeitar campo não declarado, não ignorar". A regra cita o corpo, então a leitura de query é uma extensão minha. Não verifiquei se o comportamento é o mesmo nas outras rotas da API, e não tratei isso como defeito desta fatia.
- **Gravidade:** incomoda.

**S3. Nenhum teste da F13 cobre a espera por trava (503) nas rotas de movimento e cotação.**

- **O que acontece:** o contrato promete 503 quando a conta fica travada por mais de 2 s. Verifiquei à mão: com outra sessão segurando `FOR UPDATE` na conta cripto, o `POST /movimentos-cripto` respondeu 503 em 2 s com `{"erro":"serviço indisponível"}`, e depois de liberar, 201. Nada foi gravado pela metade. A suíte de crypto não tem esse caso. O F12 tem.
- **Como reproduzir:** em outra sessão, `BEGIN; SELECT id FROM conta WHERE id='<conta>' FOR UPDATE; SELECT pg_sleep(8); COMMIT;`. Em seguida, `POST /movimentos-cripto` para essa conta.
- **O que deveria acontecer:** um teste de integração para a trava, no padrão do F12.
- **Gravidade:** incomoda. O comportamento está certo, só não está travado por teste.

## Checagens

Todas as checagens abaixo foram feitas contra a API real. Os achados estão na seção anterior.

- **Caminho feliz (3):** ativo com normalização de contrato e de símbolo, duplicado 409, e o nativo da mesma rede liberado para outro usuário. Cotação, compra e venda com posição zerada. A posição zerada some da listagem.
- **Venda retroativa (4):** venda datada antes da compra recusada com `venda maior que a posição`. Venda de 0,00000001 acima da posição recusada. Venda que zera passa. Venda no mesmo dia da compra conta junto. Isolamento por conta: compra na conta F e venda do mesmo ativo na conta A2 recusada.
- **Remoção (7):** remover compra que cobre venda, 400 e nada apagado. Remover venda e depois compra, 204 e 204, e de novo 404. Compra e venda no mesmo dia: remover a compra recusado. Remover a compra do dia 02 com venda no dia 03 recusado. Remoção contra venda em paralelo, 15 rodadas: nunca os dois passam e a posição nunca fica negativa.
- **Concorrência (4 grupos):** 10 vendas paralelas de 0,2 sobre posição de 1: exatamente 5 com 201 e 5 com 400, saldo final zero. 10 cotações paralelas no mesmo momento: 1 com 201 e 9 com 409. 10 ativos iguais em paralelo: 1 com 201 e 9 com 409. Nenhum 5xx.
- **Quantidade (17):** 9 casas com ativo de 8 recusado. Zeros à direita aceitos. Zero, negativo, notação científica, vírgula, sinal de mais, espaço, vazio, NaN, Infinity e 21 inteiros recusados. Número JSON recusado com `requisição inválida`. Teto de casas por ativo com 0 decimais: 1,5 recusado, 2 aceito.
- **Custo (7):** zero, negativo, string, decimal, `null` e 9007199254740992 recusados. 9007199254740991 aceito.
- **Datas (10):** 2026-02-30, 2026-13-01, 1899-12-31, 2200-01-01, formatos sem zero à esquerda, sem separador, com hora e com espaço recusados. 1900-01-01 e 2199-12-31 aceitos. 2024-02-29 aceito.
- **Preço (11):** zero, negativo, notação científica, ponto de milhar, vírgula, 13 inteiros, 9 casas e número JSON recusados. 12 inteiros com 8 casas e 0,00000001 aceitos.
- **Momento (15):** sem fuso, só data, ano 1899, ano 2200, futuro, data inválida e formato com espaço recusados. Offset +14:00 convertido para UTC. 1900-01-01T00:00Z aceito. Fração abaixo do microssegundo truncada: `.1234567` vira `.123456` e `.9999999` vira `.999999`. Sem momento, vale o instante da requisição.
- **Fuso (4):** quote às 23:30 de SP do dia 01 (02:30Z do dia 02) vale para o dia 01 e não para o dia 02. Quote às 00:00 de SP do dia 02 não vale para o dia 01. Cotação das 02:59Z do dia 02 vale para o dia 01. Cotação das 10:00Z do dia 02 não entra no patrimônio do dia 01.
- **Arredondamento HALF_EVEN por posição (5):** 0,005 × 1 gera 0 centavos, 0,015 gera 2, 0,025 gera 2, 0,035 gera 4. Valor de posição com 0,0000001 × 0,00000001 gera 0.
- **Valor na fronteira (1):** posição com valor exatamente 9007199254740991 centavos aceita por `/posicoes-cripto`. Não testei um centavo acima isolado. O estouro por soma foi confirmado em D1.
- **Patrimônio (9):** data no formato errado, vazia, repetida, com hora, 1899, 2200 e sem data recusadas. Posição sem cotação aparece em `semCotacao` e fica fora do total. Total bate com `contasCentavos` mais `criptoCentavos`.
- **Conta cripto (12):** saldo de 1 real, -5 recusados. Zero e ausente aceitos. `cartao` recusado. `categoriasPermitidas` recusado. `tipo` em maiúscula recusado. `moeda` recusada. Troca de tipo por `PATCH` recusada com `requisição inválida`.
- **Recusa de conta cripto (8):** lançamento (receita e despesa) recusado com `conta cripto não recebe lançamento`. `PUT /lancamentos` movendo lançamento existente para conta cripto recusado com a mesma mensagem. Recorrência recusada com a mesma mensagem. Transferência com cripto na origem e no destino recusada com `conta cripto não participa de transferência`. Compra parcelada recusada com `compra parcelada só em cartão de crédito`.
- **Autorização (cerca de 25 casos):** A não lista ativos, movimentos nem posições de B. Cotação em ativo de B responde 404 `não encontrado`, o mesmo corpo de id inexistente e de id malformado. Movimento com conta de B cripto, conta de B corrente, id inexistente e id malformado responde 400 `conta não encontrada`, idêntico. Movimento com conta de A e ativo de B responde `ativo não encontrado`, idêntico ao de ativo inexistente. Delete de movimento de B responde 404, idêntico ao de inexistente. Filtro de B em `contaId` e `ativoId` responde lista vazia. Conta cripto de B em `/contas/{id}` responde 404. Sem sessão: 401 nas rotas novas.
- **Conta arquivada (8):** compra e venda recusadas com `conta arquivada`. Cotação do ativo aceita. Remoção de movimento permitida (204). Posição da conta arquivada some de `/posicoes-cripto` e de `/patrimonio`. Desarquivar devolve a conta e a posição.
- **Limites do corpo e do tipo (3):** corpo de 5 KB responde 413. `text/plain` responde 415. Campo extra no corpo recusado com `requisição inválida`. `usuarioId` no corpo recusado.
- **Ativo (cerca de 30):** rede em caixa alta recusada. Rede inexistente recusada. Contrato em bitcoin recusado. EVM sem `0x`, com 39 hex e com caractere de checksum misto aceito em minúsculas. Solana com 0, com `l`, com 31 e 45 caracteres recusada, com 32 aceita. Símbolo com NUL, só espaço, 21 letras, 21 emojis e quebra de linha recusado. 20 emojis aceito (20 pontos de código). Nome de 101 caracteres recusado. Decimais 19, -1, 8.0 e string recusados. Campo extra recusado.
- **Ordenação:** `GET /ativos-cripto` ordena por símbolo sem diferenciar caixa, depois por rede.
- **Trava (1):** conta travada por outra sessão: 503 em 2 s, depois 201 quando liberada (ver S3).
- **Logs da API:** nenhuma linha `ERROR`. Nenhum valor de senha de teste no log. Nenhum cookie. Log de recusa traz motivo e `usuario_id`, sem valor de movimento. As únicas stack traces vêm do Tomcat, com a linha malformada que o meu harness mandou, e da espera por trava, que loga em `WARN` e é o comportamento contratado.
- **Web (3 arquivos existentes, 95 testes, e 3 testes temporários de borda que removi):** `lerQuantidade` aceita vírgula e ponto, recusa 19 casas e 21 dígitos. `lerPreco` recusa `1.5`, aceita `1.000` como mil, recusa 13 inteiros e 9 casas. `formatarDecimal` e `lerValorPositivo` batem com os limites da API. Nenhum problema de paridade com a API.

## O que passou

- Linha de base verde no HEAD: 1310 testes em `verify`.
- Contrato bate em todas as rotas novas nas recusas, nos códigos de status e nas mensagens.
- Posição nunca fica negativa em data nenhuma, nem em corrida de 15 rodadas nem em 10 vendas paralelas.
- Fuso de São Paulo na data do patrimônio e na cotação. A cotação das 23:30 de SP conta para o dia.
- HALF_EVEN por posição, com os quatro casos de meio centavo.
- Autorização: nenhum recurso de B é visível ou alterável por A, e as respostas de id alheio e de id inexistente são iguais nas respostas que comparei.
- Conta cripto recusada em todos os caminhos que o contrato lista, sem gravar nada.
- Conta arquivada: recusa de escrita, saída da posição do total, remoção permitida.
- Espera por trava devolve 503 em 2 s.

## Não testado

- Hora padrão ("hoje") perto da meia-noite em São Paulo. O relógio do ambiente é 18h30 em SP, então não reproduzi a virada de dia. O caminho padrão só foi conferido pelo valor (`data` igual a 2026-10-22).
- Corrida entre arquivar conta e gravar movimento. Não rodei.
- Interface no navegador. Só os testes unitários existentes do web e os três temporários de borda.
- Log de falha de login e de sessão, que não são desta fatia.

## Como reproduzir

```
cd /Users/mac/Programacao/meuApp/ballast/api && ./mvnw -B verify
# ambiente: Postgres 17 descartável com db/init, API com o jar de `./mvnw -B -DskipTests package`,
# variáveis REGISTRO_ABERTO=true COOKIE_SECURE=false APP_ENV=development BALLAST_RECURRENCE_JOB_ENABLED=false
# roteiro de curl: scratchpad/qa-lib.sh (fora do repositório)
```
