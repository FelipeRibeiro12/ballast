# ADR-036: Editar e apagar contas e recorrências

**Status:** aceito
**Data:** 2026-10-22

## Contexto

Conta hoje só nasce e arquiva (ADR-023); recorrência só nasce e desativa
(ADR-026). Errar o nome de uma conta, o limite de um cartão ou o valor de um
aluguel não tem conserto pela interface. O dono pediu editar e apagar as duas,
mantendo o arquivar ao lado do apagar: arquivar esconde, apagar leva junto.

## Decisão

### Editar conta

- `PATCH /contas/{id}` passa a aceitar, além de `arquivada`, `nome` e
  `saldoInicialCentavos`, e em cartão `cartao.limiteCentavos` e
  `cartao.contaPagamentoId`. Campo ausente não muda. Saldo inicial de conta
  cripto continua só 0.
- Tipo não muda. Fechamento e vencimento do cartão também não (ADR-031): mudar o
  ciclo recalcularia a `dataCaixa` de compras já lançadas.
- Nome repetido dá 409, como na criação.

### Apagar conta

- `DELETE /contas/{id}` apaga a conta e tudo que é dela: lançamentos (inclusive
  parcelas e ocorrências), recorrências com regra e divisão, cartão, lista de
  categorias permitidas e movimentos de cripto. 204. Ativo e cotação de cripto
  são do usuário, não da conta, e ficam.
- **Transferência com outra conta não mexe na outra conta.** A transferência é
  desfeita e a perna da outra conta vira um lançamento comum, com o mesmo
  título, valor, datas e status: perna de saída vira despesa em "Outros", de
  entrada vira receita em "Outras receitas". O saldo e o histórico da outra
  conta ficam iguais. Apagar um cartão antigo não faz a conta corrente "ganhar"
  de volta os pagamentos de fatura.
- Conta que paga um cartão ativo ou arquivado dá 400 `esta conta paga um
  cartão; troque a conta de pagamento antes`. O cartão aponta para ela por FK.
- As contas envolvidas são travadas em ordem de id antes de qualquer escrita,
  como na transferência.
- O web pede confirmação digitando o nome da conta e diz quantos lançamentos e
  transferências vão junto, lidos de `GET /contas/{id}/resumo-exclusao`.

### Editar recorrência

- `PUT /recorrencias/{id}` com o corpo do `POST`, para o web reaproveitar o
  formulário. O tipo não muda (400 se vier outro): a categoria e o sinal
  dependem dele.
- O template muda e as ocorrências **previstas de hoje em diante que não foram
  editadas à mão** (`desgarrado = false`) são apagadas e geradas de novo com os
  dados novos. Realizadas, canceladas, editadas à mão e as vencidas antes de hoje
  ficam como estão. A chave `(template_id, ocorrencia_data)` impede que a regra
  nova gere em cima de uma data que ficou.
- Template inativo também se edita; nada é gerado até reativar.
- Ordem de travas: template, as previstas (o `DELETE`), e só então a conta.
  Editar ocorrência trava o lançamento e depois a conta; travar a conta antes
  do `DELETE` fecharia ciclo com ela.

### Reativar e apagar recorrência

- `PATCH /recorrencias/{id}` com `ativo: true` passa a valer: reativa e gera de
  hoje até o horizonte.
- `DELETE /recorrencias/{id}` apaga o template, a regra, a divisão e todas as
  previstas dele, inclusive as vencidas. Realizadas e canceladas ficam como
  lançamentos avulsos (o vínculo com o template some). 204.

## Justificativa

Converter a perna da outra conta em lançamento comum é o único jeito de apagar
uma conta sem reescrever o saldo de outra. "Outros" e "Outras receitas" vêm da
semente de todo usuário e ainda não há CRUD de categoria que as remova.

Regerar só o que é previsto, futuro e intocado preserva tudo o que o usuário já
viu acontecer ou ajustou.

## Alternativas descartadas

- **Apagar as duas pernas da transferência**: muda o saldo da outra conta.
- **Recusar apagar conta com transferência**: na prática, recusaria quase todo
  cartão.
- **Apagar conta como soft delete**: é o que o arquivar já faz.
- **Editar recorrência só daqui para frente criando outro template**: a lista
  ficaria com dois aluguéis e o histórico partido.

## Consequência

ADR-023 ganha o `DELETE` (o que ele descartou foi usar `DELETE` para arquivar) e
o `PATCH` alargado. ADR-026 ganha editar, reativar e apagar.
