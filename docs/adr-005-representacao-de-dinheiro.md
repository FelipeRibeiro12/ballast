# ADR-005: Representação de dinheiro

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O app lida com dois tipos de valor com exigências diferentes: moeda fiduciária
(2 casas decimais) e cripto (até 18 casas, no caso de ativos baseados em
Ethereum).

Ponto flutuante está descartado de saída: `0.1 + 0.2 != 0.3` em qualquer
linguagem com IEEE 754, e erro de centavo em app de finanças destrói a
confiança no produto.

## Decisão

**Fiat:** inteiro de 64 bits em centavos. No banco, `bigint`. Em Go, um tipo
próprio `Centavos int64` — não `int64` cru, para o compilador impedir soma
acidental com outro número.

**Cripto:** `numeric(38, 18)` no banco, `decimal.Decimal` em Go. Cada ativo
declara seus decimais (`BTC` 8, `ETH` 18) e a interface formata por isso.

**Cotação:** `numeric(20, 8)`, nunca inteiro — preço de token pode ter muitas
casas significativas.

## Justificativa

Inteiro em centavos é exato, rápido e cabe folgado em 64 bits para qualquer
patrimônio pessoal plausível.

Para cripto, inteiro de 64 bits **não serve**: 1 ETH são 10¹⁸ wei, e o limite
do `int64` é cerca de 9,2 × 10¹⁸ — ou seja, estouraria em aproximadamente 9
ETH. Daí `numeric` no banco e decimal na aplicação.

## Alternativas descartadas

- **Float ou double** — descartado por imprecisão inerente.
- **`numeric` para tudo, inclusive fiat** — correto, mas mais lento e mais
  verboso em Go sem ganho: centavos em inteiro já é exato.
- **String** — evita imprecisão no transporte, mas empurra todo cálculo para a
  aplicação e impede agregação no banco.

## Consequência

- No JSON da API, valor fiat trafega como **inteiro em centavos**, não como
  decimal. Quem consome formata. Isso evita a armadilha de o JavaScript receber
  `1234.56` e fazer aritmética em float.
- Cripto trafega como **string decimal**, pelo mesmo motivo: `Number` em JS não
  representa 18 casas.
- Arredondamento acontece **uma vez, na exibição**. Nunca no meio de um
  cálculo, nunca duas vezes.
- Divisão com resto (parcelamento e divisão de salário por percentual) precisa
  de regra explícita de onde vai o centavo sobrando. Ver `contracts/dados.md`.
