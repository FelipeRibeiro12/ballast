# ADR-046: Relatórios na F18

**Status:** aceito (parcela passa a contar no mês dela, ADR-075)
**Data:** 2026-10-23

## Contexto

A F18 pede gasto por categoria, receita contra despesa no mês, patrimônio no
tempo e consumo de orçamento, legíveis em 360px. Patrimônio no tempo precisa
do saldo de cada conta, da posição de cripto com a cotação da data e do valor
de cada título de renda fixa, mês a mês. Isso sozinho é uma fatia.

## Decisão

### Escopo

- Esta fatia entrega despesa por categoria e receitas contra despesas.
- **Patrimônio no tempo vira a F18.1.**
- O consumo de orçamento já tem barra por teto na tela Orçamento e no Início
  (F16). Relatórios não repete o desenho e leva até lá pelos atalhos.

### Regra dos números

- **Competência**, como pede o CLAUDE.md para gráfico por categoria. A
  receita contra despesa usa a mesma data, para a soma das categorias bater
  com a coluna de despesa do mês. A compra no cartão conta no mês da compra.
  O pagamento da fatura é transferência e fica fora, então nada conta duas
  vezes.
- **Só o realizado até hoje**, em São Paulo: a regra do gasto do orçamento
  (ADR-043). Previsto, cancelado, lançamento com data depois de hoje e
  transferência ficam fora.
- A categoria filha soma na de cima, como no orçamento. A hierarquia tem um
  nível.
- Cada lançamento conta na categoria dele. A parcela que teve a categoria
  trocada no PUT conta na categoria nova.

### Rota

- `GET /relatorios?mes=AAAA-MM`, com o mês corrente como padrão. Uma leitura
  devolve as duas coisas que a tela desenha:
  - `despesasPorCategoria`, da maior para a menor, com `totalDespesasCentavos`;
  - `receitasEDespesas`, os seis meses que terminam em `mes`, com zero nos
    meses vazios.
- Soma fora da faixa do JSON responde 400 `relatório fora do limite`, como o
  orçamento.

### Tela

- Seção `#/relatorios` fora da barra de navegação, como Orçamento e Metas.
  Entra por um cartão no Início com as três categorias de maior gasto, e pelos
  atalhos de planejamento, que agora têm três links.
- Despesa por categoria: barra horizontal de uma cor só, com valor e parte do
  total escritos. A maior categoria ocupa a largura toda.
- Receitas contra despesas: colunas duplas num eixo só, com legenda e eixo à
  direita. Tocar numa coluna mostra o mês no detalhe. A tabela com todos os
  valores fica em "Ver em tabela".
- Seis meses porque seis pares de barras de 14px cabem em 360px com o eixo.
- Cores das séries: azul e laranja. O verde e o vermelho de receita e despesa
  do resto do app falham no validador de daltonismo (ΔE 4,2 deutan no claro e
  3,3 no escuro, abaixo do mínimo de 8). Azul e laranja dão 24,7 e 26,8.
- SVG não entrou. Barras em HTML e CSS bastam e dispensam biblioteca de
  gráfico.

## Justificativa

Uma rota e uma leitura por mês: a tela não tem estado entre os dois gráficos
e não precisa de duas chamadas. Competência nas duas séries faz o total bater
entre os gráficos. O caixa continua na tela de Lançamentos (ADR-039).

## Alternativas descartadas

- **Receita contra despesa por caixa**: o total bateria com Lançamentos, mas
  não com as categorias do mesmo mês. A compra no cartão mudaria de mês entre
  os dois gráficos da mesma tela.
- **Incluir o previsto**: o gráfico do mês corrente ficaria igual ao do fim do
  mês. O orçamento já mostra o previsto à parte.
- **Biblioteca de gráfico (Recharts, Chart.js)**: dependência nova para barras
  que o CSS desenha.
- **Pizza ou rosca por categoria**: comparar valores próximos em ângulo é
  pior que em comprimento, e nomes longos não cabem em volta.
- **Doze meses**: em 360px as barras ficariam com menos de 8px.

## Consequência

- Agora são três seções fora da barra. A dívida da navegação continua no
  backlog.
- A F18.1 traz patrimônio no tempo, com a regra de data de cada parte.
