# ADR-055: Tetos por usuário e prazo do DELETE de conta

**Status:** aceito
**Data:** 2026-10-24

## Contexto

O ADR-049 limitou usuários e requisições por IP, mas um usuário logado ainda cria
conta, recorrência, parcelada, título e movimento sem fim. Um script com uma
sessão encheria o Neon do tier gratuito (ADR-044). Já o `DELETE /contas/{id}`
mexe em quase todas as tabelas e, numa conta enorme, segura a conexão até o
`socketTimeout` de 30 s.

## Decisão

### Tetos

| Teto | Padrão | O que conta |
|---|---|---|
| contas | 100 | todas, arquivadas inclusive |
| recorrências ativas | 200 | templates com `ativo`; reativar também confere |
| compras parceladas | 1000 | primeiras parcelas (`parcela_num = 1`) |
| títulos de renda fixa | 500 | todos, resgatados inclusive |
| movimentos de cripto | 20000 | todos |
| ativos de cripto | 200 | todos |
| cotações | 100000 | as do usuário, manuais e do job |

- Cada teto vem de `ballast.limits.*` (`LIMITE_*` no ambiente), de 1 a 1.000.000.
  Vazio vale o padrão e valor fora da faixa derruba a subida, como o teto de
  usuários.
- Os padrões ficam bem acima de qualquer uso real. Servem para segurar script,
  não para dosar o app.
- Estourar responde 409 `limite de X atingido`. O 409 já é o status de
  "conflito com o que existe" nas mesmas rotas (nome repetido, ativo repetido).
  O 403 do teto de usuários vale porque lá não há sessão.
- O teto vale por `usuario_id`, nunca global. Toda contagem filtra por ele.
- Cotações entram porque `POST /ativos-cripto/{id}/cotacoes` é manual e só o
  limite por IP (600 por minuto) o seguraria. A contagem parte dos ativos do
  usuário e lê a PK de cada um, porque não há índice por `usuario_id` na
  cotação e uma migration não se justifica por isso. O teto conta também as
  cotações do job, então com 200 ativos a cotação manual esgota em cerca de um
  ano e meio. O dono sobe o número se chegar lá.
- Lançamentos ficam de fora desta rodada: crescem por uso normal e pedem teto
  e paginação juntos.

### Corrida

`UserLimits.checkRoom` roda dentro da transação da escrita, antes de gravar, e
toma `pg_advisory_xact_lock` com chave `usuario:teto`. A contagem acontece com a
trava tomada, então a segunda requisição simultânea já enxerga a linha da
primeira. Escolhi a trava consultiva e não `FOR UPDATE` na linha do usuário
porque o `FOR UPDATE` conflita com o `FOR KEY SHARE` que toda FK para `usuario`
toma, e serializaria o usuário inteiro. A chave por tipo de teto evita que criar
conta espere criar movimento. É a mesma técnica do teto de usuários (ADR-049).

A exceção `LimitReachedException` sai de dentro da transação, que desfaz tudo, e
o `ApiExceptionHandler` a traduz. A trava é a primeira coisa tomada na
transação, depois do `SET LOCAL lock_timeout`, então não fecha ciclo com as
travas de linha. Na reativação a trava vem depois da linha do template, o que
também não fecha ciclo: quem cria não trava template.

`UserLimitsTest` dispara 12 criações simultâneas de conta com teto 6 e confere
6 criadas, 6 recusadas e 6 linhas no banco.

### Prazo do DELETE de conta

- `WriteTransaction.execute(lockTimeout, statementTimeout, ...)` faz
  `SET LOCAL statement_timeout` na abertura. O valor é por instrução e inclui a
  espera por trava.
- O padrão é 15 s (`TIMEOUT_APAGAR_CONTA`, aceita `15s`, `500ms`, até 5 min).
  Folga para uma conta grande de verdade e menos que os 30 s do socket.
- Estourar (SQLState 57014) responde 503 `serviço indisponível`, como o banco
  fora e a espera por trava. Escolhi 503 e não 504: o 504 é de proxy, e o web já
  trata 503 como "tente de novo". A transação desfaz tudo, então nada é
  apagado pela metade.
- Só o `DELETE /contas/{id}` usa o prazo. As outras escritas seguem só com o
  `lock_timeout` de 2 s.

## Consequências

- Rodada de teste: o `UserLimitsTest` sobe contexto próprio com tetos baixos. Os
  da suíte inteira ficam nos padrões, que nenhum teste alcança.
- Quem passa do teto precisa apagar algo ou pedir ao dono para subir a variável.
- A criação de recorrência, parcelada, título, movimento, ativo, cotação e conta
  ganha uma contagem e uma trava consultiva por chamada. O custo é uma ida
  extra ao banco, e a contagem mais pesada (cotações) lê no máximo 100 mil
  entradas de índice.
- Fica de fora: teto de lançamentos, de metas e de orçamentos, e a paginação.
