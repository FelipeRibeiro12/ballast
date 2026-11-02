# ADR-081: NFT e posição DeFi da carteira pela Zerion

**Status:** aceito
**Data:** 2026-11-02

## Contexto

O dono quer ver NFT e DeFi (pools de liquidez, stake, depósito) nas carteiras observadas. A Alchemy
do ADR-071 lê saldo de token, não posição em protocolo: um LP ou um stake aparece como um recibo
sem contexto, e o NFT não aparece. Montar leitura por protocolo à mão não escala.

## Decisão

- **Provedor:** Zerion API v1, plano gratuito, uma chave (`ZERION_API_KEY`), autenticação Basic com
  a chave no usuário. Dois pedidos por leitura de carteira, por rede:
  `GET /wallets/{endereço}/positions/?filter[positions]=only_complex&filter[chain_ids]={rede}
  &filter[trash]=only_non_trash` e `GET /wallets/{endereço}/nft-positions/?filter[chain_ids]={rede}`.
  Cobre Ethereum, Base, Arbitrum, Polygon, Avalanche e Robinhood Chain. Solana e Bitcoin ficam sem
  DeFi e NFT por enquanto.
- **O que entra:** pool de liquidez vira uma linha só (a Zerion devolve uma posição por token do
  pool, agrupadas por `group_id`), com o valor somado. Stake, depósito, rendimento, bloqueado,
  recompensa e empréstimo. Empréstimo grava o valor negativo. Lixo (`is_trash`) e NFT de spam
  ficam de fora, como o que a Zerion marca como não exibível.
- **Modelo:** tabela `posicao_carteira` (V44), retrato da última leitura: cada leitura apaga as
  linhas da carteira e grava as novas, na mesma transação. Não é movimento, não entra no saldo
  nem no patrimônio. Valor em centavos de dólar inteiros (`bigint`), arredondado uma vez com
  `HALF_EVEN`; quantidade `numeric(38,18)`; textos de terceiro limpos e cortados em 100.
- **Falha não derruba o saldo:** a leitura de posições roda depois da gravação do saldo. Se a Zerion
  falhar, der 429 ou devolver 202 (dado em preparo), as posições antigas ficam. 400 (rede ou
  endereço que ela não cobre) vira "sem posição".
- **Sem chave:** `posicoesConfiguradas=false` na API e a tela diz que NFT e DeFi estão desligados.
- **Privacidade:** o endereço vai no caminho da URL da Zerion, como ela exige. Nenhuma exceção do
  cliente sai com a URL; o log leva só o status. Imagem de NFT não é carregada: a tela mostra só
  texto, para não abrir `img-src` da CSP nem avisar terceiros de quem abriu a carteira.

## Alternativas descartadas

- **Alchemy NFT API:** a documentação do endpoint não pôde ser lida na sessão e cobriria só NFT;
  DeFi pediria outro provedor de qualquer jeito.
- **DeBank:** pago.
- **Ler contratos (Uniswap, Aave, Lido) direto:** um adaptador por protocolo, sem fim.

## Consequências

- Cota gratuita pequena (a Zerion limita DeFi a uma fração da cota): a leitura de 60 s mínimos por
  carteira e o job de 6 h continuam sendo o teto de uso. Confirmar os limites do plano no painel.
- Valor de NFT é o piso (floor) informado pela Zerion; pode estar defasado ou nulo.
- DeFi e NFT ainda não somam no patrimônio. Fica no backlog.
