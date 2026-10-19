# Relatório QA: F2 Contas (ADR-023)

**Data:** 2026-10-19
**Branch:** `feat/f2-contas`
**Escopo:** API de contas (migration V5, rotas `/contas`) rodando contra Postgres 17 descartável, com o papel da aplicação só com DML (ADR-021). Web não testada.
**Ambiente:** container `ballast-qa-f2` (`postgres:17`, `127.0.0.1:55432`), removido ao parar. API na porta 18081, processo encerrado no fim. Credenciais descartáveis geradas para o teste, guardadas fora do repositório. Não usei o Postgres do compose do dono nem nenhum `.env`.
**Não cobre:** navegador, borda HTTPS, carga acima de cerca de 20 requisições simultâneas, falha do banco no meio de uma escrita, e a repetição do teste de primeira chamada descrito em B1 (ver "Limites").

## Suíte existente (linha de base)

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` | BUILD SUCCESS, 252 testes, 0 falhas, 0 erros |

Verde antes de qualquer teste manual, então a avaliação parte de base sã.

## Achados

### Bloqueia

**B1. Primeira chamada depois de subir a API, com o banco congelado, leva 30 s para virar 503. O contrato diz cerca de 2 s.**

- **O que acontece:** processo recém-subido, sessão válida, `docker pause` no Postgres. O primeiro `GET /contas` respondeu 503 em 30,1 s. O segundo, 503 em 2,0 s. `POST /contas` e `/healthz` em seguida, 503 em 2,0 s. Depois do `unpause`, `GET /contas` deu 200 em 0,13 s.
- **Log:** a primeira consulta ficou presa numa conexão ociosa desde a subida. A linha `HikariPool-1 - Connection ... marked as broken ... SQLSTATE(08006)` aparece 30 s depois da chamada. Hipótese, não confirmada: quem segura é o `socketTimeout: 30` de `application.yml`, e não o `connection-timeout` de 2 s que a nota do próprio arquivo promete.
- **Como reproduzir:**
  1. Subir a API (perfil dev, porta 18081, base com V1 a V5), sem nenhuma requisição antes.
  2. Logar: `POST /auth/login` e guardar o cookie.
  3. `docker pause <container do postgres>`
  4. `curl --max-time 60 -b cookie.txt -w '%{http_code} %{time_total}\n' http://127.0.0.1:18081/contas`
  5. Repetir o passo 4 (deve dar 503 em cerca de 2 s).
  6. `docker unpause <container>`
- **O que deveria acontecer:** 503 `{"erro":"serviço indisponível"}` em cerca de 2 s, na primeira chamada como nas demais. ADR-023 diz que a falha de banco vira 503 e "fecha a dívida da F1", e a dívida da F1 era exatamente esse atraso, observado em `/healthz`.
- **Gravidade:** Bloqueia. A promessa de 2 s é contrato, e o atraso acontece em toda primeira requisição depois de cada subida da API. Se o tech-lead aceitar 30 s como "responde", rebaixa para Dívida. O relatório da F1 já tinha classificado o mesmo sintoma como "corrigir antes de mergear".

### Dívida

**D1. `nome` aceita número e booleano e grava como texto.**

- **O que acontece:** `{"nome":123,"tipo":"corrente"}` cria conta de nome `"123"` (201). `true` vira `"true"`, `1.5` vira `"1.5"`. Objeto e array dão 400.
- **Como reproduzir:** `curl -b cookie.txt -H 'Content-Type: application/json' -d '{"nome":true,"tipo":"corrente"}' http://127.0.0.1:18081/contas`
- **O que deveria acontecer:** 400. O `CLAUDE.md` pede validar tipo na borda, e o `application.yml` tem `allow-coercion-of-scalars: false` com o comentário "sem isto, {"email": 1} viraria "1" em vez de erro". Essa configuração não cobre o caminho do `nome`.
- **Gravidade:** Dívida. Sem dano de segurança, mas a regra de tipo da borda não se sustenta.

**D2. O limite de 100 caracteres conta unidades UTF-16, e não caracteres.**

