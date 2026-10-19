---
paths:
  - "**/*.sol"
---

# Solidity — Claustrum

Convenções de código dos contratos. Carrega só quando um `.sol` é lido.

## Nomenclatura

Nomes em inglês no código; comentários e mensagens em português.

| Elemento | Padrão | Exemplo |
|---|---|---|
| Contratos, structs, enums, eventos, erros | PascalCase | `AssetTwin`, `TwinMinted` |
| Variáveis e funções | mixedCase | `externalId`, `mintTwin` |
| Constantes e immutables | UPPER_SNAKE | `MAX_SUPPLY`, `ORACLE_ROLE` |
| Interfaces | `I` + PascalCase | `IAssetTwin` |

**Não use underscore.** Nem em variáveis privadas, nem em funções internas. A
única exceção é quando o underscore evita um problema real — parâmetro que
sombreia variável de estado, por exemplo, onde vale `owner_`. Nesse caso,
comente o motivo.

Funções herdadas da OpenZeppelin (`_safeMint`, `_setTokenURI`) mantêm o nome
original. Não são nossas.

Se um nome de variável colidir com um nome de função, mude o da variável, não o
da função — a função é a interface pública do contrato.

## Padrão de código

- Erros customizados em vez de `require` com string — gasta menos gas, e vamos
  medir gas nos resultados do artigo.
- Eventos em toda mudança de estado relevante. O backend e o frontend consomem
  esses eventos.
- `constant` e `immutable` sempre que o valor não muda depois do deploy.
- Ordem dentro do contrato: enums, structs, estado, eventos, erros, construtor,
  funções externas, públicas, internas, privadas.
- Evite herança profunda. Entre AssetTwin e o escrow, prefira composição — é
  mais fácil de explicar e de testar.
- Pragma fixo (`0.8.24`) nos contratos que vão para deploy, não `^0.8.24`.

## Segurança

- Checks-effects-interactions em qualquer função que movimente valor.
- `ReentrancyGuard` nas funções que movimentam valor, mesmo já seguindo a ordem
  acima.
- Pull payment: o destinatário saca, o contrato não envia. Evita que um
  recebedor malicioso trave a liberação do pagamento.
- Nunca `tx.origin` para autorização. Sempre `msg.sender`.
- Validar `address(0)` em todo endereço recebido.
- Nada de loop sobre array que cresce sem limite.

## NatSpec

`@notice`, `@param` e `@return` só nas funções externas e públicas. Função
interna ou privada não leva NatSpec: se precisa de explicação, um comentário de
uma linha resolve.
