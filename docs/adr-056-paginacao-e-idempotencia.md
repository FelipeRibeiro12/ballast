# ADR-056: Paginação por cursor e chave de idempotência

**Status:** aceito
**Data:** 2026-10-24

## Contexto

O ADR-055 deixou de fora os lançamentos e a paginação. `GET /lancamentos` e
`GET /movimentos-cripto` devolvem tudo de uma vez, e um movimento de cripto pode
chegar a 20000 por usuário. As escritas de dinheiro também não têm proteção contra
repetição: duplo clique, retry de rede ou da PWA offline gravam duas vezes.

## Decisão

### Paginação

- Parâmetros opcionais `limite` (1 a 1000, padrão 1000) e `cursor`. O padrão é o
  teto, então quem não manda nada recebe o mesmo que antes, até 1000 itens.
- Formato: o corpo continua o array. O cursor da próxima página vai no cabeçalho
  `X-Next-Cursor`, só quando há mais itens. Escolhi cabeçalho e não envelope porque
  o web atual lê o array e nada muda para ele, e `X-Next-Cursor` em vez de `Link`
  porque o cliente só precisa do valor, sem montar URL atrás do proxy do Vercel
  (ADR-044). A origem é a mesma, então o cabeçalho passa sem CORS.
- O cursor é keyset, não deslocamento: base64url de `data|criadoEm|id` do último
  item. A consulta usa `(data, criado_em, id) < (...)` na mesma ordem do
  `ORDER BY`, que já era total (`id` desempata). Páginas não repetem nem pulam item
  quando entra lançamento novo no meio. Busca `limite + 1` linhas para saber se há
  próxima página, sem `COUNT`.
- O cursor vem do cliente e é só uma posição. Toda consulta continua filtrando por
  `usuario_id`; o cursor de A usado por B anda nos dados de B. Cursor malformado dá
  400 `cursor inválido`, `limite` fora da faixa dá 400.
- Os parâmetros são declarados nas rotas, então o 400 de parâmetro desconhecido
  (ADR-051) segue valendo para o resto.
- Ficam sem paginar `/contas`, `/recorrencias`, `/titulos-renda-fixa` e
  `/ativos-cripto`: os tetos do ADR-055 (100, 200, 500, 200) já limitam o tamanho
  da resposta, e paginar custaria cursor e teste sem ganho.
- `GET /lancamentos` ainda exige `de` e `ate` com no máximo 366 dias. Esta
  paginação não troca essa regra.

### Idempotency-Key

- Cabeçalho opcional nos POST que gravam valor: `/contas`, `/lancamentos`,
  `/transferencias`, `/compras-parceladas`, `/recorrencias`, `/metas`,
  `/metas/{id}/aportes`, `/movimentos-cripto`, `/titulos-renda-fixa` e as
  marcações e o resgate. A lista fica em `IdempotencyFilter.ROUTES`, que também
  alimenta a spec. Cadastro, MFA e cotação manual ignoram o cabeçalho.
- Formato: 1 a 80 caracteres de `[A-Za-z0-9._:-]`. Um UUID serve. Fora disso, ou
  com dois cabeçalhos, 400.
- Tabela `idempotencia` (V23), chave primária `(usuario_id, chave)`. Guardo SHA-256
  do corpo em bytes, a rota (caminho da requisição), o status e o corpo da resposta.
  A chave é do usuário da sessão, nunca de dado do cliente: o usuário B com a
  chave de A abre uma linha própria.
- Mesma chave, usuário, rota e corpo: devolve o status e o corpo originais com
  `Idempotent-Replayed: true`. Mesma chave com outra rota ou corpo: 422. Escolhi
  422 e não 409 porque o 409 do app já significa "conflito com o que existe" (nome
  repetido, teto atingido), e aqui a requisição é que contradiz a anterior.
- Só resposta 2xx fica guardada. Erro (400, 409, 503) apaga a claim, porque o
  cliente corrige e tenta de novo com a mesma chave, e guardar o erro o prenderia.
- Corrida, sem tocar nas transações dos serviços: um filtro depois da autorização
  faz `INSERT ... ON CONFLICT DO NOTHING` da claim em autocommit, antes da escrita.
  Quem ganha executa. Quem perde lê a linha: concluída vira repetição; em
  andamento espera (consulta a cada 50 ms, no máximo 10 s) e, passado o prazo,
  responde 409 com `Retry-After`. O índice único é a trava, e a espera não segura
  conexão do pool.
- Por que não uma transação em volta do pedido: o serviço usa JPA, que só envia o
  `INSERT` no commit. Com a transação do filtro por fora, uma violação de
  constraint (benefício, nome repetido) estouraria depois de o controller já ter
  montado a resposta, e o mapeamento de erro que os testes cobrem se perderia.
- Requisição que morre no meio deixa a claim em andamento. Com mais de 60 s ela é
  assumida por quem pedir em seguida: o `UPDATE` troca `criada_em` e compara a
  marca vista, então só uma das requisições leva. Só quem tem a marca conclui ou
  libera a linha.
- Limpeza: o job diário de identidade (`IdentityCleanupJob`) apaga as linhas com
  mais de 24 h. Reaproveitei o job em vez de abrir outro agendamento.
- Falha ao guardar a resposta (banco caiu depois da escrita) não vira erro para o
  cliente: a escrita já aconteceu e responde normalmente. A claim envelhece e é
  assumida. Um retry nessa janela grava de novo; a janela é a de um banco que caiu
  entre dois comandos.

## Consequências

- O corpo do pedido passa a ser lido uma vez no filtro (no máximo 4 KiB, o teto do
  `BodyLimitFilter`) e entregue de novo à cadeia.
- Cada POST com chave faz 2 a 3 comandos extras no banco (claim, conclusão); sem
  cabeçalho o custo é zero.
- O web manda a chave em cada POST de dinheiro e segue o cursor até o fim, com teto de 20 páginas.
- Fica de fora: guardar respostas de erro, TTL configurável, chave em rotas PUT,
  PATCH e DELETE (já são idempotentes por natureza, ou o 404 cobre).

## Cliente web

- `fetchLancamentos` e `fetchMovimentosCripto` seguem o `X-Next-Cursor` até acabar e devolvem a lista inteira. O teto é de 20 páginas; passar dele lança `ApiError`, para nunca mostrar lista cortada como se fosse completa.
- Todo POST de dinheiro em `web/src/api.ts` manda `Idempotency-Key`. O wrapper aceita `chave` opcional no fim. Sem ela, gera `crypto.randomUUID()` por chamada: cada clique em salvar é uma tentativa nova, e reenviar depois de erro de validação já usa chave nova. Hoje nenhuma tela faz retry automático, então nenhuma tela precisou mudar. Quem passar a repetir a mesma requisição gera a chave uma vez e repassa nas repetições.
- 422 (chave reusada com outro corpo) e 409 com `Retry-After` (primeira ainda rodando) caem hoje no erro genérico ou no erro de conflito de cada recurso. Mensagem própria para eles fica de fora desta fatia.