- **O que acontece:** 50 emojis (50 caracteres) criam conta (201). 51 emojis dão 400 com "nome deve ter entre 1 e 100 caracteres". 100 emojis dão 400. O Postgres, pelo `char_length`, conta 100 para os 100 emojis.
- **Como reproduzir:** `nome` com 51 vezes `😀`, em JSON.
- **O que deveria acontecer:** ADR-023 diz "de 1 a 100 caracteres". Um usuário conta 100 emojis como 100 caracteres, e a API rejeita. Com 101 emojis a rejeição seria correta nos dois critérios.
- **Causa provável:** `@Size` usa `String.length()`, que conta unidades UTF-16. O `CHECK` do banco usa `char_length`, que conta code points.
- **Gravidade:** Dívida. Rejeita nome válido, sem perda de dado.

**D3. "Sem diferenciar caixa" falha com o `İ` turco.**

- **O que acontece:** `İstambul` (U+0130) e `i̇stambul` (i mais U+0307, combinante) são aceitos juntos. Pela regra de caixa do Unicode, o segundo é a forma minúscula do primeiro. No banco (`en_US.utf8`), `lower('İstambul')` dá `istambul` e `lower('i̇stambul')` dá `i̇stambul`, então o índice `conta_usuario_nome_unico` não colide.
- **Como reproduzir:**
  1. `POST /contas` com `{"nome":"İstambul","tipo":"corrente"}` (201).
  2. `POST /contas` com `{"nome":"i̇stambul","tipo":"corrente"}` (201, deveria ser 409).
- **O que deveria acontecer:** 409 no passo 2. ADR-023 pede unicidade sem diferenciar caixa, e o caso existe para pegar nomes que aparecem lado a lado com a mesma grafia.
- **Gravidade:** Dívida. Caso de borda, mas é exatamente a regra de unicidade falhando. Nota: `lower()` depende do locale do banco, então trocar o collation exige refazer o índice. Não testei essa troca.

**D4. Surrogate solto no nome vira `?` em silêncio.**

- **O que acontece:** `{"nome":"a\ud800b","tipo":"corrente"}` cria conta (201). O banco guarda `a?b` (hex `613f62`), e a resposta devolve `a?b`. O texto que o usuário mandou não é o que ficou.
- **Como reproduzir:** `curl -b cookie.txt -H 'Content-Type: application/json' -d '{"nome":"a\ud800b","tipo":"corrente"}' http://127.0.0.1:18081/contas`, depois `SELECT encode(convert_to(nome,'UTF8'),'hex') FROM conta WHERE nome LIKE 'a%b'`.
- **O que deveria acontecer:** 400. Surrogate solto não é texto UTF-8 válido. `NoControlCharacters` olha só `Character.CONTROL` e deixa passar.
- **Gravidade:** Dívida. Erra em silêncio: o usuário digita um nome e recebe outro.

**D5. Caminho com `%2F` ou `{}` responde a página HTML do Tomcat, e não JSON.**

- **O que acontece:** `GET /contas/<uuid>%2F` dá 400 com `Content-Type: text/html` e a página "HTTP Status 400 – Bad Request". Mesmo para `/contas/{<uuid>}`. A página não mostra stack nem caminho interno; o problema é o formato.
- **Como reproduzir:** `curl -b cookie.txt -i "http://127.0.0.1:18081/contas/<uuid>%2F"`
- **O que deveria acontecer:** `{"erro":"requisição inválida"}` em JSON. O `ApiExceptionHandler` diz que toda resposta de erro sai como `{"erro": "..."}`. O cliente que faz `res.json()` quebra nesse caso.
- **Gravidade:** Dívida. A rejeição é correta; o formato não.

**D6. Nenhum teste cobre o 503 das rotas de contas.**

- **O que acontece:** `api/src/test/java/com/felipe/ballast/accounts/` não tem teste com banco fora. O 503 existe só nos testes de sessão e de `/healthz`. Por isso B1 passou na suíte verde.
- **O que deveria acontecer:** ADR-023 põe o 503 em "qualquer rota". Um teste de `GET /contas` e `POST /contas` com o banco derrubado, medindo o tempo, teria pegado B1.
- **Gravidade:** Dívida. Teste ausente onde o contrato pede.

### Sugestão

**S1. Espaço e invisível no nome: a decisão ainda não foi tomada.**

