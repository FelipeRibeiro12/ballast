# ADR-042: Aporte e valorização da cripto na F15

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A posição de cripto mostra o valor e "compras menos vendas". Isso não diz
quanto do valor veio do dinheiro posto e quanto veio do preço, e cripto subindo
parece economia. O QA da F13 (D2) apontou que a tela não tem custo médio. O
backlog previa que faltaria o custo de alguns movimentos, mas `movimento_cripto`
exige custo desde a F13; o custo faltando só chega com a sincronização da F24.

## Decisão

### Custo médio ponderado

- Por posição (conta e ativo) e por moeda, em dólar e em reais. A compra soma
  quantidade e custo. A venda tira do custo a fração vendida da quantidade, e
  o que recebeu menos essa fração é o ganho realizado. Vender tudo leva o custo
  inteiro, sem divisão, para a soma das vendas fechar com ele.
- No mesmo dia a compra entra antes da venda. A conferência da venda (ADR-035)
  soma o dia inteiro, e só nessa ordem a posição nunca passa por baixo de zero.
  Depois vem `criado_em`.
- Em reais o custo de cada movimento vai pela PTAX da data dele, como o
  `custoCentavos` da F13.3. O valor vai pela PTAX da data da posição: o ganho em
  reais inclui o efeito do dólar.
- Divisão com 34 dígitos (`DECIMAL128`). Arredonda uma vez na saída: centavos
  por `HALF_EVEN`, como o valor da posição, e o custo médio por unidade em 8
  casas, como a cotação.
- Calculado na leitura, a partir dos movimentos. Nada é guardado.

### Na posição

`GET /posicoes-cripto` ganha `custoMedioUsd`, `custoDaPosicao(Usd)Centavos`,
`ganho(Usd)Centavos` (valor menos custo da posição, os dois já em centavos) e
`realizado(Usd)Centavos`. A conta fecha: compras menos vendas é o custo da
posição menos o realizado.

### No patrimônio

`GET /patrimonio` ganha `criptoAportadoCentavos` (compras menos vendas, em
reais, das posições que entram em `criptoCentavos`) e
`criptoValorizacaoCentavos` (o resto). A valorização inclui o ganho realizado
e o dólar. Os dois são nulos quando falta a PTAX de algum movimento. Posição
sem cotação fica fora das duas somas, como fica do total.

## Justificativa

Custo médio é o método que a Receita usa para cripto e o que a pessoa compara
com o preço do dia. Aporte como compras menos vendas é o que o usuário de fato
pôs e tirou, e a valorização sai por diferença, sem um segundo cálculo que
possa divergir.

## Alternativas descartadas

- **PEPS (primeiro que entra, primeiro que sai)**: dá outro custo para a mesma
  carteira e não é o que a declaração usa.
- **Aporte e valorização de um período (de uma data a outra)**: pede o valor
  em duas datas e o fluxo entre elas. Fica para a F18, que desenha o
  patrimônio no tempo.
- **Guardar o custo médio no movimento**: apagar uma compra antiga mudaria
  todos os seguintes.
- **Renomear `custoCentavos` para dizer que é compras menos vendas**: quebra o
  contrato. A descrição na spec e o texto da tela já dizem.

## Consequência

Posição zerada continua fora da lista, e o ganho realizado dela não aparece em
lugar nenhum. A F24 vai trazer movimento sem custo, e aí o custo médio daquela
posição fica nulo e a tela pede o custo.
