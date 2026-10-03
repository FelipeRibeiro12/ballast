---
paths:
  - "web/**/*.ts"
  - "web/**/*.tsx"
---

# TypeScript

## Tipos

- `strict` ligado. Se não estiver, é dívida registrada, não escolha.
- **`any` não entra.** Quando o tipo é desconhecido de verdade, `unknown` e
  estreite com verificação. `any` desliga o compilador justamente onde ele
  seria útil.
- Não use `as` para calar o compilador. Se precisou, ou o tipo está errado ou
  falta uma verificação — resolva a causa.
- Tipo de retorno explícito em função exportada. Inferência é boa dentro do
  arquivo e ruim como contrato público.
- `type` para união e forma de dado; `interface` quando algo vai ser
  implementado ou estendido.
- União de literais em vez de `enum`:
  `type Status = 'previsto' | 'realizado' | 'cancelado'`.

## Erros

- Nunca `catch` vazio. Nunca `catch` que só loga e segue como se nada tivesse
  acontecido.
- Erro lançado é classe de erro, não string.
- `async` sem `await` em promessa é bug silencioso: toda promessa é aguardada
  ou explicitamente tratada.

## Dinheiro no cliente — regra deste projeto

O backend manda **fiat como inteiro em centavos** e **cripto como string
decimal** (ADR-005). Isso não é detalhe de serialização, é proteção:

- **Nunca** faça aritmética de dinheiro com `number` em valor decimal.
  `0.1 + 0.2 !== 0.3`.
- Centavos somam e subtraem como inteiro, sem problema. Divisão com resto
  segue a regra do modelo: resto na última parcela.
- Cripto chega como string e **continua string** até a exibição. `Number` não
  representa 18 casas decimais — converter já perde.
- Formatação é só na borda de renderização, com `Intl.NumberFormat`.
- Tipo próprio para centavos (`type Centavos = number` com função de
  construção) evita somar centavos com quantidade por acidente.

## Contrato com a API

Os tipos do cliente são **gerados** a partir do OpenAPI (ADR-002). Não escreva
à mão o tipo de uma resposta da API: se o contrato mudar, o tipo escrito à mão
mente e o compilador não avisa.

Se o tipo gerado não existe ainda, pare e peça — não improvise uma interface
paralela que depois divergir.