- **NBSP (U+00A0):** `" Nubank NBSP"` é gravado com o espaço no início (201). `" "` sozinho cria conta de nome visualmente vazio (201). O `strip()` do Java e o `btrim()` do banco não tiram NBSP.
- **U+200B (espaço de largura zero):** `"​Oculto"` e `"Oculto"` são aceitos juntos (201 e 201). Na lista aparecem iguais, e a unicidade não os pega.
- **U+202E (sobrescrita RTL):** `"‮invertido"` é aceito e aparece invertido na tela.
- **Por que é Sugestão:** ADR-023 pede "sem caractere de controle", e a implementação segue a categoria Cc, que é o sentido literal. Não viola o ADR como escrito. Falta decidir se ele cobre também formato (Cf) e espaços especiais.
- **Como reproduzir:** os três `POST /contas` acima.

**S2. `saldoInicialCentavos: null` cria conta com saldo zero.**

- **O que acontece:** `{"nome":"x","tipo":"corrente","saldoInicialCentavos":null}` dá 201 com saldo 0. O código trata `null` como ausente.
- **Decisão pendente:** o ADR-023 não fala de `null`, e a spec diz "não obrigatório, padrão 0". Ou `null` vira 400, ou a spec diz que `null` vale zero.

**S3. Chave JSON repetida: a última vence sem aviso.**

- **O que acontece:** `{"nome":"X7","nome":"X8","tipo":"corrente"}` cria "X8" (201).
- **Sugestão:** ligar `STRICT_DUPLICATE_DETECTION` no Jackson. Sem cenário de dano além de um cliente com bug, por isso fica como sugestão.

**S4. UUID aceito em formas não canônicas, e maiúsculas aceitas.**

- **O que acontece:** `GET /contas/1a15625-32c2-...` (primeiro grupo com 7 dígitos) devolve 200 com a conta. Id em maiúsculas do próprio dono devolve 200. `UUID.fromString` é permissivo.
- **Contrato:** `api.md` diz que "id que não é UUID" responde 404. Maiúsculas são UUID válido, então o contrato é ambíguo nesse ponto.
- **Impacto:** nenhum na autorização, porque é preciso o UUID exato. Decidir se maiúsculas entram e se a forma encurtada sai (regex canônico).

**S5. `?arquivadas=false&arquivadas=false` dá 400.**

- **O que acontece:** o parâmetro repetido com o mesmo valor é recusado. O `AccountListingTest` cobre `true&false` com 400, mas não o caso igual.
- **Decisão pendente:** repetição é erro, ou o último valor vale. A spec não diz.

## O que passou

**Autorização**
- A lê, arquiva e desarquiva conta de B por id: 404 com o mesmo corpo de id inexistente. No banco, a conta de B continua com `arquivada = f` e o nome intacto.
- A lista não mostra a conta de B. B lista a sua.
- Sem cookie: 401 em `GET`, `POST`, `PATCH`, `DELETE` e `PUT`.
- Ids malformados (`not-a-uuid`, 37 caracteres, sem hífen, `urn:uuid:`, UUID zero, `%20`, `1-1-1-1-1`): 404.
- Id em maiúsculas do próprio dono: 200 (ver S4).

**Verbos**
- `DELETE` e `PUT` com sessão: 405 JSON `{"erro":"método não permitido"}` com `Allow: PATCH, GET`. `POST /contas/{id}`: 405.

**Dinheiro**
- `±9007199254740991` entra e sai exato (ida e volta em `saldoInicialCentavos` e `saldoCentavos`). No banco, `±90071992547409.91`.
- `±9007199254740992` dá 400 com a mensagem da faixa.
- `12.5`, `1e3`, `1E3`, `1.0`, `0.0`, `"100"`, `"12345"`, `true`, `+5`, `00123`, `1e400`, `1e-400`, número de 33 dígitos, `9223372036854775807`, `-9223372036854775809`: 400.
- `-0` e `0` viram 0. `-1` vira `-1` e no banco `-0.01`.
- `12345` vira `123.45` no banco e volta como `12345`.

