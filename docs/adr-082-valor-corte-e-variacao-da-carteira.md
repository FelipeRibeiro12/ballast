# ADR-082: Valor por carteira, corte de US$ 0,50, DeFi no patrimônio e variação de 24 horas

**Status:** aceito
**Data:** 2026-11-04

## Contexto

A aba Cripto mostrava saldos e posições DeFi sem valor por carteira, sem total e sem dizer se o
dia foi de ganho ou perda. Poeira (token de centavos, posição DeFi de centavos) enchia a lista. O
ADR-081 deixou o DeFi fora do patrimônio.

## Decisão

- **Corte:** valor absoluto abaixo de US$ 0,50 (50 centavos de dólar) é lixo. 0,49 some, 0,50 e
  0,51 ficam (a regra do dono é "abaixo de"). Vale para saldo de token de carteira e para posição
  DeFi, em módulo (empréstimo de US$ -0,30 também some). NFT não tem corte (não tem preço). DeFi
  sem valor conhecido (nulo) aparece e não entra em soma.
- **Onde filtra:** na leitura (SQL e saída da API), nunca na gravação. O retrato de
  `posicao_carteira` e os movimentos sincronizados continuam completos: o token que valorizar
  reaparece sem nova leitura. O corte é constante no código (`DustCutoff`), não configuração.
- **Valor por carteira:** `GET /carteiras/resumo` devolve, por carteira do usuário e no total,
  valor em centavos de dólar e em centavos de real (USDC/BRL, ADR-077), token mais DeFi
  (empréstimo negativo). Cada carteira arredonda uma vez, `HALF_EVEN`, a partir do valor cheio; o
  total é a soma dos valores já arredondados das carteiras, para a coluna fechar na tela sem
  diferença de centavo. Sem cotação do dólar, o campo em real vem nulo e o dólar segue.
- **Variação de 24 h:** valor absoluto e percentual, por carteira e no total.
  Token: preço atual contra a última cotação própria (`cotacao`) com momento até 24 h antes,
  sem chamada nova e sem dependência nova. Ativo sem cotação com essa idade fica fora da variação
  e a carteira marca `variacaoParcial`. DeFi: a Zerion devolve `changes.absolute_1d` e
  `percent_1d` por posição; entram em `variacao_24h_usd_centavos` (V45) e a variação de pool soma
  as pernas. Percentual = variação sobre o valor de 24 h atrás (valor atual menos variação); base
  zero ou negativa devolve percentual nulo. Percentual em string com 2 casas (é razão, não
  dinheiro), absoluto em centavos.
- **DeFi no patrimônio:** `defiCentavos` e `defiUsdCentavos` no `Patrimonio`, somados em
  `totalCentavos` só quando a data pedida é hoje (o retrato é da última leitura e não tem
  histórico). No histórico mensal o DeFi fica fora e a tela diz isso. NFT fica fora do total.
- **Solana no DeFi:** a Zerion lista `solana` como rede (chain id `solana`). Entra no mapa de
  redes de posição. Não foi possível testar com chave real nesta sessão. Bitcoin segue sem DeFi.
- **Poeira no patrimônio por conta:** posição (conta e ativo) abaixo do corte, formada só por
  movimento sincronizado de carteira, sai do total do patrimônio. Posição manual nunca é cortada.

## Consequências

- V45 só acrescenta coluna nula. Linhas antigas ficam sem variação até a próxima leitura.
- Falha da Zerion ou da CoinGecko não derruba nada: vale o último retrato e a última cotação.
- O percentual é calculado em `BigDecimal` com escala explícita e arredondado na saída.
