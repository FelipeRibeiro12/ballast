# ADR-065: Resumo de contas por grupo e previsto na pílula

**Status:** aceito
**Data:** 2026-10-28

## Contexto

O total das contas somava tudo, cartão inclusive, e o previsto do mês ficava
numa linha à parte dos totais de Lançamentos.

## Decisão

- **Contas**: o total não conta cartão de crédito. O resumo vira um carrossel
  que desliza de lado, sem biblioteca, com os grupos: Total sem cartão,
  Corrente e poupança, Cofrinho (só se houver), Dinheiro, Benefício, Cripto,
  Investimentos, e Investimentos e cripto.
- Cripto e investimentos não têm saldo em reais na conta (ADR-035, ADR-037),
  então vêm de `GET /patrimonio` de hoje. Se a chamada falhar, esses grupos
  somem, o total fica só com as contas em reais e a tela avisa.
- O total inclui benefício, cripto e investimentos. Somas em `BigInt`.
- **Lançamentos**: o previsto de receita, despesa e resultado aparece dentro
  de cada pílula, abaixo do valor real. A linha "Previsto no mês" sai.
- Lançamento ligado a terceiro fica fora dos totais da tela (ADR-062).

## Consequência

O total de Contas deixa de bater com a soma da lista, que ainda mostra o cartão
com saldo negativo.
