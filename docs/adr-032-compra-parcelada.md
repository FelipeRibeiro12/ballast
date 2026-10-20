# ADR-032: Compra parcelada na F11

**Status:** aceito
**Data:** 2026-10-20

## Contexto

O modelo em `docs/contracts/dados.md` prevê a compra parcelada como N linhas de
`lancamento` ligadas por `parcela_de_id`, com `parcela_num` e `parcela_total`,
a mesma `data_competencia` e a `data_caixa` avançando um mês por parcela. O
ADR-031 deixou a regra da fatura pronta para isso. O backlog pede editar ou
cancelar a compra inteira e também uma parcela só.

## Decisão

- **Só em cartão de crédito e só despesa.** Parcelamento em outra conta, ou
  receita parcelada, dá 400. Carnê e boleto parcelado ficam para quando houver
  pedido: neles a parcela futura ainda não foi paga e seria prevista, não
  realizada.
- **Modelo.** `lancamento` ganha `parcela_de_id` (FK composta com `usuario_id`
  para o próprio `lancamento`), `parcela_num` e `parcela_total`, de 2 a 48. As
  três colunas são todas nulas ou todas preenchidas, `parcela_num` vai de 1 a
  `parcela_total`, e **todas as parcelas, inclusive a primeira, apontam para a
  primeira** (a primeira aponta para si mesma), para agrupar sem `OR`. Parcela
  não tem template nem divisão.
- **Datas.** Todas as parcelas têm `data_competencia` = data da compra. A
  primeira tem `data_caixa` = vencimento da fatura da compra (ADR-031); a
  parcela `k` vence `k - 1` meses depois, no dia de vencimento do cartão,
  caindo no último dia em mês curto.
- **Valor.** `valorTotalCentavos` dividido por N com `setScale(2,
  RoundingMode.DOWN)` em todas menos a última, que leva o resto. A soma fecha no
  total (a regra do centavo do CLAUDE.md). Parcela de zero centavo dá 400.
- **Status.** Todas `realizado`: a compra aconteceu e o limite do cartão já
  está comprometido. O saldo do cartão cai pelo total na hora (ADR-024), e o
  saldo projetado e as faturas mostram cada parcela no seu vencimento.
- **Rotas.**
  - `POST /compras-parceladas` com `{"titulo", "descricao"?,
    "valorTotalCentavos", "parcelas", "categoriaId", "contaId", "data"}` cria as
    N parcelas numa transação, com a conta travada e o teto do saldo conferido
    pelo total. Devolve 201 com a compra e as parcelas.
  - `GET /compras-parceladas/{id}` devolve a compra (o id é o da primeira
    parcela) com todas as parcelas.
  - `PATCH /compras-parceladas/{id}` com `{"titulo"?, "descricao"?,
    "categoriaId"?}` muda a compra inteira. Valor, número de parcelas, data e
    conta não se editam: corrigir é apagar e lançar de novo.
  - `DELETE /compras-parceladas/{id}` apaga todas as parcelas.
  - Uma parcela só: `PUT /lancamentos/{id}` muda título, descrição, categoria
    e valor daquela parcela (a soma deixa de fechar no total, como no ajuste da
    F7); trocar conta ou data de uma parcela dá 400 `edite a compra inteira`.
    `DELETE /lancamentos/{id}` apaga só aquela parcela (o estorno de uma
    parcela). A primeira parcela não se apaga sozinha, porque as outras apontam
    para ela: 400 `apague a compra inteira`.
  - Em `GET /lancamentos`, a parcela traz `compraParceladaId`, `parcelaNum` e
    `parcelaTotal` (nulos nos outros).
- **Fevereiro com fechamento e vencimento no mesmo dia** (dívida da F10).
  Quando o vencimento cortado cai no mesmo dia do fechamento cortado, o
  pagamento feito nesse dia conta na própria fatura: a janela do pago passa a
  incluir o próprio dia do vencimento quando ele é igual ao fechamento.

## Justificativa

A primeira parcela apontar para si mesma deixa "todas as parcelas da compra"
virar `WHERE parcela_de_id = :id`, com um índice parcial só.

Limitar a edição da compra inteira a texto e categoria evita reagendar
parcelas e recalcular valores já lançados, que é o mesmo problema que o
ADR-026 adiou na recorrência.

## Alternativas descartadas

- **Tabela `compra_parcelada` separada**: o modelo já resolve com as colunas em
  `lancamento`, e a soma continua derivada.
- **Parcelas futuras como `previsto`**: o saldo do cartão esconderia a dívida já
  contraída, e o disponível ficaria errado.
- **Primeira parcela com `parcela_de_id` nulo**: obrigaria `id = :id OR
  parcela_de_id = :id` em toda consulta do grupo.

## Consequência

`docs/contracts/dados.md` ganha o recorte das colunas e o índice parcial em
`parcela_de_id`. A F12 em diante não muda nada aqui.