**Nome**
- Mesmo nome com outra caixa, espaço nas pontas ou CAIXA ALTA: 409.
- Tab nas pontas sai (`"\tTab\t"` vira `"Tab"`).
- Só espaço ASCII, vazio, NUL, U+0001, U+2028, quebra de linha: 400.
- 100 caracteres: 201. 101: 400.
- Acentos (`Poupança Ç ã`), emoji isolado e `İstambul` sozinho: 201.
- Nome igual ao de conta arquivada: 409, como o ADR-023 pede.

**Corrida**
- 20 `POST` simultâneos com o mesmo nome, em 3 rodadas: 1 com 201 e 19 com 409, 1 linha no banco, nenhum 500.
- 20 variantes de caixa e espaço em paralelo: 1 com 201, 19 com 409, 1 linha.
- 20 `PATCH` paralelos alternando `true` e `false` na mesma conta: todos 200, estado final igual no banco e no `GET`.

**Corpo e Content-Type**
- `text/plain`, sem `Content-Type` e `application/vnd.api+json`: 415.
- Corpo vazio, lixo depois do JSON, JSON truncado: 400.
- Campo extra (`arquivada`, `id`, `moeda`, `usuario_id`, `saldoCentavos`): 400.
- Corpo de 4096 bytes passa o filtro (dá 400 por nome longo, não 413). De 4097 bytes: 413.
- 5000 bytes com `Content-Length` e em `chunked`: 413 `{"erro":"corpo grande demais"}`, com `Connection: close`.
- Entrada hostil sem 500: UTF-8 inválido, NUL fora de string, JSON aninhado com 1500 níveis (400).
- `Accept: text/html`: 406 JSON, como o contrato diz.

**PATCH**
- Sem corpo: 400. Sem `Content-Type`: 415.
- `"arquivada"` como string `"true"`, `"yes"`, `1`, array, `null`, objeto vazio, campo extra, JSON truncado: 400. `null` e `{}` com a mensagem do campo.
- Repetir o mesmo valor: 200 e mesma conta (idempotente, como a spec diz).

**`?arquivadas`**
- `true` e `false`: certos. `TRUE`, `True`, `1`, `yes`, `on`, vazio, `%20true`, `true%20`: 400.
- Sem o parâmetro: só ativas. Parâmetro extra (`foo=bar`) é ignorado.

**Banco fora**
- `docker pause`, sessão válida, `GET` e `POST /contas` e `/healthz` com pool já aquecido: 503 `{"erro":"serviço indisponível"}` em cerca de 2 s. Ver B1 para a primeira chamada.
- Sem cookie, `GET /contas` dá 401 em 2 ms, sem tocar no banco.
- `docker unpause`: 200 em 0,02 s.
- Durante a pausa nada foi gravado (a conta de teste "Pausado" não existe no banco).
- `docker stop` (conexão recusada): 503 em 2 s nas quatro rotas. Log com `Connection refused`.

**Log**
- Busca literal em todo o log da sessão: nomes de conta, e-mails, senhas, tokens de sessão, zero ocorrências.
- As linhas de 409 registram só `usuario_id`.
- Nenhuma linha `erro não tratado`, nenhum 500 em toda a sessão.

## Limites

- B1 foi reproduzido uma vez, em processo novo. Uma segunda repetição com reinício não rodou: a checagem de segurança da ferramenta bloqueou o comando de reinício, e não tentei contornar. Também não medi `/healthz` como primeira chamada depois de subir (a F1 registrou 90 s nesse caso).
- A corrida foi com 20 requisições simultâneas, o que está no limite do combinado. Não testei carga maior nem esgotamento do pool.
- Falha do banco no meio de um `INSERT` ou `UPDATE` não foi reproduzida; só a falha de conexão antes da consulta.

## Resumo

- **Achados:** 1 Bloqueia, 6 Dívida, 5 Sugestão.
- **Os que travam primeiro:** B1. A primeira chamada com o banco congelado leva 30 s, não 2 s.
- **Recomendação: segurar.** Autorização, isolamento por dono, corrida, limite de corpo e vazamento em log passaram em todos os cenários. B1 é o único que bloqueia. D1 e D4 são correções pequenas e dão cobertura de tipo e de texto, e D6 é o teste que faltou para pegar B1. S1 precisa de decisão de produto antes de virar código. A decisão final é do tech-lead.
