# ADR-071: Observar endereço de carteira na F24

**Status:** aceito
**Data:** 2026-10-29

## Contexto

A F24 cadastra um endereço público numa conta `cripto` e lê o saldo dele na
rede (ADR-009). O ADR-009 deixou três pontos abertos: o indexador, o modelo da
posição sincronizada e o que fazer com token de spam. O ADR-035 fixou que a
posição é a soma de movimentos e que `confiavel` é por usuário; o ADR-042 que
movimento sem custo deixa o custo médio nulo.

## Decisão

### Indexador: Alchemy, plano gratuito

- Uma conta só cobre as três redes do MVP: JSON-RPC de Ethereum e Base, com
  `alchemy_getTokenBalances` e `alchemy_getTokenMetadata` para descobrir tokens,
  e o RPC padrão do Solana (`getBalance`, `getTokenAccountsByOwner`). Cadastro
  sem cartão. A chave vem de `ALCHEMY_API_KEY`; sem chave, o job não roda e a
  rota de sincronizar responde que o indexador não está configurado (leitura
  antiga continua visível).
- RPC público sozinho (publicnode e parecidos) só dá o saldo nativo: não lista
  token. Helius só cobre Solana e pediria uma segunda conta e uma segunda
  chave. Descartados.
- A chave de Alchemy faz parte da URL. Por isso o cliente nunca deixa a
  exceção do `RestClient` sair: loga só a classe e o status, e lança
  `IndexerUnavailableException` sem causa. Endereço vai no corpo do POST
  JSON-RPC, nunca na URL.
- Os termos do plano gratuito e a licença não puderam ser conferidos nesta
  sessão (sem acesso à rede). Conferir na hora de criar a chave; a troca é
  trocar a implementação de `WalletIndexer`.
- Timeout de 5 s para conexão e leitura, no estilo do `CoinGeckoQuoteSource`.
  No máximo 100 tokens por endereço por leitura; o que passar disso é ignorado
  e a leitura marcada como parcial na resposta de erro do log (sem endereço).

### Interface

`WalletIndexer.read(rede, endereco)` devolve a lista de `Holding(contrato,
simbolo, decimais, quantidade)` com `contrato` nulo para o ativo nativo. Falha
vira `IndexerUnavailableException`. O teste usa um fake; nenhum teste chama a
rede.

### Validação do endereço

- EVM: `0x` mais 40 hexadecimais. Tudo minúsculo ou tudo maiúsculo passa sem
  checksum. Caixa mista exige checksum EIP-55 correto. Gravado em minúsculas
  (o unique do banco não pode tratar o mesmo endereço como dois).
- Solana: base58 que decodifica em exatamente 32 bytes. Gravado como veio.
- Keccak-256 (EIP-55) não vem no JDK. Implementado à mão, em uma classe
  pequena, conferido com os vetores do próprio EIP-55. Evita dependência que a
  F25 vai escolher de qualquer jeito (secp256k1).
- `unique (usuario_id, rede, endereco)`. Duplicado responde 409.

### Modelo: o saldo lido vira movimento sem custo

Sem tabela de posição guardando quantidade (ADR-035). `movimento_cripto` ganha:

- `tipo` aceita também `entrada` e `saida`.
- `origem` (`manual` | `sincronizada`, padrão `manual`) e `carteira_id` (nulo no
  manual, FK para a carteira, `ON DELETE CASCADE`).
- `custo` e `moeda` passam a aceitar nulo, só quando `origem = 'sincronizada'`
  e o tipo é `entrada` ou `saida`. Manual continua exigindo custo.

A sincronização compara a quantidade lida com a soma dos movimentos
sincronizados daquela carteira e daquele ativo e grava a diferença como
`entrada` (positiva) ou `saida` (negativa), com data de hoje em
`America/Sao_Paulo` e custo nulo. Rodar duas vezes seguidas não grava nada na
segunda. Saldo que cai a zero grava a `saida` que zera.

- Carteira lida some se o usuário apagar a carteira: os movimentos dela vão
  junto.
- Movimento sincronizado não se apaga nem se edita à mão (ADR-009), a não ser
  pelo custo: `PUT /movimentos-cripto/{id}/custo` grava o custo em dólar
  (ADR-038), uma vez, e a entrada passa a contar como compra no custo médio. Já
  com custo informado, `PUT` de novo é 409.
