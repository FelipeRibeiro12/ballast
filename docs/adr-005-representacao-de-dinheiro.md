# ADR-005: Representação de dinheiro

**Status:** aceito, emendado em 2026-10-19 pela troca de stack (ADR-016)
**Data:** 2026-10-03

## Contexto

O app lida com dois tipos de valor com exigências diferentes: moeda fiduciária
(2 casas decimais) e cripto (até 18 casas, no caso de ativos baseados em
Ethereum).

Ponto flutuante está descartado de saída: `0.1 + 0.2 != 0.3` em qualquer
linguagem com IEEE 754, e erro de centavo em app de finanças destrói a
confiança no produto.

## Decisão

**Fiat:** `numeric(18, 2)` no banco, `BigDecimal` na aplicação, **inteiro em
centavos no JSON**.

**Cripto:** `numeric(38, 18)` no banco, `BigDecimal` na aplicação, string
decimal no JSON. Cada ativo declara seus decimais (`BTC` 8, `ETH` 18) e a
interface formata por isso.

**Cotação:** `numeric(20, 8)`, nunca inteiro — preço de token pode ter muitas
casas significativas.

### Emenda de 2026-10-19

A versão original usava inteiro de centavos também no banco e na aplicação
(`bigint`, e um tipo `Centavos int64` em Go), porque Go não tem decimal nativo.
Em Java `BigDecimal` é da plataforma, e fiat e cripto passam a ter a mesma
representação interna. **O contrato do JSON não muda.**

A conversão acontece só no DTO: `valor.movePointRight(2).longValueExact()` na
saída, `BigDecimal.valueOf(centavos, 2)` na entrada. `longValueExact` lança
exceção se sobrar fração ou estourar, em vez de truncar calado.

A precisão é 18, não 19, para que todo valor que o banco aceita caiba em `long`
de centavos: `numeric(19, 2)` vai até ~10¹⁹ centavos, e o `long` para em
9,2 × 10¹⁸.

Nenhuma tabela com valor monetário existe ainda (a F1 só tem `usuario`,
`sessao` e `tentativa_login`), então não há migration de conversão.

## Justificativa

Uma representação só na aplicação elimina a conversão entre inteiro e decimal
em cada cálculo que mistura os dois (cotação × quantidade, percentual de
salário). O JSON fica em centavos porque o problema que ele resolve é do outro
lado: o JavaScript transforma número decimal em `double`.

Para cripto, inteiro de 64 bits **não serve**: 1 ETH são 10¹⁸ wei, e o limite
do `int64` é cerca de 9,2 × 10¹⁸ — ou seja, estouraria em aproximadamente 9
ETH. Daí `numeric` no banco e decimal na aplicação.

## Alternativas descartadas

- **Float ou double**: descartado por imprecisão inerente. Vale também para
  `Double` e para variável intermediária "só de cálculo".
- **Inteiro de centavos também no banco e no Java**: foi a decisão original,
  certa em Go. Em Java vira duas representações para o mesmo conceito.
- **String**: evita imprecisão no transporte, mas empurra todo cálculo para a
  aplicação e impede agregação no banco.
- **Fiat como string decimal no JSON**: mudaria o contrato com o web sem
  ganho: centavos em inteiro já é exato do lado do JavaScript.

## Consequência

- No JSON da API, valor fiat trafega como **inteiro em centavos**, nunca como
  decimal. Quem consome formata. Isso evita a armadilha de o JavaScript receber
  `1234.56` e fazer aritmética em `double`.
- Cripto trafega como **string decimal**, pelo mesmo motivo: `Number` em JS não
  representa 18 casas.
- **O frontend tem duas regras de dinheiro, uma por tipo.** Fiat chega como
  `number` inteiro em centavos, cripto como `string` decimal. Quem gera o
  cliente TS ou escreve formatação precisa tratar os dois de forma separada.
- Arredondamento acontece **uma vez, na exibição**. Nunca no meio de um
  cálculo, nunca duas vezes.
- Divisão com resto (parcelamento e divisão de salário por percentual) precisa
  de regra explícita de onde vai o centavo sobrando. Ver `contracts/dados.md`.

### Armadilhas de `BigDecimal` que viram regra

Três erros silenciosos, que passam em teste superficial:

- **Construir com `double`**: `new BigDecimal(0.1)` guarda o lixo binário do
  `double`. Sempre `new BigDecimal("0.1")`, com `String`.
- **Comparar com `equals`**: `new BigDecimal("1.0")` não é `equals` a
  `new BigDecimal("1.00")`, porque a escala entra na comparação. Use
  `compareTo`.
- **Dividir sem escala**: dízima lança `ArithmeticException`. `setScale` (ou a
  escala no `divide`) com `RoundingMode` explícito, e o modo escolhido fica
  comentado com o porquê.

As mesmas regras estão em `.claude/stacks/java-spring.md`.
