# ADR-057: Ordem de travas, categorias de semente e vínculos no banco

**Status:** aceito
**Data:** 2026-10-24

## Contexto

A QA da F13.1 (D1) mostrou 503 por deadlock ao editar uma recorrência, ou apagar uma
conta, ao mesmo tempo que se edita uma ocorrência dela, em 5 de 5 rodadas. Outras
cinco dívidas do backlog pediam o banco no lugar da convenção: a semente achada
pelo nome, a filha de categoria com tipo livre, o cartão como saída de transferência,
o aporte de meta sem ligação com a transferência e o `lockTemplate` com a divisão
nula.

## Decisão

### Causa do deadlock

Reproduzido por `LockOrderConcurrencyTest` (25 rodadas). O detalhe do Postgres
mostrou duas transações esperando uma a outra por linha de `lancamento`:

- Editar ocorrência (PUT) travava o lançamento, depois a conta, e o segundo UPDATE
  da mesma linha (`markDetached`) e o INSERT da marca de apagada pediam o template
  em `KEY SHARE`. Uma linha alterada pela própria transação tem a FK conferida de
  novo, mesmo com a chave igual.
- Editar recorrência e apagar conta travavam o template `FOR UPDATE` primeiro e
  depois iam atrás das linhas de `lancamento`, e esperavam a ocorrência que já
  segurava a linha e pedia o template.

Ciclo de duas pontas: lançamento, depois template contra template, depois lançamento.
O `DELETE` das previstas ainda travava em ordem de varredura, não de id.

### Ordem única de aquisição

**template, lançamentos por id, conta.** Quem precisa de mais de um recurso trava
nessa ordem e nunca volta atrás.

| Operação | Ordem |
| --- | --- |
| Confirmar, cancelar, editar, apagar ocorrência | template (`FOR KEY SHARE`, só se tem template), lançamento, conta |
| Editar recorrência | template (`FOR UPDATE`), previstas por id, conta |
| Desativar | template, previstas por id |
| Apagar recorrência | template, todas as linhas dela por id |
| Reativar e job de materialização | template, conta (o job trava a conta) |
| Apagar conta | templates da conta, lançamentos da conta por id, contas por id |
| Apagar transferência | transferência, pernas por id, contas por id |

Escolhi essa ordem, e não a de exemplo do pedido (conta, template, lançamentos), porque
é a que quase todos os caminhos já seguiam. Inverter exigia mexer em apagar
transferência, compra parcelada e lançamento avulso sem ganho de segurança. A única
violação era a ocorrência pedir o template depois do lançamento, e a correção é
travá-lo antes, em modo compartilhado: não segura as ocorrências entre si, só espera
quem edita ou apaga o template.

`lockTemplate` passou a travar só o template (`FOR UPDATE OF t`) e ler regra e divisão
na mesma consulta, e nenhum caminho enxerga o template com a divisão nula.

### O job trava a conta

O job lia `arquivada` sem travar a conta, para não fechar ciclo com PUT e confirmar
(lançamento, depois conta). Com a ordem acima o job trava o template e depois a conta,
como qualquer escrita, e arquivar (que faz `UPDATE` na conta) entra na fila atrás
dele. Não sobra mais prevista em conta arquivada. O prazo de espera do job continua
200 ms: se a conta está ocupada, a rodada daquele template é pulada e vale a de
amanhã. O custo é que uma escrita do usuário espera, no máximo, esse prazo se chegar
com o job no meio da conta.

### Categorias de semente

`categoria.semente` (V24) marca Salário (`salario`), Outros (`outros`) e Outras
receitas (`outras_receitas`). O backfill por nome só existe na migration, e o código
acha pela marca: o próximo salário do Posso gastar (a raiz marcada ou a filha dela) e
a conversão da perna de transferência ao apagar conta. Índice único parcial por
usuário e CHECKs de valor, de tipo e de "só na raiz". O CRUD de categoria, quando vier,
não pode apagar nem mudar o tipo de categoria marcada; se uma marca faltar, o apagar
conta falha em vez de gravar categoria errada.

### Filha com o tipo do pai

FK composta `(pai_id, usuario_id, pai_tipo)` para `categoria (id, usuario_id, tipo)`
(V25), com `pai_tipo` gerada. Dado existente que violaria vira raiz, com sufixo no
nome se colidir. Nenhuma rota cria filha, então o esperado é não tocar linha nenhuma.

### Cartão não é saída de transferência

FK composta com a coluna gerada `conta.pode_ser_origem` (V26), a técnica da V10. A
checagem da aplicação continua, para dar a mensagem; o banco é a rede.

### Aporte de meta ligado à transferência

`aporte_meta.transferencia_id` opcional (V27), FK composta com o usuário e índice único
parcial. Decisões:

- **Editar a transferência:** não existe. A API só tem `POST`, `GET` e `DELETE`; corrigir
  é apagar e criar. Nada a sincronizar.
- **Apagar a transferência vinculada:** recusado. O banco (`ON DELETE NO ACTION`) barra
  e a API responde 409 `transferência vinculada a aporte`. Apagar o aporte, ou a meta
  (cascade), solta a transferência.
- **Apagar a conta de uma transferência vinculada:** também 409 com a mesma mensagem.
  Apagar a conta desfaz as transferências dela, e deixar isso passar faria o banco
  recusar no meio, com 500.
- **Uma transferência, um aporte:** o mesmo dinheiro não conta duas vezes. Segundo
  aporte com a mesma transferência dá 409 `transferência já vinculada a outro aporte`.
- O vínculo só informa. O valor do aporte é o que o usuário mandou, pode diferir do da
  transferência (a meta guarda uma parte), e o acumulado continua a soma dos aportes.
- Transferência de outro usuário, inexistente ou id que não é UUID: 400
  `transferenciaId não encontrada`, todos com a mesma mensagem.

## Consequências

- Contrato: `POST /metas/{id}/aportes` aceita `transferenciaId`; `GET .../aportes` devolve
  `transferenciaId` (nulo sem vínculo); `DELETE /transferencias/{id}` e `DELETE
  /contas/{id}` ganham 409. Os dois primeiros campos são aditivos.
- Testes que fixavam o job sem travar a conta mudaram: o job que espera uma linha ocupada
  agora segura a conta por até 200 ms e desiste da rodada, e a corrida com arquivar não
  deixa prevista.
- A V26 falha se já existir transferência de saída em cartão no banco. A aplicação nunca
  gravou esse caso.
- Não há CRUD de categoria: as garantias da semente valem para quando ele existir.
