# Relatório QA: F4 Transferência entre contas (ADR-025)

**Data:** 2026-10-19
**Branch:** `feat/f4-transferencia` (V7 commitada em `00d6b5e`; API de transferência no working tree, não commitada)
**Escopo:** API de transferência (`POST`, `GET`, `DELETE /transferencias`), pernas em `/lancamentos`, saldo em `/contas`, log. Web não testada (outro agente).
**Ambiente:** container `ballast-qa-f4` (`postgres:17`, `127.0.0.1:55434`), papel da aplicação `ballast_app` só com LOGIN (`db/init/01-cria-usuario-da-aplicacao.sh`), migração como `qa_dono` (superusuário do container descartável). API na porta 18083, subida com variáveis na linha de comando, processo encerrado no fim, container removido. Credenciais descartáveis guardadas fora do repositório e apagadas no fim. Não usei o Postgres do compose do dono, não li nem imprimi nenhum `.env`.
**Não cobre:** navegador, HTTPS, `Accept: text/html` nas rotas de transferência, a divergência entre `api/openapi.yaml` e o código (checada pelo CI, não rodei), idempotência (não existe no contrato), concorrência acima de 40 threads, falha do banco no meio de um `INSERT`.

## Suíte existente (linha de base)

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` | BUILD SUCCESS, 518 testes, 0 falhas, 0 erros |

Verde antes de qualquer teste manual. Nenhum código de produção foi alterado.

## Achados

### Bloqueia

Nenhum.

### Dívida

Nenhuma.

### Sugestão

Os três abaixo não violam o contrato como escrito. Estão separados dos bugs de propósito.

**S1. Mensagem errada ao tentar criar transferência por `POST /lancamentos`.**

- **O que acontece:** `POST /lancamentos` com `tipo: "transferencia"` sem `categoriaId` responde 400 `{"erro":"categoriaId é obrigatório"}`. Com `categoriaId` presente responde 400 `{"erro":"requisição inválida"}`. Nos dois casos a recusa acontece (nada grava), mas a mensagem não diz que o tipo não é aceito nessa rota.
- **Como reproduzir:** `POST /lancamentos` com `{"tipo":"transferencia","titulo":"x","contaId":"<conta>","valorCentavos":1,"data":"2026-03-10"}`, com e sem `categoriaId`.
- **O que deveria acontecer:** mensagem que aponte o tipo, por exemplo `use a rota de transferência`, igual à que `PUT` e `DELETE` dão numa perna.
- **Gravidade:** Sugestão. Erra a mensagem, não o comportamento.

**S2. `POST /transferencias` repetido com o mesmo corpo cria duas transferências.**

- **O que acontece:** dois `POST` idênticos, em sequência, respondem 201 e 201. O saldo se move duas vezes (conta X 1000 → 800, conta Y 0 → 200, com 2 transferências de 100). Não há chave de idempotência.
- **Como reproduzir:** criar duas contas ativas com saldo na origem; enviar duas vezes `POST /transferencias` com `{"contaOrigemId":X,"contaDestinoId":Y,"valorCentavos":100,"data":"2026-03-10","titulo":"Duplo"}`. Conferir `GET /contas` e `select count(*) from transferencia`.
- **O que deveria acontecer:** o contrato não diz. Hoje, um duplo clique na tela move o dinheiro duas vezes sem aviso. A decisão é de produto: chave de idempotência no cliente, ou trava do botão no web. Registrar no backlog antes de a tela de transferência sair.
- **Gravidade:** Sugestão, com decisão pendente do dono. Se o dono quiser proteção no servidor, sobe para Dívida.

**S3. Ids em maiúsculas aceitos em transferência.**

- **O que acontece:** `contaOrigemId` e `contaDestinoId` em maiúsculas são aceitos (201), e `GET /transferencias/{id}` com o id em maiúsculas responde 200. A recusa "contas devem ser diferentes" continua valendo com origem em maiúsculas e destino em minúsculas da mesma conta (400, nada grava).
- **Por que é Sugestão:** é o mesmo comportamento já registrado como S2 da F3 (`f3-lancamentos.md`). UUID em maiúsculas é UUID válido e não há dano: quem não é dono recebe a mesma recusa.
- **Como reproduzir:** `POST /transferencias` com `contaOrigemId` = id da conta em maiúsculas, destino outra conta própria.
- **Gravidade:** Sugestão (preferência de estilo sobre a forma do id).

## Não reproduzido

- **Correção do log do 500 ("Failing row").** Não consegui provocar um 500 pelo HTTP nas rotas de transferência. Verifiquei o que dá para verificar: nenhuma ocorrência de `Failing row` em toda a sessão (cerca de 1.500 linhas de log de uma subida, incluindo os 503 de banco pausado e parado, com stack trace de conexão). A correção do log do 500 não foi exercitada diretamente.
- **Lançamento passando do teto (B1 da F3).** Reproduzido como regressão: dois lançamentos de receita de 9007199254740991 na mesma conta, segundo recusado com 400, saldo final 9007199254740991. Corrigido nesta branch.

## O que passou

**Par**
- Duas pernas com mesmo valor (1234), mesma `dataCaixa` e `dataCompetencia`, mesmo título e descrição, `categoriaId` nulo, `tipo` `transferencia`.
- Direção: a perna da origem sai como `saida` e a do destino como `entrada`, em `GET /lancamentos` e em `GET /lancamentos/{id}`.
- Filtro `contaId`: a conta origem mostra só a perna `saida`; a conta destino, só a `entrada`.
- Título ausente, nulo ou só espaço vira `Transferência`.
- Banco: nenhuma perna órfã, nenhuma ligação com perna faltando, nenhum par com valor, data, título ou descrição diferentes, nenhuma perna com categoria, nenhuma perna em conta diferente da ligação, soma global de saídas menos entradas igual a zero (2.434 transferências na base de teste).

**Saldo**
- Transferência de 25,00 entre contas de 100,00 e 0,00: origem 75,00, destino 25,00, soma 100,00 antes e depois.
- Apagar devolve: 100,00 e 0,00.
- Mistura com centavos (receita 1000,33, despesa 0,01, transferência 333,33 de ida, 0,01 de volta, despesa 99,99, receita 0,10): saldos 667,00 e 233,43, iguais em `GET /contas`, recalculados só pela lista e perna a perna no detalhe.
- Conta com saldo zero transferindo para outra (vai para -50,00): aceito, saldo negativo permitido.
- Transferência do teto inteiro (9007199254740991) entre duas contas: origem 0, destino no teto, aceito.
- Apagar que estouraria a faixa (origem sobe além do teto): 400 `saldo da conta fora do limite`, nada apagado, transferência continua existindo.

**Autorização**
- Origem de B ou destino de B em `POST` do usuário A: 400 `conta não encontrada` nos dois casos, fotografia do banco de A e B idêntica antes e depois.
- A chama `GET /transferencias/{id}` de B: 404 `{"erro":"não encontrado"}`. `DELETE` de B: 404. Transferência de B intacta no banco e ainda 200 para B.
- Lista do A não contém nenhuma perna de B. `contaId` de conta de B na lista de A: vazio.
- `GET`, `PUT` e `DELETE /lancamentos/{id}` de perna de B, com a sessão de A: 404 nos três.
- Sem cookie: `GET`, `POST` e `DELETE /transferencias` dão 401 e nada muda.

**Pernas**
- `PUT /lancamentos/{saida}` e `PUT /lancamentos/{entrada}` (com tipo `receita`, `despesa`, conta trocada, valor alterado): 400 `use a rota de transferência`, nada muda.
- `DELETE /lancamentos/{saida}` e `DELETE /lancamentos/{entrada}`: 400 `use a rota de transferência`, nada muda.
- `GET`, `DELETE /transferencias/{id}` com id de perna: 404.
- `PUT` e `DELETE /lancamentos/{id}` com id de transferência: 404.
- `POST /lancamentos` com `tipo: transferencia`: 400 (mensagem ver S1).
- Fotografia completa do banco de A (contagens e todos os lançamentos) idêntica antes e depois de todas as tentativas.
- Apagar transferência com a conta de origem arquivada: 204 e saldos corretos (mesmo comportamento do lançamento comum).

**Recusas**
Todas com 400 ou 413 ou 415, e com fotografia do banco (contagens de `lancamento` e `transferencia`, saldos e linhas) idêntica antes e depois:
- contas iguais; origem arquivada; destino arquivada; origem inexistente;
- valor 0, -1, teto + 1, `12.5`, `"100"`, `null`, `1.0`, `1e2`, `true`;
- saldo do destino estourando; saldo da origem estourando para baixo;
- data `2026-02-31`, `2026-3-1`, ausente; data `1899-12-31`;
- `contaOrigemId` `abc`, número, vazio, `null`, ausente;
- título de 101 caracteres, com NUL escapado, com quebra de linha;
- descrição de 501 caracteres;
- campos extras `tipo`, `saidaId`, `usuarioId`, `entradaId`, `categoriaId`, `status`, `dataCaixa`: 400 cada;
- `Content-Type: text/plain`: 415; corpo de 4097 bytes: 413; JSON truncado: 400.
- Mensagens de erro genéricas: nenhuma ecoa título, descrição, nome de conta ou e-mail.

**Limites aceitos (para provar que a faixa é a certa)**
- Valor 1 centavo: 201. Teto inteiro: 201. Título de 100 caracteres: 201. Datas 1900-01-01 e 2199-12-31: 201.

**Contrato (`docs/contracts/api.md`)**
- `POST` 201 com exatamente as chaves `id, contaOrigemId, contaDestinoId, valorCentavos, data, titulo, descricao, saidaId, entradaId, criadaEm`. `GET /transferencias/{id}` devolve o mesmo corpo.
- Descrição com espaço nas pontas sai sem espaço.
- `PUT /transferencias/{id}`: 405 (não existe, como o ADR-025 pede).
- Lançamento comum traz `transferenciaId` e `direcao` nulos. `categoriaId` das pernas nulo.
- `PUT /lancamentos` de lançamento comum para `tipo: transferencia`: 400. `PUT` com `categoriaId` nulo em receita: 400.

**Corrida**
- 20 transferências X→Y e 20 Y→X em paralelo, com barreira: todas 201, nenhum 5xx, saldos finais iguais aos iniciais (10.000.000 cada), 80 pernas e 40 ligações no banco.
- Teto: destino com 150 centavos de folga, 30 transferências de 100 em paralelo: exatamente 1 com 201, 29 com 400 `saldo da conta fora do limite`, destino termina 50 centavos abaixo do teto. Nenhum 5xx.
- Arquivar o destino com 20 threads transferindo sem parar: `PATCH` 200. Das 492 requisições enviadas depois do 200 do arquivar, nenhuma voltou 201 (todas 400). As 148 pernas gravadas na conta são todas de transferências respondidas 201 antes do arquivar. Nenhum 5xx.
- `DELETE` da transferência, `PUT` e `DELETE` das duas pernas, ao mesmo tempo, 15 rodadas: nenhum 5xx, nenhuma perna aceita (400 ou 404 sempre), e em todas as rodadas o par ficou inteiro ou sumiu inteiro. Resultado típico: `[204, 404, 400, 400]`.
- Dois `DELETE` da mesma transferência ao mesmo tempo, 15 rodadas: as 15 deram exatamente um 204 e um 404.
- 10 `PUT` de lançamento trocando de conta, 10 transferências P→Q e 10 Q→P, tudo em paralelo: sem 5xx, cada resposta 200 ou 201, saldo total consistente com as receitas criadas.
- Arquivar com 16 threads de `POST /lancamentos` (regressão do D1 da F3): depois do 200 do arquivar, 547 requisições enviadas, nenhuma 201 (248 201 antes, 569 400 no total).

**Banco fora**
- Pausa com pool aquecido: `POST`, `DELETE` e `GET /transferencias` com 503 `{"erro":"serviço indisponível"}` em 2,0 a 2,1 s. Nada gravado (contagens idênticas).
- Pausa com API recém-subida (pool frio), primeira chamada com o banco congelado: `POST` 503 em 2,03 s, `DELETE` 503 em 2,01 s, nada gravado.
- Parada (`docker stop`): `POST` e `DELETE` 503 em 0,0 a 0,02 s (conexão recusada); `GET /transferencias/{id}` 503 em 2,0 s. Nada gravado.
- Volta (`docker start`): primeira chamada 201, segunda 204, sem reiniciar a API.

**Log**
- Zero ocorrências de `Failing row` e de `erro não tratado` em toda a sessão.
- Nenhuma senha de teste, nenhum cookie de sessão, nenhum e-mail completo, nenhum título, descrição ou nome de conta de teste no log.
- Login falho registra `login falhou email=f***@exemplo.com`, sem senha.
- Erros de banco no log são stack trace de conexão (Hikari, `This connection has been closed`, `connection refused`), sem SQL com valores.

**Volume** (20.000 lançamentos comuns e 2.000 transferências, 4.000 pernas, num usuário, inseridos direto no banco como na F3)
- `GET /contas`: mediana 8,0 ms, máximo 8,6 ms. Saldo da API igual ao calculado no SQL nas duas contas.
- `GET /lancamentos` de março (2.077 linhas): mediana 24,1 ms, máximo 29,0 ms.
- `GET /lancamentos` de 2026 inteiro, 366 dias (24.000 linhas): mediana 133,7 ms, máximo 138,1 ms.
- `GET /lancamentos` de março com `contaId` (1.038 linhas): mediana 8,3 ms.

**Integridade do banco** (mesma base, depois de todas as rodadas acima)
- Pernas órfãs: 0. Ligações com perna faltando: 0. Pares com valor, data, título ou descrição diferentes: 0. Pernas com categoria: 0. Pernas em conta diferente da ligação: 0. Soma de saídas menos entradas: 0.

## Erros de harness registrados

Falhas que foram erro do meu roteiro, não do produto. Ficam registradas para o tech-lead não ler como achado:
- Corrida, dois `DELETE` simultâneos: a primeira versão travou em barreira porque chamava `result()` antes de submeter a segunda chamada. Corrigido no roteiro e rerrodado: passou.
- Corrida, misto de PUT e transferências: esperava soma 10.000.000, mas as 10 receitas criadas antes entram no saldo. Soma correta 10.001.000, batendo com o banco. Corrigido o valor esperado e rerrodado nos mesmos dados: o banco confirma o número.
- Pernas: o check de `PUT` e `DELETE` de perna lia o índice errado da lista de respostas e falhou. As seis respostas reais eram 400 com `use a rota de transferência`, como o ADR pede. Não rerrodei o roteiro funcional inteiro; o resultado acima vem da saída observada.
- Recusas: esperava 400 para `contaOrigemId` em maiúsculas. A API aceita; é o achado S3, não erro de produto.

Nenhum desses erros mudou código de produção.

## Limites

- Duplo clique (S2) não foi testado com navegador. Medi só a API.
- A corrida de arquivar mediu ordem por horário de envio contra horário de resposta do 200, o que é um critério sólido: depois do 200 o arquivar já está commitado, então qualquer `POST` enviado depois dele tem que ver a conta arquivada.
- Carga inserida direto no banco, não pela API.
- Concorrência testada com até 40 threads.

## Resumo

- **Achados:** 0 Bloqueia, 0 Dívida, 3 Sugestão (S1 mensagem de `POST /lancamentos` com tipo transferência; S2 duplo POST cria duas transferências, decisão de produto; S3 ids em maiúsculas, já registrado na F3).
- **Os que travam primeiro:** nenhum trava. Entre os três, S2 é o único com dano concreto para o usuário (duplo clique movendo o dinheiro duas vezes), por isso vale a decisão do dono antes da tela de transferência.
- **Recomendação: liberar.** Nenhum achado bloqueante. Suíte verde antes dos testes; não alterei código, então não rerrodei a suíte depois. Autorização, isolamento de dono, par garantido, saldo com centavos, corrida, banco fora e volume passaram. Os dois achados da F3 que estavam abertos (D1 arquivar com lançamento em paralelo e D2 caractere fora do BMP) estão corrigidos e verificados aqui, e o B1 (teto do saldo) também. A decisão final é do tech-lead.
