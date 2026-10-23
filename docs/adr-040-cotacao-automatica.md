# ADR-040: Cotação automática pela CoinGecko na F14

**Status:** aceito
**Data:** 2026-10-23

## Contexto

Até a F13.3 a cotação de cripto é só a digitada (ADR-035, ADR-038). A F14 pede
um worker com ticker, série histórica, a última cotação conhecida guardada e a
data da cotação visível na tela, com o teste obrigatório de que a API de
cotação fora do ar não trava lançamento. O provedor estava em "A completar" no
CLAUDE.md; o dono escolheu a **CoinGecko no plano Demo** (gratuito, com chave,
30 pedidos por minuto e 10 mil por mês).

## Decisão

### Provedor

- API v3 em `https://api.coingecko.com/api/v3`, chave no cabeçalho
  `x-cg-demo-api-key`, nunca na URL. A chave vem de `COINGECKO_API_KEY` e a URL
  de `COINGECKO_API_URL` (vazia vale a padrão). Sem chave o job não chama nada
  e avisa uma vez no log ao subir.
- Nativo por `/simple/price?ids=` com o id da moeda: `bitcoin`, `ethereum`
  (também para o ETH da Base e da Arbitrum), `polygon-ecosystem-token` (o POL)
  e `solana`. Todos num pedido só.
- Token por `/simple/token_price/{plataforma}?contract_addresses=`, até 100
  contratos por pedido: `ethereum`, `base`, `arbitrum-one`, `polygon-pos`,
  `solana`.
- `vs_currencies=usd`, `include_last_updated_at=true` e `precision=full`. O
  preço chega como número JSON e é lido direto em `BigDecimal`, sem `double`.
- Timeout de 5 s para conectar e para ler, como no Banco Central (ADR-037).
- O que vai para a CoinGecko é rede e contrato do token, que é dado público. O
  endereço da carteira do usuário nunca sai.

### Job

- `QuoteJob` roda 30 s depois de subir e a cada `ballast.cotacoes.interval`
  (padrão 30 min). Desliga com `BALLAST_COTACOES_JOB_ENABLED=false`; os testes
  desligam e chamam o `QuoteUpdater` direto.
- Pede os pares `(rede, contrato)` distintos dos ativos `confiavel` de todos os
  usuários. É processo do sistema, não rota: cada linha gravada fica com o
  `usuario_id` do dono do ativo, e a leitura continua escopada por usuário.
  Ativo não confiável (spam da F24) não é cotado.
- Grava em `cotacao` com `fonte = coingecko`, `moeda = USD` e `momento` =
  `last_updated_at` do provedor. O conflito na PK `(ativo_id, momento)` é
  descartado: a mesma atualização do provedor não duplica, e a cotação
  digitada no mesmo instante fica.
- Preço com 8 casas por `HALF_EVEN` (o mesmo motivo do valor da posição no
  ADR-035). Fica de fora o que arredonda para zero, o que não cabe em
  `numeric(20,8)` e o que vem com data mais de 5 min à frente do relógio.
- Uma rodada por vez (`tryLock`, como no `IndexUpdater`). Falha da fonte vira
  um aviso no log com o nome da exceção, sem a mensagem, e a rodada seguinte
  tenta de novo.

### Série e última cotação

- Cada atualização nova do provedor é uma linha a mais: a série histórica cresce
  daqui para frente. A posição e o patrimônio continuam pegando a última
  cotação até a data, seja digitada ou automática, e a CoinGecko fora não muda
  nada nisso: nenhuma requisição chama o provedor.
- `GET /posicoes-cripto` traz `cotacao.fonte` (`manual` ou `coingecko`). O web
  mostra dia, hora em São Paulo e a fonte.

### Banco

- `V16` alarga `cotacao_fonte_valida` para `('manual', 'coingecko')`.
- Sem índice novo: o job lê `ativo_cripto` inteira, que tem poucas linhas por
  usuário, e o `INSERT ... SELECT` filtra pela mesma tabela.

### Cota do plano

No pior caso uma rodada faz 6 pedidos (nativos e as cinco plataformas). A cada
30 min isso dá perto de 8.700 por mês, dentro dos 10 mil do Demo. Com só
nativos são 1.440.

## Justificativa

Job fora da requisição é o que garante o teste obrigatório: o lançamento nunca
espera a rede. A última cotação guardada já é o cache, sem tabela nova.

`last_updated_at` como momento torna a gravação idempotente sem guardar estado
do job.

## Alternativas descartadas

- **Buscar a cotação na requisição, com cache em memória**: a CoinGecko lenta
  seguraria a tela, e o cache some a cada restart.
- **Tabela de cotação global, sem `usuario_id`**: o ADR-035 decidiu ativo e
  cotação por usuário; trocar agora mexeria em toda leitura de posição.
- **Chave na URL (`x_cg_demo_api_key`)**: a URL vai para log de proxy e para a
  mensagem de exceção.
- **Buscar o histórico para trás (`market_chart/range`)**: precisa do id da
  moeda para cada token e de um pedido por ativo. Fica para a F14.1.

## Consequência

`dados.md` registra a fonte nova e `api.md` o campo `fonte` da posição. A F14.1
traz o histórico anterior à primeira cotação. A F24 (carteira observada) vai
gravar ativo com `confiavel = false`, que o job já ignora.
