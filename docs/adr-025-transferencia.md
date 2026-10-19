# ADR-025: Transferência entre contas na F4

**Status:** aceito
**Data:** 2026-10-19

## Contexto

Transferência move dinheiro entre duas contas do mesmo usuário sem ser receita
nem despesa. O modelo em `docs/contracts/dados.md` a descreve como duas linhas
de `lancamento` com `tipo = transferencia`, ligadas por uma linha de
`transferencia`. O ADR-024 deixou o `CHECK` de tipo e a categoria obrigatória
para esta fatia alargar.

## Decisão

- **Modelo.** Tabela `transferencia` com `id`, `usuario_id`, `saida_id` e
  `entrada_id`, como em `dados.md`. Cada perna é um `lancamento` com `tipo =
  transferencia`, mesmo valor e mesma data nas duas datas.
- **Lançamento alargado.** O `CHECK` de tipo passa a aceitar `transferencia`.
  `categoria_id` fica nulo **se e somente se** o tipo é `transferencia`.
- **O banco garante o par.** Saída e entrada são do mesmo usuário da
  transferência, nunca o mesmo lançamento, e em contas diferentes (invariante
  7 de `dados.md`). Cada lançamento participa de no máximo uma transferência.
  O `dba` escolhe o mecanismo (FK composta, `CHECK`, gatilho de restrição
  adiável) e registra o porquê. Valor e data iguais nas duas pernas, a
  aplicação garante e o teste trava.
- **Sinal no saldo.** A perna de saída subtrai e a de entrada soma. A direção
  vem da ligação em `transferencia`, não de coluna nova no lançamento.
- **Relatório.** Todo total de receita ou despesa exclui `transferencia`. Na
  tela do mês, as pernas aparecem na lista, fora dos totais de receita e
  despesa.
- **Rotas.**
  - `POST /transferencias` com `{"contaOrigemId", "contaDestinoId",
    "valorCentavos", "data", "titulo"?, "descricao"?}` cria as duas pernas e a
    ligação numa transação e devolve 201. Título ausente vira
    "Transferência".
  - `GET /transferencias/{id}` e `DELETE /transferencias/{id}`, que apaga a
    ligação e as duas pernas. De outro usuário ou inexistente, 404.
  - Sem `PUT` na F4: corrigir é apagar e criar de novo.
  - Em `GET /lancamentos`, a perna traz `transferenciaId` e `direcao`
    (`saida` ou `entrada`); nos outros tipos os dois vêm nulos.
  - `PUT` e `DELETE /lancamentos/{id}` numa perna de transferência dão 400
    `use a rota de transferência`, para o par nunca ficar pela metade.
- **Concorrência.** As duas contas são travadas em ordem de id (ADR-024).
  Conta arquivada, conta de outro usuário, contas iguais e saldo fora da faixa
  em qualquer das duas dão 400, sem gravar nada.

## Justificativa

Com a direção vinda da ligação, o lançamento não ganha coluna que só faz
sentido para um tipo. O custo é a consulta de saldo juntar `transferencia`.

Recusar editar a perna por `/lancamentos` é o jeito mais simples de o par
nunca divergir em valor, data ou conta.

## Alternativas descartadas

- **Tipos `transferencia_saida` e `transferencia_entrada`**: tiram o
  `JOIN` do saldo, mas desviam do modelo e duplicam o tipo em todo relatório.
- **Uma linha de lançamento só, com conta de origem e de destino**: quebra a
  regra de que todo lançamento tem uma conta, e o saldo por conta vira duas
  consultas.
- **`PUT /transferencias/{id}` já agora**: a fatia é curta de propósito; editar
  entra quando houver pedido.

## Consequência

`GET /lancamentos` ganha dois campos. O web separa transferência dos totais
do mês. Recorrência (F5) não gera transferência nesta versão.
