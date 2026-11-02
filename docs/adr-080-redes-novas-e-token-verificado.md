# ADR-080: Redes novas e token verificado na leitura de carteira

**Status:** aceito
**Data:** 2026-11-02

## Contexto

O dono viu muito token fantasma na carteira e um total de R$ 6,20 que não batia com o que tem.
Pelo código: todo token novo nascia `confiavel=false`, ficava fora do patrimônio e o job de cotação
só cota ativo confiável. Os tokens verdadeiros sumiam do total e o spam de airdrop enchia a lista.
A leitura também parava em 100 tokens da primeira página, e o spam podia empurrar o token real para
fora. Só Ethereum, Base e Solana eram lidas (F30 do backlog).

## Decisão

- **Token verificado:** o indexador só devolve token que a CoinGecko cota pelo contrato (a mesma fonte
  do ADR-040). Token sem cotação some da leitura em vez de aparecer como "não confiável". O que
  passa nasce `confiavel=true`, e ativo antigo não confiável que aparece verificado é promovido.
  Se a fonte de preço cair, a leitura sai parcial: nada novo entra e nada é zerado.
- **Páginas:** a leitura EVM percorre até 10 páginas de saldo antes de filtrar, e só depois pede
  metadado dos verificados (teto de 100). O filtro vem antes do teto.
- **Redes EVM novas:** Arbitrum (`arb-mainnet`), Polygon (`polygon-mainnet`), Avalanche
  (`avax-mainnet`) e Robinhood Chain (`robinhood-mainnet`), todas em `*.g.alchemy.com`, hosts
  conferidos na tabela de redes da Alchemy. Mesma chave. Robinhood não tem id de plataforma na
  CoinGecko aqui: só o ETH nativo é cotado e a prova de posse fica desligada até o chain id ser
  conferido.
- **Bitcoin:** lê pela API Esplora pública da Blockstream (`/address/{endereço}`), sem chave:
  saldo confirmado é o que entrou menos o que saiu. O nó da Alchemy não tem índice por endereço. O
  endereço vai na URL do provedor público, como já vai no corpo para a Alchemy; nenhuma exceção
  sai com a URL. Endereço validado com checksum (base58check, bech32, bech32m). Sem prova de posse.
- Migration V43 alarga os CHECKs de rede e de formato de endereço.

## Consequências

- Token pequeno que a CoinGecko não lista não aparece. Quem quiser pode cadastrar o ativo à mão.
- Primeira leitura depois do deploy zera os fantasmas antigos (saída sincronizada) e traz os reais.
- Robinhood Chain: token ERC-20 não é cotado até existir plataforma na CoinGecko.
