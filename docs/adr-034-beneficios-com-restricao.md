# ADR-034: Conta de benefício com categorias permitidas na F12

**Status:** aceito
**Data:** 2026-10-20

## Contexto

Vale-refeição e vale-alimentação são dinheiro que só paga certas coisas. O
modelo em `docs/contracts/dados.md` põe a restrição na conta
(`conta_categoria_permitida`): sem nenhuma linha, a conta aceita qualquer
categoria; com linhas, só as listadas, validado na aplicação e por gatilho
(invariante 4). O backlog pede também o saldo do benefício separado no painel.

## Decisão

### Conta

- O `CHECK` de tipo de `conta` passa a aceitar `beneficio`.
- `conta_categoria_permitida (conta_id, categoria_id, usuario_id)` com FK
  composta para a conta de benefício do mesmo usuário (pelo padrão de coluna
  gerada da V10) e para categoria de **despesa** do mesmo usuário. Chave
  primária `(conta_id, categoria_id)`.
- `POST /contas` com `tipo: beneficio` aceita `categoriasPermitidas` (lista de
  ids de categoria de despesa, até 50, sem repetir); os outros tipos não
  aceitam o campo. `PUT /contas/{id}/categorias-permitidas` com
  `{"categoriaIds": [...]}` troca a lista inteira; lista vazia libera tudo.
  `GET /contas` traz `categoriasPermitidas` (lista; vazia quando tudo é
  permitido; nula nos outros tipos).

### A regra

- **Vale só para despesa.** A recarga do benefício é receita e usa a
  categoria que o usuário quiser (em geral "Benefício"). Restringir a receita
  obrigaria a pôr categoria de receita na lista de quem só quer dizer "o VR
  paga comida".
- Despesa em conta de benefício com lista não vazia precisa de categoria da
  lista: 400 `categoria não permitida nesta conta`. Vale para `POST` e `PUT
  /lancamentos`, confirmar com ajuste, recorrência na criação e no job.
- **Gatilho no banco** (`BEFORE INSERT OR UPDATE` em `lancamento`, com
  `search_path` fixo) recusa a mesma coisa, com nome de constraint próprio,
  para a regra não depender só da aplicação (invariante 4).
- Mudar a lista não mexe em lançamento já gravado: a regra vale para escrita
  nova e para edição. O job que materializa uma recorrência cuja categoria
  saiu da lista pula aquele template, com log, e segue.
- Conta de benefício não é origem nem destino de transferência (400 `conta de
  benefício não participa de transferência`), nem conta de compra parcelada
  (já é só cartão) nem conta de pagamento de cartão (já é só corrente ou
  poupança).

### Saldo separado

- O saldo do Início ("Saldo nas contas") passa a excluir as contas de
  benefício, e um cartão próprio mostra "Benefícios" com o saldo somado delas.
- O saldo projetado sem `contaId` (ADR-029) soma as contas ativas **exceto**
  benefício: o que o usuário quer saber é quando o dinheiro de pagar contas
  acaba. Com `contaId` de uma conta de benefício, a projeção é dela.
- Transferência não envolve benefício, então a exclusão não deixa perna sem
  par na projeção.

## Justificativa

Restringir só a despesa é o que o produto descreve ("o VR só paga comida") sem
burocracia na recarga.

O gatilho fecha a corrida em que a lista muda entre a checagem da aplicação e
o INSERT; a aplicação continua checando antes para dar mensagem boa.

Benefício fora do saldo projetado evita a falsa sensação de folga: R$ 800 de VR
não pagam o aluguel.

## Alternativas descartadas

- **Restringir receita também**: pede categoria de receita na lista sem ganho.
- **Só aplicação, sem gatilho**: contraria o invariante 4 de `dados.md`.
- **Somar benefício no saldo projetado com um aviso**: a linha principal do
  gráfico mentiria sobre o dinheiro livre.

## Consequência

ADR-029 ganha a exclusão de benefício na projeção sem `contaId`. `dados.md`
registra a tabela, o gatilho e o `CHECK` alargado.
