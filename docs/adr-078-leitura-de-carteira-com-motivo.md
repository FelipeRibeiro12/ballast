# ADR-078: Leitura de carteira com motivo e primeira leitura no cadastro

**Status:** aceito
**Data:** 2026-10-30

## Contexto

O dono cadastrou uma carteira e a tela disse "Não consegui atualizar agora.
Ainda não há leitura anterior." sem explicar nada. As causas pelo código:

1. O indexador Alchemy exige `ALCHEMY_API_KEY`; sem ela a leitura manual
   responde "indexador não configurado".
2. Cada rede exigia a sua URL (`ALCHEMY_ETHEREUM_URL`, `ALCHEMY_BASE_URL`,
   `ALCHEMY_SOLANA_URL`); sem URL, a rede não lia.
3. O job de 6 em 6 horas fica desligado por padrão
   (`BALLAST_CARTEIRAS_JOB_ENABLED`).
4. Cadastrar a carteira não disparava nenhuma leitura.
5. O web juntava todas as falhas numa frase só.
6. O logger `org.springframework.web.client` em DEBUG mostra a URL da chamada,
   e a URL da Alchemy leva a chave no caminho.

## Decisão

- A API devolve um motivo estável por tentativa (`motivo`): indexador não
  configurado, indexador fora do ar, indexador recusou a chave (401 ou 403),
  cota esgotada (429), resposta inválida e limite de 60 s. Também devolve
  `ultimaTentativaEm`, `proximaLeituraEm`, `indexadorConfigurado` e
  `sincronizacaoAutomatica`. "Rede sem URL" não existe, porque toda rede tem URL
  padrão; "carteira sem saldo" não é falha e o web a lê de `sincronizadaEm`
  preenchido com `saldos` vazio. Nenhum motivo traz chave, URL ou texto da
  exceção. O web traduz cada motivo e, no primeiro, diz ao dono para pôr
  `ALCHEMY_API_KEY` no `api.env`. O motivo da última tentativa fica na migration
  V41, que alarga o CHECK de `carteira_observada.erro_ultima_sync`.
- Só com `ALCHEMY_API_KEY`, as URLs já assumiam (confirmado no código) o padrão da Alchemy:
  `https://eth-mainnet.g.alchemy.com/v2/`, `https://base-mainnet.g.alchemy.com/v2/`
  e `https://solana-mainnet.g.alchemy.com/v2/`, com a chave no fim. URL
  explícita por rede continua mandando. **Incerteza:** os hosts vêm da
  documentação conhecida da Alchemy e não foram conferidos por rede aqui; se
  algum mudar, a URL explícita resolve sem deploy de código.
- A chave nunca aparece em log, mensagem de erro, resposta ou `toString`. O
  logger de cliente HTTP do Spring fica em nível mínimo `WARN`, e há teste que
  captura o log.
- Depois de cadastrar a carteira, uma primeira leitura roda fora da transação do
  cadastro, com o limite de 60 s. Se falhar, o cadastro já está commitado e a
  carteira fica como estava. O botão "Atualizar" por carteira repete a leitura,
  com estado de carregando.
- Para ligar a sincronização o dono põe na VM `ALCHEMY_API_KEY` e
  `BALLAST_CARTEIRAS_JOB_ENABLED=true` (`deploy/api.env.example` e runbook).

## Alternativas descartadas

- **Guardar a mensagem da exceção para mostrar:** vaza URL com chave.
- **Ligar o job por padrão:** sem chave ele só falha em silêncio. Continua
  explícito.

## Consequência

Fecha a dívida de log em DEBUG que mostrava a URL. A resposta de erro da leitura
manual ganha o campo de motivo no `openapi.yaml`.
