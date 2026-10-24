# ADR-058: Renda fixa e cripto endurecidos (onda 6a)

**Status:** aceito
**Data:** 2026-10-24

## Contexto

As revisões e os QAs das fatias F13.2, F13.3, F14 e F15 deixaram dívidas no
backend de renda fixa e cripto. Esta onda paga as de API. O web é outra rodada:
todo campo novo da resposta é opcional, e o formato que o web já consome não
muda. Nenhuma migration: o modelo de dados fica como está.

## Decisões

### 1. A busca do Banco Central sai da requisição

- Gravar título (POST e PUT) e movimento de cripto antes da primeira PTAX pede a
  busca com `IndexUpdater.requestFrom` e responde na hora. A busca roda numa
  thread só, em segundo plano. Pedidos que chegam durante uma busca juntam-se
  na data mais antiga e viram uma rodada.
- O cálculo usa o que já está guardado. Enquanto o índice não chega, o título
  vale como aplicado e o movimento em dólar volta com `custoCentavos` nulo: a
  leitura seguinte já traz o valor em reais. Antes a resposta do POST esperava
  até 15 s e relia o movimento.
- `BcbIndexSource` lê no máximo 2 MiB por resposta (cinco anos de CDI diário
  têm uns 150 KB) e interrompe a busca que passa de 15 s no total. O timeout de
  5 s do cliente vale por leitura e um servidor que pinga um byte por vez
  passaria dele. Estourar o teto ou o prazo é erro de busca: o job loga o nome
  da exceção, sem a URL, e tenta de novo na rodada seguinte.
- O job das 8h segue síncrono na thread dele.

### 2. Título vencido deixa de render

Depois do `vencimento` o valor fica congelado no valor da data de vencimento,
com o IOF e o IR contados até ela. A listagem traz `vencido: true` a partir do
dia do vencimento. Marcação depois do vencimento vale como o valor final que o
banco mostrou, sem render.

### 3. Desfazer resgate

`DELETE /titulos-renda-fixa/{id}/resgate` responde 204, escopado pelo usuário.
Idempotente: título sem resgate também dá 204. Título alheio, inexistente ou id
mal formado dá 404 com o mesmo corpo.

### 4. Marcação fora do período

Decisão: **recusar na escrita e esconder o que já está gravado.**

- Resgate com data antes de uma marcação já gravada: 400 `resgate não pode ser
  antes de uma marcação` (no mesmo dia vale). Editar a aplicação para depois de
  uma marcação: 400 `dataAplicacao não pode ser depois de uma marcação`.
  Marcação depois do resgate já era recusada.
- A marcação só vale entre a aplicação e o resgate, inclusive. Fora disso ela
  não entra no cálculo nem em `ultimaMarcacao`. A linha continua no banco: se o
  resgate for desfeito, a marcação volta a valer.

Alternativa descartada: apagar as marcações na hora do resgate ou da edição.
Perde dado do usuário e o desfazer não restituiria.

### 5. Título e posição sem valor

- Título cujo bruto, líquido ou rendimento passa de 9007199254740991 centavos
  vem com os campos de valor nulos e `motivoSemValor: "fora do limite"`. A
  lista nunca mais responde 400 por isso, e o 400 `renda fixa fora do limite`
  deixou de existir.
- `GET /patrimonio` soma os outros títulos e lista os excluídos em
  `rendaFixaSemValor: [{tituloId, motivo}]`. Só a soma total fora da faixa segue
  dando 400 `patrimônio fora do limite`.
- Posição de cripto com qualquer valor fora da faixa vem com todo valor e custo
  nulos (a quantidade fica) e `motivoSemValor: "fora do limite"`. O 400
  `posição fora do limite` deixou de existir. No patrimônio ela entra em
  `semCotacao` com `motivo: "fora do limite"`; as sem cotação ganham `motivo:
  "sem cotação"`.

### 6. Aviso de índice por título

`avisoIndice` (texto curto ou nulo) diz quando o índice guardado não cobre o
período que rende, do ponto de partida (aplicação ou marcação) até a data:

- sem CDI nenhum, CDI que começa mais de 4 dias depois do ponto de partida, ou
  CDI que termina mais de 5 dias antes da data (folga de fim de semana e
  feriado, e o CDI de hoje só sai amanhã);
- no `ipca`, também sem IPCA, IPCA que começa depois do mês de partida, ou IPCA
  mais de 2 meses atrás da data.

O cálculo segue com o que há. Título resgatado ou aplicado no dia não avisa.

### 7. Editar e apagar ativo e cotação

- `PUT /ativos-cripto/{id}` com `{simbolo, nome, decimais}`. Rede e contrato são
  a identidade e não mudam (campo a mais dá 400). Menos decimais do que algum
  movimento usa: 409 `há movimento com mais casas decimais`.
- `DELETE /ativos-cripto/{id}`: 204, e leva as cotações. Com movimento, 409
  `ativo tem movimentos`; a FK dos movimentos segura o movimento que entra no
  meio.
- `PUT` e `DELETE /ativos-cripto/{id}/cotacoes/{momento}`, com o `momento` que
  a resposta da cotação devolveu (ISO-8601 com fuso). O `PUT` leva só `{precoUsd}`.
  Só a cotação manual muda; a da CoinGecko é 409 `cotação automática não se
  altera`. Ativo alheio, momento sem cotação ou fora do formato: 404.

### 8. Conta cripto e a projeção de saldo

`GET /saldo-projetado` sem `contaId` deixa a conta cripto fora do escopo (a de
benefício já ficava). Com `contaId` de conta cripto: 400 `conta cripto não tem
saldo projetado`; conta cripto de outro usuário segue 404. Conta de
investimento não mudou: não tem saldo nem lançamento, e fica fora do escopo da
tarefa.

### 9. Custo do cálculo

- A raiz 252 do fator prefixado fica num mapa em memória por taxa (chave sem
  zeros à direita, `ConcurrentHashMap`). Teto de 1000 entradas: cheio, calcula
  sem guardar. Há milhões de taxas possíveis, então mapa sem teto seria
  vazamento.
- `IndexQueries.insert` grava em lote de 500 (`JdbcTemplate.batchUpdate`) com o
  mesmo `ON CONFLICT DO NOTHING`.
- `earliestInvestment` virou `earliestApplicationOrMovement`: a consulta olha o
  primeiro título e o primeiro movimento de cripto.

## Testes novos

Busca muda, lenta e gigante (servidor HTTP local), escrita rápida com a busca
presa, título vencido, desfazer resgate, marcação fora do período, título e
posição sem valor, aviso de índice, editar e apagar ativo e cotação, conta
cripto na projeção, 503 em toda rota de cripto e de renda fixa, venda com PTAX,
posição em reais e dólar e meio centavo na conversão.

## Consequência

O web precisa tratar os campos novos quando quiser mostrá-los: `vencido`,
`avisoIndice` e `motivoSemValor` no título, `motivoSemValor` na posição,
`motivo` em `semCotacao`, `rendaFixaSemValor` no patrimônio e as quatro rotas
novas de cripto e a de desfazer resgate. `custoCentavos` do movimento em dólar
pode vir nulo no POST (antes vinha preenchido quando a busca da PTAX dava certo
dentro da requisição).
