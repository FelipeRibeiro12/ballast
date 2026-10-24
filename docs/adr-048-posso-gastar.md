# ADR-048: Posso gastar quanto, na F19

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A F19 pede o saldo menos as contas a vencer até o próximo salário, respeitando
a restrição dos benefícios. O saldo projetado (ADR-029) já soma, dia a dia, o
realizado e o previsto por `data_caixa` nas contas que não são de benefício.

## Decisão

### O número

- **Livre é o menor saldo projetado de hoje até a véspera do próximo
  salário.** Saldo de hoje menos a soma das despesas até lá não serve,
  porque uma receita no meio do caminho cobriria uma despesa que vence antes
  dela. O menor saldo responde quanto dá para gastar hoje sem faltar para
  nenhuma conta já prevista.
- As regras do saldo projetado valem como estão. Prevista vencida entra, e a
  resposta traz o total delas para a tela avisar. Cartão entra pelo
  vencimento da fatura. Transferência entre contas do escopo se anula.
- **Por dia** é o livre dividido pelos dias da janela, contando hoje e a
  véspera, com `RoundingMode.DOWN`: o valor por dia nunca promete mais do que
  existe. É nulo quando o livre não é positivo.
- Livre negativo é quanto falta. A tela diz "Faltam" e o dia em que o saldo
  fica negativo.

### O próximo salário

- É a primeira receita, prevista ou realizada, com `data_caixa` depois de hoje
  na categoria **Salário** da semente ou numa filha dela, numa conta ativa que
  não é de benefício. A busca vai até 366 dias, o limite da projeção.
- O salário de hoje já é dinheiro de hoje. O próximo é o seguinte.
- Salário dividido (ADR-027) chega em partes. A próxima parte já fecha a
  janela, porque é ali que entra dinheiro de novo.
- Recarga de benefício não é salário, mesmo na categoria Salário.
- Sem salário à vista, a janela fecha no último dia do mês, e a tela pede a
  recorrência na categoria Salário.

### Benefícios

- Ficam fora do livre, como no saldo projetado (ADR-034).
- Cada conta de benefício ativa vem à parte, com o menor saldo projetado dela
  na mesma janela.

### Rota e tela

- A rota é `GET /posso-gastar`, sem parâmetro. Usa o `ProjectionService` uma
  vez para o total e uma vez por benefício.
- Na tela, é o primeiro cartão do Início, e recarrega quando um lançamento
  muda, como o saldo projetado.

## Justificativa

Reusar a projeção mantém uma regra só de "o que entra e sai em cada dia". O
cartão responde a pergunta do dia sem o usuário abrir o gráfico.

## Alternativas descartadas

- **Saldo de hoje menos as despesas até o salário**: superestima quando uma
  despesa vence antes de uma receita do meio do mês.
- **Maior receita recorrente como salário**: adivinha. Um aluguel recebido
  pode ser maior que o salário.
- **Marcar a categoria de salário numa coluna nova**: precisa do CRUD de
  categoria, que ainda não existe. Hoje o nome da semente é estável.
- **Arredondar o valor por dia para o mais perto**: em 21 dias, o meio
  centavo para cima soma mais do que o livre.

## Consequência

Quando chegar o CRUD de categoria, renomear Salário quebra a busca. A dívida
fica no backlog: marcar a categoria de salário explicitamente nessa fatia.
