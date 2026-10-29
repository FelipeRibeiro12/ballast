# ADR-072: Conectar carteira e prova de posse na F25

**Status:** aceito
**Data:** 2026-10-29

## Contexto

A F24 (ADR-071) cadastra o endereço digitado. A F25 acrescenta um botão que
descobre as carteiras instaladas no navegador e preenche o campo de endereço, e
uma prova de posse opcional por assinatura de mensagem. O ADR-009 fixa os
limites: o app observa, nunca assina transação, nunca pede seed nem chave, e
digitar o endereço continua sendo caminho de primeira classe.

## Decisão

### Conectar só preenche

- O botão "Conectar carteira" abre a lista de carteiras descobertas. Escolher
  uma pede ao navegador a lista de contas (`eth_requestAccounts` no EVM,
  `standard:connect` no Solana) e coloca o endereço no campo. Nada é salvo
  sozinho: o usuário ainda envia o formulário.
- Sem extensão a lista fica vazia, com mensagem clara, e o campo manual
  continua intacto. Recusa do usuário (código 4001 ou rejeição) mostra aviso e
  não quebra a tela.
- O texto da interface diz que o app nunca pede seed, chave privada nem
  assinatura de transação.
- Nenhuma chamada de transação existe no código: `eth_sendTransaction`,
  `signTransaction` e `signAndSendTransaction` ficam fora (teste de varredura).
- Endereço não vai para log, URL, `localStorage` nem para o console.

### Bibliotecas do web

- EVM: `@wagmi/core` v2 com `viem`, sem a camada React do `wagmi` (evita
  `react-query` e provider global). Descoberta por EIP-6963 (`mipd` embutido no
  wagmi). Só `injected` com `multiInjectedProviderDiscovery`.
- Solana: `@wallet-standard/app` (`getWallets`), mais leve que
  `@solana/wallet-adapter`, que puxa React e dezenas de adaptadores. Só usa as
  features `standard:connect` e `solana:signMessage`.
- Tudo carregado por `import()` dinâmico quando o usuário clica em conectar. O
  bundle inicial não cresce. Versões exatas, lockfile commitado. Licenças: wagmi
  e viem MIT, wallet-standard Apache-2.0; todos mantidos.
- Nenhuma variável `VITE_` nova. Não há projectId nem chave.
- Ordem da lista: Rabby, depois Phantom, depois as demais por nome. A
  identificação é pelo nome que a carteira anuncia (`info.name` no EIP-6963,
  `name` no Wallet Standard), sem confiar nele para nada além da ordem.

### Prova de posse (opcional, sobre carteira já cadastrada)

Duas rotas, escopadas por `usuario_id`. Carteira de outro usuário responde 404.

- `POST /carteiras/{id}/posse/desafio` devolve `{nonce, mensagem, expiraEm}`.
  O servidor monta a mensagem; o cliente assina exatamente o texto recebido.
- `POST /carteiras/{id}/posse/verificar` recebe `{nonce, assinatura}` e
  responde a carteira com `verificadaEm` preenchido. Falha responde 400 com
  mensagem genérica (`assinatura inválida` ou `desafio expirado`), sem dizer
  qual parte falhou além disso.

Nonce:

- 16 bytes de `SecureRandom`, em hexadecimal. Vale por 5 minutos.
- Tabela `posse_desafio` (V37), uma linha por carteira (`carteira_id` é a
  chave): pedir outro desafio substitui o anterior. A linha guarda `nonce`,
  `mensagem`, `expira_em`.
- Consumo atômico: `DELETE ... WHERE carteira_id = ? AND usuario_id = ?
  RETURNING` na verificação, antes de checar a assinatura. Falha de assinatura
  também queima o nonce. Reuso e expiração caem na mesma resposta.
- O nonce recebido é comparado com o guardado por `MessageDigest.isEqual`.
- Limite: no máximo um desafio novo a cada 5 s por carteira (cai no 429 do
  limitador da borda, ADR-049, mais o teto natural da substituição).

Mensagem:

- EVM, formato SIWE (EIP-4361): domínio, endereço, declaração "Provar que esta
  carteira é minha no Ballast. Isto não é uma transação e não custa nada.",
  `URI`, `Version: 1`, `Chain ID` (1 Ethereum, 8453 Base), `Nonce`, `Issued At`
  e `Expiration Time`. Assinada com `personal_sign` (EIP-191).
- Solana: texto simples com as mesmas linhas (domínio, endereço, rede, nonce,
  emitida e expira em), assinado com `signMessage` (Ed25519 sobre os bytes
  UTF-8).
- O domínio vem de `ballast.posse.domain` (`BALLAST_POSSE_DOMAIN`), não do
  cabeçalho `Host` da requisição. A rede e o domínio entram na mensagem para a
  assinatura não valer em outro site nem em outra rede.

Verificação no servidor:

- EVM: recupera o endereço da assinatura de 65 bytes (`r`, `s`, `v` com 27/28
  ou 0/1), hash `keccak256("\x19Ethereum Signed Message:\n" + len + mensagem)`
  com o `Keccak256` da F24, e compara com o endereço da carteira em minúsculas.
  `s` alto (maleabilidade) é rejeitado. A recuperação na curva secp256k1 usa
  `org.bouncycastle:bcprov-jdk18on` (MIT, mantido). O JDK não traz secp256k1 e
  escrever aritmética de curva à mão é risco que não vale 1 dependência. O ADR-071
  descartou o Bouncy Castle só para o Keccak.
- Solana: assinatura de 64 bytes (base64 vinda do cliente) verificada com o
  `Signature` `Ed25519` do JDK contra a chave pública de 32 bytes (o endereço
  base58 decodificado).
- Guarda só `carteira_observada.verificada_em` (já existe desde a V36). A
  assinatura e a mensagem não ficam depois do consumo. Nada disso entra em log.
- Mudar a carteira de endereço não existe (o endereço é imutável), então a
  prova não precisa ser invalidada.

### Migration

`V37__cria_posse_desafio.sql`: tabela `posse_desafio` com FK composta
`(carteira_id, usuario_id)` para `carteira_observada (id, usuario_id)`,
`ON DELETE CASCADE`. `verificada_em` já existe, sem mudança.

## Alternativas descartadas

- **wagmi com React e react-query**: provider global e mais bundle por nada.
- **`@solana/wallet-adapter`**: pesa mais e traz adaptadores que o app não usa.
- **Recuperar secp256k1 com BigInteger à mão**: crypto caseira.
- **Cliente enviando a mensagem para o servidor verificar**: o servidor perde o
  controle de domínio, rede e expiração.
- **Sinalizar posse como condição para sincronizar**: a prova é opcional
  (ADR-009). O selo "posse verificada" só informa.

## Consequência

`dados.md` ganha `posse_desafio`. O contrato ganha 2 rotas e o campo
`verificadaEm` na carteira. A conexão e a prova são recurso do navegador do
usuário: o servidor nunca sabe qual carteira ele usa.

## Implementação

- Dependência: `org.bouncycastle:bcprov-jdk18on` 1.86 (última estável no Maven Central em 2026-10-29; já estava no pom desde o ADR-015, sem dependência nova).
- Domínio da mensagem: `BALLAST_POSSE_DOMAIN`. Fora de `APP_ENV=development` a API não sobe sem ele (`PosseEnvironmentValidator`); em desenvolvimento vale `localhost:5173`.
- A hora de emissão do desafio não tem coluna: é `expira_em` menos 5 min. O limite de 5 s por carteira é o `WHERE` do `ON CONFLICT` na gravação do desafio, e responde 429 `muitas tentativas, tente mais tarde`.