- Custo médio: posição que tem alguma `entrada` ou `saida` sem custo fica com
  `custoMedioUsd`, custo da posição e ganho **nulos** (a mesma saída de "falta
  o dólar" do ADR-042). A resposta traz `custoPendente: true`. O valor de
  mercado da posição continua calculado.
- `criptoAportadoCentavos` e `criptoValorizacaoCentavos` ficam nulos quando
  alguma posição tem custo pendente (regra do ADR-042 para dado faltando).

### Ativo desconhecido e spam

- Ativo novo vindo da leitura nasce `confiavel = false`, identificado por
  `rede` + `contrato`. O nativo da rede (ETH em Ethereum e Base, SOL em
  Solana) nasce `confiavel = true`: o contrato é nulo e a identidade não tem
  como ser forjada.
- Ativo `confiavel = false` não entra em `criptoCentavos`, nem no patrimônio,
  nem na lista de `semCotacao`, nem recebe cotação do job. O usuário o promove
  (`PUT /ativos-cripto/{id}` ganha `confiavel` opcional; ausente mantém o valor) e aí ele vale. A lista de posições o mostra
  com `confiavel: false` para ele decidir.
- O símbolo vem do indexador; no Solana, que não traz metadado, vira os 8
  primeiros caracteres do mint. Nome e símbolo do indexador são texto não
  confiável: truncados, sem espaço nas pontas, e exibidos escapados pelo React.
- O teto de ativos do ADR-055 vale. Estourado, a leitura ignora os ativos
  novos que não cabem e segue com os que já existem.

### Indexador fora do ar

Falha de rede, timeout, 4xx, 5xx ou resposta fora do formato: a leitura inteira
é descartada (nada é gravado, tudo ou nada), `erro_ultima_sync` guarda um
código genérico (`indexador_indisponivel`, `resposta_invalida`, `sem_chave`) e
`sincronizada_em` fica como estava. A resposta é 200 com a carteira, os saldos
antigos, `sincronizadaEm` e `leituraFalhou: true`. Nunca erro, nunca zero.

### Rotas e dados

Tudo escopado por `usuario_id`, outro usuário vê 404.

- `POST /carteiras` com `{contaId, rede, endereco, rotulo?}`. Conta tem que ser
  do usuário e do tipo `cripto`, ativa. Conta de outro usuário responde 400 `conta não encontrada`, como em `/movimentos-cripto`. Rede só `ethereum`, `base` ou `solana`.
- `GET /carteiras`, `GET /carteiras/{id}`, `DELETE /carteiras/{id}`.
- `POST /carteiras/{id}/sincronizar`. No máximo uma leitura por carteira a cada
  60 s: dentro do prazo devolve o estado atual sem chamar o indexador.
- `PUT /movimentos-cripto/{id}/custo`.
- Teto de 20 carteiras por usuário, `LIMITE_CARTEIRAS` (ADR-055), 409 ao estourar.
- O endereço aparece no corpo das respostas (o dono precisa vê-lo) e em nenhum
  log, URL, mensagem de erro ou nome de métrica. Nenhuma rota recebe endereço
  no caminho.
- O job sincroniza todas as carteiras ativas a cada 6 horas, uma por vez,
  com pausa entre elas, ligado por `ballast.carteiras.job-enabled`.

### Tela

Cadastro digitando o endereço, sem extensão alguma. Texto fixo: o app só
observa, nunca pede seed, chave privada nem assinatura de transação. Lista com
a data da última leitura, aviso de leitura que falhou, selo de ativo não
confiável e aviso de custo pendente com campo para informar.

## Alternativas descartadas

- **Tabela `posicao_cripto` com quantidade guardada**: dois lugares para a
  mesma verdade (ADR-035).
- **Indexar histórico de transações**: dá `hash_tx` e data real, mas exige
  paginação por endereço e plano pago em muita carteira. O saldo atual cobre o
  que a F24 promete. Fica no backlog como melhoria.
- **Bouncy Castle para o Keccak**: dependência grande por 40 linhas.

## Consequência

`dados.md` ganha `carteira_observada` real e as colunas novas de
`movimento_cripto`. A F25 só preenche o endereço e a prova de posse
(`verificada_em`). Quem mostra posição precisa tratar `custoPendente` e
`confiavel`.
